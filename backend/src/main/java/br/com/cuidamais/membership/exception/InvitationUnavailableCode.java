package br.com.cuidamais.membership.exception;

/** Motivos estáveis do 410 de convite indisponível (AC-004.5); VENCIDO é {@code INVITATION_EXPIRED}. */
public enum InvitationUnavailableCode {
    INVITATION_EXPIRED,
    INVITATION_CANCELLED,
    INVITATION_USED
}
