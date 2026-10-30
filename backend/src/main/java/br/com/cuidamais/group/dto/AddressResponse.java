package br.com.cuidamais.group.dto;

import br.com.cuidamais.shared.domain.Endereco;

/**
 * Corpo do objeto {@code address} nas respostas de {@link ElderResponse}/{@link
 * EmergencyContactResponse}, espelhando o schema {@code Address} do contrato (D-25) — os mesmos
 * 7 campos de {@link br.com.cuidamais.auth.dto.AddressRequest}, mas do lado da leitura (nenhuma
 * validação aqui, só conversão de {@link Endereco}).
 */
public record AddressResponse(
        String zipCode, String street, String number, String complement, String district, String city, String state) {

    /** Converte o objeto de valor de domínio ({@link Endereco}) para o formato do contrato. */
    public static AddressResponse of(Endereco endereco) {
        return new AddressResponse(
                endereco.zipCode(),
                endereco.street(),
                endereco.number(),
                endereco.complement(),
                endereco.district(),
                endereco.city(),
                endereco.state());
    }
}
