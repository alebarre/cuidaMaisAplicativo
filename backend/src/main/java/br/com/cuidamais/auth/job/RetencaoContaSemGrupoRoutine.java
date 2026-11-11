package br.com.cuidamais.auth.job;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.notification.event.AccountDeletedEmail;
import br.com.cuidamais.notification.event.ElderReminderEmail;
import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.jobs.SkipLockedClaimer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Rotina agendada (T-034, plan D-09 rev. 2, item 3, segunda metade) que trata a retenção de uma
 * conta {@code ATIVO} (e-mail confirmado) que nunca ganhou nenhuma {@link
 * br.com.cuidamais.membership.domain.Membership} — critério "ATIVO sem grupo" de D-33, o mesmo usado por
 * {@code AccountState#hasGroup()}: uma conta é elegível aqui só se {@code emailVerifiedAt IS NOT
 * NULL} e não existir nenhuma linha em {@code memberships} para o seu {@code user_id}, qualquer
 * status. Único AC coberto: <b>AC-014.3</b> — lembrete aos 23 dias ({@link ElderReminderEmail}) e
 * exclusão completa aos 30 dias desde a confirmação do e-mail, com o e-mail curto de exclusão
 * ({@link AccountDeletedEmail}).
 *
 * <p><strong>Fora do escopo desta classe</strong> (apesar de estarem no mesmo parágrafo de D-09
 * item 3 do plan): a retenção de membership {@code RECUSADO}/{@code EXPIRADO} via {@code
 * retention_deadline} é AC-014.1, tarefa futura <strong>T-049</strong>; esta rotina não cria, não
 * lê e não apaga nenhuma membership nesses dois estados, nem toca em {@code retention_deadline}.
 *
 * <p><strong>Borda dos "23"/"30 dias" (fechada, mesma convenção de {@link
 * DescarteContasNaoConfirmadasRoutine} para as 24h de AC-001.5):</strong> uma conta já é elegível
 * para o lembrete/exclusão no instante exato em que completa 23/30 dias desde {@code
 * email_verified_at} ({@code emailVerifiedAt + 23d <= agora} / {@code + 30d <= agora}), não só
 * estritamente depois.
 */
@Component
public class RetencaoContaSemGrupoRoutine implements ScheduledRoutine {

    /** AC-014.3: prazo do lembrete desde a confirmação do e-mail. */
    private static final Duration PRAZO_LEMBRETE = Duration.ofDays(23);

    /** AC-014.3: prazo da exclusão completa desde a confirmação do e-mail. */
    private static final Duration PRAZO_EXCLUSAO = Duration.ofDays(30);

    /**
     * Limite de contas processadas por rodada, por etapa (mesmo valor e mesma justificativa de
     * {@link DescarteContasNaoConfirmadasRoutine#MAX_ROWS}: grande o bastante para esvaziar o
     * lote comum, pequeno o bastante para não manter um lock pessimista sobre muitas linhas por
     * muito tempo numa única transação).
     */
    private static final int MAX_ROWS = 200;

    /**
     * "ATIVO sem grupo" elegível à exclusão: e-mail confirmado há 30 dias ou mais (borda fechada)
     * e sem nenhuma membership, de qualquer status, para o {@code user_id} (D-33). A subquery
     * JPQL cruza os pacotes {@code auth}/{@code membership}, válida porque as duas entidades
     * compartilham o mesmo {@code EntityManager}.
     */
    private static final String JPQL_EXCLUSAO =
            "select u from User u where u.emailVerifiedAt is not null and u.emailVerifiedAt <= :corte30d "
                    + "and u.id not in (select m.userId from Membership m)";

    /**
     * "ATIVO sem grupo" elegível ao lembrete: mesmo critério de "sem grupo" da exclusão, mais
     * {@code elderReminderSentAt IS NULL} (nunca lembrado) e {@code emailVerifiedAt} entre 23 e 30
     * dias atrás (nunca reenvia para quem já passou dos 30 dias, que é tratado na etapa de
     * exclusão deste mesmo ciclo ou de um ciclo anterior).
     */
    private static final String JPQL_LEMBRETE =
            "select u from User u where u.emailVerifiedAt is not null and u.elderReminderSentAt is null "
                    + "and u.emailVerifiedAt <= :corte23d and u.emailVerifiedAt > :corte30d "
                    + "and u.id not in (select m.userId from Membership m)";

    private final SkipLockedClaimer claimer;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public RetencaoContaSemGrupoRoutine(
            SkipLockedClaimer claimer, UserRepository users, ApplicationEventPublisher events) {
        this.claimer = claimer;
        this.users = users;
        this.events = events;
    }

    /** Nome curto da rotina (P5: sem dado pessoal), usado só em log pelo {@code JobRunner}. */
    @Override
    public String name() {
        return "retencao-conta-sem-grupo";
    }

    /**
     * Uma rodada, em duas etapas independentes (cada uma com sua própria reivindicação via
     * {@link SkipLockedClaimer}): primeiro a exclusão (30 dias), depois o lembrete (23 dias). A
     * exclusão vem antes para que uma conta que já cruzou os 30 dias sem nunca ter sido
     * processada receba só o e-mail de exclusão neste ciclo — a condição {@code emailVerifiedAt
     * > corte30d} da consulta do lembrete já a excluiria de qualquer forma, mas a ordem reforça
     * essa garantia mesmo que a consulta mude no futuro.
     *
     * @param clock relógio do contexto (D-10); nunca o relógio de sistema — o "agora" e os cortes
     *     de 23/30 dias vêm sempre daqui, para que os testes controlem o tempo com {@code
     *     MutableClock}
     */
    @Override
    public void run(Clock clock) {
        // 1. exclusão (30 dias): reivindica as contas "sem grupo" cujo e-mail foi confirmado há
        // 30 dias ou mais (borda fechada), apaga cada uma de fato (cascade cuida de otp_codes/
        // refresh_tokens) e publica o e-mail curto de exclusão para quem foi apagado.
        Instant corte30d = clock.instant().minus(PRAZO_EXCLUSAO);
        List<User> contasParaExcluir =
                claimer.claim(User.class, JPQL_EXCLUSAO, Map.of("corte30d", corte30d), MAX_ROWS);
        List<String> emailsExcluidos = new ArrayList<>(contasParaExcluir.size());
        for (User conta : contasParaExcluir) {
            emailsExcluidos.add(conta.getEmail());
        }
        users.deleteAll(contasParaExcluir);
        for (String email : emailsExcluidos) {
            events.publishEvent(new AccountDeletedEmail(email));
        }

        // 2. lembrete (23 dias): reivindica as contas "sem grupo" ainda não lembradas, com e-mail
        // confirmado entre 23 e 30 dias atrás; marca o envio (para não duplicar numa rodada
        // seguinte) e publica o lembrete.
        Instant corte23d = clock.instant().minus(PRAZO_LEMBRETE);
        List<User> contasParaLembrar = claimer.claim(
                User.class, JPQL_LEMBRETE, Map.of("corte23d", corte23d, "corte30d", corte30d), MAX_ROWS);
        for (User conta : contasParaLembrar) {
            conta.markElderReminderSent(clock);
            users.save(conta);
            events.publishEvent(new ElderReminderEmail(conta.getEmail()));
        }
    }
}
