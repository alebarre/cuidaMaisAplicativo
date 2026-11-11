package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
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

    /**
     * Trecho JPQL (T-049, AC-014.1) da condição "membership recusada/expirada com a base de
     * retenção vencida": status {@code RECUSADO}/{@code EXPIRADO} e {@code retention_deadline <=
     * :agora}, ou, no legado sem prazo gravado, {@code decided_at <= :corte} (decided_at + 30 d <=
     * agora). Sem nenhuma das duas datas a conta nunca é elegível. Alias da membership: {@code m}.
     */
    String NAO_APROVADA_COM_BASE_VENCIDA = " m.status in ("
            + "br.com.cuidamais.membership.domain.MembershipStatus.RECUSADO,"
            + "br.com.cuidamais.membership.domain.MembershipStatus.EXPIRADO)"
            + " and (m.retentionDeadline <= :agora or (m.retentionDeadline is null and m.decidedAt <= :corte))";

    /**
     * Início do trecho JPQL "nenhum convite do e-mail segura o prazo" (AC-014.1, D-09 T-049); o
     * chamador concatena a expressão do e-mail e {@link #FIM_SEM_CONVITE_QUE_SEGURA}. Um convite
     * segura a conta se foi criado depois do corte, ou cancelado depois do corte, ou se está
     * {@code ENVIADO}/{@code VENCIDO} com {@code expiresAt} depois do corte ({@code corte = agora
     * - 30 d}; {@code CANCELADO} conta só pelo {@code cancelledAt}). Vale para qualquer grupo.
     */
    String INICIO_SEM_CONVITE_QUE_SEGURA =
            " and not exists (select 1 from Invitation i where i.email = ";

    /** Fim do trecho iniciado em {@link #INICIO_SEM_CONVITE_QUE_SEGURA}. */
    String FIM_SEM_CONVITE_QUE_SEGURA = " and (i.createdAt > :corte or i.cancelledAt > :corte"
            + " or (i.status in ('ENVIADO', 'VENCIDO') and i.expiresAt > :corte)))";

    /**
     * Consulta (T-049, sem lock) dos e-mails das contas elegíveis à exclusão por retenção: membership
     * {@code RECUSADO}/{@code EXPIRADO} com base vencida e nenhum convite do e-mail segurando o prazo
     * ({@link #NAO_APROVADA_COM_BASE_VENCIDA}, {@link #INICIO_SEM_CONVITE_QUE_SEGURA}). Ordenada por
     * e-mail (ordem determinística de locks, D-42) e limitada por {@code limit}; o mesmo critério no
     * filtro evita que linhas inelegíveis ocupem as vagas da rodada. A rotina reconfere sob lock.
     *
     * @param agora instante atual do relógio do contexto
     * @param corte {@code agora - 30 dias}
     */
    @Query("select u.email from Membership m, User u where u.id = m.userId and"
            + NAO_APROVADA_COM_BASE_VENCIDA + INICIO_SEM_CONVITE_QUE_SEGURA + "u.email"
            + FIM_SEM_CONVITE_QUE_SEGURA + " order by u.email")
    List<String> findEmailsElegiveisRetencao(
            @Param("agora") Instant agora, @Param("corte") Instant corte, Limit limit);
}
