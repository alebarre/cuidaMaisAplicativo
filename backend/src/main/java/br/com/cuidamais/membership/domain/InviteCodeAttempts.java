package br.com.cuidamais.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Contador de tentativas de código de convite <strong>por e-mail</strong> (D-34, AC-003.11),
 * exista convite ou não (AC-004.7).
 *
 * <p>Regra de domínio em memória (D-07): 5 erros seguidos bloqueiam o e-mail por 30 minutos
 * (AC-003.3); o contador zera ao fim do bloqueio, quando um convite é criado para o e-mail e
 * quando um código é usado. O limite de 3 reenvios automáticos ({@code autoResends}) só zera com
 * código usado (D-34). O {@link Clock} é sempre recebido por parâmetro (D-10) e o fim do bloqueio é
 * verificado <strong>na leitura</strong> (D-09), sem depender de o job já ter rodado.
 *
 * <p>Representação do estado com as colunas de {@code invite_code_attempts}:
 *
 * <ul>
 *   <li>{@code blocked_until} nulo: sem bloqueio e sem reenvio automático pendente.
 *   <li>{@code blocked_until} no futuro: e-mail bloqueado; {@code failed_attempts} vale 5.
 *   <li>{@code blocked_until} já no passado: o bloqueio terminou e o reenvio automático do job
 *       (D-09 item 4) ainda está devido. Erros depois do fim contam num ciclo novo (a partir de 0)
 *       sem apagar essa pendência; {@code consumeAutoResend}, {@code dismissAutoResend}, convite
 *       criado e código usado limpam {@code blocked_until}.
 * </ul>
 *
 * <p>Mapeada como {@code @Entity} sobre {@code invite_code_attempts} (V1__foundation.sql, T-010)
 * para preparar a persistência de tarefas futuras (T-040, T-041, T-048); nenhuma tarefa deste
 * pacote ainda cria repositório. Mensagens de exceção não carregam o e-mail (P5).
 */
@Entity
@Table(name = "invite_code_attempts")
public class InviteCodeAttempts {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(30);
    private static final int MAX_AUTO_RESENDS = 3;

