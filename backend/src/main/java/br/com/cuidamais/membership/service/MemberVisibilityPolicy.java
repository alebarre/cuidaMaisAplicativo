package br.com.cuidamais.membership.service;

import br.com.cuidamais.membership.domain.Role;
import java.util.UUID;

/**
 * Política de visibilidade de contato entre membros do grupo de cuidado (D-28).
 *
 * <p>Regra única, aplicada de uma só vez a endereço, telefone e e-mail (AC-011.5, AC-011.8): o
 * viewer vê o contato de um membro se ele é o próprio alvo, ou se o viewer é {@link Role#FAMILIAR}
 * (papel que inclui o Responsável, D-29). O papel do alvo não entra na fórmula.
 *
 * <p>Classe sem estado: não é {@code @Entity} nem bean Spring, apenas lógica de domínio pura e
 * testável (D-07).
 */
public final class MemberVisibilityPolicy {

    private MemberVisibilityPolicy() {}

    /** Quem está consultando o contato: identificador (para "é o próprio") e papel. */
    public record Viewer(UUID userId, Role role) {}

    /**
     * Decide se {@code viewer} pode ver o endereço, telefone e e-mail de {@code targetUserId}
     * (AC-011.5, AC-011.8): é o próprio, ou o viewer é {@link Role#FAMILIAR}.
     */
    public static boolean canSeeContact(Viewer viewer, UUID targetUserId) {
        return viewer.userId().equals(targetUserId) || viewer.role() == Role.FAMILIAR;
    }
}
