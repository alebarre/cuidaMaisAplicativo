package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationOrigin;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.dto.InvitationPageResponse;
import br.com.cuidamais.membership.dto.InvitationResponse;
import br.com.cuidamais.membership.exception.InvitationConflictCode;
import br.com.cuidamais.membership.exception.InvitationConflictException;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.InvitationEmail;
import br.com.cuidamais.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de convite do Responsável (T-040): criar, listar, reenviar e cancelar (AC-002.4,
 * AC-003.1 a AC-003.12, D-04, D-23, D-31, D-32, D-34, D-39). A regra de estado do convite mora em
 * {@link Invitation} e do contador em {@link InviteCodeAttempts} (D-07); este serviço decide
 * <strong>quando</strong> chamá-las, aplica as regras de elegibilidade do e-mail, persiste e
 * publica o e-mail depois do commit. O código de 6 dígitos só existe em texto na memória, a caminho
 * do e-mail; no banco vai apenas o hash (D-04). Só o administrador chega aqui (a anotação do
 * controller bloqueia os demais com 403).
 */
@Service
public class InvitationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvitationRepository invitations;
    private final InviteCodeAttemptsRepository attemptsRepository;
    private final InviteCodeAttemptsLocker attemptsLocker;
    private final MembershipRepository memberships;
    private final ElderRepository elders;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;
    private final InvitationCodeGenerator codeGenerator;

    /** Injeção por construtor de repositórios, acesso travado ao contador (R-03), gerador de código (D-04), encoder (D-04), publicador e relógio (D-10). */
    public InvitationService(
            InvitationRepository invitations,
            InviteCodeAttemptsRepository attemptsRepository,
            InviteCodeAttemptsLocker attemptsLocker,
            MembershipRepository memberships,
            ElderRepository elders,
            UserRepository users,
            InvitationCodeGenerator codeGenerator,
            PasswordEncoder encoder,
            ApplicationEventPublisher events,
            EntityManager entityManager,
            Clock clock) {
        this.invitations = invitations;
        this.attemptsRepository = attemptsRepository;
        this.attemptsLocker = attemptsLocker;
        this.memberships = memberships;
        this.elders = elders;
        this.users = users;
        this.codeGenerator = codeGenerator;
        this.encoder = encoder;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Convida um e-mail para o grupo do Responsável (AC-003.1). Se já há convite {@code ENVIADO}
     * para o e-mail, ele é cancelado e substituído (AC-003.12, D-31).
     *
     * @throws InvitationConflictException NO_ELDER, ALREADY_MEMBER ou EMAIL_UNAVAILABLE
     * @throws ResourceNotFoundException {@code elderId} não é o idoso do grupo do chamador (D-32)
     */
    @Transactional
    public InvitationResponse create(UUID elderId, UUID callerUserId, String rawEmail) {
        // 1. resolve o grupo do chamador; grupo sem idoso é NO_ELDER antes de qualquer 404 (AC-002.4).
        UUID groupId = groupOf(callerUserId);
        Elder elder = elders.findByGroupId(groupId)
                .orElseThrow(() -> new InvitationConflictException(InvitationConflictCode.NO_ELDER));
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }

        // 2. normaliza o e-mail e trava o contador ANTES de qualquer escrita (ordem global de locks:
        //    invite_code_attempts -> invitations -> memberships), evitando deadlock com o resgate.
        String email = rawEmail.strip().toLowerCase(Locale.ROOT);
        InviteCodeAttempts attempts = attemptsLocker.lock(email);

        // 3. confere elegibilidade (AC-003.5, AC-003.6, AC-003.7).
        checkEligible(groupId, email);

        // 4. e-mail com convite ENVIADO = reenvio: o anterior é cancelado (AC-003.12, D-31).
        List<Invitation> pending = invitations.findByGroupIdAndEmailAndStatus(groupId, email, InvitationStatus.ENVIADO);
        String code = codeGenerator.generate();
        String hash = encoder.encode(code);
        Invitation created;
        if (pending.isEmpty()) {
            created = Invitation.send(groupId, email, hash, InvitationOrigin.MANUAL, callerUserId, clock);
        } else {
            created = Invitation.resend(pending.getFirst(), hash, InvitationOrigin.MANUAL, callerUserId, clock);
            for (int i = 1; i < pending.size(); i++) {
                pending.get(i).cancel(clock);
            }
        }

        // 5. persiste, zera o contador do e-mail (já travado) e agenda o e-mail pós-commit.
        return persistAndNotify(created, attempts, callerUserId, elder, code);
    }

    /**
     * Lista os convites do grupo, mais recentes primeiro, com tentativas/bloqueio do e-mail
     * (AC-003.4, AC-003.8, D-39). O filtro {@code VENCIDO}/{@code ENVIADO} usa o status derivado.
     *
     * @throws ResourceNotFoundException {@code elderId} não é o idoso do grupo do chamador (D-32)
     */
    @Transactional(readOnly = true)
    public InvitationPageResponse list(UUID elderId, UUID callerUserId, InvitationStatus status, int page, int size) {
        // 1. posse do idoso (404 genérico, D-32).
        UUID groupId = resolveOwnGroup(elderId, callerUserId);

        // 2. paginação saneada, mais recentes primeiro.
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));

        // 3. consulta conforme o filtro (VENCIDO e ENVIADO derivados do relógio).
        Page<Invitation> result;
        if (status == null) {
            result = invitations.findByGroupId(groupId, pageable);
        } else if (status == InvitationStatus.ENVIADO) {
            result = invitations.findSentActive(groupId, clock.instant(), pageable);
        } else if (status == InvitationStatus.VENCIDO) {
            result = invitations.findExpired(groupId, clock.instant(), pageable);
        } else {
            result = invitations.findByGroupIdAndStatus(groupId, status, pageable);
        }

        // 4. contadores por e-mail lidos em lote (D-39).
        Map<String, InviteCodeAttempts> attempts = attemptsRepository
                .findAllById(result.getContent().stream().map(Invitation::getEmail).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InviteCodeAttempts::getEmail, Function.identity()));
        List<InvitationResponse> items = result.getContent().stream()
                .map(i -> InvitationResponse.of(i, attempts.get(i.getEmail()), clock))
                .toList();
        return new InvitationPageResponse(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), items);
    }

    /**
     * Reenvia um convite {@code ENVIADO}, {@code VENCIDO} ou {@code CANCELADO}: cria um novo (e cancela os demais ENVIADO vigentes do mesmo grupo e e-mail) com
     * código e prazo novos e zera tentativas/bloqueio, sem zerar {@code autoResends} (AC-003.4,
     * AC-003.9, AC-003.3).
     *
     * @throws InvitationConflictException INVITATION_USED se o convite já foi usado
     * @throws ResourceNotFoundException convite inexistente ou de outro grupo (D-32)
     */
    @Transactional
    public InvitationResponse resend(UUID elderId, UUID invitationId, UUID callerUserId) {
        // 1. posse do idoso e do convite (404 genérico).
        UUID groupId = resolveOwnGroup(elderId, callerUserId);
        Elder elder = elders.findByGroupId(groupId).orElseThrow(ResourceNotFoundException::new);
        Invitation previous = invitations.findByIdAndGroupId(invitationId, groupId)
                .orElseThrow(ResourceNotFoundException::new);

        // 2. trava o contador do e-mail do convite (lido sem escrita) antes de qualquer escrita:
        //    ordem global invite_code_attempts -> invitations -> memberships (evita deadlock com o resgate).
        InviteCodeAttempts attempts = attemptsLocker.lock(previous.getEmail());
        entityManager.refresh(previous); // relê o estado após o lock (um cadastro concorrente pode tê-lo usado).

        // 3. convite usado não se reenvia (D-31).
        if (previous.getStatus() == InvitationStatus.USADO) {
            throw new InvitationConflictException(InvitationConflictCode.INVITATION_USED);
        }

        // 4. novo convite (o anterior ENVIADO vigente é cancelado pelo domínio).
        String code = codeGenerator.generate();
        Invitation created = Invitation.resend(
                previous, encoder.encode(code), InvitationOrigin.MANUAL, callerUserId, clock);

        // 4b. cancela TODOS os outros ENVIADO vigentes do mesmo grupo e e-mail (D-42): o contador já
        //     está travado, então um convite criado por outra transação (ex.: o reenvio automático
        //     que cancelou o 'previous' um instante antes) já está visível e não pode sobreviver.
        for (Invitation other : invitations.findByGroupIdAndEmailAndStatus(
                previous.getGroupId(), previous.getEmail(), InvitationStatus.ENVIADO)) {
            if (!other.getId().equals(previous.getId()) && clock.instant().isBefore(other.getExpiresAt())) {
                other.cancel(clock);
                invitations.save(other);
            }
        }

        // 5. persiste o anterior (pode ter mudado), o novo, zera o contador e notifica.
        invitations.save(previous);
        return persistAndNotify(created, attempts, callerUserId, elder, code);
    }

    /**
     * Cancela um convite {@code ENVIADO} (AC-003.4).
     *
     * @throws InvitationConflictException INVITATION_NOT_CANCELLABLE se não está ENVIADO (inclui o
     *     ENVIADO já vencido pelo relógio, apresentado como VENCIDO)
     * @throws ResourceNotFoundException convite inexistente ou de outro grupo (D-32)
     */
    @Transactional
    public InvitationResponse cancel(UUID elderId, UUID invitationId, UUID callerUserId) {
        // 1. posse do idoso e do convite.
        UUID groupId = resolveOwnGroup(elderId, callerUserId);
        Invitation invitation = invitations.findByIdAndGroupId(invitationId, groupId)
                .orElseThrow(ResourceNotFoundException::new);

        // 2. trava o contador do e-mail antes de escrever (ordem global de locks, R-08) e relê o
        //    convite, que um cadastro/reenvio concorrente pode ter mudado enquanto esperávamos.
        attemptsLocker.lock(invitation.getEmail());
        entityManager.refresh(invitation);

        // 3. só ENVIADO vigente é cancelável; o vencido derivado é VENCIDO para o cliente.
        if (invitation.getStatus() != InvitationStatus.ENVIADO
                || !clock.instant().isBefore(invitation.getExpiresAt())) {
            throw new InvitationConflictException(InvitationConflictCode.INVITATION_NOT_CANCELLABLE);
        }

        // 4. transição no domínio e resposta com o contador atual.
        invitation.cancel(clock);
        invitations.save(invitation);
        return InvitationResponse.of(
                invitation, attemptsRepository.findById(invitation.getEmail()).orElse(null), clock);
    }

    /**
     * Persiste o convite novo, zera o contador do e-mail (já travado e criado pelo chamador, antes
     * de qualquer escrita; AC-003.9, AC-003.11) e publica o e-mail do convite, entregue só após o
     * commit (D-23).
     */
    private InvitationResponse persistAndNotify(
            Invitation created, InviteCodeAttempts attempts, UUID callerUserId, Elder elder, String code) {
        // 1. grava o convite (só hash do código).
        invitations.save(created);

        // 2. zera tentativas e bloqueio, preservando autoResends.
        attempts.registerInvitationCreated(clock);
        attemptsRepository.save(attempts);

        // 3. e-mail com nome do Responsável e só o primeiro nome do idoso (P5).
        User responsible = users.findById(callerUserId).orElseThrow(ResourceNotFoundException::new);
        events.publishEvent(new InvitationEmail(
                created.getEmail(), code, responsible.getName(), firstName(elder.getName())));
        return InvitationResponse.of(created, attempts, clock);
    }

    /**
     * Regras de elegibilidade do e-mail (AC-003.5, AC-003.6, AC-003.7): conta de outro grupo ou
     * sem grupo é EMAIL_UNAVAILABLE (mesma resposta); membro ATIVO/AGUARDANDO_APROVACAO do próprio
     * grupo é ALREADY_MEMBER; RECUSADO/EXPIRADO/REMOVIDO podem ser reconvidados.
     */
    private void checkEligible(UUID groupId, String email) {
        Optional<User> user = users.findByEmail(email);
        if (user.isEmpty()) {
            return;
        }
        Optional<Membership> membership = memberships.findByUserId(user.get().getId());
        if (membership.isEmpty() || !membership.get().getGroupId().equals(groupId)) {
            throw new InvitationConflictException(InvitationConflictCode.EMAIL_UNAVAILABLE);
        }
        MembershipStatus status = membership.get().getStatus();
        if (status == MembershipStatus.ATIVO || status == MembershipStatus.AGUARDANDO_APROVACAO) {
            throw new InvitationConflictException(InvitationConflictCode.ALREADY_MEMBER);
        }
    }

    /** Grupo do chamador (D-02: no máximo uma membership por conta); sem vínculo é 404. */
    private UUID groupOf(UUID callerUserId) {
        return memberships.findByUserId(callerUserId)
                .map(Membership::getGroupId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** Grupo do chamador conferido contra o {@code elderId} da rota; divergência é 404 (D-32). */
    private UUID resolveOwnGroup(UUID elderId, UUID callerUserId) {
        UUID groupId = groupOf(callerUserId);
        Elder elder = elders.findByGroupId(groupId).orElseThrow(ResourceNotFoundException::new);
        if (!elder.getId().equals(elderId)) {
            throw new ResourceNotFoundException();
        }
        return groupId;
    }

    /**
     * Primeiro nome do idoso; o sobrenome nunca sai por e-mail (AC-003.1, P5). Público para que a
     * rotina de reenvio automático (T-048) monte o e-mail exatamente igual ao do convite normal,
     * sem duplicar a regra de P5.
     */
    public static String firstName(String fullName) {
        String trimmed = fullName.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
