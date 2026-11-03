package br.com.cuidamais.membership.dto;

import java.time.Instant;

/**
 * Resposta 201 de {@code POST /invitations/register} (schema {@code AccessRequestCreated},
 * AC-004.4): o pedido de acesso ficou {@code AGUARDANDO_APROVACAO} até {@code approvalDeadline}
 * (24 h). Não expõe dado de saúde nem identificadores (P5).
 */
public record AccessRequestCreatedResponse(String status, Instant approvalDeadline) {

    /** Resposta de um pedido recém-criado, sempre no estado AGUARDANDO_APROVACAO. */
    public static AccessRequestCreatedResponse of(Instant approvalDeadline) {
        return new AccessRequestCreatedResponse("AGUARDANDO_APROVACAO", approvalDeadline);
    }
}
