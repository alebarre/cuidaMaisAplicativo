package br.com.cuidamais.auth.service;

import br.com.cuidamais.auth.domain.OtpCode;
import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.dto.ResendCodeRequest;
import br.com.cuidamais.auth.exception.TooManyRequestsException;
import br.com.cuidamais.auth.repository.OtpCodeRepository;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de negócio de {@code POST /auth/resend-code} (T-026, AC-001.6, AC-005.3; D-15): reenvia o
 * código <strong>pendente</strong> (ainda não consumido) de uma finalidade, exista ou não a conta
 * — a resposta ao chamador é sempre a mesma (202 sem corpo, ver {@code AuthController#resendCode})
 * e este método nunca lança nada para e-mail desconhecido ou "nada pendente", só para o limite de
 * 1/min (AC-005.3).
 *
 * <p>"Nada pendente" (decisão do orquestrador, documentada no Javadoc de {@code
 * ResendCodeAndPasswordResetTest}): o código mais recente daquela finalidade <strong>já
 * consumido</strong> (ou inexistente) não é reenviável aqui — um código {@code CONFIRMACAO_EMAIL}
 * já consumido pertence a uma conta já {@code ATIVO}, e reenviá-lo mandaria um código de
 * confirmação a quem já confirmou, reabrindo {@code verify-email} (que regrava {@code
 * email_verified_at} e abre sessão sem senha). Só um código ainda não consumido — mesmo que
 * expirado ou com as tentativas esgotadas — é reenviável, porque é exatamente para esses dois
 * casos que o reenvio existe (AC-005.2 "é preciso pedir um novo").
 */
@Service
public class ResendCodeService {

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final OtpService otpService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * @param userRepository busca a conta pelo e-mail informado
     * @param otpCodeRepository busca o código pendente da finalidade e persiste o reenvio
     * @param otpService gera o novo código e aplica {@link OtpCode#resend} (D-04, D-07)
     * @param events publica o e-mail do novo código dentro da transação (D-23)
     * @param clock relógio injetado (D-10), usado para o {@code Retry-After} do 429
     */
    public ResendCodeService(
            UserRepository userRepository,
            OtpCodeRepository otpCodeRepository,
            OtpService otpService,
            ApplicationEventPublisher events,
            Clock clock) {
        this.userRepository = userRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.otpService = otpService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Reenvia o código pendente da finalidade pedida (AC-001.6, AC-005.3).
     *
     * @param email e-mail informado no formulário (qualquer caixa; é normalizado aqui)
     * @param purpose valor em inglês do contrato ({@code EMAIL_VERIFICATION}/{@code
     *     PASSWORD_RESET}, já validado pelo formato em {@link ResendCodeRequest})
     * @throws TooManyRequestsException pedido chegou antes de completar 1 min desde o último envio
     */
    @Transactional
    public void resend(String email, String purpose) {
        // 1. normaliza o e-mail (mesmo padrão de SignupService/VerifyEmailService).
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        // 2. mapeia o valor em inglês do contrato para a finalidade do domínio (não no DTO).
        OtpPurpose otpPurpose = toOtpPurpose(purpose);

        // 3. e-mail sem conta nenhuma: 202 neutro, nada a fazer (D-15, P5).
        Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();

        // 4. sem código pendente (inexistente ou já consumido) daquela finalidade: 202 neutro,
        // nada a fazer — ver a nota da classe sobre por que um código consumido não é reaberto.
        Optional<OtpCode> maybePending = otpCodeRepository
                .findFirstByUserIdAndPurposeOrderByLastSentAtDesc(user.getId(), otpPurpose)
                .filter(otp -> otp.getConsumedAt() == null);
        if (maybePending.isEmpty()) {
            return;
        }
        OtpCode otp = maybePending.get();

        // 5. reenvia (AC-005.3); antes de 60 s desde o último envio, recusa com 429 + Retry-After.
        String newPlainCode;
        try {
            newPlainCode = otpService.resend(otp);
        } catch (IllegalStateException antesDaHora) {
            throw new TooManyRequestsException(otp.secondsUntilResendAllowed(clock));
        }
        otpCodeRepository.save(otp);

        // 6. publica o e-mail do novo código (só sai depois do commit da transação, D-23).
        events.publishEvent(new ConfirmationCodeEmail(user.getEmail(), newPlainCode, otpPurpose));
    }

    /**
     * Mapeia o valor em inglês do contrato ({@code ResendCodeRequest.purpose}) para a finalidade
     * do domínio. Os nomes do enum Java ({@code CONFIRMACAO_EMAIL}/{@code REDEFINICAO_SENHA}) não
     * são aceitos pela API — só chegam aqui porque {@link ResendCodeRequest} já garantiu, por
     * {@code @Pattern}, que {@code purpose} é um dos dois valores do contrato.
     */
    private static OtpPurpose toOtpPurpose(String purpose) {
        return switch (purpose) {
            case "EMAIL_VERIFICATION" -> OtpPurpose.CONFIRMACAO_EMAIL;
            case "PASSWORD_RESET" -> OtpPurpose.REDEFINICAO_SENHA;
            default ->
                    throw new IllegalArgumentException(
                            "purpose fora do contrato (deveria ter sido recusado pela validação): " + purpose);
        };
    }
}
