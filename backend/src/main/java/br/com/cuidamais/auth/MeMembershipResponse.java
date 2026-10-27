package br.com.cuidamais.auth;

import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipStatus;
import br.com.cuidamais.membership.Role;
import java.util.UUID;

/**
 * Objeto {@code membership} de {@link MeResponse} (T-032), espelhando o sub-objeto homônimo do
 * schema {@code Me} do contrato: o vínculo da conta com o grupo, quando existe. Todos os campos
 * são preenchidos sempre que esta classe é instanciada (D-05: toda membership tem grupo e
 * idoso) — é {@link MeResponse} que decide, à parte, se este objeto existe ou é {@code null}.
 *
 * @param id identificador da membership
 * @param elderId idoso do grupo da membership
 * @param role papel do membro no grupo (D-29)
 * @param isAdmin se o membro é o Responsável administrador do grupo
 * @param status estado atual do vínculo (AC-012.1)
 */
public record MeMembershipResponse(UUID id, UUID elderId, Role role, boolean isAdmin, MembershipStatus status) {

    /**
     * Monta o sub-objeto a partir da membership já carregada e do {@code elderId} resolvido à
     * parte (mesmo padrão de {@code AccountStateResponse.of}: esta classe não busca nada, só
     * converte o que já foi lido).
     *
     * @param membership vínculo já persistido da conta
     * @param elderId idoso do grupo da membership
     */
    public static MeMembershipResponse of(Membership membership, UUID elderId) {
        return new MeMembershipResponse(
                membership.getId(), elderId, membership.getRole(), membership.isAdmin(), membership.getStatus());
    }
}
