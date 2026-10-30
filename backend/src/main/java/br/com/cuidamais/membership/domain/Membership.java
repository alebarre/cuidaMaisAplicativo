package br.com.cuidamais.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Vínculo de uma pessoa com um grupo de cuidado, com a máquina de estados de AC-012.1 (D-07).
 *
 * <p>As transições de estado são métodos desta entidade e lançam {@link IllegalStateException}
 * (ou subclasse) quando a transição não é permitida a partir do estado atual — assim a regra de
 * negócio é testável inteiramente em memória, sem Spring nem banco. O {@link Clock} é sempre
 * recebido por parâmetro (D-10) para que os testes controlem o "agora" sem esperar.
 *
 * <p>Mapeada como {@code @Entity} sobre a tabela {@code memberships} (V1__foundation.sql, T-010)
 * para preparar a persistência de tarefas futuras; nenhuma tarefa deste pacote ainda cria
 * repositório ou expõe esta entidade em um controller.
 */
@Entity
@Table(name = "memberships")
public class Membership {

    private static final Duration PRAZO_APROVACAO = Duration.ofHours(24);

    /** Estados a partir dos quais um reconvite (AC-004.6) é aceito. */
    private static final Set<MembershipStatus> REINSTAURAVEL =
            Set.of(MembershipStatus.RECUSADO, MembershipStatus.EXPIRADO, MembershipStatus.REMOVIDO);

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Papel do membro no grupo (D-29). A coluna {@code memberships.role} é {@code NOT NULL}
     * (V1__foundation.sql), mas o papel só é de fato escolhido pelo Responsável na aprovação
     * (AC-006.3) ou de novo a cada reconvite (AC-004.6) — enquanto a membership está
     * {@code AGUARDANDO_APROVACAO} este campo guarda um valor <strong>provisório</strong>
     * ({@link Role#FAMILIAR}) sem nenhum significado de negócio, só para satisfazer o
     * {@code NOT NULL} do banco sem reabrir a migração já aprovada da T-010. {@link
     * #approve(Role, UUID, Clock)} sempre sobrescreve esse valor pelo papel realmente escolhido;
     * nada lê este campo como válido antes de {@code status == ATIVO}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MembershipStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #request} ou {@link #admin}. */
    protected Membership() {}

    /**
     * Convidado conclui o cadastro (AC-004.3): nasce {@code AGUARDANDO_APROVACAO}, sem
     * administração, com o prazo de 24 h de AC-006.5 para o Responsável decidir.
     */
    public static Membership request(UUID groupId, UUID userId, Clock clock) {
        Instant agora = clock.instant();
        Membership m = new Membership();
        m.id = UUID.randomUUID();
        m.groupId = groupId;
        m.userId = userId;
        m.role = Role.FAMILIAR; // provisório — ver Javadoc do campo `role`.
        m.admin = false;
        m.status = MembershipStatus.AGUARDANDO_APROVACAO;
        m.requestedAt = agora;
        m.expiresAt = agora.plus(PRAZO_APROVACAO);
        return m;
    }

    /**
     * Membership do Responsável que cria o grupo (D-05, D-29): nasce direto {@code ATIVO},
     * papel {@link Role#FAMILIAR} e com administração — não passa por aprovação.
     */
    public static Membership admin(UUID groupId, UUID userId, Clock clock) {
        Instant agora = clock.instant();
        Membership m = new Membership();
        m.id = UUID.randomUUID();
        m.groupId = groupId;
        m.userId = userId;
        m.role = Role.FAMILIAR;
        m.admin = true;
        m.status = MembershipStatus.ATIVO;
        m.requestedAt = agora;
        return m;
    }

    /**
     * Responsável aprova o pedido (AC-006.3), escolhendo o papel definitivo. Só é aceito a
     * partir de {@code AGUARDANDO_APROVACAO} e dentro do prazo de 24 h (AC-006.8): um pedido já
     * vencido falha mesmo que o job de expiração (D-09) ainda não tenha atualizado o status.
     */
    public void approve(Role role, UUID decidedBy, Clock clock) {
        Objects.requireNonNull(role, "role é obrigatório para aprovar (AC-006.3)");
        requirePendenteDentroDoPrazo(clock);
        this.role = role;
        this.decidedBy = decidedBy;
        this.decidedAt = clock.instant();
        this.status = MembershipStatus.ATIVO;
    }

    /**
     * Responsável recusa o pedido (AC-006.4). Mesma janela de validade de {@link #approve}
     * (AC-006.8).
     */
    public void reject(UUID decidedBy, Clock clock) {
        requirePendenteDentroDoPrazo(clock);
        this.decidedBy = decidedBy;
        this.decidedAt = clock.instant();
        this.status = MembershipStatus.RECUSADO;
    }

    /**
     * Job agendado (D-09) marca como vencido um pedido sem decisão depois de 24 h (AC-006.5). Só
     * é aceito a partir de {@code AGUARDANDO_APROVACAO} e depois do prazo já ter passado.
     */
    public void expire(Clock clock) {
        if (status != MembershipStatus.AGUARDANDO_APROVACAO) {
            throw new IllegalStateException(
                    "Só é possível expirar um pedido AGUARDANDO_APROVACAO");
        }
        if (clock.instant().isBefore(expiresAt)) {
            throw new IllegalStateException("O prazo de 24 h ainda não venceu (AC-006.5)");
        }
        this.status = MembershipStatus.EXPIRADO;
    }

    /**
     * Responsável remove um membro ativo (AC-009.2). O próprio Responsável nunca pode ser
     * removido (AC-009.4).
     */
    public void remove(Clock clock) {
        requireAtivoSemAdministracao();
        this.status = MembershipStatus.REMOVIDO;
    }

    /**
     * Responsável troca o papel de um membro ativo (AC-009.1). O papel do Responsável nunca é
     * alterado por aqui (AC-009.4) — este é o único método de troca de papel, e não existe
     * sobrecarga que receba uma flag de administração.
     */
    public void changeRole(Role novoRole) {
        requireAtivoSemAdministracao();
        Objects.requireNonNull(novoRole, "role é obrigatório para trocar o papel (AC-009.1)");
        this.role = novoRole;
    }

    /**
     * Reconvite (AC-004.6): volta um membro recusado, expirado ou removido para
     * {@code AGUARDANDO_APROVACAO}, recomeçando o prazo de 24 h. O Responsável escolhe o papel
     * de novo na próxima aprovação.
     */
    public void reinstate(Clock clock) {
        if (!REINSTAURAVEL.contains(status)) {
            throw new IllegalStateException(
                    "Só é possível reinstaurar a partir de RECUSADO, EXPIRADO ou REMOVIDO"
                            + " (AC-004.6)");
        }
        Instant agora = clock.instant();
        this.requestedAt = agora;
        this.expiresAt = agora.plus(PRAZO_APROVACAO);
        this.status = MembershipStatus.AGUARDANDO_APROVACAO;
    }

    private void requirePendenteDentroDoPrazo(Clock clock) {
        if (status != MembershipStatus.AGUARDANDO_APROVACAO) {
            throw new IllegalStateException(
                    "Só é possível decidir um pedido AGUARDANDO_APROVACAO");
        }
        if (!clock.instant().isBefore(expiresAt)) {
            throw new IllegalStateException("O pedido já venceu (AC-006.8)");
        }
    }

    private void requireAtivoSemAdministracao() {
        if (status != MembershipStatus.ATIVO) {
            throw new IllegalStateException("Só é possível agir sobre uma membership ATIVA");
        }
        if (admin) {
            throw new IllegalStateException(
                    "O Responsável do grupo nunca é removido nem tem o papel alterado (AC-009.4)");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAdmin() {
        return admin;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public long getVersion() {
        return version;
    }
}
