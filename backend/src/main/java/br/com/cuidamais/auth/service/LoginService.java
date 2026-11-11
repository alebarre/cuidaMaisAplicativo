package br.com.cuidamais.auth.service;

import br.com.cuidamais.auth.domain.AccountState;
import br.com.cuidamais.auth.domain.AccountStateValue;
import br.com.cuidamais.auth.domain.IssuedRefreshToken;
import br.com.cuidamais.auth.domain.LoginSession;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.exception.AccountNotActiveException;
import br.com.cuidamais.auth.exception.InvalidCredentialsException;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.PasswordLockEmail;
import java.time.Clock;
import java.util.Locale;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regra de negócio de {@code POST /auth/login} (T-023, AC-008.1, AC-008.2, AC-008.4; D-11, D-12,
 * D-14 rev. 2, D-15, D-33).
 *
 * <p>Três respostas possíveis, nesta ordem de prioridade:
 *
 * <ul>
 *   <li>{@code 401} ({@link InvalidCredentialsException}) — e-mail inexistente, senha errada ou
 *       conta bloqueada por 5 erros seguidos nos últimos 15 min (D-14 rev. 2). Os três motivos são
 *       <strong>indistinguíveis</strong> na resposta (D-15, P5): nem o status, nem o corpo, nem o
 *       tempo de resposta podem revelar qual dos três ocorreu, senão seria possível descobrir se
 *       um e-mail tem cadastro ou se uma conta está bloqueada só testando respostas.
 *   <li>{@code 403} ({@link AccountNotActiveException}) — e-mail e senha corretos, mas a conta
 *       derivada por {@link AccountState#of} não está {@code ATIVO} (D-33); só se chega a conferir
 *       o estado depois da senha confirmada, exatamente para que o {@code code} do estado nunca
 *       escape para quem não provou que é o dono da conta (AC-008.2).
 *   <li>{@code 200} ({@link LoginSession}) — e-mail e senha corretos e conta {@code ATIVO}: abre
 *       sessão (D-11/D-12), no mesmo padrão de {@link VerifyEmailService}.
 * </ul>
 */
@Service
public class LoginService {

    /**
     * Hash BCrypt fixo, gerado uma única vez na carga da classe, que nunca corresponde a senha
     * nenhuma de verdade. {@link #login} compara a senha informada contra ele quando o e-mail não
     * existe, só para gastar o mesmo tempo de CPU (o custo de uma verificação BCrypt) que uma
     * tentativa contra uma conta real gastaria — sem isso, "e-mail não existe" responderia mais
     * rápido que "e-mail existe mas a senha é errada", e essa diferença de tempo revelaria se um
     * e-mail tem cadastro (D-15, P5), mesmo com o corpo da resposta idêntico nos dois casos.
     */
    private static final String HASH_FICTICIO =
            new BCryptPasswordEncoder().encode("senha-ficticia-para-equalizar-o-tempo-de-resposta");

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Transação isolada (REQUIRES_NEW, mesmo padrão de {@code RefreshTokenService#rotate}) usada
     * só para gravar o erro de senha (e publicar o {@link PasswordLockEmail}, se for a transição):
     * precisa confirmar no banco mesmo que o {@link InvalidCredentialsException} lançado a seguir
     * fizesse o chamador desfazer algo por cima — e, mais importante aqui, precisa de uma
     * transação <strong>ativa</strong> no momento de {@code events.publishEvent}, porque
     * {@code EmailEventListener} só dispara em {@code AFTER_COMMIT}: um evento publicado fora de
     * qualquer transação é descartado (sem {@code fallbackExecution}, D-23).
     */
    private final TransactionTemplate transacaoIsolada;

    /**
     * @param userRepository busca a conta pelo e-mail e persiste o contador/bloqueio de login
     * @param membershipRepository busca o vínculo (se houver) que decide o estado da conta (D-33)
     * @param passwordEncoder confere a senha informada contra o hash guardado (D-14)
     * @param refreshTokenService emite o refresh token opaco da nova sessão (D-11)
     * @param jwtService emite o access token JWT de 15 min da nova sessão (D-11)
     * @param events publica o {@link PasswordLockEmail} exatamente na transição para bloqueado
     * @param clock relógio injetado (D-10), usado para o bloqueio e para o JWT
     * @param transactionManager usado para montar a transação isolada do erro de senha
     */
    public LoginService(
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.events = events;
        this.clock = clock;
        this.transacaoIsolada = new TransactionTemplate(transactionManager);
        this.transacaoIsolada.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Processa uma tentativa de login (AC-008.1, AC-008.2, AC-008.4). Este método <em>não</em> é
     * {@code @Transactional} de propósito, pelo mesmo motivo de {@code VerifyEmailService#verify}:
     * se o método inteiro fosse uma única transação, a exceção lançada no final (senha errada ou
     * conta não ativa) desfaria qualquer {@code save()} feito antes dela, e o contador de 5 erros
     * nunca andaria. Por isso o caminho de senha errada grava o incremento (e publica o {@code
     * PasswordLockEmail}, se for a transição) na {@link #transacaoIsolada} — que confirma sozinha,
     * antes do {@link InvalidCredentialsException} ser lançado.
     *
     * @param email e-mail informado no formulário (qualquer caixa; é normalizado aqui)
     * @param password senha em texto puro informada no formulário
     * @return a sessão aberta (JWT + refresh token) quando e-mail, senha e estado {@code ATIVO} se confirmam
     * @throws InvalidCredentialsException e-mail inexistente, senha errada, ou conta bloqueada
     * @throws AccountNotActiveException e-mail e senha corretos, mas a conta não está {@code ATIVO}
     */
    public LoginSession login(String email, String password) {
        // 1. normaliza o e-mail para minúsculas (mesmo padrão de SignupService/VerifyEmailService).
        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        // 2. e-mail sem conta nenhuma: 401 único (D-15). Antes de lançar, confere a senha contra um
        // hash fictício (ver Javadoc de HASH_FICTICIO) só para equalizar o tempo de resposta com o
        // caminho de "e-mail existe, senha errada" — mesmo sabendo que a comparação vai falhar.
        var userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            passwordEncoder.matches(password, HASH_FICTICIO);
            throw new InvalidCredentialsException();
        }
        User user = userOpt.get();

        // 3. libera um bloqueio já vencido (D-09, verificado na leitura) antes de avaliar a
        // tentativa de agora — senão uma janela de 15 min expirada continuaria parecendo bloqueada.
        user.clearExpiredLoginLock(clock);

        // 4. conta bloqueada: 401 idêntico, sem conferir a senha de forma alguma (o bloqueio em si
        // já decide a resposta; não há nada a equalizar, nem nada a persistir).
        if (user.isLoginLocked(clock)) {
            throw new InvalidCredentialsException();
        }

        // 5. confere a senha de verdade.
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            // 5a. senha errada: registra o erro (pode virar a transição para bloqueado), salva e
            // publica o aviso de bloqueio numa transação isolada que confirma por conta própria —
            // precisa ficar gravado (e o e-mail precisa poder ser entregue) mesmo que este método
            // termine lançando a exceção a seguir (ver Javadoc da classe e de transacaoIsolada).
            transacaoIsolada.executeWithoutResult(status -> {
                boolean deveNotificar = user.registerFailedLogin(clock);
                userRepository.save(user);
                if (deveNotificar) {
                    events.publishEvent(new PasswordLockEmail(user.getEmail(), user.getLockedUntil()));
                }
            });
            throw new InvalidCredentialsException();
        }

        // 5b. senha certa: zera o contador/bloqueio e persiste.
        user.registerSuccessfulLogin();
        userRepository.save(user);

        // 6. deriva o estado da conta (D-33) a partir do vínculo (zero ou um, D-02) e só abre
        // sessão se for ATIVO — qualquer outro estado revela o motivo em um 403 (AC-008.2).
        Membership membership = membershipRepository.findByUserId(user.getId()).orElse(null);
        AccountState state = AccountState.of(user, membership);
        if (state.state() != AccountStateValue.ATIVO) {
            throw new AccountNotActiveException(state.state());
        }

        // 7. abre a sessão (D-11): refresh token opaco (cookie) + access token JWT (corpo).
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId());
        String accessToken = jwtService.issue(user.getId(), clock);
        return new LoginSession(accessToken, refreshToken);
    }
}
