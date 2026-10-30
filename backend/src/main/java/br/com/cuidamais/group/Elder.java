package br.com.cuidamais.group;

import br.com.cuidamais.shared.Endereco;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * O idoso cuidado por um grupo (plan D-01): dados cadastrais, endereço e observações de saúde em
 * texto livre (alergias/condições, D-36), um por {@link CareGroup} (1:1, {@code
 * elders.group_id UNIQUE} — AC-002.1).
 *
 * <p>Nasce junto com o grupo em {@code POST /elders} (AC-001.2, T-030) e é só editado por
 * substituição completa em {@code PUT /elders/{id}} (AC-002.9, D-36): {@link #update} não guarda
 * nenhum histórico de versão, é um {@code UPDATE} simples dos campos atuais. A foto
 * ({@code photoPath}) é gravada/removida por {@link #updatePhoto}/{@link #removePhoto}
 * (AC-002.6, T-031) e guarda só o caminho do arquivo em disco — os bytes em si vivem fora do
 * banco ({@code ElderPhotoStorage}, D-30).
 *
 * <p>Mapeada como {@code @Entity} plana sobre a tabela {@code elders} (V1__foundation.sql,
 * T-010), sem relação JPA para {@code CareGroup}/{@code EmergencyContact} (D-10, mesmo estilo de
 * {@link br.com.cuidamais.membership.Membership}): {@code groupId} é só um {@code UUID}.
 */
@Entity
@Table(name = "elders")
public class Elder {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Embedded
    @AttributeOverrides({
        // Mesmo motivo documentado em User: zip_code/state são CHAR(8)/CHAR(2) na migração
        // (V1__foundation.sql), então columnDefinition precisa declarar "bpchar" (nome interno
        // do CHAR no catálogo do PostgreSQL) para o ddl-auto=validate não falhar (D-06).
        @AttributeOverride(
                name = "zipCode",
                column = @Column(name = "address_zip_code", columnDefinition = "bpchar(8)")),
        @AttributeOverride(name = "street", column = @Column(name = "address_street")),
        @AttributeOverride(name = "number", column = @Column(name = "address_number")),
        @AttributeOverride(name = "complement", column = @Column(name = "address_complement")),
        @AttributeOverride(name = "district", column = @Column(name = "address_district")),
        @AttributeOverride(name = "city", column = @Column(name = "address_city")),
        @AttributeOverride(
                name = "state",
                column = @Column(name = "address_state", columnDefinition = "bpchar(2)"))
    })
    private Endereco address;

    /** Única foto do idoso (AC-002.6); {@code null} enquanto não houver foto gravada. */
    @Column(name = "photo_path")
    private String photoPath;

    /** Texto livre até 500 caracteres (AC-002.8, D-36); {@code null} quando não informado. */
    @Column(name = "allergies", length = 500)
    private String allergies;

    /** Texto livre até 500 caracteres (AC-002.8, D-36); {@code null} quando não informado. */
    @Column(name = "conditions", length = 500)
    private String conditions;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #create}. */
    protected Elder() {}

    /**
     * Cadastra o idoso do grupo recém-criado (AC-001.2, AC-002.2, T-030).
     *
     * @param groupId grupo ao qual este idoso pertence (1:1, {@code elders.group_id UNIQUE})
     * @param name nome do idoso (obrigatório)
     * @param birthDate nascimento (obrigatório, validado como não futuro antes de chegar aqui —
     *     AC-002.7; o banco também reforça com {@code ck_elders_birth_date_not_future})
     * @param address endereço do idoso (obrigatório, D-25)
     * @param allergies alergias em texto livre, ou {@code null} (AC-002.8)
     * @param conditions condições de saúde em texto livre, ou {@code null} (AC-002.8)
     * @param clock relógio injetado (D-10) para {@code updatedAt}
     */
    public static Elder create(
            UUID groupId,
            String name,
            LocalDate birthDate,
            Endereco address,
            String allergies,
            String conditions,
            Clock clock) {
        Elder elder = new Elder();
        elder.id = UUID.randomUUID();
        elder.groupId = groupId;
        elder.name = name;
        elder.birthDate = birthDate;
        elder.address = address;
        elder.allergies = allergies;
        elder.conditions = conditions;
        elder.updatedAt = clock.instant();
        return elder;
    }

    /**
     * Substitui todos os dados editáveis do idoso (AC-002.3, AC-002.9, D-36): é um {@code UPDATE}
     * simples sobre a mesma linha — nenhum histórico/versão extra é gravado, por isso não há
     * método de "corrigir só um campo"; o {@code PUT} sempre manda o conjunto inteiro de novo.
     *
     * @param name novo nome
     * @param birthDate novo nascimento
     * @param address novo endereço
     * @param allergies novas alergias, ou {@code null}
     * @param conditions novas condições, ou {@code null}
     * @param clock relógio injetado (D-10) para o novo {@code updatedAt}
     */
    public void update(
            String name,
            LocalDate birthDate,
            Endereco address,
            String allergies,
            String conditions,
            Clock clock) {
        this.name = name;
        this.birthDate = birthDate;
        this.address = address;
        this.allergies = allergies;
        this.conditions = conditions;
        this.updatedAt = clock.instant();
    }

    /**
     * Grava a foto enviada pelo Responsável (AC-002.6, T-031, D-30): substitui, se houver, o
     * caminho da foto anterior — cada idoso tem no máximo uma foto, nunca um histórico delas.
     * Quem já apagou o arquivo antigo em disco (se existia) é {@code ElderService}, antes de
     * chamar este método; aqui só é atualizado o ponteiro guardado na entidade.
     *
     * @param photoPath caminho (relativo ao diretório de armazenamento, D-30) do arquivo recém
     *     gravado
     * @param clock relógio injetado (D-10) para o novo {@code updatedAt}
     */
    public void updatePhoto(String photoPath, Clock clock) {
        this.photoPath = photoPath;
        this.updatedAt = clock.instant();
    }

    /**
     * Remove a foto do idoso (AC-002.6, T-031, D-30): volta {@code photoPath} a {@code null},
     * fazendo {@code Elder.photoUrl} voltar a ser {@code null} nas respostas seguintes. Quem
     * apaga o arquivo em disco é {@code ElderService}, antes de chamar este método.
     *
     * @param clock relógio injetado (D-10) para o novo {@code updatedAt}
     */
    public void removePhoto(Clock clock) {
        this.photoPath = null;
        this.updatedAt = clock.instant();
    }

    /** Identificador do idoso, gerado em {@link #create}. */
    public UUID getId() {
        return id;
    }

    /** Grupo ao qual este idoso pertence (1:1). */
    public UUID getGroupId() {
        return groupId;
    }

    /** Nome do idoso. */
    public String getName() {
        return name;
    }

    /** Data de nascimento do idoso. */
    public LocalDate getBirthDate() {
        return birthDate;
    }

    /** Endereço do idoso (D-25). */
    public Endereco getAddress() {
        return address;
    }

    /**
     * Caminho (relativo ao diretório de armazenamento, {@code ElderPhotoStorage}, D-30) da única
     * foto do idoso; {@code null} enquanto nenhuma foto foi enviada ou depois de removida
     * (AC-002.6, T-031).
     */
    public String getPhotoPath() {
        return photoPath;
    }

    /** Alergias em texto livre; {@code null} quando não informadas. */
    public String getAllergies() {
        return allergies;
    }

    /** Condições de saúde em texto livre; {@code null} quando não informadas. */
    public String getConditions() {
        return conditions;
    }

    /** Última vez que os dados do idoso foram gravados (criação ou edição). */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
