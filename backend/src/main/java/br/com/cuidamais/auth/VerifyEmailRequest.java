package br.com.cuidamais.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code POST /auth/verify-email} (T-022), espelhando o schema {@code
 * VerifyEmailRequest} do contrato: e-mail da conta a confirmar e o código de 6 dígitos recebido
 * por e-mail (AC-005.1). O padrão {@code ^\d{6}$} em {@code code} garante que corpos fora do
 * formato esperado (letras, tamanho diferente, espaços) sejam recusados como erro de validação
 * (400 {@code ValidationProblem}, T-007) sem nunca chegar a gastar uma tentativa do código OTP.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email,
        @NotBlank(message = "Código é obrigatório")
                @Pattern(regexp = "^\\d{6}$", message = "Código deve ter 6 dígitos")
                String code) {
}
