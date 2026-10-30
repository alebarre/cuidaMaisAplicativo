package br.com.cuidamais.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Grupo de cuidado (plan D-01, D-05 rev. 2): o "contêiner" que une exatamente um idoso
 * ({@link Elder}, via {@code elders.group_id UNIQUE}) a um conjunto de {@code memberships}
 * (T-023) — o Responsável que o criou, mais Cuidadores/Familiares aprovados depois.
 *
 * <p>Nasce sempre junto com o idoso, numa única transação de {@code POST /elders}
 * (AC-001.2, AC-002.1, T-030): não existe rota para criar um grupo "vazio". {@link #create}
 * é o único construtor de domínio.
 *
 * <p>Mapeada como {@code @Entity} plana sobre a tabela {@code care_groups}
 * (V1__foundation.sql, T-010), sem relação JPA para {@code Elder}/{@code Membership} — as
 * colunas de ligação são {@code UUID} simples, no mesmo estilo de {@link
 * br.com.cuidamais.membership.Membership} (D-10).
 */
@Entity
@Table(name = "care_groups")
public class CareGroup {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #create}. */
    protected CareGroup() {}

    /**
     * Cria o grupo no momento do cadastro do idoso (AC-001.2, D-05 rev. 2): guarda quem o criou
     * (futuro Responsável administrador) e o instante de criação, vindo do relógio injetado
     * (D-10), nunca do relógio de sistema.
     *
     * @param createdBy conta que está cadastrando o idoso (vira o Responsável administrador)
     * @param clock relógio injetado para {@code createdAt}
     */
    public static CareGroup create(UUID createdBy, Clock clock) {
        CareGroup group = new CareGroup();
        group.id = UUID.randomUUID();
        group.createdBy = createdBy;
        group.createdAt = clock.instant();
        return group;
    }

    /** Identificador do grupo, gerado em {@link #create}. */
    public UUID getId() {
        return id;
    }

    /** Conta que cadastrou o idoso e criou o grupo (vira o Responsável administrador, D-05). */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /** Instante de criação do grupo. */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
