package br.com.cuidamais.auth.repository;

import br.com.cuidamais.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link User} (T-020). {@link #findByEmail} espera o e-mail já normalizado para
 * minúsculas por quem chama, já que {@code users.email} é gravado em minúsculas (D-15, AC-001.4).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
