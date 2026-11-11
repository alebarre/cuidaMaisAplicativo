package br.com.cuidamais.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pacote de endurecimento do {@link InviteCodeAttemptsLocker} (D-34, R-08), fora do tasks.md, pedido
 * pelo dono da spec a partir de uma corrida reproduzida pelo verificador da T-049.
 *
 * <p>Corrida coberta: a linha do contador existe, a transacao B chama {@code lock(email)} (ou
 * {@code lockIfPresent}) e fica esperando o {@code SELECT ... FOR UPDATE} do {@code refresh}
 * porque a transacao P segura a linha; P entao APAGA a linha e comita. Um {@code refresh} direto de B
 * encontraria zero linhas e lancaria {@code EntityNotFoundException} (que viraria HTTP 500 ou
 * derrubaria a rotina). Comportamento garantido: {@code lock} nunca falha por isso (recria a linha e devolve um
 * contador novo, zerado e travado) e {@code lockIfPresent} devolve {@code Optional.empty()}.
 *
 * <p>Tambem fixa os caminhos comuns, que devem continuar iguais: criar a linha zerada quando falta
 * ({@code lock}), devolver a linha preexistente com os valores relidos do banco (mesmo que a
 * entidade ja esteja no contexto de persistencia com valores antigos) e vazio quando falta
 * ({@code lockIfPresent}).
 *
 * <p>Sincronizacao: sem {@code sleep} cego. P so apaga a linha depois que o Postgres mostra, em
 * {@code pg_stat_activity}, uma sessao ativa esperando um lock ({@code wait_event_type = 'Lock'})
 * numa consulta sobre {@code invite_code_attempts} (ou seja, B realmente bloqueou). A unica
 * dependencia de tempo e o limite maximo (timeout) das esperas, que so estoura se algo travar.
 * Cada teste usa um e-mail proprio e o apaga no fim; as linhas nunca tem {@code blocked_until}, de
 * modo que nenhuma rotina agendada as enxerga.
 */
class InviteCodeAttemptsLockerTest extends IntegrationTest {

    /** Estado nao-zero do contador preexistente (distingue "relido" de "recriado zerado"). */
    private static final int FALHAS_INICIAIS = 3;

    private static final int REENVIOS_INICIAIS = 2;

    @Autowired
    InviteCodeAttemptsLocker locker;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    private final String email = "locker-" + UUID.randomUUID() + "@teste.com";

    /** Estado do contador visto por quem o travou, copiado dentro da transacao. */
    private record Visto(int falhas, Instant bloqueadoAte, int reenvios) {}

    /** Resultado de B: o que ela viu e/ou a excecao que a derrubou (para falhar por assercao, nao por erro de thread). */
    private record ResultadoB(Visto visto, boolean vazio, Throwable erro) {}

    @AfterEach
    void apagaLinha() {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
    }

    // ============================================================ corrida: linha apagada durante a espera

    /**
     * D-34/R-08, caminho {@code lock}: P segura a linha preexistente (falhas=3, reenvios=2); B chama
     * {@code lock} e bloqueia no FOR UPDATE; P apaga a linha e comita. B deve terminar SEM excecao,
     * com um contador novo (0 falhas, sem bloqueio, 0 reenvios) e a linha deve existir ao final,
     * recriada por B (nao o valor antigo de P, que foi apagado).
     */
    @Test
    @DisplayName("D-34/R-08: lock() cuja linha é apagada e comitada por outra transação enquanto espera não falha: devolve contador novo zerado e a linha existe")
    void r08_lock_recria_a_linha_apagada_enquanto_esperava() throws Exception {
        semearLinha(FALHAS_INICIAIS, REENVIOS_INICIAIS);

        ResultadoB b = corrida(true);

        // Etapa 1: B nao pode ter falhado (hoje: EntityNotFoundException do refresh).
        assertThat(b.erro()).as("lock() nao deve lancar quando a linha some durante a espera").isNull();
        // Etapa 2: o contador devolvido e novo e zerado (nao o que P segurava).
        assertThat(b.visto()).isEqualTo(new Visto(0, null, 0));
        // Etapa 3: a linha existe ao final (B a recriou) e esta zerada no banco.
        assertThat(contagemDeLinhas()).as("a linha foi recriada por B").isEqualTo(1);
        assertThat(lerDoBanco()).isEqualTo(new Visto(0, null, 0));
    }

