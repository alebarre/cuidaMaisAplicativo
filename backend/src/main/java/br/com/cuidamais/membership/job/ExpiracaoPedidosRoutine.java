package br.com.cuidamais.membership.job;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.RequestResult;
import br.com.cuidamais.notification.event.RequestResultEmail;
import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.jobs.SkipLockedClaimer;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Rotina agendada (T-046, plan D-09 rev. 2, item 1) que expira os pedidos de acesso sem decisão:
 * toda {@link Membership} {@code AGUARDANDO_APROVACAO} cujo {@code expires_at} já chegou
 * (AC-006.5) passa a {@code EXPIRADO}, ganha {@code retention_deadline = agora + 30 dias}
 * (AC-014.1, só a gravação do prazo; a exclusão é a T-049) e a pessoa recebe o e-mail de resultado
 * {@code EXPIRADO} (AC-007.1/007.2), publicado na transação e enviado só após o commit (D-23).
 *
 * <p>A regra de transição mora na entidade ({@link Membership#expire(Clock)}, D-07); esta classe só
 * seleciona, delega e notifica. Antes do job o pedido vencido já responde 410 em approve/reject
 * (AC-006.8), pois o domínio compara o relógio com {@code expires_at}; a rotina apenas
 * materializa o estado.
 *
 * <p><strong>Borda fechada:</strong> {@code expires_at <= agora}, a mesma regra do 410 e da
 * listagem/contagem (um pedido com exatamente 24 h já vence).
 *
 * <p><strong>Idempotência e concorrência (D-42, D-08):</strong> o lote é reivindicado com {@code
 * SELECT ... FOR UPDATE SKIP LOCKED} via {@link SkipLockedClaimer}; só entram linhas ainda
 * {@code AGUARDANDO_APROVACAO}, então uma segunda rodada (ou uma rodada concorrente) nunca
 * reprocessa a mesma linha nem reenvia e-mail, e {@code retention_deadline} nunca é recalculado.
 * Contra uma aprovação/recusa simultânea: se o Responsável já gravou a decisão, a linha deixa de
 * ser {@code AGUARDANDO_APROVACAO} e não é selecionada; se a decisão ainda não foi confirmada, a
 * linha está bloqueada por ela e o {@code SKIP LOCKED} a ignora (a próxima rodada reavalia); e se
 * o job a reivindicou primeiro, a decisão espera o commit e falha pelo lock otimista ({@code
 * @Version}, D-08) como 409 — nunca um 500 nem e-mails contraditórios.
 */
@Component
public class ExpiracaoPedidosRoutine implements ScheduledRoutine {

    /**
     * Limite de pedidos expirados por rodada (mesmo valor e justificativa das demais rotinas de
     * D-09): esvazia o lote comum sem manter locks pessimistas sobre muitas linhas por muito tempo;
     * o excedente é tratado na rodada seguinte.
     */
    private static final int MAX_ROWS = 200;

    /** Pedidos vencidos (borda fechada), reivindicáveis com SKIP LOCKED. */
    private static final String JPQL = "select m from Membership m where m.status = "
            + "br.com.cuidamais.membership.domain.MembershipStatus.AGUARDANDO_APROVACAO "
            + "and m.expiresAt <= :agora order by m.expiresAt, m.id";

    private final SkipLockedClaimer claimer;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public ExpiracaoPedidosRoutine(
            SkipLockedClaimer claimer,
            MembershipRepository memberships,
            UserRepository users,
            ApplicationEventPublisher events) {
        this.claimer = claimer;
        this.memberships = memberships;
        this.users = users;
        this.events = events;
    }

    /** Nome curto da rotina (P5: sem dado pessoal), usado só em log pelo {@code JobRunner}. */
    @Override
    public String name() {
        return "expiracao-pedidos";
    }

    /**
     * Uma rodada: reivindica os pedidos vencidos, expira cada um pelo domínio e avisa a pessoa.
     *
     * @param clock relógio do contexto (D-10); nunca o relógio de sistema — o "agora" do corte e do
     *     {@code retention_deadline} vêm daqui, para que os testes controlem o tempo
     */
    @Override
    public void run(Clock clock) {
        // 1. reivindica o lote vencido com lock pessimista + SKIP LOCKED (D-42): linhas que outra
        // rodada ou uma decisão em andamento seguram são ignoradas e ficam para a próxima rodada.
        List<Membership> vencidos =
                claimer.claim(Membership.class, JPQL, Map.of("agora", clock.instant()), MAX_ROWS);

        for (Membership pedido : vencidos) {
            // 2. transição de domínio (D-07): EXPIRADO + retention_deadline = agora + 30 dias.
            pedido.expire(clock);
            memberships.save(pedido);

            // 3. e-mail à pessoa, publicado na transação (sai só após o commit, D-23). Se a conta
            // não existir mais, o pedido é expirado igual e apenas não há a quem avisar: a rodada
            // não pode quebrar por causa de um registro (P5: nada é logado).
            Optional<User> pessoa = users.findById(pedido.getUserId());
            pessoa.ifPresent(u -> events.publishEvent(new RequestResultEmail(u.getEmail(), RequestResult.EXPIRADO)));
        }
    }
}
