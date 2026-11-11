package br.com.cuidamais.notification.event;

/** Aviso ao dono de um e-mail já cadastrado de que alguém tentou criar conta com ele (AC-001.4). */
public record DuplicateSignupEmail(String to) implements EmailEvent {}
