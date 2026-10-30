package br.com.cuidamais.notification.event;

/** Lembrete, 7 dias antes da exclusão, para concluir o cadastro do idoso (AC-014.3). */
public record ElderReminderEmail(String to) implements EmailEvent {}
