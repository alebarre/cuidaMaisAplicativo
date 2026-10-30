package br.com.cuidamais.group;

/**
 * Corpo de um item de {@code emergencyContacts} nas respostas de {@link ElderResponse},
 * espelhando o schema {@code EmergencyContact} do contrato — os mesmos 4 campos de {@link
 * EmergencyContactRequest}, do lado da leitura.
 */
public record EmergencyContactResponse(String name, String relationship, String phone, AddressResponse address) {

    /** Converte a entidade de domínio ({@link EmergencyContact}) para o formato do contrato. */
    public static EmergencyContactResponse of(EmergencyContact contact) {
        return new EmergencyContactResponse(
                contact.getName(), contact.getRelationship(), contact.getPhone(), AddressResponse.of(contact.getAddress()));
    }
}
