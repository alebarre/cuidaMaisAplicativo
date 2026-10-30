package br.com.cuidamais.group.dto;

import br.com.cuidamais.auth.dto.AddressRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Corpo de {@code POST /elders} e {@code PUT /elders/{elderId}} (contrato, schema {@code
 * ElderInput}): os três campos obrigatórios do idoso (AC-002.2: nome, nascimento, endereço) mais
 * alergias/condições opcionais (AC-002.8, D-36, limite de 500 caracteres) e a lista de contatos
 * de emergência (AC-002.5, zero a dez; o {@code PUT} a usa como substituição completa, D-36).
 *
 * <p>{@code birthDate} usa {@code @PastOrPresent} (AC-002.7 "não pode ser futura"; "hoje" ainda é
 * aceito). Uma data sintaticamente inválida (ex. {@code "2024-02-30"}) nunca chega a esta
 * validação: falha antes, na desserialização do Jackson — ver o
 * {@code @ExceptionHandler(HttpMessageNotReadableException.class)} novo em
 * {@code GlobalExceptionHandler} (gap documentado pela T-030).
 */
public record ElderRequest(
        @NotBlank(message = "Nome é obrigatório")
                @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
                String name,
        @NotNull(message = "Data de nascimento é obrigatória")
                @PastOrPresent(message = "Data de nascimento não pode ser futura")
                LocalDate birthDate,
        @NotNull(message = "Endereço é obrigatório") @Valid AddressRequest address,
        @Size(max = 500, message = "Alergias deve ter no máximo 500 caracteres") String allergies,
        @Size(max = 500, message = "Condições deve ter no máximo 500 caracteres") String conditions,
        @Size(max = 10, message = "No máximo 10 contatos de emergência") @Valid
                List<EmergencyContactRequest> emergencyContacts) {

    /**
     * Normaliza {@code emergencyContacts} ausente do JSON para lista vazia (o contrato declara
     * {@code default: []}, mas o Jackson deixa o campo {@code null} quando omitido) — assim o
     * resto do código (serviço, {@code @Size(max = 10)}) nunca precisa verificar nulidade.
     */
    public ElderRequest {
        if (emergencyContacts == null) {
            emergencyContacts = List.of();
        }
    }
}
