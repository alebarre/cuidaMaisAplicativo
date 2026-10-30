package br.com.cuidamais.membership.domain;

import java.time.Instant;

/**
 * Resultado de uma tentativa errada de código de convite (AC-004.7, D-16), devolvido por {@link
 * InviteCodeAttempts#registerFailedAttempt(java.time.Clock)}.
 *
 * <ul>
 *   <li>{@code blocked = false}: a tentativa foi contada; {@code remainingAttempts} é o N de
 *       "Restam N tentativas" (4, 3, 2 ou 1) e {@code blockedUntil} é {@code null}.
 *   <li>{@code blocked = true}: o e-mail está bloqueado (5ª tentativa errada ou qualquer tentativa
 *       durante os 30 minutos); {@code remainingAttempts} é 0 e {@code blockedUntil} é o fim do
 *       bloqueio.
 * </ul>
 */
public record InviteCodeAttemptOutcome(boolean blocked, int remainingAttempts, Instant blockedUntil) {}
