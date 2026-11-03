package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de {@link Membership} (T-023). {@link #findByUserId} devolve no máximo um
 * registro porque {@code memberships.user_id} é {@code UNIQUE} (D-02: zero ou uma membership por
 * conta) — usado por {@code br.com.cuidamais.auth.service.LoginService} para derivar o
 * {@code br.com.cuidamais.auth.domain.AccountState} de quem está entrando, via
 * {@code AccountState#of(User, Membership)}.
 */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    /**
     * Condição JPQL única de "vigência" (D-09): qualquer status passa, exceto {@code
     * AGUARDANDO_APROVACAO} com prazo esgotado ({@code expiresAt <= :now}). Compartilhada pela
     * listagem (T-043) e pela contagem do badge (T-044) para que as duas nunca divirjam.
     */
    String VIGENTE = " and (m.status <> br.com.cuidamais.membership.domain.MembershipStatus."
            + "AGUARDANDO_APROVACAO or m.expiresAt > :now)";

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

    /**
     * Página de membros de um grupo num status (T-043), ordenada por nome da conta sem diferenciar
     * caixa e depois por id. {@code AGUARDANDO_APROVACAO} com prazo esgotado ({@code now >=
     * expiresAt}, mesma regra de {@link Membership#approve}) é excluído mesmo antes do job (D-09).
     * O {@link Pageable} deve vir sem ordenação própria.
     */
    @Query(
            value = "select m from Membership m, User u where u.id = m.userId and m.groupId = :groupId"
                    + " and m.status = :status" + VIGENTE
                    + " order by lower(u.name), m.id",
            countQuery = "select count(m) from Membership m where m.groupId = :groupId"
                    + " and m.status = :status" + VIGENTE)
    Page<Membership> findMembersPage(
            @Param("groupId") UUID groupId,
            @Param("status") MembershipStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Conta os pedidos pendentes vigentes de um grupo (T-044, AC-006.1/AC-006.2): status {@code
     * AGUARDANDO_APROVACAO} com {@code now < expiresAt}, a mesma regra da listagem (D-09), sem
     * carregar entidades.
     */
    @Query("select count(m) from Membership m where m.groupId = :groupId"
            + " and m.status = br.com.cuidamais.membership.domain.MembershipStatus.AGUARDANDO_APROVACAO"
            + VIGENTE)
    long countPendingVigentes(@Param("groupId") UUID groupId, @Param("now") Instant now);
}
