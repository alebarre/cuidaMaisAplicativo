package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.exception.InvalidCodeException;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.domain.InviteCodeAttemptOutcome;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.dto.RedeemInvitationResponse;
import br.com.cuidamais.membership.exception.InvitationUnavailableCode;
import br.com.cuidamais.membership.exception.InvitationUnavailableException;
import br.com.cuidamais.membership.exception.InviteBlockedException;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.exception.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passo 1 do convidado (T-041, D-16 rev. 2, D-34): confere e-mail + código de convite e decide o
 * próximo passo ({@code REGISTER} ou {@code REINSTATED}) ou o erro (400 com tentativas restantes,
 * 410 convite indisponível, 423 e-mail bloqueado). As regras de estado ficam em {@link
 * Invitation}, {@link Membership} e {@link InviteCodeAttempts} (D-07); aqui só se decide a ordem.
 *
 * <p>Por P5 e AC-004.7, e-mail sem convite, código errado e conta em estado incompatível geram a
 * mesma resposta 400. Como a tentativa errada precisa ser gravada junto com a resposta de erro, as
 * exceções de resposta não revertem a transação ({@code noRollbackFor}). Nada de e-mail ou código
 * é logado.
 */
@Service
public class InvitationRedeemService {

    private static final Set<MembershipStatus> REINSTATABLE =
            Set.of(MembershipStatus.RECUSADO, MembershipStatus.EXPIRADO, MembershipStatus.REMOVIDO);

    private final InvitationRepository invitations;
    private final InviteCodeAttemptsRepository attemptsRepository;
    private final InviteCodeAttemptsLocker attemptsLocker;
    private final MembershipRepository memberships;
    private final ElderRepository elders;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final PendingRequestNotifier pendingNotifier;
    private final Clock clock;

    /**
     * Hash BCrypt fictício (gerado no startup, a partir de valor aleatório descartado; não é
     * segredo) usado para gastar o mesmo custo de {@code matches} quando não há convite a conferir
     * (P5: sem canal de tempo que revele e-mails convidados).
     */
    private final String dummyHash;

