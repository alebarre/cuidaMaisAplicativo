package br.com.cuidamais.auth;

import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Regra de negócio de {@code POST /auth/verify-email} (T-022, AC-001.2, AC-005.1, AC-005.2; D-05
 * rev. 2, D-33). Confirma o código de 6 dígitos pendente de uma conta e, só com o código certo,
 * ativa a conta ({@code email_verified_at}, D-33) e abre a sessão (D-11) — <strong>sem</strong>
 * criar grupo nem membership, que só nascem em {@code POST /elders} (D-05 rev. 2, tarefa futura).
 *
 * <p>Por decisão do orquestrador (P5), e-mail sem conta nenhuma e "nada pendente para confirmar"
 * (conta sem código {@code CONFIRMACAO_EMAIL} emitido, código expirado, já consumido ou esgotado)
 * respondem com a <strong>mesma</strong> {@link CodeExpiredOrExhaustedException}: o contrato não
 * prevê {@code 404} nesta rota, e distinguir e-mail desconhecido de código expirado revelaria se
 * aquele e-mail tem cadastro.
 *
 * <p><strong>Nota sobre transação (AC-005.2):</strong> este método <em>não</em> é {@code
 * @Transactional} de propósito. Quando o código é errado, a tentativa (o incremento de {@code
 * otp_codes.attempts}) precisa ficar gravada mesmo que o método termine lançando {@link
 * InvalidCodeException} — e cada chamada a um método de {@code save} de um {@code JpaRepository}
 * já abre e confirma sua própria transação quando não há uma transação maior abrangendo-a. Se
 * este método fosse {@code @Transactional}, a exceção lançada depois do {@code save} desfaria o
 * incremento junto (rollback), e o contador de 5 tentativas nunca andaria.
 */
@Service
public class VerifyEmailService {

    /** AC-005: o único propósito de código que esta rota confirma (D-05 rev. 2). */
    private static final OtpPurpose FINALIDADE = OtpPurpose.CONFIRMACAO_EMAIL;

    /** AC-005.2: total de tentativas permitidas por código (mesmo limite de {@link OtpCode}). */
    private static final int MAX_TENTATIVAS = 5;

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final OtpService otpService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final Clock clock;

    /**
     * @param userRepository busca a conta pelo e-mail e persiste a confirmação
     * @param otpCodeRepository busca o código pendente da conta e persiste tentativas/consumo
     * @param otpService verifica o código submetido contra o hash guardado (D-04, D-07)
     * @param refreshTokenService emite o refresh token opaco da nova sessão (D-11)
     * @param jwtService emite o access token JWT de 15 min da nova sessão (D-11)
     * @param clock relógio injetado (D-10), usado para ativar a conta e para o JWT
     */
    public VerifyEmailService(
            UserRepository userRepository,
            OtpCodeRepository otpCodeRepository,
            OtpService otpService,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            Clock clock) {
        this.userRepository = userRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpService = otpService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.clock = clock;
    }

    /**
     * Confirma o e-mail de uma conta com o código recebido (AC-001.2, AC-005.1, AC-005.2).
     *
     * @param email e-mail informado no formulário (qualquer caixa; é normalizado aqui)
     * @param code código de 6 dígitos submetido (já validado pelo formato em {@link
     *     VerifyEmailRequest})
     * @return a sessão aberta (JWT + refresh token) quando o código é o certo
     * @throws CodeExpiredOrExhaustedException e-mail sem conta, conta sem código pendente, código
     *     expirado, já consumido ou com as 5 tentativas esgotadas (P5: mesma resposta nos cinco casos)
     * @throws InvalidCodeException código de formato válido mas diferente do código certo pendente
     */
    public VerifiedEmailSession verify(String email, String code) {
        // 1. normaliza o e-mail para minúsculas (mesmo padrão de SignupService).
        String normalizedEmail = email.toLowerCase(Locale.ROOT);

        // 2. e-mail sem conta nenhuma: mesma resposta de "nada a confirmar" (P5, sem 404).
        User user = userRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(CodeExpiredOrExhaustedException::new);

        // 3. sem código CONFIRMACAO_EMAIL pendente para esta conta: idem.
        OtpCode otpCode = otpCodeRepository
                .findFirstByUserIdAndPurposeOrderByLastSentAtDesc(user.getId(), FINALIDADE)
                .orElseThrow(CodeExpiredOrExhaustedException::new);

        // 4. confere o código submetido contra o hash guardado (D-04/D-07).
        boolean correto;
        try {
            correto = otpService.verify(otpCode, code);
        } catch (IllegalStateException expiradoConsumidoOuEsgotado) {
            // Estado do código já decide a recusa (expirado, consumido ou sem tentativas) sem
            // comparar o valor — não há nada novo para persistir aqui.
            throw new CodeExpiredOrExhaustedException();
        }

        if (!correto) {
            // 5. código errado: a tentativa incrementada precisa ficar gravada mesmo com a
            // exceção lançada a seguir — por isso o save() vem antes do throw (ver Javadoc da
            // classe sobre a ausência de @Transactional neste método).
            otpCodeRepository.save(otpCode);
            throw new InvalidCodeException(MAX_TENTATIVAS - otpCode.getAttempts());
        }

        // 6. código certo: marca o código como consumido (uso único, D-07) e ativa a conta
        // (email_verified_at, D-33) — sem criar grupo nem membership (D-05 rev. 2).
        otpCodeRepository.save(otpCode);
        user.verifyEmail(clock);
        userRepository.save(user);

        // 7. abre a sessão (D-11): refresh token opaco (cookie) + access token JWT (corpo).
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId());
        String accessToken = jwtService.issue(user.getId(), clock);
        return new VerifiedEmailSession(accessToken, refreshToken);
    }
}
