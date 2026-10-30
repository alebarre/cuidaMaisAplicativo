package br.com.cuidamais.auth.service;

import br.com.cuidamais.auth.domain.IssuedOtp;
import br.com.cuidamais.auth.domain.OtpCode;
import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.exception.CodeExpiredOrExhaustedException;
import br.com.cuidamais.auth.exception.InvalidCodeException;
import br.com.cuidamais.auth.exception.TooManyRequestsException;
import br.com.cuidamais.auth.repository.OtpCodeRepository;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de negócio de {@code POST /auth/password-reset/request} e {@code
 * POST /auth/password-reset/confirm} (T-026, AC-010.1 a AC-010.5; D-15, D-37).
 *
 * <p>{@link #requestReset} pede o código de redefinição (resposta sempre neutra, 202 sem corpo —
 * D-15): diferente de {@link ResendCodeService}, aqui um código {@code REDEFINICAO_SENHA}
 * <strong>já consumido</strong> ainda conta como "já existe um" e é tratado como reenvio (decisão
 * do orquestrador) — só quando a conta nunca recebeu nenhum código dessa finalidade é que um é
 * emitido do zero.
 *
 * <p>{@link #confirm} define a senha nova com o código recebido (AC-010.3, mesmo padrão 400/410
 * de {@code VerifyEmailService}/{@code verify-email}, T-022) e, só com o código certo, troca
 * {@code users.password_hash} e encerra <strong>todas</strong> as sessões da conta (D-37,
 * AC-010.4) — sem tocar em nenhum outro campo nem no estado derivado dela (AC-010.5): qualquer
 * conta existente, em qualquer estado, pode redefinir a senha e continua exatamente no mesmo
 * estado depois.
 */
@Service
public class PasswordResetService {

    /** Única finalidade de código que estas duas rotas tratam (D-05 rev. 2, análogo a AC-005). */
    private static final OtpPurpose FINALIDADE = OtpPurpose.REDEFINICAO_SENHA;

