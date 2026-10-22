package br.com.cuidamais.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link RefreshToken} (T-021). Além do CRUD padrão, o {@link RefreshTokenService}
 * precisa de três buscas específicas: localizar a linha a partir do hash do token apresentado no
 * cookie ({@link #findByTokenHash}), localizar todas as linhas de uma família para a revogação em
 * cascata no reuso (D-11, {@link #findByFamilyId}) e localizar <strong>todas</strong> as linhas de
 * uma conta, de todas as famílias, para a redefinição de senha (D-37, {@link #findByUserId},
 * T-026) — diferente de {@link #findByFamilyId}, que só alcança um aparelho.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Localiza a linha cujo {@code token_hash} é exatamente o hash informado (busca por igualdade, D-04). */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Todas as linhas de uma mesma família de rotação (D-11), usada para revogar a família inteira no reuso. */
    List<RefreshToken> findByFamilyId(UUID familyId);

    /**
     * Todas as linhas da conta, de <strong>todas</strong> as famílias (D-37) — usada por {@code
     * password-reset/confirm} (T-026, AC-010.4) para encerrar toda sessão da conta, não só a de
     * um aparelho.
     */
    List<RefreshToken> findByUserId(UUID userId);
}
