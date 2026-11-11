package br.com.cuidamais.membership.domain;

/**
 * Origem de um convite (D-34): {@code MANUAL} quando o Responsável convida ou reenvia pelo app;
 * {@code AUTO} quando o reenvio é disparado pelo job agendado (T-048) sem ação do Responsável.
 */
public enum InvitationOrigin {
    MANUAL,
    AUTO
}
