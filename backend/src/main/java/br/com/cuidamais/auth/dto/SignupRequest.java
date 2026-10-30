package br.com.cuidamais.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /auth/signup} (T-020), espelhando o schema {@code SignupRequest} do
 * contrato. {@code @Valid} em cascata em {@code address} para que a validação de {@link
 * AddressRequest} produza {@code errors[].field} prefixado com {@code "address."} (ex.
 * {@code "address.zipCode"}).
 */
public record SignupRequest(
        @NotBlank(message = "Nome é obrigatório")
                @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
                String name,
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                @Size(max = 254, message = "E-mail deve ter no máximo 254 caracteres")
                String email,
        @NotBlank(message = "Telefone é obrigatório")
                @Pattern(regexp = "\\d{10,11}", message = "Telefone deve ter DDD + 8 ou 9 dígitos, só números")
                String phone,
        @NotNull(message = "Endereço é obrigatório") @Valid AddressRequest address,
        @NotBlank(message = "Senha é obrigatória")
                @Size(min = 8, max = 128, message = "Senha deve ter entre 8 e 128 caracteres")
                @Pattern(
                        regexp = "(?=.*[A-Za-z])(?=.*\\d).+",
                        message = "Senha deve ter ao menos uma letra e um número")
                String password) {
}
