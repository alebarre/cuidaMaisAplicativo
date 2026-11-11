package br.com.cuidamais.membership.dto;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.shared.dto.AddressResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * Membro do grupo como a lista o enxerga (schema {@code Member} do contrato, T-043). Campos
 * {@code null} são omitidos do JSON ({@code NON_NULL}): é assim que {@code email}, {@code phone}
 * e {@code address} ficam <strong>ausentes</strong> (e não {@code null}) quando a política de
 * visibilidade os nega (AC-011.5, AC-011.8, D-28), e que {@code approvalDeadline} só aparece
 * enquanto o pedido está {@code AGUARDANDO_APROVACAO} (AC-006.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberResponse(
        UUID id,
        UUID userId,
        String name,
        String email,
        String phone,
        Role role,
        boolean isAdmin,
        MembershipStatus status,
        AddressResponse address,
        Instant requestedAt,
        Instant approvalDeadline,
        Instant decidedAt,
        long version) {

    /**
     * Monta o item a partir do vínculo e da conta do membro.
     *
     * @param canSeeContact resultado da {@code MemberVisibilityPolicy} para (quem consulta, este
     *     membro); {@code false} omite e-mail, telefone e endereço
     */
    public static MemberResponse of(Membership membership, User user, boolean canSeeContact) {
        // 1. o prazo de aprovação só faz sentido enquanto o pedido está pendente.
        Instant deadline = membership.getStatus() == MembershipStatus.AGUARDANDO_APROVACAO
                ? membership.getExpiresAt()
                : null;
        // 2. contato (e-mail, telefone, endereço) só quando a política permite.
        return new MemberResponse(
                membership.getId(),
                membership.getUserId(),
                user.getName(),
                canSeeContact ? user.getEmail() : null,
                canSeeContact ? user.getPhone() : null,
                membership.getRole(),
                membership.isAdmin(),
                membership.getStatus(),
                canSeeContact ? AddressResponse.of(user.getAddress()) : null,
                membership.getRequestedAt(),
                deadline,
                membership.getDecidedAt(),
                membership.getVersion());
    }
}