    /** Injeção por construtor de repositórios, acesso travado ao contador (R-03), encoder (D-04), emissor de token, avisador dos Responsáveis e relógio (D-10). */
    public InvitationRedeemService(
            InvitationRepository invitations,
            InviteCodeAttemptsRepository attemptsRepository,
            InviteCodeAttemptsLocker attemptsLocker,
            MembershipRepository memberships,
            ElderRepository elders,
            UserRepository users,
            PasswordEncoder encoder,
            JwtService jwtService,
            PendingRequestNotifier pendingNotifier,
            Clock clock) {
        this.invitations = invitations;
        this.attemptsRepository = attemptsRepository;
        this.attemptsLocker = attemptsLocker;
        this.memberships = memberships;
        this.elders = elders;
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.pendingNotifier = pendingNotifier;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Resgata o convite do e-mail com o código informado.
     *
     * @throws InviteBlockedException e-mail bloqueado (423)
     * @throws InvitationUnavailableException código bate com convite vencido/cancelado/usado (410)
     * @throws InvalidCodeException código inválido ou conta incompatível (400)
     */
    @Transactional(
            noRollbackFor = {
                InvalidCodeException.class,
                InviteBlockedException.class,
                InvitationUnavailableException.class
            })
    public RedeemInvitationResponse redeem(String rawEmail, String code) {
        // 1. normaliza o e-mail (D-34 esclarecimento 1).
        String email = rawEmail.strip().toLowerCase(Locale.ROOT);

        // 2. atalho sem lock: e-mail já bloqueado => 423 antes de conferir o código (D-34 esclarecimento 4).
        Optional<InviteCodeAttempts> unlocked = attemptsRepository.findById(email);
        if (unlocked.isPresent() && unlocked.get().isBlocked(clock)) {
            throw new InviteBlockedException(unlocked.get().blockedUntil(clock));
        }

        // 3. confere o código (BCrypt, lento) SEM segurar o lock da linha: o lock só vale da escrita
        //    em diante, para um atacante não manter o e-mail da vítima serializado durante o hash.
        List<Invitation> all = invitations.findByEmailOrderByCreatedAtDesc(email);
        if (all.isEmpty()) {
            // custo igual ao do ramo com convites (P5): descarta o resultado.
            encoder.matches(code, dummyHash);
        }
        Optional<Invitation> current = all.stream()
                .filter(this::isCurrent)
                .filter(i -> encoder.matches(code, i.getCodeHash()))
                .findFirst();

        // 4. código certo de convite vigente: decide REGISTER / REINSTATED, ou trata como inválido.
        if (current.isPresent()) {
            // 4a. trava o contador existente e redecide o bloqueio sobre o valor travado.
            Optional<InviteCodeAttempts> locked = attemptsLocker.lockIfPresent(email);
            rejectIfBlocked(locked);
            Optional<RedeemInvitationResponse> response = accept(current.get(), email, locked);
            if (response.isPresent()) {
                return response.get();
            }
            throw failedAttempt(email);
        }

        // 5. código certo de convite que não está vigente => 410 (não conta tentativa).
        for (Invitation invitation : all) {
            if (!isCurrent(invitation) && encoder.matches(code, invitation.getCodeHash())) {
                throw new InvitationUnavailableException(unavailableCodeOf(invitation));
            }
        }

        // 6. nenhum convite bate (ou e-mail sem convite): tentativa errada.
        throw failedAttempt(email);
    }

    /** Se o contador travado indica bloqueio vigente, responde 423 (decisão sobre o valor travado). */
    private void rejectIfBlocked(Optional<InviteCodeAttempts> locked) {
        if (locked.isPresent() && locked.get().isBlocked(clock)) {
            throw new InviteBlockedException(locked.get().blockedUntil(clock));
        }
    }

    /**
     * Código certo de convite vigente: devolve a resposta se a conta permite (sem conta =>
     * REGISTER; conta RECUSADO/EXPIRADO/REMOVIDO do mesmo grupo => REINSTATED); vazio se a conta
     * existe em estado incompatível (tratado como código inválido, P5).
     */
    private Optional<RedeemInvitationResponse> accept(
            Invitation invitation, String email, Optional<InviteCodeAttempts> existing) {
        Elder elder = elders.findByGroupId(invitation.getGroupId()).orElseThrow(ResourceNotFoundException::new);
        String elderFirstName = firstName(elder.getName());
        Optional<User> user = users.findByEmail(email);

        // 1. sem conta: REGISTER; o convite só vira USADO ao concluir o cadastro (T-042).
        if (user.isEmpty()) {
            existing.ifPresent(a -> codeUsed(a));
            String token = jwtService.issueRegistrationToken(email, invitation.getId(), clock);
            return Optional.of(RedeemInvitationResponse.register(
                    token, (int) JwtService.REGISTRATION_TOKEN_TTL.toSeconds(), email, elderFirstName));
        }

        // 2. conta existe: só reingressa quem tem membership reinstaurável do próprio grupo do convite.
        Optional<Membership> membership = memberships.findByUserId(user.get().getId());
        if (membership.isEmpty()
                || !membership.get().getGroupId().equals(invitation.getGroupId())
                || !REINSTATABLE.contains(membership.get().getStatus())) {
            return Optional.empty();
        }

        // 3. REINSTATED: volta a aguardar aprovação (24 h), consome o convite, zera contador.
        Membership m = membership.get();
        m.reinstate(clock);
        memberships.save(m);
        invitation.use(clock);
        invitations.save(invitation);
        existing.ifPresent(a -> codeUsed(a));

        // 4. avisa os Responsáveis (admins ativos do grupo) após o commit (D-23, AC-006.7).
        pendingNotifier.notifyAdmins(m, user.get());
        return Optional.of(RedeemInvitationResponse.reinstated(email, elderFirstName, m.getExpiresAt()));
    }

    /** Zera contador e reenvios automáticos do e-mail porque um código foi aceito (AC-003.11c). */
    private void codeUsed(InviteCodeAttempts attempts) {
        attempts.registerCodeUsed(clock);
        attemptsRepository.save(attempts);
    }

    /**
     * Registra a tentativa errada sob lock de linha (criando a linha do e-mail se faltar, sem
     * atualização perdida nem violação de PK sob concorrência) e devolve a exceção da resposta:
     * 423 se o e-mail já estava bloqueado ou esta tentativa bloqueou (5ª), senão 400 com as
     * tentativas restantes. O lock só é tomado aqui, depois do BCrypt, e liberado no fim da
     * transação.
     */
    private RuntimeException failedAttempt(String email) {
        // 1. trava (e cria, se preciso) o contador e relê o estado atual do banco.
        InviteCodeAttempts attempts = attemptsLocker.lock(email);
        // 2. outro pedido pode ter bloqueado o e-mail enquanto conferíamos o código: 423 sem contar.
        if (attempts.isBlocked(clock)) {
            return new InviteBlockedException(attempts.blockedUntil(clock));
        }
        // 3. registra a falha no valor travado e devolve a resposta conforme o resultado.
        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);
        attemptsRepository.save(attempts);
        if (outcome.blocked()) {
            return new InviteBlockedException(outcome.blockedUntil());
        }
        return new InvalidCodeException(outcome.remainingAttempts());
    }

    /** Convite ENVIADO e ainda dentro do prazo pelo relógio (AC-003.2, VENCIDO derivado). */
    private boolean isCurrent(Invitation invitation) {
        return invitation.getStatus() == InvitationStatus.ENVIADO
                && clock.instant().isBefore(invitation.getExpiresAt());
    }

    /** Código do 410 para um convite não vigente: ENVIADO vencido pelo relógio conta como VENCIDO. */
    private static InvitationUnavailableCode unavailableCodeOf(Invitation invitation) {
        return switch (invitation.getStatus()) {
            case CANCELADO -> InvitationUnavailableCode.INVITATION_CANCELLED;
            case USADO -> InvitationUnavailableCode.INVITATION_USED;
            case ENVIADO, VENCIDO -> InvitationUnavailableCode.INVITATION_EXPIRED;
        };
    }

    /** Primeiro nome do idoso; o sobrenome nunca sai (AC-004.2, P5). */
    private static String firstName(String fullName) {
        String trimmed = fullName.strip();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