    @Id
    @Column(name = "email", nullable = false, updatable = false, length = 320)
    private String email;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "auto_resends", nullable = false)
    private int autoResends;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #forEmail}. */
    protected InviteCodeAttempts() {}

    /**
     * Contador novo e zerado para o e-mail normalizado (sem espaços nas pontas e em minúsculas),
     * que é a chave do contador (AC-003.11).
     *
     * @throws NullPointerException se {@code email} for nulo
     * @throws IllegalArgumentException se {@code email} for vazio ou só espaços
     */
    public static InviteCodeAttempts forEmail(String email, Clock clock) {
        Objects.requireNonNull(email, "email é obrigatório");
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("email não pode ser vazio ou em branco");
        }
        InviteCodeAttempts attempts = new InviteCodeAttempts();
        attempts.email = normalized;
        attempts.failedAttempts = 0;
        attempts.blockedUntil = null;
        attempts.autoResends = 0;
        attempts.updatedAt = clock.instant();
        return attempts;
    }

    public String getEmail() {
        return email;
    }

    /** Erros acumulados que valem agora, de 0 a 5: 5 durante o bloqueio, 0 a partir do fim dele. */
    public int failedAttempts(Clock clock) {
        return currentFailedAttempts(clock.instant());
    }

    /** Bloqueado enquanto {@code agora < fim do bloqueio} (AC-003.3). */
    public boolean isBlocked(Clock clock) {
        return isBlockedAt(clock.instant());
    }

    /** Fim do bloqueio enquanto bloqueado; {@code null} fora do bloqueio. */
    public Instant blockedUntil(Clock clock) {
        return isBlockedAt(clock.instant()) ? blockedUntil : null;
    }

    /** Reenvios automáticos já feitos sem uso de código, de 0 a 3 (AC-003.3). */
    public int getAutoResends() {
        return autoResends;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Código errado ou e-mail sem convite (AC-004.7). Da 1ª à 4ª tentativa devolve quantas restam;
     * na 5ª bloqueia por 30 minutos (AC-003.3). Durante o bloqueio devolve o mesmo resultado
     * bloqueado sem contar nem estender o bloqueio.
     */
    public InviteCodeAttemptOutcome registerFailedAttempt(Clock clock) {
        Instant now = clock.instant();
        if (isBlockedAt(now)) {
            return new InviteCodeAttemptOutcome(true, 0, blockedUntil);
        }
        int attempts = currentFailedAttempts(now) + 1;
        failedAttempts = attempts;
        updatedAt = now;
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntil = now.plus(BLOCK_DURATION);
            return new InviteCodeAttemptOutcome(true, 0, blockedUntil);
        }
        return new InviteCodeAttemptOutcome(false, MAX_FAILED_ATTEMPTS - attempts, null);
    }

    /**
     * Um código daquele e-mail foi usado com sucesso (convite {@code USADO}): zera o contador e o
     * limite de reenvios automáticos e cancela o reenvio automático devido (AC-003.11, D-34).
     *
     * @throws IllegalStateException durante o bloqueio, sem alterar nada: nenhum código pode ser
     *     usado enquanto o e-mail está bloqueado, mesmo certo (AC-004.7)
     */
    public void registerCodeUsed(Clock clock) {
        Instant now = clock.instant();
        if (isBlockedAt(now)) {
            throw new IllegalStateException(
                    "E-mail bloqueado por tentativas erradas; nenhum código pode ser usado"
                            + " (AC-004.7)");
        }
        failedAttempts = 0;
        blockedUntil = null;
        autoResends = 0;
        updatedAt = now;
    }

    /**
     * Um convite foi criado para o e-mail (envio, reenvio manual ou automático): zera o contador,
     * encerra o bloqueio na hora e cancela o reenvio automático devido (AC-003.9, AC-003.11). Não
     * mexe em {@code autoResends} (AC-003.3).
     */
    public void registerInvitationCreated(Clock clock) {
        failedAttempts = 0;
        blockedUntil = null;
        updatedAt = clock.instant();
    }

    /**
     * {@code true} quando um bloqueio terminou ({@code agora >= fim}) e o job (D-09 item 4) ainda
     * não o tratou.
     */
    public boolean isAutoResendDue(Clock clock) {
        return isAutoResendDueAt(clock.instant());
    }

    /**
     * O job trata o reenvio devido de um e-mail com convite {@code ENVIADO}: devolve {@code true} e
     * soma 1 em {@code autoResends} se ainda há menos de 3; {@code false} sem somar se o teto já foi
     * atingido (AC-003.3). Nos dois casos o reenvio deixa de estar devido.
     *
     * @throws IllegalStateException se não há reenvio devido, sem alterar nada
     */
    public boolean consumeAutoResend(Clock clock) {
        Instant now = clock.instant();
        settleAutoResend(now);
        if (autoResends >= MAX_AUTO_RESENDS) {
            return false;
        }
        autoResends++;
        return true;
    }

    /**
     * O job trata o reenvio devido de um e-mail <strong>sem</strong> convite {@code ENVIADO}
     * (AC-003.3: nada é enviado): o reenvio deixa de estar devido e {@code autoResends} não muda.
     *
     * @throws IllegalStateException se não há reenvio devido, sem alterar nada
     */
    public void dismissAutoResend(Clock clock) {
        settleAutoResend(clock.instant());
    }

    // ------------------------------------------------------------------ apoio

    private boolean isBlockedAt(Instant now) {
        return blockedUntil != null && now.isBefore(blockedUntil);
    }

    private boolean isAutoResendDueAt(Instant now) {
        return blockedUntil != null && !now.isBefore(blockedUntil);
    }

    /**
     * Fim do bloqueio verificado na leitura (D-09): com {@code blocked_until} já no passado e o
     * contador ainda nos 5 erros do ciclo bloqueado, valem 0 erros; erros do ciclo novo (contados
     * depois do fim) permanecem.
     */
    private int currentFailedAttempts(Instant now) {
        if (isAutoResendDueAt(now) && failedAttempts >= MAX_FAILED_ATTEMPTS) {
            return 0;
        }
        return failedAttempts;
    }

    /** O job tratou o reenvio devido: efetiva o fim do bloqueio e limpa a pendência. */
    private void settleAutoResend(Instant now) {
        if (!isAutoResendDueAt(now)) {
            throw new IllegalStateException("Não há reenvio automático devido para tratar");
        }
        failedAttempts = currentFailedAttempts(now);
        blockedUntil = null;
        updatedAt = now;
    }
}
