package br.com.cuidamais.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /auth/password-reset/confirm} (T-026, AC-010.3), espelhando o schema
 * {@code PasswordResetConfirmRequest} do contrato: e-mail da conta, código de 6 dígitos recebido
 * (AC-005.1) e a senha nova, já na mesma chamada (D-37 — não existe uma segunda etapa só para a
 * senha). {@code newPassword} segue exatamente as mesmas regras de {@link
 * SignupRequest#password()} (AC-001.3: mínimo 8 caracteres, com ao menos uma letra e um número).
 */
public record PasswordResetConfirmRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email,
        @NotBlank(message = "Código é obrigatório")
                @Pattern(regexp = "^\\d{6}$", message = "Código deve ter 6 dígitos")
                String code,
        @NotBlank(message = "Senha é obrigatória")
                @Size(min = 8, max = 128, message = "Senha deve ter entre 8 e 128 caracteres")
                @Pattern(
                        regexp = "(?=.*[A-Za-z])(?=.*\\d).+",
                        message = "Senha deve ter ao menos uma letra e um número")
                String newPassword) {
}
