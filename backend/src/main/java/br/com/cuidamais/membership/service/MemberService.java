package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.dto.MemberPageResponse;
import br.com.cuidamais.membership.dto.MemberResponse;
import br.com.cuidamais.membership.dto.PendingCountResponse;
import br.com.cuidamais.membership.exception.MemberConflictCode;
import br.com.cuidamais.membership.exception.MemberConflictException;
import br.com.cuidamais.membership.exception.RequestExpiredException;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.RequestResult;
import br.com.cuidamais.notification.event.RequestResultEmail;
import br.com.cuidamais.shared.exception.NotAllowedException;
import br.com.cuidamais.shared.exception.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta da lista de membros do grupo (T-043): AC-006.2, AC-006.9, AC-009.5, AC-011.5 e
 * AC-011.8. Sem filtro lista só {@code ATIVO} para qualquer membro ativo; o filtro {@code status}
 * é exclusivo do Responsável (403 {@code NOT_ALLOWED} para os demais, D-32). Pedido pendente
 * vencido pelo relógio nunca aparece, sem depender do job (D-09). A visibilidade de contato vem
 * da {@link MemberVisibilityPolicy} (D-28), nunca reimplementada aqui.
 *
 * <p>Também decide pedidos de acesso (T-045): {@code approve} e {@code reject} (AC-006.3,
 * AC-006.4, AC-006.8, AC-007.1, AC-009.4). Prazo vencido responde 410 antes de qualquer 409; a
 * corrida entre duas decisões é resolvida pelo lock otimista (D-08) e o e-mail de resultado sai
 * só após o commit (D-23).
 */
