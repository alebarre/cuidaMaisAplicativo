package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.Membership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link Membership} (T-023). {@link #findByUserId} devolve no máximo um
 * registro porque {@code memberships.user_id} é {@code UNIQUE} (D-02: zero ou uma membership por
 * conta) — usado por {@code br.com.cuidamais.auth.service.LoginService} para derivar o
 * {@code br.com.cuidamais.auth.domain.AccountState} de quem está entrando, via
 * {@code AccountState#of(User, Membership)}.
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /**
     * Busca o único vínculo de uma conta com um grupo, se houver (D-02). {@code Optional.empty()}
     * é o caso normal de uma conta {@code ATIVO} sem grupo (AC-001.7).
     */
    Optional<Membership> findByUserId(UUID userId);

    /**
     * Busca TODAS as memberships de um grupo, em qualquer status (T-033, D-38) — ao contrário de
     * {@link #findByUserId}, que é sempre no máximo uma por conta. Usado por {@code
     * ElderService#deleteCareGroup} para coletar, antes do apagamento, a lista completa de contas
     * do grupo (Responsável + membros em {@code ATIVO}/{@code AGUARDANDO_APROVACAO}/{@code
     * RECUSADO}/{@code EXPIRADO}/{@code REMOVIDO}) que precisam ter o {@code User} apagado e
     * receber o e-mail de aviso de AC-013.3.
     */
    List<Membership> findByGroupId(UUID groupId);
}
