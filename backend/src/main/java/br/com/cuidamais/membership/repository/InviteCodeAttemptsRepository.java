package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência do contador de tentativas de código de convite por e-mail (D-34, AC-003.11),
 * cuja chave primária é o próprio e-mail normalizado. Usado ao criar/reenviar convites (zera o
 * contador), ao listar (D-39) e ao resgatar (T-041). Escritas concorrentes devem passar por
 * {@code InviteCodeAttemptsLocker}, que usa {@link #insertIfAbsent} + lock de linha.
 */
public interface InviteCodeAttemptsRepository extends JpaRepository<InviteCodeAttempts, String> {

    /**
     * Garante que a linha do e-mail exista (zerada, sem bloqueio) de forma atômica: se outra
     * transação a criar ao mesmo tempo, o {@code ON CONFLICT} evita violar a PK (R-03). Deve rodar
     * dentro de uma transação.
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value = "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends,"
                    + " updated_at) VALUES (:email, 0, NULL, 0, :now) ON CONFLICT (email) DO NOTHING")
    int insertIfAbsent(@Param("email") String email, @Param("now") Instant now);
}
