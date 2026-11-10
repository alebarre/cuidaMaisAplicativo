package br.com.cuidamais.membership.dto;

import br.com.cuidamais.membership.domain.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo de {@code PATCH /elders/{elderId}/members/{memberId}} (schema {@code ChangeRoleRequest} do
 * contrato, AC-009.1). {@code role} é o novo papel do membro e só pode ser CUIDADOR ou FAMILIAR
 * (D-29): o enum {@link Role} não tem RESPONSAVEL, então pedir esse valor (ou qualquer outro
 * desconhecido, ou minúsculas) falha na desserialização e vira 400 — ninguém é promovido a
 * Responsável por esta rota (AC-009.4). Ausente ou nulo é rejeitado por {@link NotNull}.
 */
public record ChangeRoleRequest(@NotNull(message = "Papel é obrigatório") Role role) {}
