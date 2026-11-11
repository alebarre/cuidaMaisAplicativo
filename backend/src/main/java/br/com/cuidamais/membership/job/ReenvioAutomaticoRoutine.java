package br.com.cuidamais.membership.job;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationOrigin;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import br.com.cuidamais.membership.service.InvitationCodeGenerator;
import br.com.cuidamais.membership.service.InvitationService;
import br.com.cuidamais.notification.event.InvitationEmail;
import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.jobs.SkipLockedClaimer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Rotina agendada (T-048, plan D-09 rev. 2, item 4) do reenvio automático de convite (AC-003.3):
 * quando o bloqueio de 30 minutos por 5 códigos errados termina, o e-mail bloqueado recebe, se
 * ainda houver convite {@code ENVIADO} vigente, um convite novo (código novo, prazo novo de 7
 * dias, {@code origin = AUTO}) no lugar do antigo, até 3 vezes (o 4º é negado). A negação pelo teto
 * (R-04) deixa um único log INFO de mensagem fixa, para a operação perceber que o teto foi
 * atingido, sem nenhum dado pessoal (P5: nada de e-mail, código, nome nem identificadores).
 *
 * <p>Seleciona as linhas de {@code invite_code_attempts} com {@code blocked_until <= agora}
 * (<strong>só</strong> esse predicado, borda fechada: o contador já vale zero na leitura e erros do
 * ciclo novo não cancelam o reenvio devido). Para cada linha: com convite {@code ENVIADO} vigente
 * ({@code agora < expires_at}; o vencido pelo relógio conta como "sem convite" e a rotina não o
 * cancela), {@link InviteCodeAttempts#consumeAutoResend} decide; concedido, o convite anterior é
 * cancelado, o novo é criado (mesmo grupo, {@code created_by} do anterior), o contador de erros
 * zera (AC-003.11, preservando {@code autoResends}) e o {@link InvitationEmail} é publicado na
 * transação — sai só após o commit (D-23), no mesmo formato do convite normal (nome do criador e só
 * o primeiro nome do idoso, P5). Negado (o 4º), nada é enviado e o convite segue {@code ENVIADO}.
 * Sem convite vigente, {@link InviteCodeAttempts#dismissAutoResend} descarta o reenvio sem gastar o
 * teto. Nos dois caminhos {@code blocked_until} é limpo. Um convite criado durante o bloqueio ou
 * antes da rodada já limpou {@code blocked_until} (AC-003.9) e a linha nem é selecionada.
 *
 * <p>As regras de estado moram em {@link Invitation} e {@link InviteCodeAttempts} (D-07).
 *
 * <p><strong>Idempotência e concorrência (D-42, R-03, R-08):</strong> o lote é reivindicado com
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} via {@link SkipLockedClaimer} sobre o <em>contador</em>,
 * primeiro lock da ordem global invite_code_attempts -> invitations -> memberships; assim a rotina
 * nunca inverte a ordem dos fluxos manuais (criar, reenviar, cancelar, resgatar, cadastrar), que
 * também travam o contador antes de escrever, e não há deadlock. Linha em uso por um desses fluxos
 * é pulada e reavaliada na rodada seguinte; se o fluxo manual já terminou, a leitura (feita depois
 * do lock) enxerga o resultado dele. Como o tratamento limpa {@code blocked_until}, uma segunda
 * rodada ou uma rodada concorrente nunca reenvia a mesma linha duas vezes.
 */
@Component
public class ReenvioAutomaticoRoutine implements ScheduledRoutine {

    /** Logger da rotina; só recebe mensagens fixas, sem dado pessoal (P5). */
    private static final Logger log = LoggerFactory.getLogger(ReenvioAutomaticoRoutine.class);

    /**
     * Limite de contadores tratados por rodada (mesmo valor e justificativa das demais rotinas de
     * D-09): evita manter locks pessimistas e hashes BCrypt sobre muitas linhas de uma vez; o
     * excedente é tratado na rodada seguinte.
     */
    private static final int MAX_ROWS = 200;

    /** Contadores cujo bloqueio terminou (borda fechada), reivindicáveis com SKIP LOCKED. */
    private static final String JPQL = "select a from InviteCodeAttempts a where a.blockedUntil <= :agora "
            + "order by a.blockedUntil, a.email";

    private final SkipLockedClaimer claimer;
    private final InvitationRepository invitations;
    private final InviteCodeAttemptsRepository attemptsRepository;
    private final ElderRepository elders;
    private final UserRepository users;
    private final InvitationCodeGenerator codeGenerator;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    /** Injeção por construtor das dependências da rotina (reivindicação, repositórios, código, hash e eventos). */
    public ReenvioAutomaticoRoutine(
            SkipLockedClaimer claimer,
            InvitationRepository invitations,
            InviteCodeAttemptsRepository attemptsRepository,
            ElderRepository elders,
            UserRepository users,
            InvitationCodeGenerator codeGenerator,
            PasswordEncoder encoder,
            ApplicationEventPublisher events) {
        this.claimer = claimer;
        this.invitations = invitations;
        this.attemptsRepository = attemptsRepository;
        this.elders = elders;
        this.users = users;
        this.codeGenerator = codeGenerator;
        this.encoder = encoder;
        this.events = events;
    }

    /** Nome curto da rotina (P5: sem dado pessoal), usado só em log pelo {@code JobRunner}. */
    @Override
    public String name() {
        return "reenvio-automatico";
    }

    /**
     * Uma rodada: reivindica os contadores com bloqueio terminado e trata o reenvio devido de cada um.
     *
     * @param clock relógio do contexto (D-10); nunca o de sistema — o corte, o prazo de 7 dias e o
     *     "vigente" vêm daqui, para que os testes controlem o tempo
     */
    @Override
    public void run(Clock clock) {
        // 1. reivindica o lote com lock pessimista + SKIP LOCKED (D-42): o contador é travado
        // primeiro (R-08); linhas seguradas por outro fluxo ficam para a próxima rodada.
        List<InviteCodeAttempts> devidos =
                claimer.claim(InviteCodeAttempts.class, JPQL, Map.of("agora", clock.instant()), MAX_ROWS);

        for (InviteCodeAttempts attempts : devidos) {
            tratar(attempts, clock);
        }
    }

    /**
     * Trata o reenvio devido de um e-mail: reenvia, nega pelo teto (com o log fixo de R-04) ou
     * descarta se não há convite (sem log e sem gastar o teto).
     */
    private void tratar(InviteCodeAttempts attempts, Clock clock) {
        String email = attempts.getEmail();

        // 1. convite ENVIADO vigente mais recente do e-mail (o vencido pelo relógio não conta e a
        // rotina não o cancela). Lido depois do lock do contador, então enxerga o que um fluxo
        // manual concorrente já confirmou.
        Optional<Invitation> vigente = invitations.findByEmailOrderByCreatedAtDesc(email).stream()
                .filter(i -> i.getStatus() == InvitationStatus.ENVIADO
                        && clock.instant().isBefore(i.getExpiresAt()))
                .findFirst();

        // 2. com convite vigente, precisa também do criador e do idoso para montar o e-mail; se
        // algum deles não existe mais não há como enviar, e o reenvio é descartado como "sem convite".
        Optional<User> criador = vigente.flatMap(i -> users.findById(i.getCreatedBy()));
        Optional<Elder> idoso = vigente.flatMap(i -> elders.findByGroupId(i.getGroupId()));
        if (vigente.isEmpty() || criador.isEmpty() || idoso.isEmpty()) {
            // 2a. sem convite para reenviar: nada é enviado e o teto não é gasto; bloqueio limpo.
            attempts.dismissAutoResend(clock);
            attemptsRepository.save(attempts);
            return;
        }

        // 3. decide pelo teto de 3 (domínio): o 4º é negado, sem e-mail, convite intacto.
        if (!attempts.consumeAutoResend(clock)) {
            // 3a. R-04: registra a negação com mensagem fixa e sem argumentos (P5: nenhum dado
            // pessoal, nem e-mail, código, nome ou id), só para a operação enxergar o teto.
            log.info("Reenvio automático negado: teto de reenvios automáticos atingido (R-04)");
            attemptsRepository.save(attempts);
            return;
        }

        // 4. concedido: novo código (só o hash vai ao banco) e convite AUTO no lugar do anterior,
        // que o domínio cancela; created_by herdado do convite anterior.
        Invitation anterior = vigente.get();
        String codigo = codeGenerator.generate();
        Invitation novo = Invitation.resend(
                anterior, encoder.encode(codigo), InvitationOrigin.AUTO, anterior.getCreatedBy(), clock);
        invitations.save(anterior);
        invitations.save(novo);

        // 5. zera os erros do ciclo (AC-003.11), preservando autoResends, e persiste o contador.
        attempts.registerInvitationCreated(clock);
        attemptsRepository.save(attempts);

        // 6. e-mail igual ao do convite normal, publicado na transação (sai após o commit, D-23).
        events.publishEvent(new InvitationEmail(
                email, codigo, criador.get().getName(), InvitationService.firstName(idoso.get().getName())));
    }
}
