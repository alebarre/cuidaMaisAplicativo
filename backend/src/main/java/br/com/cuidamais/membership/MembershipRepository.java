package br.com.cuidamais.membership;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link Membership} (T-023). {@link #findByUserId} devolve no máximo um
 * registro porque {@code memberships.user_id} é {@code UNIQUE} (D-02: zero ou uma membership por
 * conta) — usado por {@code br.com.cuidamais.auth.LoginService} para derivar o
 * {@code br.com.cuidamais.auth.AccountState} de quem está entrando, via
 * {@code AccountState#of(User, Membership)}.
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /**
     * Busca o único vínculo de uma conta com um grupo, se houver (D-02). {@code Optional.empty()}
     * é o caso normal de uma conta {@code ATIVO} sem grupo (AC-001.7).
     */
    Optional<Membership> findByUserId(UUID userId);
}
