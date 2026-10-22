package br.com.cuidamais.auth;

import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.jobs.SkipLockedClaimer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Rotina agendada (T-028, plan D-09 rev. 2, item 2) que descarta contas em
 * {@code AGUARDANDO_CONFIRMACAO_EMAIL} (sem {@code email_verified_at}) cujo cadastro completou 24
 * horas sem a pessoa confirmar o e-mail (AC-001.5). O descarte apaga a conta de fato (não é um
 * estado novo nem uma marca de "inativo") para que o e-mail fique imediatamente livre para um
 * novo "Criar grupo de cuidado"; {@code otp_codes.user_id}/{@code refresh_tokens.user_id} têm
 * {@code ON DELETE CASCADE} para {@code users.id} (V1__foundation.sql), e uma conta ainda não
 * confirmada nunca tem grupo/membership/invitation associados, então apagar o {@link User} aqui é
 * seguro.
 *
 * <p><strong>Nenhum e-mail é enviado por esta rotina</strong> — é o próprio núcleo do AC-001.5
 * ("é descartada, sem e-mail de aviso"): a matriz de notificação da spec marca o aviso ao dono
 * como "não (silencioso)", porque depois do descarte a conta simplesmente deixa de existir. Por
 * isso esta classe não injeta nenhum serviço de e-mail/notificação.
 *
 * <p><strong>Borda dos "24 horas" (fechada):</strong> uma conta já é elegível no instante exato em
 * que completa 24h sem confirmação ({@code createdAt + 24h <= agora}), não só estritamente depois
 * — decisão já tomada pelos testes desta tarefa, para que uma rodada do job não precise esperar
 * mais um ciclo além do prazo anunciado à pessoa.
 *
 * <p>Reivindica o lote com {@link SkipLockedClaimer} (D-42: {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}) para que duas rodadas concorrentes do agendador nunca disputem a mesma linha.
 * {@code maxRows = 200} é só um limite de capacidade por rodada (sem convenção prévia no
 * projeto): grande o bastante para esvaziar o lote comum, pequeno o bastante para não manter um
 * lock pessimista sobre muitas linhas por muito tempo numa única transação.
 */
@Component
public class DescarteContasNaoConfirmadasRoutine implements ScheduledRoutine {

    /** AC-001.5: prazo de confirmação do e-mail depois do cadastro. */
    private static final Duration PRAZO_CONFIRMACAO = Duration.ofHours(24);

    /** Limite de contas descartadas por rodada (ver Javadoc da classe). */
    private static final int MAX_ROWS = 200;

    private static final String JPQL =
            "select u from User u where u.emailVerifiedAt is null and u.createdAt <= :corte";

    private final SkipLockedClaimer claimer;
    private final UserRepository users;

    public DescarteContasNaoConfirmadasRoutine(SkipLockedClaimer claimer, UserRepository users) {
        this.claimer = claimer;
        this.users = users;
    }

    /** Nome curto da rotina (P5: sem dado pessoal), usado só em log pelo {@code JobRunner}. */
    @Override
    public String name() {
        return "descarte-contas-nao-confirmadas";
    }

    /**
     * Uma rodada: reivindica as contas não confirmadas que já completaram 24h de criação e as
     * apaga, sem enviar nenhum e-mail (AC-001.5).
     *
     * @param clock relógio do contexto (D-10); nunca o relógio de sistema — o "agora" e o corte de
     *     24h vêm sempre daqui, para que os testes controlem o tempo com {@code MutableClock}
     */
    @Override
    public void run(Clock clock) {
        // 1. calcula o corte: toda conta criada até este instante, sem confirmação, já completou
        // (ou passou) as 24h do prazo (borda fechada).
        Instant corte = clock.instant().minus(PRAZO_CONFIRMACAO);

        // 2. reivindica o lote com lock pessimista + SKIP LOCKED (D-42), para não disputar linha
        // com outra rodada concorrente do agendador.
        List<User> contasVencidas =
                claimer.claim(User.class, JPQL, Map.of("corte", corte), MAX_ROWS);

        // 3. apaga de fato (não marca estado): libera o e-mail imediatamente e, por ON DELETE
        // CASCADE, arrasta os otp_codes/refresh_tokens da conta. Nenhum e-mail é enviado aqui.
        users.deleteAll(contasVencidas);
    }
}
