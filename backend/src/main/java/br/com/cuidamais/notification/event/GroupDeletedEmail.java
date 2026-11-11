package br.com.cuidamais.notification.event;

/**
 * Grupo excluído e dados apagados (AC-013.3). O endereço é coletado <strong>antes</strong> do
 * apagamento (D-23).
 */
public record GroupDeletedEmail(String to) implements EmailEvent {}
