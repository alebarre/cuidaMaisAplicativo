package br.com.cuidamais.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Serviço de emissão, verificação e reenvio de códigos OTP (T-013, AC-005): gera o código de 6
 * dígitos em texto puro só para ir no e-mail (D-04) e delega as regras de estado — prazo de 15
 * min (AC-005.1), 5 tentativas (AC-005.2) e reenvio 1/min (AC-005.3) — para {@link OtpCode}
 * (D-07). O {@link PasswordEncoder} é o mesmo mecanismo (BCrypt) usado para senhas (D-14); o
 * {@link Clock} é sempre recebido no construtor (D-10) para os testes controlarem o "agora".
 *
 * <p>{@code @Service} desde a T-020, que é a primeira tarefa a precisar deste bean gerenciado
 * (pelo {@code SignupService}); o construtor com {@link PasswordEncoder} e {@link Clock}
 * continua o mesmo, resolvido pelos beans de {@code SecurityConfig} e {@code ClockConfig}.
 */
@Service
public class OtpService {

    private static final int DIGITOS = 6;
    private static final int LIMITE_EXCLUSIVO = 1_000_000;

    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OtpService(PasswordEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    /** Gera um código novo e emite um {@link OtpCode} que guarda apenas o hash (D-04). */
    public IssuedOtp issue(UUID userId, OtpPurpose purpose) {
        String plainCode = gerarCodigo();
        OtpCode otp = OtpCode.issue(userId, purpose, encoder.encode(plainCode), clock);
        return new IssuedOtp(otp, plainCode);
    }

    /** Repassa a verificação para {@link OtpCode#verify}, com as mesmas regras e exceções. */
    public boolean verify(OtpCode otp, String submittedCode) {
        return otp.verify(submittedCode, encoder, clock);
    }

    /**
     * Gera um novo código, aplica {@link OtpCode#resend} e devolve o novo texto puro para o
     * e-mail (AC-005.3).
     */
    public String resend(OtpCode otp) {
        String novoCodigo = gerarCodigo();
        otp.resend(encoder.encode(novoCodigo), clock);
        return novoCodigo;
    }

    private String gerarCodigo() {
        int numero = random.nextInt(LIMITE_EXCLUSIVO);
        return String.format("%0" + DIGITOS + "d", numero);
    }
}
