package br.com.cuidamais.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Convite para ingresso em um grupo de cuidado, com a máquina de estados de AC-003.10 (D-07).
 *
 * <p>As transições de estado são métodos desta entidade e lançam {@link IllegalStateException}
 * quando a transição não é permitida a partir do estado atual — assim a regra de negócio é
 * testável inteiramente em memória, sem Spring nem banco. O {@link Clock} é sempre recebido por
 * parâmetro (D-10) para que os testes controlem o "agora" sem esperar.
 *
 * <p>O código de convite nunca é visto em texto por esta entidade: {@code codeHash} já chega em
 * hash (D-04) — o texto puro só existe fora dela, no momento do envio por e-mail.
 *
 * <p>Mapeada como {@code @Entity} sobre a tabela {@code invitations} (V1__foundation.sql, T-010)
 * para preparar a persistência de tarefas futuras; nenhuma tarefa deste pacote ainda cria
 * repositório ou expõe esta entidade em um controller.
 */
@Entity
@Table(name = "invitations")
public class Invitation {

    private static final Duration PRAZO_CONVITE = Duration.ofDays(7);

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "email", nullable = false, updatable = false, length = 320)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvitationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 10, updatable = false)
    private InvitationOrigin origin;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #send} ou {@link #resend}. */
    protected Invitation() {}

    /**
     * Responsável convida (AC-003.1) ou o job reenvia automaticamente (AC-003.3): nasce
     * {@code ENVIADO}, {@code createdAt = agora}, com o prazo de 7 dias de AC-003.2 para o
     * convite ser usado.
     */
    public static Invitation send(
            UUID groupId,
            String email,
            String codeHash,
            InvitationOrigin origin,
            UUID createdBy,
            Clock clock) {
        Instant agora = clock.instant();
        Invitation inv = new Invitation();
        inv.id = UUID.randomUUID();
        inv.groupId = groupId;
        inv.email = email;
        inv.codeHash = codeHash;
        inv.origin = origin;
        inv.createdBy = createdBy;
        inv.status = InvitationStatus.ENVIADO;
        inv.createdAt = agora;
        inv.expiresAt = agora.plus(PRAZO_CONVITE);
        return inv;
    }

    /**
     * Reenvio (D-31 / AC-003.4 / AC-003.12): {@code previous} precisa não estar {@code USADO}. Se
     * {@code previous} ainda está {@code ENVIADO} e dentro do prazo, ele é cancelado agora; se já
     * está {@code VENCIDO} ou {@code CANCELADO}, ou {@code ENVIADO} mas já efetivamente vencido
     * pelo tempo (D-09 — o job de expiração ainda não rodou, mas o prazo já passou), ele não é
     * alterado, para não distorcer o histórico marcando como cancelado algo que já venceu. Sempre
     * devolve um novo convite {@code ENVIADO}, para o mesmo grupo e e-mail do anterior.
     */
    public static Invitation resend(
            Invitation previous,
            String newCodeHash,
            InvitationOrigin origin,
            UUID createdBy,
            Clock clock) {
        if (previous.status == InvitationStatus.USADO) {
            throw new IllegalStateException(
                    "Não é possível reenviar um convite já USADO (D-31)");
        }
        if (previous.status == InvitationStatus.ENVIADO
                && clock.instant().isBefore(previous.expiresAt)) {
            previous.cancel(clock);
        }
        return send(previous.groupId, previous.email, newCodeHash, origin, createdBy, clock);
    }

    /**
     * E-mail e código confirmados no cadastro (AC-004.3 / AC-004.6): só é aceito a partir de
     * {@code ENVIADO} e dentro do prazo de 7 dias (AC-003.2) — um convite já vencido falha mesmo
     * que o job de expiração (D-09) ainda não tenha atualizado o status. Uso único: grava
     * {@code usedAt} e vira {@code USADO}.
     */
    public void use(Clock clock) {
        if (status != InvitationStatus.ENVIADO) {
            throw new IllegalStateException("Só é possível usar um convite ENVIADO");
        }
        if (!clock.instant().isBefore(expiresAt)) {
            throw new IllegalStateException("O convite já venceu (AC-003.2)");
        }
        this.usedAt = clock.instant();
        this.status = InvitationStatus.USADO;
    }

    /**
     * Job agendado (D-09) marca como vencido um convite sem uso depois de 7 dias (AC-003.2). Só é
     * aceito a partir de {@code ENVIADO} e depois do prazo já ter passado.
     */
    public void expire(Clock clock) {
        if (status != InvitationStatus.ENVIADO) {
            throw new IllegalStateException("Só é possível expirar um convite ENVIADO");
        }
        if (clock.instant().isBefore(expiresAt)) {
            throw new IllegalStateException("O prazo de 7 dias ainda não venceu (AC-003.2)");
        }
        this.status = InvitationStatus.VENCIDO;
    }

    /** Responsável cancela o convite (AC-003.4). Só é aceito a partir de {@code ENVIADO}. */
    public void cancel(Clock clock) {
        if (status != InvitationStatus.ENVIADO) {
            throw new IllegalStateException("Só é possível cancelar um convite ENVIADO");
        }
        this.cancelledAt = clock.instant();
        this.status = InvitationStatus.CANCELADO;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public InvitationOrigin getOrigin() {
        return origin;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
