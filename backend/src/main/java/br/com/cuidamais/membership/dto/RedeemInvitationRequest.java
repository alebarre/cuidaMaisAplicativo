package br.com.cuidamais.membership.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo de {@code POST /invitations/redeem} (schema {@code RedeemInvitationRequest}): e-mail e
 * código de 6 dígitos. O e-mail tem as pontas aparadas já na construção, para que "  Ana@X.com "
 * seja o mesmo e-mail do contador e do bloqueio (D-34 esclarecimento 1); a caixa baixa é aplicada
 * pelo serviço. Formato inválido gera 400 de validação sem tocar o contador.
 */
public record RedeemInvitationRequest(
        @NotBlank(message = "E-mail é obrigatório") @Email(message = "E-mail deve ter um formato válido")
                String email,
        @NotBlank(message = "Código é obrigatório")
                @Pattern(regexp = "^\\d{6}$", message = "O código deve ter 6 dígitos")
                String code) {

    /** Apara espaços nas pontas do e-mail antes da validação; nulo segue para o {@code @NotBlank}. */
    public RedeemInvitationRequest {
        email = email == null ? null : email.strip();
    }
}
