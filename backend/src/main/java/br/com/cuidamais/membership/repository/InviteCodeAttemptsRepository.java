package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência do contador de tentativas de código de convite por e-mail (D-34, AC-003.11),
 * cuja chave primária é o próprio e-mail normalizado. Usado ao criar/reenviar convites (zera o
 * contador) e ao listar (D-39: tentativas, bloqueio e reenvios automáticos).
 */
public interface InviteCodeAttemptsRepository extends JpaRepository<InviteCodeAttempts, String> {}
