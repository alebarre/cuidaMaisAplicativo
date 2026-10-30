package br.com.cuidamais.notification.event;

/**
 * Convite (AC-003.1): código de 6 dígitos, nome do Responsável e <strong>somente o primeiro
 * nome</strong> do idoso (P5).
 */
public record InvitationEmail(String to, String code, String responsibleName, String elderFirstName)
        implements EmailEvent {}
