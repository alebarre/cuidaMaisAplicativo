package br.com.cuidamais.notification.event;

import java.time.Instant;

/**
 * Pedido pendente ao Responsável (AC-006.7): nome de quem pediu e prazo para decidir. Nada de
 * saúde (P5).
 */
public record PendingRequestEmail(String to, String requesterName, Instant deadline) implements EmailEvent {}
