package br.com.cuidamais.membership.exception;

/**
 * Motivos estáveis de conflito (HTTP 409) nas rotas de decisão sobre membros (T-045). O nome do
 * enum é o {@code code} do Problem da resposta {@code MemberConflict} do contrato.
 */
public enum MemberConflictCode {
    /** O pedido não está mais aguardando aprovação (já ATIVO, RECUSADO ou REMOVIDO; AC-006.3/006.4). */
    REQUEST_ALREADY_DECIDED,
    /** A {@code version} enviada no approve não é a atual: alguém mexeu no pedido antes (D-08). */
    VERSION_CONFLICT,
    /** O alvo é o Responsável do grupo, que nunca é decidido/alterado/removido (AC-009.4). */
    ADMIN_PROTECTED
}
