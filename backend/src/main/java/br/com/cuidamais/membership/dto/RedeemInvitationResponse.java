package br.com.cuidamais.membership.dto;

import java.time.Instant;

/**
 * Resposta 200 de {@code POST /invitations/redeem} (schema {@code RedeemInvitationResponse}).
 * Em {@code REGISTER} traz {@code registrationToken}/{@code expiresIn}; em {@code REINSTATED}
 * traz {@code approvalDeadline}. Só o primeiro nome do idoso é exposto (P5).
 */
public record RedeemInvitationResponse(
        String next,
        String registrationToken,
        Integer expiresIn,
        String email,
        String elderFirstName,
        Instant approvalDeadline) {

    /** Próximo passo {@code REGISTER}: o convidado segue ao formulário com o token de 15 min (AC-004.2). */
    public static RedeemInvitationResponse register(
            String token, int expiresInSeconds, String email, String elderFirstName) {
        return new RedeemInvitationResponse("REGISTER", token, expiresInSeconds, email, elderFirstName, null);
    }

    /** Próximo passo {@code REINSTATED}: conta antiga voltou a aguardar aprovação até o prazo (AC-004.6). */
    public static RedeemInvitationResponse reinstated(String email, String elderFirstName, Instant deadline) {
        return new RedeemInvitationResponse("REINSTATED", null, null, email, elderFirstName, deadline);
    }
}
