package br.com.cuidamais.membership.domain;

/**
 * Estado do vínculo de uma pessoa com um grupo de cuidado (tabela de AC-012.1, D-07).
 *
 * <p>Transições válidas (ver {@link Membership}):
 *
 * <pre>
 * (não existe) --request()--&gt; AGUARDANDO_APROVACAO
 * AGUARDANDO_APROVACAO --approve()--&gt; ATIVO
 * AGUARDANDO_APROVACAO --reject()--&gt;  RECUSADO
 * AGUARDANDO_APROVACAO --expire()--&gt;  EXPIRADO
 * ATIVO (não admin)    --remove()--&gt;  REMOVIDO
 * RECUSADO | EXPIRADO | REMOVIDO --reinstate()--&gt; AGUARDANDO_APROVACAO
 * </pre>
 */
public enum MembershipStatus {
    AGUARDANDO_APROVACAO,
    ATIVO,
    RECUSADO,
    EXPIRADO,
    REMOVIDO
}