    /** AC-005.2: total de tentativas permitidas por código (mesmo limite de {@link OtpCode}). */
    private static final int MAX_TENTATIVAS = 5;

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * @param userRepository busca a conta pelo e-mail e persiste a troca de senha
     * @param otpCodeRepository busca/persiste o código {@code REDEFINICAO_SENHA} da conta
     * @param otpService emite/reenvia/verifica o código (D-04, D-07)
     * @param passwordEncoder gera o hash BCrypt da senha nova (D-14)
     * @param refreshTokenService encerra todas as sessões da conta depois do {@code confirm} (D-37)
     * @param events publica o e-mail do código dentro da transação (D-23)
     * @param clock relógio injetado (D-10), usado para o {@code Retry-After} do 429
     */
    public PasswordResetService(
            UserRepository userRepository,
            OtpCodeRepository otpCodeRepository,
            OtpService otpService,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            ApplicationEventPublisher events,
            Clock clock) {
        this.userRepository = userRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Pede o código de redefinição de senha (AC-010.1, AC-010.2, D-15).
     *
     * @param email e-mail informado (qualquer caixa; é normalizado aqui)
     * @throws TooManyRequestsException já havia um código e o pedido chegou antes de completar
     *     1 min desde o último envio (AC-005.3, tratado como reenvio)
     */
    @Transactional
    public void requestReset(String email) {
        // 1. normaliza o e-mail.
        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        // 2. e-mail sem conta nenhuma: 202 neutro, nada a fazer (AC-010.2, D-15, P5).
        Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();

        // 3. já existe algum código REDEFINICAO_SENHA (mesmo consumido)? Decisão do orquestrador:
        // isso conta como "já existe um" e o pedido é tratado como reenvio, não como emissão nova
        // — diferente de ResendCodeService, que exige o código ainda não consumido.
        Optional<OtpCode> existente =
                otpCodeRepository.findFirstByUserIdAndPurposeOrderByLastSentAtDesc(user.getId(), FINALIDADE);

        String plainCode;
        OtpCode otp;
        if (existente.isEmpty()) {
            // 4a. nenhum código ainda: emite um novo do zero (AC-010.1, AC-005.1).
            IssuedOtp emitido = otpService.issue(user.getId(), FINALIDADE);
            otp = emitido.otp();
            plainCode = emitido.plainCode();
        } else {
            // 4b. já existe um: reenvia (AC-005.3); antes de 60 s desde o último envio, 429.
            otp = existente.get();
            try {
                plainCode = otpService.resend(otp);
            } catch (IllegalStateException antesDaHora) {
                throw new TooManyRequestsException(otp.secondsUntilResendAllowed(clock));
            }
        }
        otpCodeRepository.save(otp);

        // 5. publica o e-mail do código (só sai depois do commit da transação, D-23).
        events.publishEvent(new ConfirmationCodeEmail(user.getEmail(), plainCode, FINALIDADE));
    }

    /**
     * Confirma a redefinição de senha com o código recebido (AC-010.3, AC-005.1, AC-005.2).
     *
     * @param email e-mail informado (qualquer caixa; é normalizado aqui)
     * @param code código de 6 dígitos submetido (já validado pelo formato em {@link
     *     PasswordResetConfirmRequest})
     * @param newPassword senha nova (já validada pelas regras de AC-001.3 em {@link
     *     PasswordResetConfirmRequest})
     * @throws CodeExpiredOrExhaustedException e-mail sem conta, conta sem código {@code
     *     REDEFINICAO_SENHA} pendente, código expirado, já consumido ou com as tentativas
     *     esgotadas (P5: mesma resposta nos cinco casos, sem revelar se o e-mail tem cadastro)
     * @throws InvalidCodeException código de formato válido mas diferente do código certo pendente
     */
    /*
     * Nota sobre transação (AC-005.2), mesmo cuidado de VerifyEmailService#verify: este método
     * não é @Transactional de propósito. Quando o código é errado, o incremento de
     * otp_codes.attempts precisa ficar gravado mesmo que o método termine lançando
     * InvalidCodeException — e cada save() de um JpaRepository já abre e confirma sua própria
     * transação quando não há uma transação maior abrangendo-a. Se este método fosse
     * @Transactional, a exceção lançada depois do save() desfaria o incremento junto (rollback),
     * e o contador de 5 tentativas nunca andaria.
     */
    public void confirm(String email, String code, String newPassword) {
        // 1. normaliza o e-mail.
        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        // 2. e-mail sem conta nenhuma: mesma resposta de "nada a confirmar" (P5, 410 genérico).
        User user = userRepository.findByEmail(normalizedEmail).orElseThrow(CodeExpiredOrExhaustedException::new);

        // 3. sem código REDEFINICAO_SENHA pendente (nunca emitido): idem.
        OtpCode otp = otpCodeRepository
                .findFirstByUserIdAndPurposeOrderByLastSentAtDesc(user.getId(), FINALIDADE)
                .orElseThrow(CodeExpiredOrExhaustedException::new);

        // 4. confere o código submetido (expirado/consumido/esgotado também vira 410 — OtpCode#verify).
        boolean correto;
        try {
            correto = otpService.verify(otp, code);
        } catch (IllegalStateException expiradoConsumidoOuEsgotado) {
            throw new CodeExpiredOrExhaustedException();
        }

        if (!correto) {
            // 5. código errado: persiste a tentativa incrementada mesmo com a exceção lançada a
            // seguir (mesmo cuidado de VerifyEmailService — sem isso o contador nunca andaria).
            otpCodeRepository.save(otp);
            throw new InvalidCodeException(MAX_TENTATIVAS - otp.getAttempts());
        }

        // 6. código certo: consome o código (uso único) e troca só a senha — D-37/AC-010.5: nada
        // mais na conta muda (nem email_verified_at, nem o estado derivado dela).
        otpCodeRepository.save(otp);
        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // 7. encerra TODAS as sessões da conta, de todas as famílias (D-37, AC-010.4) — a pessoa
        // precisa entrar de novo com a senha nova em todo aparelho; o confirm não abre sessão
        // nenhuma (sem cookie, sem refresh token novo).
        refreshTokenService.revokeAllForUser(user.getId());
    }
}
