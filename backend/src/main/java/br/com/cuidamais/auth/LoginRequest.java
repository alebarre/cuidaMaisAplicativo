package br.com.cuidamais.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /auth/login} (T-023), espelhando o schema {@code LoginRequest} do
 * contrato: e-mail e senha informados no formulário de entrada. Sem {@code @Pattern} de força de
 * senha aqui — essa regra é do cadastro (AC-001.3), não da tentativa de entrar: uma senha fraca
 * salva antigamente ainda precisa poder ser usada para logar.
 */
public record LoginRequest(
        @NotBlank(message = "E-mail é obrigatório")
                @Email(message = "E-mail deve ter um formato válido")
                String email,
        @NotBlank(message = "Senha é obrigatória") String password) {
}
