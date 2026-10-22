package br.com.cuidamais.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code POST /auth/resend-code} (T-026, AC-001.6, AC-005.3), espelhando o schema
 * {@code ResendCodeRequest} do contrato. {@code purpose} só aceita os dois valores em inglês do
 * contrato ({@code EMAIL_VERIFICATION}/{@code PASSWORD_RESET}) — qualquer outro valor (inclusive
 * os nomes do enum Java {@link OtpPurpose}, caixa diferente ou vazio) é recusado aqui como erro de
 * validação (400 {@code ValidationProblem}), antes mesmo de chegar ao serviço. O mapeamento para
 * {@link OtpPurpose} é responsabilidade do serviço ({@code ResendCodeService}), não deste DTO.
 */
public record ResendCodeRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email,
        @NotBlank(message = "Finalidade é obrigatória")
                @Pattern(
                        regexp = "^(EMAIL_VERIFICATION|PASSWORD_RESET)$",
                        message = "Finalidade deve ser EMAIL_VERIFICATION ou PASSWORD_RESET")
                String purpose) {
}
