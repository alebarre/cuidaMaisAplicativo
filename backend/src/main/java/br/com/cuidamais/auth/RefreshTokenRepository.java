package br.com.cuidamais.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link RefreshToken} (T-021). Além do CRUD padrão, o {@link RefreshTokenService}
 * precisa de duas buscas específicas: localizar a linha a partir do hash do token apresentado no
 * cookie ({@link #findByTokenHash}) e localizar todas as linhas de uma família para a revogação
 * em cascata no reuso (D-11, {@link #findByFamilyId}).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Localiza a linha cujo {@code token_hash} é exatamente o hash informado (busca por igualdade, D-04). */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Todas as linhas de uma mesma família de rotação (D-11), usada para revogar a família inteira no reuso. */
    List<RefreshToken> findByFamilyId(UUID familyId);
}
