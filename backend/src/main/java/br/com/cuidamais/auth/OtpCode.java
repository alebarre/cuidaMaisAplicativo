package br.com.cuidamais.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Código OTP de uso único (T-013), com a máquina de estados de AC-005 (D-07): emissão, verificação
 * e reenvio são métodos desta entidade e lançam {@link IllegalStateException} (ou subclasse)
 * quando a operação não é permitida no estado atual — testável inteiramente em memória, sem
 * Spring nem banco. O {@link Clock} é sempre recebido por parâmetro (D-10).
 *
 * <p>Guarda somente o hash do código (D-04): o texto puro nunca é persistido, só trafega em
 * memória (ver {@link IssuedOtp}) para ir no e-mail.
 *
 * <p>Mapeada como {@code @Entity} sobre a tabela {@code otp_codes} (V1__foundation.sql, T-010)
 * para preparar a persistência de tarefas futuras; esta tarefa não cria repositório nem expõe
 * esta entidade em um controller.
 */
@Entity
@Table(name = "otp_codes")
public class OtpCode {

    private static final Duration VALIDADE = Duration.ofMinutes(15);
    private static final Duration INTERVALO_REENVIO = Duration.ofSeconds(60);
    private static final int MAX_TENTATIVAS = 5;

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30, updatable = false)
    private OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #issue}. */
    protected OtpCode() {}

    /**
     * Emite um novo código (AC-005.1): recebe só o hash (D-04), nasce sem tentativas, não
     * consumido, com prazo de 15 min a partir de agora.
     *
     * <p>{@code agora} é truncado para microssegundos (T-022): {@code expires_at}/{@code
     * last_sent_at} são colunas {@code timestamp} do PostgreSQL, que só guarda até microssegundos
     * — sem truncar aqui, o valor em memória (nanossegundos) e o valor depois de um
     * save/reload divergiriam por arredondamento do driver JDBC, podendo folgar o prazo de 15 min
     * em até ~500 ns e quebrar a verificação exata do limite (AC-005.1) depois de um reload da
     * entidade (ex. {@code VerifyEmailService}, que sempre busca o {@code OtpCode} do banco).
     */
    public static OtpCode issue(UUID userId, OtpPurpose purpose, String codeHash, Clock clock) {
        Instant agora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        OtpCode otp = new OtpCode();
        otp.id = UUID.randomUUID();
        otp.userId = userId;
        otp.purpose = purpose;
        otp.codeHash = codeHash;
        otp.attempts = 0;
        otp.expiresAt = agora.plus(VALIDADE);
        otp.lastSentAt = agora;
        otp.consumedAt = null;
        return otp;
    }

    /**
     * Verifica o código submetido (AC-005.1, AC-005.2). Primeiro recusa por estado — expirado, já
     * consumido ou tentativas esgotadas — sem comparar o código submetido, já que o motivo da
     * recusa já está decidido e não faz sentido contar mais uma tentativa errada. Senão, compara o
     * hash: código certo marca {@code consumedAt} e devolve {@code true}; código errado conta uma
     * tentativa e devolve {@code false}.
     */
    public boolean verify(String submittedCode, PasswordEncoder encoder, Clock clock) {
        Instant agora = clock.instant();
        if (!agora.isBefore(expiresAt)) {
            throw new IllegalStateException("Código OTP expirado (AC-005.1)");
        }
        if (consumedAt != null) {
            throw new IllegalStateException("Código OTP já foi consumido (AC-005.1)");
        }
        if (attempts >= MAX_TENTATIVAS) {
            throw new IllegalStateException("Código OTP esgotou as tentativas (AC-005.2)");
        }
        if (encoder.matches(submittedCode, codeHash)) {
            this.consumedAt = agora;
            return true;
        }
        this.attempts++;
        return false;
    }

    /**
     * Reenvia um novo código (AC-005.3): sempre aceito — mesmo com o código anterior expirado,
     * consumido ou com tentativas esgotadas, já que reenviar é como a pessoa volta a poder se
     * autenticar — desde que respeitado o intervalo mínimo de 60 s desde o último envio.
     */
    public void resend(String newCodeHash, Clock clock) {
        // Mesmo truncamento de #issue: garante que expires_at/last_sent_at sobrevivam a um
        // save/reload sem variar por arredondamento de precisão do driver JDBC (T-022).
        Instant agora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (agora.isBefore(lastSentAt.plus(INTERVALO_REENVIO))) {
            throw new IllegalStateException("Reenvio de OTP respeita o intervalo de 60 s (AC-005.3)");
        }
        this.codeHash = newCodeHash;
        this.attempts = 0;
        this.expiresAt = agora.plus(VALIDADE);
        this.lastSentAt = agora;
        this.consumedAt = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }
}
