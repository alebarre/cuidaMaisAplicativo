package br.com.cuidamais.membership.exception;

/**
 * Motivos estáveis de conflito (HTTP 409) nas rotas de convite. O nome do enum é o {@code code}
 * do Problem do contrato (InvitationConflictProblem e Problem das rotas resend/cancel).
 */
public enum InvitationConflictCode {
    /** Membro ATIVO ou com pedido em análise no próprio grupo (AC-003.5). */
    ALREADY_MEMBER,
    /** E-mail de outro grupo ou de conta sem grupo; mesma resposta nos dois casos (AC-003.7). */
    EMAIL_UNAVAILABLE,
    /** O grupo do Responsável ainda não tem idoso cadastrado (AC-002.4). */
    NO_ELDER,
    /** Convite USADO não pode ser reenviado (AC-003.4, D-31). */
    INVITATION_USED,
    /** Só convite ENVIADO pode ser cancelado (AC-003.4). */
    INVITATION_NOT_CANCELLABLE
}
