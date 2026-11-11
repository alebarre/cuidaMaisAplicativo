package br.com.cuidamais.membership.job;

import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.membership.service.InviteCodeAttemptsLocker;
import br.com.cuidamais.notification.event.AccountDeletedEmail;
import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.jobs.SkipLockedClaimer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Rotina agendada (T-049, plan D-09 rev. 2, item 3, e os esclarecimentos registrados na T-049) que
 * exclui, por retenção, as contas cuja {@link Membership} está {@code RECUSADO} ou {@code EXPIRADO}
 * (AC-014.1). Contas {@code REMOVIDO}, {@code ATIVO} e {@code AGUARDANDO_APROVACAO} nunca são
 * tocadas (AC-014.2).
 *
 * <p><strong>Elegibilidade (borda fechada, {@code <=}, como as demais rotinas de D-09):</strong>
 * a conta é excluída quando (a) a base de retenção venceu — {@code retention_deadline <= agora} ou,
 * no legado sem prazo gravado, {@code decided_at + 30 d <= agora}; sem nenhuma das duas datas a
 * conta nunca é elegível — E (b) nenhum convite do e-mail da conta, em qualquer grupo e status,
 * ainda segura o prazo: {@code createdAt + 30 d > agora}, ou {@code cancelledAt + 30 d > agora}
 * ({@code CANCELADO} conta só pelo cancelamento, nunca pelo {@code expiresAt}), ou {@code ENVIADO}/
 * {@code VENCIDO} com {@code expiresAt + 30 d > agora} (o {@code ENVIADO} vigente bloqueia; o vencido
 * pelo relógio recomeça a contagem em {@code expiresAt}, pois nenhum job grava {@code VENCIDO}, D-09).
 * Convites de outro e-mail nunca influenciam. É o "recálculo" do prazo por convite, derivado aqui e
 * não gravado nos fluxos de convite.
 *
 * <p><strong>Exclusão:</strong> apaga, nesta ordem, todos os convites do e-mail, a membership, o
 * usuário (otp/refresh caem em cascata no banco) e a linha de {@code invite_code_attempts} do
 * e-mail, e publica {@link AccountDeletedEmail} na própria transação (enviado só após o commit,
 * D-23). O e-mail fica livre para um novo cadastro.
 *
 * <p><strong>Concorrência e idempotência (R-08, D-42):</strong> a ordem global de locks é {@code
 * invite_code_attempts -> invitations -> memberships}. Por isso a rodada (1) consulta SEM lock os
 * e-mails candidatos, em ordem de e-mail e até {@link #MAX_ROWS}; (2) para cada e-mail trava primeiro
 * o contador ({@link InviteCodeAttemptsLocker#lock}), o mesmo lock que os serviços de convite tomam
 * antes de escrever — serializando contra criação/cancelamento de convite; (3) SOB esse lock reconfere
 * a elegibilidade e reivindica a membership com {@code SKIP LOCKED} ({@link SkipLockedClaimer}),
 * pulando linhas seguradas por outra execução; (4) só então apaga. Um convite criado ou cancelado ao
 * mesmo tempo ou impede a exclusão (a reconferência o enxerga) ou ocorre depois dela. Como a conta
 * some, uma segunda rodada (ou rodada concorrente) não a encontra e nunca repete o e-mail.
 */
@Component
public class RetencaoContasNaoAprovadasRoutine implements ScheduledRoutine {

    /** AC-014.1: tempo de retenção, também a janela com que cada convite segura a conta. */
    private static final Duration PRAZO_RETENCAO = Duration.ofDays(30);

    /**
     * Limite de contas por rodada (mesmo valor e justificativa das demais rotinas de D-09): evita
     * manter muitos locks por muito tempo numa única transação; o excedente sai na rodada seguinte.
     */
    private static final int MAX_ROWS = 200;

    /**
     * Reivindicação da membership de um e-mail sob o lock do contador: o mesmo critério da consulta
     * de candidatos, agora para um e-mail só. O usuário entra por subconsulta (e não por junção) para
     * que o {@code FOR UPDATE} recaia apenas sobre a linha da membership.
     */
    private static final String JPQL_RECONFERENCIA = "select m from Membership m where m.userId = "
            + "(select u.id from User u where u.email = :email) and"
            + MembershipRepository.NAO_APROVADA_COM_BASE_VENCIDA
            + MembershipRepository.INICIO_SEM_CONVITE_QUE_SEGURA + ":email"
            + MembershipRepository.FIM_SEM_CONVITE_QUE_SEGURA;

    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final InviteCodeAttemptsRepository attemptsRepository;
    private final InviteCodeAttemptsLocker locker;
    private final SkipLockedClaimer claimer;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    /** Injeção por construtor das dependências da rotina (repositórios, lock do contador, claimer e eventos). */
    public RetencaoContasNaoAprovadasRoutine(
            MembershipRepository memberships,
            InvitationRepository invitations,
            InviteCodeAttemptsRepository attemptsRepository,
            InviteCodeAttemptsLocker locker,
            SkipLockedClaimer claimer,
            UserRepository users,
            ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.attemptsRepository = attemptsRepository;
        this.locker = locker;
        this.claimer = claimer;
        this.users = users;
        this.events = events;
    }

    /** Nome curto da rotina (P5: sem dado pessoal), usado só em log pelo {@code JobRunner}. */
    @Override
    public String name() {
        return "retencao-contas-nao-aprovadas";
    }

    /**
     * Uma rodada: lista os e-mails candidatos sem lock e trata cada um sob o lock do contador.
     *
     * @param clock relógio do contexto (D-10); nunca o de sistema — o "agora" e o corte de 30 dias
     *     vêm daqui, para que os testes controlem o tempo
     */
    @Override
    public void run(Clock clock) {
        // 1. "agora" e corte de 30 dias (um convite segura a conta enquanto sua data > corte).
        Instant agora = clock.instant();
        Instant corte = agora.minus(PRAZO_RETENCAO);

        // 2. candidatos sem lock, em ordem de e-mail (ordem determinística de locks) e limitados;
        // o filtro já descarta as contas seguradas por convite, para não gastar as vagas da rodada.
        List<String> candidatos =
                memberships.findEmailsElegiveisRetencao(agora, corte, Limit.of(MAX_ROWS));

        // 3. cada candidato é reconferido e, se ainda elegível, excluído sob o lock do contador.
        for (String email : candidatos) {
            tratar(email, agora, corte, clock);
        }
    }

    /**
     * Trata um e-mail candidato: trava o contador (1º lock da ordem R-08), reconfere a elegibilidade
     * sob o lock e, se continua elegível, exclui a conta e avisa a pessoa.
     */
    private void tratar(String email, Instant agora, Instant corte, Clock clock) {
        // 1. o contador existia antes de travarmos? (se não, o lock() o cria zerado só para travar)
        boolean contadorJaExistia = attemptsRepository.existsById(email);

        // 2. 1º lock da ordem global: o mesmo que os serviços de convite tomam antes de escrever.
        InviteCodeAttempts contador = locker.lock(email);

        // 3. reconferência sob o lock, reivindicando a membership com SKIP LOCKED: linhas seguradas
        // por outra execução são puladas (a próxima rodada reavalia); se um convite surgiu ou o
        // estado mudou enquanto esperávamos o lock, a consulta já não devolve a linha.
        Optional<Membership> elegivel = claimer
                .claim(Membership.class, JPQL_RECONFERENCIA,
                        Map.of("email", email, "agora", agora, "corte", corte), 1)
                .stream()
                .findFirst();

        if (elegivel.isEmpty()) {
            // 3a. nada a excluir. Se o contador foi criado só para esta travada e segue zerado, ele é
            // removido para não deixar lixo (equivale a "não existe", que é o que lock() assume).
            if (!contadorJaExistia && estaZerado(contador, clock)) {
                attemptsRepository.delete(contador);
            }
            return;
        }

        // 4. exclusão na ordem de dependência das FKs: convites do e-mail (referenciam users),
        // membership (user_id sem ON DELETE), usuário (otp/refresh em cascata) e, por fim, o contador.
        Membership membership = elegivel.get();
        invitations.deleteByEmail(email);
        memberships.delete(membership);
        memberships.flush();
        users.findById(membership.getUserId()).ifPresent(users::delete);
        users.flush();
        attemptsRepository.delete(contador);
        attemptsRepository.flush();

        // 5. e-mail curto de exclusão, publicado na transação (sai só após o commit, D-23).
        events.publishEvent(new AccountDeletedEmail(email));
    }

    /**
     * Diz se o contador está no estado "recém-criado" (sem falhas, bloqueio ou reenvios), isto é,
     * equivalente a não existir; só nesse caso a rotina o apaga quando nada foi excluído.
     */
    private static boolean estaZerado(InviteCodeAttempts contador, Clock clock) {
        return contador.failedAttempts(clock) == 0
                && contador.blockedUntil(clock) == null
                && contador.getAutoResends() == 0;
    }
}
