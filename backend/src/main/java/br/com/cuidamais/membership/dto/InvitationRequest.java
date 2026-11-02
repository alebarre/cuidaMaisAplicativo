package br.com.cuidamais.membership.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /elders/{elderId}/invitations} (schema {@code EmailOnly} do contrato): o
 * e-mail da pessoa a convidar (AC-003.1). A normalização (strip + minúsculas) é do serviço.
 */
public record InvitationRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email) {}
