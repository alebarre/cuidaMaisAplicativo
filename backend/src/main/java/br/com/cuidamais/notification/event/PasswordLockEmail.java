package br.com.cuidamais.notification.event;

import java.time.Instant;

/** Um único e-mail no momento em que o bloqueio por senha errada começa (AC-008.4). */
public record PasswordLockEmail(String to, Instant lockedUntil) implements EmailEvent {}
