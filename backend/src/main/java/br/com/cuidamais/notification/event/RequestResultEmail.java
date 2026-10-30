package br.com.cuidamais.notification.event;

/**
 * Resultado do pedido à pessoa (AC-007.1); recusa e expiração orientam a pedir um novo convite
 * (AC-007.2).
 */
public record RequestResultEmail(String to, RequestResult result) implements EmailEvent {}
