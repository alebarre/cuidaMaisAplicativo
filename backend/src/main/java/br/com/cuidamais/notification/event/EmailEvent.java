package br.com.cuidamais.notification.event;

/**
 * Evento de domínio que resulta em um e-mail (plan D-23). É <strong>selado</strong>: a lista de
 * tipos permitidos é a lista completa de e-mails da feature 000, e cada tipo é um {@code record}
 * que carrega <strong>somente</strong> o que o template precisa — nunca dados de saúde
 * (Constitution P5). Quem publica é a regra de negócio, via {@code
 * ApplicationEventPublisher.publishEvent(evento)} dentro da transação; o envio acontece depois do
 * commit.
 */
public sealed interface EmailEvent
        permits ConfirmationCodeEmail,
                DuplicateSignupEmail,
                InvitationEmail,
                PendingRequestEmail,
                RequestResultEmail,
                PasswordLockEmail,
                GroupDeletedEmail,
                ElderReminderEmail,
                AccountDeletedEmail {

    /** Endereço do destinatário. */
    String to();
}
