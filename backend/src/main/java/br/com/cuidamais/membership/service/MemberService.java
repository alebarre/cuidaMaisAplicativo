package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.dto.MemberPageResponse;
import br.com.cuidamais.membership.dto.MemberResponse;
import br.com.cuidamais.membership.dto.PendingCountResponse;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.exception.NotAllowedException;
import br.com.cuidamais.shared.exception.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 */
@Service
public class MemberService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MembershipRepository memberships;
    private final ElderRepository elders;
    private final UserRepository users;
    private final Clock clock;

    /** Injeção por construtor dos repositórios e do relógio (D-10). */
    public MemberService(
            MembershipRepository memberships, ElderRepository elders, UserRepository users, Clock clock) {
        this.memberships = memberships;
        this.elders = elders;
        this.users = users;
        this.clock = clock;
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
}
