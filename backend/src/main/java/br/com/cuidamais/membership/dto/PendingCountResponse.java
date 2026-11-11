package br.com.cuidamais.membership.dto;

/**
 * Resposta de {@code GET /elders/{elderId}/members/pending-count} (T-044, AC-006.1): apenas a
 * quantidade de pedidos aguardando aprovação com prazo vigente, para o badge do Responsável.
 * Nunca carrega dados de membros (P5).
 *
 * @param count número de pedidos pendentes vigentes (&gt;= 0)
 */
public record PendingCountResponse(long count) {}