@Service
public class MemberService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MembershipRepository memberships;
    private final ElderRepository elders;
    private final UserRepository users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /** Injeção por construtor dos repositórios e do relógio (D-10). */
    public MemberService(
            MembershipRepository memberships, ElderRepository elders, UserRepository users, Clock clock,
            ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.elders = elders;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Lista os membros do grupo do chamador, ordenados por nome (sem diferenciar caixa) e id.
     *
     * @param status filtro opcional, só aceito do Responsável
     * @throws ResourceNotFoundException {@code elderId} não é o idoso do grupo do chamador (D-32)
     * @throws NotAllowedException filtro {@code status} usado por quem não é o Responsável
     */
    @Transactional(readOnly = true)
    public MemberPageResponse list(UUID elderId, UUID callerUserId, MembershipStatus status, int page, int size) {
        // 1. grupo e vínculo do chamador; sem vínculo ou idoso diferente é 404 genérico (D-32).
        Membership viewerMembership = memberships.findByUserId(callerUserId)
                .orElseThrow(ResourceNotFoundException::new);
        Elder elder = elders.findByGroupId(viewerMembership.getGroupId())
                .orElseThrow(ResourceNotFoundException::new);
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }

        // 2. filtro status só do Responsável (AC-006.9), checado só depois de provar a posse do grupo.
        if (status != null && !viewerMembership.isAdmin()) {
            throw new NotAllowedException();
        }
        MembershipStatus effective = status == null ? MembershipStatus.ATIVO : status;

        // 3. paginação saneada, como em GET /invitations (sem 400).
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        // 4. consulta paginada; pendente vencido pelo Clock é excluído na própria consulta (D-09).
        Page<Membership> result = memberships.findMembersPage(
                viewerMembership.getGroupId(), effective, clock.instant(), pageable);

        // 5. contas dos membros da página carregadas em lote (evita N+1).
        Map<UUID, User> usersById = users
                .findAllById(result.getContent().stream().map(Membership::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // 6. monta os itens aplicando a política única de contato (AC-011.5, AC-011.8).
        MemberVisibilityPolicy.Viewer viewer =
                new MemberVisibilityPolicy.Viewer(callerUserId, viewerMembership.getRole());
        List<MemberResponse> items = result.getContent().stream()
                .map(m -> MemberResponse.of(
                        m, usersById.get(m.getUserId()), MemberVisibilityPolicy.canSeeContact(viewer, m.getUserId())))
                .toList();
        return new MemberPageResponse(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), items);
    }

    /**
     * Conta os pedidos aguardando aprovação com prazo vigente (badge do Responsável, AC-006.1 e
     * AC-006.2). A exigência de Responsável (403) é aplicada pela anotação da rota; aqui só se
     * prova a posse do grupo (D-32).
     *
     * @throws ResourceNotFoundException {@code elderId} não é o idoso do grupo do chamador
     */
    @Transactional(readOnly = true)
    public PendingCountResponse pendingCount(UUID elderId, UUID callerUserId) {
        // 1. grupo e vínculo do chamador; sem vínculo ou idoso diferente é 404 genérico (D-32).
        Membership viewerMembership = memberships.findByUserId(callerUserId)
                .orElseThrow(ResourceNotFoundException::new);
        Elder elder = elders.findByGroupId(viewerMembership.getGroupId())
                .orElseThrow(ResourceNotFoundException::new);
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }

        // 2. contagem dedicada, com a mesma regra de vigência da listagem (D-09).
        return new PendingCountResponse(
                memberships.countPendingVigentes(viewerMembership.getGroupId(), clock.instant()));
    }

    /**
     * Aprova um pedido (T-045, AC-006.3, AC-007.1): ATIVO com o papel escolhido e e-mail à pessoa.
     *
     * @param expectedVersion versão que o Responsável viu; diferente da atual é 409 (D-08)
     * @throws ResourceNotFoundException grupo/idoso/membro fora do escopo do chamador (D-32)
     * @throws RequestExpiredException pedido vencido (410, precede qualquer 409)
     * @throws MemberConflictException alvo é o Responsável, pedido já decidido ou versão obsoleta
     */
    @Transactional
    public MemberResponse approve(UUID elderId, UUID memberId, UUID callerUserId, Role role, long expectedVersion) {
        return decide(elderId, memberId, callerUserId, RequestResult.APROVADO, role, expectedVersion);
    }

    /**
     * Recusa um pedido (T-045, AC-006.4, AC-007.1/007.2): RECUSADO e e-mail à pessoa. Sem versão
     * no contrato; a concorrência é coberta pelo lock otimista da própria entidade.
     *
     * @throws ResourceNotFoundException grupo/idoso/membro fora do escopo do chamador (D-32)
     * @throws RequestExpiredException pedido vencido (410)
     * @throws MemberConflictException alvo é o Responsável ou pedido já decidido
     */
    @Transactional
    public MemberResponse reject(UUID elderId, UUID memberId, UUID callerUserId) {
        return decide(elderId, memberId, callerUserId, RequestResult.RECUSADO, null, null);
    }

    /**
     * Caminho comum de approve/reject: escopo (D-32), precedência 409/410, regra de domínio (D-07),
     * gravação com flush e e-mail pós-commit (D-23).
     */
    private MemberResponse decide(
            UUID elderId, UUID memberId, UUID callerUserId, RequestResult result, Role role, Long expectedVersion) {
        // 1. grupo e idoso do chamador (mesma resolução da listagem); fora do escopo é 404 (D-32).
        Membership caller = memberships.findByUserId(callerUserId).orElseThrow(ResourceNotFoundException::new);
        Elder elder = elders.findByGroupId(caller.getGroupId()).orElseThrow(ResourceNotFoundException::new);
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }

        // 2. o membro precisa existir e pertencer ao mesmo grupo (= elder da URL); senão 404.
        Membership target = memberships.findById(memberId).orElseThrow(ResourceNotFoundException::new);
        if (!target.getGroupId().equals(caller.getGroupId())) {
            throw new ResourceNotFoundException();
        }

        // 3. o Responsável nunca é alvo de decisão (AC-009.4).
        if (target.isAdmin()) {
            throw new MemberConflictException(MemberConflictCode.ADMIN_PROTECTED);
        }

        // 4. vencido (EXPIRADO, ou pendente com agora >= expiresAt) é 410, antes de qualquer 409 (D-09 rev.2).
        boolean pendente = target.getStatus() == MembershipStatus.AGUARDANDO_APROVACAO;
        if (target.getStatus() == MembershipStatus.EXPIRADO
                || (pendente && !clock.instant().isBefore(target.getExpiresAt()))) {
            throw new RequestExpiredException();
        }

        // 5. já decidido (ATIVO/RECUSADO/REMOVIDO) é 409.
        if (!pendente) {
            throw new MemberConflictException(MemberConflictCode.REQUEST_ALREADY_DECIDED);
        }

        // 6. versão obsoleta no approve é 409 (D-08).
        if (expectedVersion != null && expectedVersion != target.getVersion()) {
            throw new MemberConflictException(MemberConflictCode.VERSION_CONFLICT);
        }

        // 7. transição de domínio (D-07); IllegalStateException de domínio vira 409 sem vazar mensagem.
        try {
            if (result == RequestResult.APROVADO) {
                target.approve(role, callerUserId, clock);
            } else {
                target.reject(callerUserId, clock);
            }
        } catch (IllegalStateException e) {
            throw new MemberConflictException(MemberConflictCode.REQUEST_ALREADY_DECIDED);
        }

        // 8. saveAndFlush DENTRO do método: com lock otimista (D-08) o UPDATE ... WHERE version=?
        // dos perdedores de uma corrida afeta 0 linhas e falha aqui, onde dá para virar 409; um flush
        // adiado para o commit escaparia como 500. Escolha mínima, sem lock pessimista.
        try {
            memberships.saveAndFlush(target);
        } catch (OptimisticLockingFailureException e) {
            throw new MemberConflictException(MemberConflictCode.VERSION_CONFLICT);
        }

        // 9. e-mail à pessoa: publicado na transação, só sai após o commit (D-23); nada se falhou.
        User person = users.findById(target.getUserId()).orElseThrow(ResourceNotFoundException::new);
        events.publishEvent(new RequestResultEmail(person.getEmail(), result));

        // 10. o Responsável vê todos os campos do membro.
        return MemberResponse.of(target, person, true);
    }
}
