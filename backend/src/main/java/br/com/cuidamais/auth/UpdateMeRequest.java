package br.com.cuidamais.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code PUT /me} (T-032), espelhando o schema {@code UpdateMeRequest} do contrato:
 * só os dois campos que AC-011.6 permite editar "a qualquer momento" — {@code name}/{@code
 * email} não aparecem aqui de propósito, continuam fora do alcance deste endpoint.
 *
 * <p>{@code @Valid} em cascata em {@code address} pelo mesmo motivo de {@link SignupRequest}:
 * produzir {@code errors[].field} prefixado com {@code "address."} (ex. {@code
 * "address.city"}).
 */
public record UpdateMeRequest(
        @NotBlank(message = "Telefone é obrigatório")
                @Pattern(regexp = "\\d{10,11}", message = "Telefone deve ter DDD + 8 ou 9 dígitos, só números")
                String phone,
        @NotNull(message = "Endereço é obrigatório") @Valid AddressRequest address) {
}
