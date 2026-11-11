package br.com.cuidamais.notification.event;

/** E-mail curto de exclusão da conta por retenção (AC-014.1, AC-014.3). */
public record AccountDeletedEmail(String to) implements EmailEvent {}
