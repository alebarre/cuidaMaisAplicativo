package br.com.cuidamais.group;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Corpo de sucesso de {@code POST /elders}, {@code GET /elders/{elderId}} e {@code PUT
 * /elders/{elderId}} (T-030), espelhando o schema {@code Elder} do contrato ({@code ElderInput}
 * mais {@code id}, {@code photoUrl} e {@code updatedAt}).
 *
 * <p>{@code photoUrl} é sempre {@code null} nesta tarefa — {@code Elder.photoPath} só é
 * preenchido pela T-031 (upload de foto), que também resolverá a URL pública a partir dele.
 */
public record ElderResponse(
        UUID id,
        String name,
        LocalDate birthDate,
        AddressResponse address,
        String allergies,
        String conditions,
        List<EmergencyContactResponse> emergencyContacts,
        String photoUrl,
        Instant updatedAt) {

    /**
     * Converte a entidade de domínio ({@link Elder}) e seus contatos (já carregados, em ordem —
     * {@code ElderRepository}/{@code EmergencyContactRepository}) para o formato do contrato.
     */
    public static ElderResponse of(Elder elder, List<EmergencyContact> contacts) {
        List<EmergencyContactResponse> contatos =
                contacts.stream().map(EmergencyContactResponse::of).toList();
        return new ElderResponse(
                elder.getId(),
                elder.getName(),
                elder.getBirthDate(),
                AddressResponse.of(elder.getAddress()),
                elder.getAllergies(),
                elder.getConditions(),
                contatos,
                null,
                elder.getUpdatedAt());
    }
}
