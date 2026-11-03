package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.auth.service.JwtService.RegistrationClaims;
import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.dto.AccessRequestCreatedResponse;
import br.com.cuidamais.membership.dto.RegisterWithInvitationRequest;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.dto.AddressRequest;
import br.com.cuidamais.shared.exception.UnauthorizedSessionException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passo 2 do convidado (T-042, AC-004.3, AC-004.4, AC-006.7, AC-003.10; D-16 rev. 2, D-33, D-34):
 * troca o {@code registrationToken} por uma conta nova com e-mail já verificado (o código de
 * convite o comprovou) e um vínculo {@code AGUARDANDO_APROVACAO} de 24 h ao grupo do convite,
 * consome o convite (ENVIADO para USADO) e avisa os Responsáveis.
 *
 * <p>O token é de uso único na prática porque o convite vira USADO na mesma transação. Qualquer
 * motivo de recusa (token inválido, convite não mais vigente, e-mail que já tem conta ou está
 * bloqueado) é o mesmo 401 genérico, sem revelar o motivo (P5). Respeita a ordem global de locks
 * (R-08): contador do e-mail, depois convite, depois vínculo.
 */
@Service
public class InvitationRegisterService {

    private final JwtService jwtService;
    private final InviteCodeAttemptsLocker attemptsLocker;
    private final InviteCodeAttemptsRepository attemptsRepository;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final PendingRequestNotifier pendingNotifier;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Injeção por construtor de emissor/validador de token, acesso travado ao contador, repositórios, encoder (D-14), avisador, gerenciador de entidades (releitura sob lock) e relógio (D-10). */
    public InvitationRegisterService(
            JwtService jwtService,
            InviteCodeAttemptsLocker attemptsLocker,
            InviteCodeAttemptsRepository attemptsRepository,
            InvitationRepository invitations,
            UserRepository users,
            MembershipRepository memberships,
            PasswordEncoder encoder,
            PendingRequestNotifier pendingNotifier,
            EntityManager entityManager,
            Clock clock) {
        this.jwtService = jwtService;
        this.attemptsLocker = attemptsLocker;
        this.attemptsRepository = attemptsRepository;
        this.invitations = invitations;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.pendingNotifier = pendingNotifier;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Conclui o cadastro do convidado.
     *
     * @throws UnauthorizedSessionException token inválido/expirado, convite não vigente, e-mail
     *     bloqueado ou já com conta (401 genérico)
     */
    @Transactional
    public AccessRequestCreatedResponse register(RegisterWithInvitationRequest request) {
        // 1. valida o token (assinatura, prazo, finalidade, claims); o e-mail vem só dele.
        RegistrationClaims claims;
        try {
            claims = jwtService.parseRegistrationToken(request.registrationToken(), clock);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedSessionException();
        }
        String email = claims.email().strip().toLowerCase(Locale.ROOT);

        // 2. pré-checagem barata e SEM lock (só otimização): token com convite que já não serve ou
        // e-mail com conta recusa antes de gastar BCrypt; a checagem autoritativa é a de baixo.
        Invitation invitation = invitations.findById(claims.invitationId()).orElse(null);
        requireServing(invitation, email);

        // 3. senha em BCrypt antes de travar (trabalho lento fora do lock, evita DoS por e-mail).
        String passwordHash = encoder.encode(request.password());

        // 4. trava o contador do e-mail ANTES de qualquer escrita (R-08).
        InviteCodeAttempts attempts = attemptsLocker.lock(email);
        if (attempts.isBlocked(clock)) {
            throw new UnauthorizedSessionException();
        }

        // 5. repete as checagens sob lock, relendo o convite do banco (a leitura da etapa 2 pode
        // estar obsoleta no contexto de persistência): esta é a checagem que vale.
        entityManager.refresh(invitation);
        requireServing(invitation, email);

        // 6. cria a conta já com e-mail verificado (D-33).
        User user = User.signup(
                request.name(), email, request.phone(), toEndereco(request.address()), passwordHash, clock);
        user.verifyEmail(clock);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // cadastro comum do mesmo e-mail venceu a corrida: trata como conta já existente.
            throw new UnauthorizedSessionException();
        }

        // 7. vínculo AGUARDANDO_APROVACAO (24 h), convite consumido, contador zerado.
        Membership membership = Membership.request(invitation.getGroupId(), user.getId(), clock);
        memberships.save(membership);
        invitation.use(clock);
        invitations.save(invitation);
        attempts.registerCodeUsed(clock);
        attemptsRepository.save(attempts);

        // 8. avisa os Responsáveis após o commit (D-23, AC-006.7).
        pendingNotifier.notifyAdmins(membership, user);

        // 9. 201 com o prazo de decisão.
        return AccessRequestCreatedResponse.of(membership.getExpiresAt());
    }

    /**
     * Exige convite existente, do e-mail do token, ENVIADO e vigente pelo Clock, e e-mail sem
     * conta; qualquer falha é o mesmo 401 genérico (P5).
     */
    private void requireServing(Invitation invitation, String email) {
        if (invitation == null
                || !invitation.getEmail().equals(email)
                || invitation.getStatus() != InvitationStatus.ENVIADO
                || !clock.instant().isBefore(invitation.getExpiresAt())
                || users.findByEmail(email).isPresent()) {
            throw new UnauthorizedSessionException();
        }
    }

    /** Converte o endereço validado do corpo no objeto de valor de domínio (D-25). */
    private static Endereco toEndereco(AddressRequest address) {
        return new Endereco(
                address.zipCode(),
                address.street(),
                address.number(),
                address.complement(),
                address.district(),
                address.city(),
                address.state());
    }
}