    /**
     * D-34/R-08, caminho {@code lockIfPresent}: mesma corrida; como o metodo nunca cria a linha e ela
     * sumiu enquanto B esperava, o resultado deve ser {@code Optional.empty()} (sem excecao) e a
     * linha continua inexistente.
     */
    @Test
    @DisplayName("D-34/R-08: lockIfPresent() cuja linha é apagada e comitada por outra transação enquanto espera devolve vazio, sem exceção, e não recria a linha")
    void r08_lockIfPresent_devolve_vazio_quando_a_linha_some_enquanto_esperava() throws Exception {
        semearLinha(FALHAS_INICIAIS, REENVIOS_INICIAIS);

        ResultadoB b = corrida(false);

        // Etapa 1: sem excecao (hoje: EntityNotFoundException do refresh).
        assertThat(b.erro()).as("lockIfPresent() nao deve lancar quando a linha some durante a espera").isNull();
        // Etapa 2: vazio, porque a linha nao existe mais.
        assertThat(b.vazio()).as("Optional.empty() quando a linha sumiu").isTrue();
        // Etapa 3: lockIfPresent nao cria linha.
        assertThat(contagemDeLinhas()).as("lockIfPresent nao recria a linha").isZero();
    }

    // ============================================================ caminhos comuns (sem corrida)

    /**
     * Caminho comum de {@code lock}: sem linha previa, cria o contador zerado e travado; ao comitar a
     * linha existe no banco com 0 falhas, sem bloqueio e 0 reenvios.
     */
    @Test
    @DisplayName("D-34: lock() cria o contador zerado quando a linha não existe")
    void d34_lock_cria_a_linha_quando_falta() {
        assertThat(contagemDeLinhas()).isZero();

        Visto visto = transacao().execute(s -> visto(locker.lock(email)));

        assertThat(visto).isEqualTo(new Visto(0, null, 0));
        assertThat(contagemDeLinhas()).isEqualTo(1);
        assertThat(lerDoBanco()).isEqualTo(new Visto(0, null, 0));
    }

    /**
     * Caminho comum de {@code lock}: com linha previa, devolve os valores do banco (3 falhas, 2
     * reenvios) e nao a zera. Nao e a corrida: ninguem apaga nada.
     */
    @Test
    @DisplayName("D-34: lock() devolve a linha preexistente com os valores do banco, sem zerá-la")
    void d34_lock_devolve_a_linha_preexistente() {
        semearLinha(FALHAS_INICIAIS, REENVIOS_INICIAIS);

        Visto visto = transacao().execute(s -> visto(locker.lock(email)));

        assertThat(visto).isEqualTo(new Visto(FALHAS_INICIAIS, null, REENVIOS_INICIAIS));
        assertThat(lerDoBanco()).isEqualTo(new Visto(FALHAS_INICIAIS, null, REENVIOS_INICIAIS));
    }

    /**
     * R-08: a leitura sob lock e sempre relida do banco, mesmo que a entidade ja esteja no contexto de
     * persistencia com valor antigo. Passos: (1) carrega a entidade (3 falhas); (2) outra "escrita"
     * muda a linha no banco para 4 falhas / 1 reenvio por SQL; (3) {@code lock} deve devolver 4 / 1.
     */
    @Test
    @DisplayName("R-08: lock() relê do banco o valor atual mesmo com a entidade já carregada no contexto")
    void r08_lock_rele_o_valor_do_banco_apesar_do_contexto() {
        semearLinha(FALHAS_INICIAIS, REENVIOS_INICIAIS);

        Visto visto = transacao().execute(s -> {
            // Etapa 1: entidade carregada e gerenciada com o valor antigo (3 falhas, 2 reenvios).
            InviteCodeAttempts antiga = entityManager.find(InviteCodeAttempts.class, email);
            assertThat(antiga.getAutoResends()).isEqualTo(REENVIOS_INICIAIS);
            // Etapa 2: o banco muda por baixo do contexto (SQL direto na mesma transacao).
            jdbc.update("UPDATE invite_code_attempts SET failed_attempts = 4, auto_resends = 1 WHERE email = ?", email);
            // Etapa 3: o lock relê e devolve o valor atual do banco.
            return visto(locker.lock(email));
        });

        assertThat(visto).isEqualTo(new Visto(4, null, 1));
    }

    /**
     * Caminho comum de {@code lockIfPresent}: sem linha, devolve vazio e nao cria nada.
     */
    @Test
    @DisplayName("D-34: lockIfPresent() devolve vazio e não cria a linha quando ela não existe")
    void d34_lockIfPresent_vazio_quando_nao_ha_linha() {
        Optional<Visto> resultado = transacao().execute(s -> locker.lockIfPresent(email).map(this::visto));

        assertThat(resultado).isEmpty();
        assertThat(contagemDeLinhas()).isZero();
    }

