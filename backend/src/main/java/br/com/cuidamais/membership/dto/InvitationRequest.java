package br.com.cuidamais.membership.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /elders/{elderId}/invitations} (schema {@code EmailOnly} do contrato): o
 * e-mail da pessoa a convidar (AC-003.1). Os espaços nas pontas são aparados já na construção,
 * para que a validação de formato não rejeite {@code "  Ana@X.com "}; a caixa baixa (D-34) é do
 * serviço.
 */
public record InvitationRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email) {

    /** Apara espaços nas pontas do e-mail antes da validação; nulo segue para o {@code @NotBlank}. */
    public InvitationRequest {
        email = email == null ? null : email.strip();
    }
}
