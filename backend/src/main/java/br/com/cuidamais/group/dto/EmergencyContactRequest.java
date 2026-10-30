package br.com.cuidamais.group.dto;

import br.com.cuidamais.auth.dto.AddressRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de um item de {@code emergencyContacts} em {@code ElderInput} (contrato, schema {@code
 * EmergencyContact}; AC-002.5: nome, parentesco, telefone e endereço são todos obrigatórios).
 *
 * <p>{@code @Valid} em cascata em {@code address} produz {@code errors[].field} prefixado (ex.
 * {@code "emergencyContacts[0].phone"}, convenção padrão do Spring Bean Validation para listas
 * anotadas com {@code @Valid} dentro de {@link ElderRequest}) — nenhum código extra precisa
 * montar esse prefixo, o {@code GlobalExceptionHandler} já existente já produz esse formato.
 */
public record EmergencyContactRequest(
        @NotBlank(message = "Nome é obrigatório")
                @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
                String name,
        @NotBlank(message = "Parentesco é obrigatório")
                @Size(min = 1, max = 40, message = "Parentesco deve ter entre 1 e 40 caracteres")
                String relationship,
        @NotBlank(message = "Telefone é obrigatório")
                @Pattern(regexp = "\\d{10,11}", message = "Telefone deve ter DDD + 8 ou 9 dígitos, só números")
                String phone,
        @NotNull(message = "Endereço é obrigatório") @Valid AddressRequest address) {
}
