package br.com.cuidamais.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo do objeto {@code address} de {@link SignupRequest}, espelhando o schema {@code Address}
 * do contrato (D-25, AC-011.1, AC-011.2).
 *
 * <p>{@code state} é {@code String} (e não um enum Java) de propósito: um valor fora das 27
 * siglas num enum seria um erro de desserialização do Jackson (400 genérico, sem {@code
 * ValidationProblem}), e o contrato exige {@code errors[].field = "address.state"} igual aos
 * demais campos inválidos — por isso a enumeração das siglas vira {@code @Pattern}.
 */
public record AddressRequest(
        @NotBlank(message = "CEP é obrigatório")
                @Pattern(regexp = "\\d{8}", message = "CEP deve ter exatamente 8 dígitos numéricos")
                String zipCode,
        @NotBlank(message = "Rua é obrigatória") @Size(max = 150, message = "Rua deve ter no máximo 150 caracteres")
                String street,
        @NotBlank(message = "Número é obrigatório")
                @Pattern(
                        regexp = "(\\d{1,6}[A-Za-z]?|S/N)",
                        message = "Número deve ser dígitos (com letra opcional) ou \"S/N\"")
                String number,
        @Size(max = 80, message = "Complemento deve ter no máximo 80 caracteres") String complement,
        @NotBlank(message = "Bairro é obrigatório")
                @Size(max = 80, message = "Bairro deve ter no máximo 80 caracteres")
                String district,
        @NotBlank(message = "Cidade é obrigatória")
                @Size(max = 80, message = "Cidade deve ter no máximo 80 caracteres")
                String city,
        @NotBlank(message = "UF é obrigatória")
                @Pattern(
                        regexp =
                                "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO",
                        message = "UF deve ser uma das 27 unidades da federação")
                String state) {
}
