package br.com.cuidamais.membership.domain;

/**
 * Estado de um convite de ingresso em um grupo de cuidado (tabela de AC-003.10, D-07).
 *
 * <p>Transições válidas (ver {@link Invitation}):
 *
 * <pre>
 * (não existe) --send()--&gt;   ENVIADO
 * ENVIADO      --use()--&gt;    USADO
 * ENVIADO      --expire()--&gt; VENCIDO
 * ENVIADO      --cancel()--&gt; CANCELADO
 * ENVIADO      --resend()--&gt; CANCELADO (e nasce um novo convite ENVIADO)
 * </pre>
 */
public enum InvitationStatus {
    ENVIADO,
    USADO,
    VENCIDO,
    CANCELADO
}
