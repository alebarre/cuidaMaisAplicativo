package br.com.cuidamais.group.domain;

import br.com.cuidamais.shared.domain.Endereco;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Contato de emergência de um {@link Elder} (plan D-26: tabela própria, não JSONB, para permitir
 * índice/ordenação e evitar validação manual de estrutura livre).
 *
 * <p>Cada idoso pode ter de zero a dez contatos (AC-002.5, limite aplicado na validação do
 * {@code ElderRequest}, não aqui); {@code position} guarda a ordem em que foram informados no
 * cadastro/edição, preservada na resposta da API. {@code PUT /elders/{id}} substitui a lista
 * inteira (AC-002.3, AC-002.9): não há método de edição parcial de um contato existente, só
 * criação de uma linha nova para cada posição da lista enviada.
 *
 * <p>Mapeada como {@code @Entity} plana sobre a tabela {@code emergency_contacts}
 * (V1__foundation.sql, T-010), sem relação JPA para {@link Elder} (D-10, mesmo estilo de {@link
 * br.com.cuidamais.membership.domain.Membership}): {@code elderId} é só um {@code UUID}.
 */
@Entity
@Table(name = "emergency_contacts")
public class EmergencyContact {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "elder_id", nullable = false, updatable = false)
    private UUID elderId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "relationship", nullable = false)
    private String relationship;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Embedded
    @AttributeOverrides({
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

    @Column(name = "position", nullable = false)
    private int position;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #create}. */
    protected EmergencyContact() {}

    /**
     * Cria um contato de emergência do idoso, na posição informada (ordem de cadastro, AC-002.5).
     *
     * @param elderId idoso a quem este contato pertence
     * @param name nome do contato
     * @param relationship parentesco/relação com o idoso
     * @param phone telefone de contato (DDD + número, só dígitos)
     * @param address endereço do contato (D-25)
     * @param position posição deste contato na lista do idoso (índice na requisição)
     */
    public static EmergencyContact create(
            UUID elderId, String name, String relationship, String phone, Endereco address, int position) {
        EmergencyContact contact = new EmergencyContact();
        contact.id = UUID.randomUUID();
        contact.elderId = elderId;
        contact.name = name;
        contact.relationship = relationship;
        contact.phone = phone;
        contact.address = address;
        contact.position = position;
        return contact;
    }

    /** Identificador do contato, gerado em {@link #create}. */
    public UUID getId() {
        return id;
    }

    /** Idoso a quem este contato pertence. */
    public UUID getElderId() {
        return elderId;
    }

    /** Nome do contato. */
    public String getName() {
        return name;
    }

    /** Parentesco/relação do contato com o idoso. */
    public String getRelationship() {
        return relationship;
    }

    /** Telefone de contato. */
    public String getPhone() {
        return phone;
    }

    /** Endereço do contato (D-25). */
    public Endereco getAddress() {
        return address;
    }

    /** Posição deste contato na lista do idoso (ordem de cadastro/edição). */
    public int getPosition() {
        return position;
    }
}
