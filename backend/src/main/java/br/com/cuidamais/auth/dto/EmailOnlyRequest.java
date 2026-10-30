package br.com.cuidamais.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /auth/password-reset/request} (T-026, AC-010.1), espelhando o schema
 * {@code EmailOnly} do contrato: só o e-mail da conta que pede o código de redefinição de senha.
 * Resposta sempre neutra (D-15) — este DTO não distingue e-mail existente de inexistente, isso é
 * responsabilidade do serviço.
 */
public record EmailOnlyRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email) {
}
