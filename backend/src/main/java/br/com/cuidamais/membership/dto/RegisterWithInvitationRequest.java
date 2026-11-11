package br.com.cuidamais.membership.dto;

import br.com.cuidamais.shared.dto.AddressRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /invitations/register} (schema {@code RegisterWithInvitationRequest}):
 * passo 2 do convidado (AC-004.3). Não há campo de e-mail: ele vem sempre do {@code
 * registrationToken} (AC-004.2). Nome, telefone, endereço e senha seguem os mesmos limites do
 * cadastro comum (AC-001.3, AC-011.1, AC-011.2); falha de validação é 400 antes de qualquer
 * escrita, portanto sem consumir o token.
 */
public record RegisterWithInvitationRequest(
        @NotBlank(message = "Token de cadastro é obrigatório") String registrationToken,
        @NotBlank(message = "Nome é obrigatório")
                @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
                String name,
        @NotBlank(message = "Telefone é obrigatório")
                @Pattern(regexp = "\\d{10,11}", message = "Telefone deve ter DDD + 8 ou 9 dígitos, só números")
                String phone,
        @NotNull(message = "Endereço é obrigatório") @Valid AddressRequest address,
        @NotBlank(message = "Senha é obrigatória")
                @Size(min = 8, max = 128, message = "Senha deve ter entre 8 e 128 caracteres")
                @Pattern(
                        regexp = "(?=.*[A-Za-z])(?=.*\\d).+",
                        message = "Senha deve ter ao menos uma letra e um número")
                String password) {}