    /**
     * Caminho comum de {@code lockIfPresent}: com linha, devolve-a travada e com os valores relidos do
     * banco (mesma garantia de reler que {@code lock}).
     */
    @Test
    @DisplayName("R-08: lockIfPresent() devolve a linha existente com os valores relidos do banco")
    void r08_lockIfPresent_rele_o_valor_do_banco() {
        semearLinha(FALHAS_INICIAIS, REENVIOS_INICIAIS);

        Optional<Visto> resultado = transacao().execute(s -> {
            // Etapa 1: contexto com o valor antigo.
            entityManager.find(InviteCodeAttempts.class, email);
            // Etapa 2: o banco muda por baixo.
            jdbc.update("UPDATE invite_code_attempts SET failed_attempts = 4, auto_resends = 1 WHERE email = ?", email);
            // Etapa 3: lockIfPresent relê.
            return locker.lockIfPresent(email).map(this::visto);
        });

        assertThat(resultado).contains(new Visto(4, null, 1));
    }

    // ============================================================ apoio: a corrida

    /**
     * Executa a corrida: P trava a linha e a segura; B chama {@code lock} ({@code usarLock = true}) ou
     * {@code lockIfPresent} e bloqueia; so entao P apaga a linha e comita. Devolve o que B viu.
     */
    private ResultadoB corrida(boolean usarLock) throws Exception {
        CountDownLatch pTravou = new CountDownLatch(1);
        CountDownLatch pPodeApagar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // P: trava a linha preexistente e a segura ate B estar bloqueada; entao apaga e comita.
            Future<?> p = pool.submit(() -> transacao().executeWithoutResult(s -> {
                locker.lock(email);
                pTravou.countDown();
                aguardar(pPodeApagar);
                jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
            }));
            assertThat(pTravou.await(30, TimeUnit.SECONDS)).as("P travou a linha").isTrue();

            // B: chama o metodo sob teste; fica bloqueada no FOR UPDATE ate P comitar.
            Future<ResultadoB> b = pool.submit(() -> {
                try {
                    return transacao().execute(s -> {
                        if (usarLock) {
                            return new ResultadoB(visto(locker.lock(email)), false, null);
                        }
                        Optional<InviteCodeAttempts> r = locker.lockIfPresent(email);
                        return new ResultadoB(r.map(this::visto).orElse(null), r.isEmpty(), null);
                    });
                } catch (Throwable t) {
                    return new ResultadoB(null, false, t);
                }
            });

            // Sincroniza de forma deterministica: so libera P quando o Postgres mostra B esperando lock.
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertThat(sessoesEsperandoLockNoContador())
                            .as("B deve estar bloqueada esperando o lock da linha")
                            .isPositive());
            pPodeApagar.countDown();

            p.get(30, TimeUnit.SECONDS);
            return b.get(60, TimeUnit.SECONDS);
        } finally {
            pPodeApagar.countDown();
            pool.shutdownNow();
        }
    }

    /** Sessoes ativas esperando um lock de linha/transacao numa consulta sobre invite_code_attempts. */
    private int sessoesEsperandoLockNoContador() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND wait_event_type = 'Lock'"
                        + " AND query ILIKE '%invite_code_attempts%' AND pid <> pg_backend_pid()",
                Integer.class);
        return n == null ? 0 : n;
    }

    // ============================================================ apoio: banco e transacao

    private TransactionTemplate transacao() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private Visto visto(InviteCodeAttempts a) {
        return new Visto(a.failedAttempts(clock), a.blockedUntil(clock), a.getAutoResends());
    }

    private void semearLinha(int falhas, int reenvios) {
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, NULL, ?, ?)",
                email, falhas, reenvios, Timestamp.from(clock.instant()));
    }

    private int contagemDeLinhas() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM invite_code_attempts WHERE email = ?", Integer.class, email);
        return n == null ? 0 : n;
    }

    private Visto lerDoBanco() {
        return jdbc.queryForObject(
                "SELECT failed_attempts, blocked_until, auto_resends FROM invite_code_attempts WHERE email = ?",
                (rs, i) -> {
                    Timestamp ate = rs.getTimestamp("blocked_until");
                    return new Visto(rs.getInt("failed_attempts"), ate == null ? null : ate.toInstant(), rs.getInt("auto_resends"));
                },
                email);
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch não liberado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
