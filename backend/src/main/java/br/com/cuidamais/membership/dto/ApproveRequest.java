package br.com.cuidamais.membership.dto;

import br.com.cuidamais.membership.domain.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo de {@code POST /elders/{elderId}/members/{memberId}/approve} (schema {@code ApproveRequest}
 * do contrato). {@code role} é o papel escolhido pelo Responsável (AC-006.3) e só pode ser
 * CUIDADOR ou FAMILIAR (o enum {@link Role} não tem outro valor; valor desconhecido falha na
 * desserialização e vira 400). {@code version} é a versão que o Responsável viu, base do lock
 * otimista (D-08).
 */
public record ApproveRequest(
        @NotNull(message = "Papel é obrigatório") Role role,
        @NotNull(message = "Versão é obrigatória") Long version) {}
