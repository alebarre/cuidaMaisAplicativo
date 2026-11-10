package br.com.cuidamais.membership.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-046 (plan D-09 rev. 2, item 1) - rotina agendada de expiracao de pedidos de acesso
 * ({@code ExpiracaoPedidosRoutine}, bean {@code ScheduledRoutine} de {@code membership.job}). A cada
 * rodada, toda membership {@code AGUARDANDO_APROVACAO} com {@code expires_at <= agora} (borda
 * FECHADA, a mesma regra do 410 de AC-006.8) passa a {@code EXPIRADO}, ganha {@code
 * retention_deadline = agora + 30 dias} e a pessoa recebe o e-mail de resultado EXPIRADO.
 *
 * <p>ACs cobertos: AC-006.5 (24 h sem decisao => EXPIRADO), AC-006.8 (aprovar/recusar vencido e 410
 * {@code REQUEST_EXPIRED} MESMO antes do job e depois dele), AC-007.1 (e-mail ao expirar), AC-007.2
 * (o e-mail orienta pedir novo convite; a pessoa pode ser convidada de novo, AC-003.6) e AC-014.1
 * (so a gravacao do {@code retention_deadline}; a exclusao aos 30 dias e a T-049). Decisoes: D-09
 * rev. 2 item 1, D-10 (tempo so pelo {@code MutableClock}), D-23 (e-mail so apos o commit), D-42
 * (SKIP LOCKED e idempotencia: rodar 2x nunca envia e-mail em dobro).
 *
 * <p><b>Decisoes fixadas nestes testes (a confirmar pelo dono da spec):</b>
 * <ul>
 *   <li>{@code retention_deadline} = instante do JOB + 30 dias (nao {@code expires_at + 30 d}):
 *       D-09 diz "agora + 30 d" e AC-014.1 conta "desde a transicao para EXPIRADO"; um job atrasado
 *       (rodando 26 h depois do pedido) grava o prazo a partir de quando a transicao de fato ocorreu.
 *   <li>O pedido expirado NAO ganha {@code decided_by} nem {@code decided_at}: ninguem decidiu
 *       ({@code Membership.expire} nao os toca); a coluna {@code retention_deadline} e lida por JDBC,
 *       sem depender de getter.
 *   <li>Transicoes que nao sao expiracao ({@code ATIVO}, {@code RECUSADO}, {@code REMOVIDO} e
 *       {@code EXPIRADO} ja expirado) nao sao tocadas pelo job: status, {@code decided_*} e
 *       {@code retention_deadline} ficam como estavam e nenhum e-mail sai.
 *   <li>Corrida job x aprovacao: o relogio e o mesmo para as duas acoes, entao so ha disputa real
 *       na virada do prazo; o resultado e SEMPRE coerente: ou {@code ATIVO} (aprovacao venceu, o job
 *       nao expira, 1 e-mail de aprovacao, sem {@code retention_deadline}) ou {@code EXPIRADO}
 *       (job venceu, aprovacao 410/409, 1 e-mail de expiracao, com {@code retention_deadline}),
 *       jamais 500 nem e-mails contraditorios.
 * </ul>
 *
 * <p>Os testes usam {@link JobRunner#runAll()} e nao importam a classe da rotina (assim a falha
 * antes da implementacao e de comportamento, nao de compilacao): o {@code JobRunner} executa todas as
 * rotinas registradas, cada uma na propria transacao, com o {@code MutableClock} do contexto.
 */
@AutoConfigureMockMvc
class ExpiracaoPedidosTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String REGISTER = "/api/v1/invitations/register";
    private static final String SENHA = "Senha@Forte123";
    private static final String CODIGO = "123456";
    private static final String MSG_EXPIRADO = "Este pedido expirou. Envie um novo convite.";
    private static final String ASSUNTO_EXPIRADO = "Cuida+: seu pedido expirou";
    private static final String ASSUNTO_APROVADO = "Cuida+: seu pedido foi aprovado";
    private static final Duration PRAZO_PEDIDO = Duration.ofHours(24);
    private static final Duration RETENCAO = Duration.ofDays(30);
    private static final int RODADAS = 5;

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JobRunner runner;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private record Pedido(User user, UUID memberId) {}

    /**
     * O banco e o Mailpit sao compartilhados por todas as classes de teste e o relogio nunca volta:
     * pedidos vencidos deixados por outras classes seriam expirados (com e-mail) pela primeira
     * rodada do job aqui, inundando a caixa (a API do Mailpit lista so as 50 mais recentes). Por
     * isso cada teste comeca drenando esse legado (uma rodada, esperando a caixa estabilizar) e
     * zerando o Mailpit; os pedidos do teste so sao criados depois.
     */
    @BeforeEach
    void drenaLegadoEZeraCaixa() {
        runner.runAll();
        int[] ultimo = {-1};
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).until(() -> {
            int atual = mailpit.count();
            boolean estavel = atual == ultimo[0];
            ultimo[0] = atual;
            return estavel;
        });
        mailpit.deleteAll();
    }

    // ============================================================ AC-006.8 antes do job

    /**
     * Pronto quando (parte 1): com o relogio 24 h adiante e SEM o job ter rodado, approve e reject
     * respondem 410 REQUEST_EXPIRED com a mensagem exata, o status no banco continua
     * AGUARDANDO_APROVACAO (quem expira e o job), nada de retention_deadline e nenhum e-mail.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.8: com o relógio +24h e antes do job, approve e reject => 410 REQUEST_EXPIRED e o status ainda é AGUARDANDO_APROVACAO")
    void ac_006_8_antes_do_job_decidir_responde_410(String acao) throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Vencida");
        long versao = versaoAtual(p);
        clock.advance(PRAZO_PEDIDO);
        mailpit.deleteAll();

        // Cada linha: a decisao (approve ou reject) de um pedido vencido que o job ainda nao tocou.
        esperar410(decidir(acao, g, p, g.responsavel(), versao));

        assertThat(statusNoBanco(p)).as("quem expira e o job, nao a leitura").isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(retentionDeadline(p)).as("sem job, sem retention_deadline").isNull();
        semEmail(p.user().getEmail());
    }

    // ============================================================ AC-006.5 / AC-006.8 depois do job

    /**
     * AC-006.5: pedido AGUARDANDO_APROVACAO com 24 h completas, depois de uma rodada do job, esta
     * EXPIRADO no banco (prova que a rotina real esta registrada e grava a transicao).
     */
    @Test
    @DisplayName("AC-006.5: depois do job com o relógio +24h, o pedido está EXPIRADO")
    void ac_006_5_job_marca_pedido_vencido_como_expirado() {
        Grupo g = grupo();
        Pedido p = pedido(g, "Expira");
        assertThat(statusNoBanco(p)).as("pre-condicao").isEqualTo("AGUARDANDO_APROVACAO");
        clock.advance(PRAZO_PEDIDO);

        runner.runAll();

        assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
    }

    /**
     * AC-006.8: depois do job o pedido segue respondendo 410 REQUEST_EXPIRED (agora porque o status
     * e EXPIRADO, nao mais pelo relogio) em approve e reject; o status nao muda e nenhum e-mail novo
     * sai alem do de expiracao.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.8: depois do job, approve e reject continuam 410 REQUEST_EXPIRED e o status segue EXPIRADO")
    void ac_006_8_depois_do_job_decidir_continua_410(String acao) throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "ExpiradaDepois");
        long versao = versaoAtual(p);
        clock.advance(PRAZO_PEDIDO);
        runner.runAll();
        assertThat(statusNoBanco(p)).as("pre-condicao: o job expirou").isEqualTo("EXPIRADO");
        umEmail(p.user().getEmail()); // o de expiracao; aguarda-o para o proximo passo ser inequivoco

        // Cada linha: approve ou reject de um pedido ja EXPIRADO.
        esperar410(decidir(acao, g, p, g.responsavel(), versao));

        assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
        assertThat(mailpit.messagesTo(p.user().getEmail())).as("so o e-mail de expiracao").hasSize(1);
    }

    // ============================================================ AC-007.1 / AC-007.2 e-mail

    /**
     * AC-007.1: a rodada envia exatamente 1 e-mail ao endereco da pessoa, com o assunto do template
     * EXPIRADO, e o corpo nao traz dado de saude nem o nome do idoso (P5).
     */
    @Test
    @DisplayName("AC-007.1: o job envia exatamente 1 e-mail de expiração à pessoa, sem dado do idoso")
    void ac_007_1_job_envia_um_email_de_expiracao() {
        Grupo g = grupo();
        Pedido p = pedido(g, "EmailExpira");
        clock.advance(PRAZO_PEDIDO);
        mailpit.deleteAll();

        runner.runAll();

        MailpitClient.Detail mail = umEmail(p.user().getEmail());
        assertThat(mail.subject()).isEqualTo(ASSUNTO_EXPIRADO);
        assertThat(mail.text()).contains("expirou");
        assertThat(mail.text()).as("P5: nada do idoso nem do Responsavel").doesNotContain("Francisca").doesNotContain("Zélia");
    }

    /** AC-007.2: o texto do e-mail de expiracao orienta a pedir um novo convite ao Responsavel. */
    @Test
    @DisplayName("AC-007.2: o e-mail de expiração orienta a pedir um novo convite ao Responsável")
    void ac_007_2_email_de_expiracao_orienta_novo_convite() {
        Grupo g = grupo();
        Pedido p = pedido(g, "EmailOrienta");
        clock.advance(PRAZO_PEDIDO);
        mailpit.deleteAll();

        runner.runAll();

        assertThat(umEmail(p.user().getEmail()).text()).contains("peça um novo convite ao Responsável");
    }

    // ============================================================ AC-014.1 retention_deadline

    /**
     * Pronto quando (parte 2) / AC-014.1: com o job rodando no instante exato do vencimento (24 h),
     * retention_deadline = esse instante + 30 dias exatos. Antes do job a coluna e nula.
     */
    @Test
    @DisplayName("AC-014.1: retention_deadline = instante do job + 30 dias exatos (job no instante do vencimento)")
    void ac_014_1_retention_deadline_e_agora_mais_30_dias() {
        Grupo g = grupo();
        Pedido p = pedido(g, "Retida");
        assertThat(retentionDeadline(p)).as("pre-condicao: nula antes do job").isNull();
        clock.advance(PRAZO_PEDIDO);
        Instant instanteDoJob = clock.instant();

        runner.runAll();

        assertThat(retentionDeadline(p)).isEqualTo(instanteDoJob.plus(RETENCAO));
    }

    /**
     * AC-014.1 (decisao: conta a partir da transicao, nao do vencimento): se o job roda atrasado, 26 h
     * depois do pedido, retention_deadline = instante do job + 30 d, e NAO expires_at + 30 d.
     */
    @Test
    @DisplayName("AC-014.1: job atrasado (26h) grava retention_deadline = instante do job + 30 dias, não expires_at + 30 dias")
    void ac_014_1_retention_conta_do_instante_do_job_e_nao_do_vencimento() {
        Grupo g = grupo();
        Pedido p = pedido(g, "RetidaAtrasada");
        Instant vencimento = expiresAt(p);
        clock.advance(Duration.ofHours(26));
        Instant instanteDoJob = clock.instant();

        runner.runAll();

        assertThat(retentionDeadline(p)).isEqualTo(instanteDoJob.plus(RETENCAO));
        assertThat(retentionDeadline(p)).isNotEqualTo(vencimento.plus(RETENCAO));
    }

    // ============================================================ AC-006.5 decided_by / decided_at

    /**
     * AC-006.5: ninguem decidiu, entao o pedido expirado nao ganha decided_by nem decided_at (o
     * dominio {@code expire} so muda o status e a retencao).
     */
    @Test
    @DisplayName("AC-006.5: pedido expirado pelo job não ganha decided_by nem decided_at")
    void ac_006_5_expirado_nao_tem_decided_by_nem_decided_at() {
        Grupo g = grupo();
        Pedido p = pedido(g, "SemDecisor");
        clock.advance(PRAZO_PEDIDO);

        runner.runAll();

        assertThat(statusNoBanco(p)).as("pre-condicao: expirou").isEqualTo("EXPIRADO");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM memberships WHERE id = ?", UUID.class, p.memberId())).isNull();
        assertThat(jdbc.queryForObject("SELECT decided_at FROM memberships WHERE id = ?", Timestamp.class, p.memberId())).isNull();
    }

    // ============================================================ AC-006.5 bordas

    /**
     * Borda fechada (mesma regra do 410 de AC-006.8): 23h59 e 23h59m59,999s ainda NAO expiram (nem
     * e-mail, nem retencao); 24h exatas e 24h+1ms expiram, cada uma com exatamente 1 e-mail. Cada
     * linha: tempo decorrido e se deve expirar.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PT23H59M:false", "PT23H59M59.999S:false", "PT24H:true", "PT24H0.001S:true"})
    @DisplayName("AC-006.5: borda fechada - 23h59 não expira; 24h exatas expira")
    void ac_006_5_bordas_do_prazo(String caso) {
        String[] partes = caso.split(":");
        boolean deveExpirar = Boolean.parseBoolean(partes[1]);
        Grupo g = grupo();
        Pedido p = pedido(g, "Borda");
        clock.advance(Duration.parse(partes[0]));
        mailpit.deleteAll();

        runner.runAll();

        if (deveExpirar) {
            // Linhas 24h e 24h+1ms: vence (agora >= expires_at), com e-mail e retencao gravada.
            assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
            assertThat(umEmail(p.user().getEmail()).subject()).isEqualTo(ASSUNTO_EXPIRADO);
            assertThat(retentionDeadline(p)).isEqualTo(clock.instant().plus(RETENCAO));
        } else {
            // Linhas 23h59 e 23h59m59,999s: ainda vigente; nada muda e nada e enviado.
            assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
            assertThat(retentionDeadline(p)).isNull();
            semEmail(p.user().getEmail());
        }
    }

    // ============================================================ nao toca em outros estados

    /**
     * O job so mexe em AGUARDANDO_APROVACAO: ATIVO, RECUSADO, REMOVIDO e EXPIRADO (ja expirado), mesmo
     * muito depois de expires_at, ficam com status, decided_by, decided_at e retention_deadline
     * exatamente como estavam e sem nenhum e-mail. Cada linha: um estado de partida.
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"ATIVO", "RECUSADO", "REMOVIDO", "EXPIRADO"})
    @DisplayName("AC-006.5: o job não toca em ATIVO, RECUSADO, REMOVIDO nem EXPIRADO já expirado")
    void ac_006_5_nao_toca_em_outros_estados(MembershipStatus estado) {
        Grupo g = grupo();
        Pedido p = pedido(g, "Outro" + estado);
        levarAoEstado(g, p, estado);
        clock.advance(Duration.ofHours(30));
        String statusAntes = statusNoBanco(p);
        UUID decididoPorAntes = decidedBy(p);
        Timestamp decididoEmAntes = decidedAt(p);
        Instant retencaoAntes = retentionDeadline(p);
        mailpit.deleteAll();

        runner.runAll();

        assertThat(statusNoBanco(p)).isEqualTo(statusAntes).isEqualTo(estado.name());
        assertThat(decidedBy(p)).isEqualTo(decididoPorAntes);
        assertThat(decidedAt(p)).isEqualTo(decididoEmAntes);
        assertThat(retentionDeadline(p)).as("retention_deadline intacto").isEqualTo(retencaoAntes);
        semEmail(p.user().getEmail());
    }

    // ============================================================ idempotencia (D-42)

    /**
     * D-42: rodar o job varias vezes (duas seguidas e outras em rodadas posteriores, com o relogio
     * avancando dias) nunca envia um 2o e-mail nem muda o retention_deadline gravado na 1a vez.
     */
    @Test
    @DisplayName("AC-007.1/D-42: rodar o job de novo não reenvia o e-mail nem muda o retention_deadline")
    void ac_007_1_job_idempotente_em_rodadas_repetidas() {
        Grupo g = grupo();
        Pedido p = pedido(g, "Idempotente");
        clock.advance(PRAZO_PEDIDO);
        Instant primeiraRodada = clock.instant();
        mailpit.deleteAll();

        // Etapa 1: primeira rodada expira e envia.
        runner.runAll();
        umEmail(p.user().getEmail());
        // Etapa 2: segunda rodada imediata, no mesmo instante.
        runner.runAll();
        // Etapa 3: rodadas posteriores, com o relogio avancando (5 min, 1 dia, 29 dias).
        clock.advance(Duration.ofMinutes(5));
        runner.runAll();
        clock.advance(Duration.ofDays(1));
        runner.runAll();
        clock.advance(Duration.ofDays(29));
        runner.runAll();

        assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
        assertThat(umEmail(p.user().getEmail()).subject()).as("continua 1 so e-mail").isEqualTo(ASSUNTO_EXPIRADO);
        assertThat(retentionDeadline(p)).as("prazo da 1a rodada, nunca recalculado").isEqualTo(primeiraRodada.plus(RETENCAO));
    }

    /**
     * D-42: tres execucoes concorrentes do job sobre varios pedidos vencidos (SKIP LOCKED): todos
     * ficam EXPIRADOS com exatamente 1 e-mail cada, sem 500 nem e-mail duplicado; uma rodada
     * sequencial depois nao muda nada. Repetido em varias rodadas com grupos novos.
     */
    @Test
    @DisplayName("AC-007.1/D-42: três execuções concorrentes do job => cada pedido expira uma vez, com 1 e-mail")
    void ac_007_1_execucoes_concorrentes_do_job_nao_duplicam_email() throws Exception {
        for (int rodada = 1; rodada <= 3; rodada++) {
            Grupo g = grupo();
            List<Pedido> pedidos = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                pedidos.add(pedido(g, "Conc" + rodada + "x" + i));
            }
            clock.advance(PRAZO_PEDIDO);
            mailpit.deleteAll();

            // Etapa: tres threads largam juntas rodando o job.
            List<Callable<Integer>> acoes = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                acoes.add(() -> {
                    runner.runAll();
                    return 0;
                });
            }
            largadaSimultanea(acoes);
            runner.runAll(); // sequencial: nada pode mudar

            for (Pedido p : pedidos) {
                String ctx = "rodada " + rodada + ", " + p.user().getName();
                assertThat(statusNoBanco(p)).as(ctx).isEqualTo("EXPIRADO");
                assertThat(umEmail(p.user().getEmail()).subject()).as(ctx).isEqualTo(ASSUNTO_EXPIRADO);
            }
        }
    }

    // ============================================================ varios pedidos de uma vez

    /**
     * Varios pedidos, de dois grupos, numa so rodada: os 3 vencidos (2 do grupo A, 1 do B) expiram,
     * cada um com 1 e-mail e retention_deadline; os 2 vigentes (um por grupo, criados 12 h depois)
     * continuam AGUARDANDO_APROVACAO, sem e-mail e sem retencao.
     */
    @Test
    @DisplayName("AC-006.5/AC-007.1: vencidos e vigentes de grupos diferentes - só os vencidos expiram, 1 e-mail cada")
    void ac_006_5_varios_pedidos_so_os_vencidos_expiram() {
        Grupo a = grupo();
        Grupo b = grupo();
        Pedido a1 = pedido(a, "VencidaA1");
        Pedido a2 = pedido(a, "VencidaA2");
        Pedido b1 = pedido(b, "VencidaB1");
        clock.advance(Duration.ofHours(12));
        Pedido a3 = pedido(a, "VigenteA3");
        Pedido b2 = pedido(b, "VigenteB2");
        clock.advance(Duration.ofHours(12)); // a1, a2, b1 completam 24 h; a3, b2 tem 12 h
        Instant instanteDoJob = clock.instant();
        mailpit.deleteAll();

        runner.runAll();

        // Etapa: os tres vencidos, de grupos diferentes.
        for (Pedido vencido : List.of(a1, a2, b1)) {
            assertThat(statusNoBanco(vencido)).as(vencido.user().getName()).isEqualTo("EXPIRADO");
            assertThat(retentionDeadline(vencido)).isEqualTo(instanteDoJob.plus(RETENCAO));
            assertThat(umEmail(vencido.user().getEmail()).subject()).isEqualTo(ASSUNTO_EXPIRADO);
        }
        // Etapa: os dois vigentes ficam intactos.
        for (Pedido vigente : List.of(a3, b2)) {
            assertThat(statusNoBanco(vigente)).as(vigente.user().getName()).isEqualTo("AGUARDANDO_APROVACAO");
            assertThat(retentionDeadline(vigente)).isNull();
            semEmail(vigente.user().getEmail());
        }
    }

    // ============================================================ efeito no Responsavel

    /**
     * AC-006.9 / AC-006.2: depois do job o Responsavel ve o pedido em GET /members?status=EXPIRADO
     * (antes do job a lista de EXPIRADO nao o tem, pois o status ainda era AGUARDANDO), com
     * decidedAt ausente (ninguem decidiu); o pending-count continua contando so o vigente e a lista
     * AGUARDANDO_APROVACAO so traz o vigente.
     */
    @Test
    @DisplayName("AC-006.5: depois do job o Responsável vê o pedido em ?status=EXPIRADO e o pending-count só conta o vigente")
    void ac_006_5_responsavel_ve_o_expirado_e_o_count_cai() throws Exception {
        Grupo g = grupo();
        Pedido velha = pedido(g, "Velha");
        clock.advance(Duration.ofHours(12));
        Pedido recente = pedido(g, "Recente");
        clock.advance(Duration.ofHours(12)); // velha venceu; recente tem 12 h

        // Etapa 1: antes do job a lista EXPIRADO nao tem a velha (o status persistido ainda e AGUARDANDO).
        assertThat(idsDaLista(g, "EXPIRADO")).doesNotContain(velha.memberId().toString());
        assertThat(contagem(g)).as("vencido nao conta nem antes do job").isEqualTo(1);

        runner.runAll();

        // Etapa 2: depois do job a velha aparece como EXPIRADO, sem decidedAt.
        JsonNode itemExpirado = itemDaLista(g, "EXPIRADO", velha);
        assertThat(itemExpirado.path("status").asText()).isEqualTo("EXPIRADO");
        JsonNode decidedAt = itemExpirado.path("decidedAt");
        assertThat(decidedAt.isMissingNode() || decidedAt.isNull()).as("ninguem decidiu").isTrue();
        // Etapa 3: o badge e a lista de pendentes so tem a recente.
        assertThat(contagem(g)).isEqualTo(1);
        assertThat(idsDaLista(g, "AGUARDANDO_APROVACAO")).containsExactly(recente.memberId().toString());
    }

    /**
     * AC-008.2 / interceptor: depois de expirada, a pessoa nao acessa o grupo: com o token dela
     * qualquer rota de grupo responde 403 ACCOUNT_STATE (conta nao ATIVA, igual a pendente) e o login
     * com a senha certa responde 403 EXPIRED (AccountStateProblem).
     */
    @Test
    @DisplayName("AC-007.2: a pessoa EXPIRADA não acessa o grupo (403 ACCOUNT_STATE) e o login responde 403 EXPIRED")
    void ac_007_2_pessoa_expirada_nao_acessa_o_grupo() throws Exception {
        Grupo g = grupo();
        User pessoa = conta("Expirada Sem Acesso", passwordEncoder.encode(SENHA));
        Membership m = memberships.save(Membership.request(g.groupId(), pessoa.getId(), clock));
        clock.advance(PRAZO_PEDIDO);
        runner.runAll();
        assertThat(jdbc.queryForObject("SELECT status FROM memberships WHERE id = ?", String.class, m.getId()))
                .as("pre-condicao: expirou").isEqualTo("EXPIRADO");

        // Etapa 1: rota de grupo com o token da pessoa.
        MvcResult rota = mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(pessoa)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode problema = json.readTree(rota.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(problema)).isEmpty();
        assertThat(problema.path("code").asText()).isEqualTo("ACCOUNT_STATE");

        // Etapa 2: login com a senha certa.
        MvcResult login = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", pessoa.getEmail(), "password", SENHA))))
                .andExpect(status().isForbidden())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        assertThat(json.readTree(login.getResponse().getContentAsString()).path("code").asText()).isEqualTo("EXPIRED");
    }

    /**
     * AC-003.6 / AC-007.2: depois de expirada pelo job, a pessoa pode ser convidada de novo pelo
     * Responsavel do mesmo grupo (201), em vez de ALREADY_MEMBER (que e o que um pendente recebe).
     */
    @Test
    @DisplayName("AC-003.6: depois de expirada pelo job, a pessoa pode ser convidada de novo (201)")
    void ac_003_6_expirada_pode_ser_convidada_de_novo() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Reconvidada");
        clock.advance(PRAZO_PEDIDO);
        runner.runAll();

        mockMvc.perform(post(ELDERS + "/{e}/invitations", g.elderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content(json.writeValueAsString(Map.of("email", p.user().getEmail()))))
                .andExpect(status().isCreated());
    }

    // ============================================================ corrida job x aprovacao

    /**
     * D-08 / D-42 / AC-006.8: o relogio e compartilhado, entao a disputa real so existe na virada do
     * prazo. Em cada rodada o relogio fica 1 min antes do vencimento; uma thread aprova e outra
     * avanca 2 min (passando do vencimento) e roda o job, largando juntas. Resultados validos: ATIVO
     * (aprovacao venceu: 200, 1 e-mail de aprovacao, sem retention_deadline, job nao expira) ou
     * EXPIRADO (job venceu: aprovacao 410/409, 1 e-mail de expiracao, com retention_deadline); nunca
     * 500, nunca os dois e-mails, nunca AGUARDANDO.
     */
    @Test
    @DisplayName("AC-006.8/D-08: corrida job x aprovação na virada do prazo => ATIVO ou EXPIRADO coerente, sem 500 nem e-mail contraditório")
    void ac_006_8_corrida_job_x_aprovacao() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Pedido p = pedido(g, "Corrida" + rodada);
            long versao = versaoAtual(p);
            clock.advance(PRAZO_PEDIDO.minusMinutes(1));
            // Token emitido ANTES da virada: vale 15 min, entao ainda e valido depois dos +2 min.
            MockHttpServletRequestBuilder aprovacao = aprovar(g, p, g.responsavel(), Role.CUIDADOR, versao);
            mailpit.deleteAll();

            List<Callable<Integer>> acoes = List.of(
                    () -> mockMvc.perform(aprovacao).andReturn().getResponse().getStatus(),
                    () -> {
                        clock.advance(Duration.ofMinutes(2));
                        runner.runAll();
                        return 0;
                    });
            List<Integer> res = largadaSimultanea(acoes);

            int statusAprovacao = res.get(0);
            String ctx = "rodada " + rodada + ", aprovacao=" + statusAprovacao + ", status=" + statusNoBanco(p);
            assertThat(statusAprovacao).as("sem 500 - %s", ctx).isIn(200, 409, 410);
            if ("ATIVO".equals(statusNoBanco(p))) {
                // A aprovacao venceu: o job nao pode ter expirado nada nem enviado e-mail de expiracao.
                assertThat(statusAprovacao).as(ctx).isEqualTo(200);
                assertThat(retentionDeadline(p)).as(ctx).isNull();
                assertThat(decidedBy(p)).as(ctx).isEqualTo(g.responsavel().getId());
                assertThat(umEmail(p.user().getEmail()).subject()).as(ctx).isEqualTo(ASSUNTO_APROVADO);
            } else {
                // O job venceu: a aprovacao falhou (410, ou 409 se perdeu no meio) e so sai o e-mail de expiracao.
                assertThat(statusNoBanco(p)).as(ctx).isEqualTo("EXPIRADO");
                assertThat(statusAprovacao).as(ctx).isIn(409, 410);
                assertThat(retentionDeadline(p)).as(ctx).isNotNull();
                assertThat(decidedBy(p)).as(ctx).isNull();
                assertThat(umEmail(p.user().getEmail()).subject()).as(ctx).isEqualTo(ASSUNTO_EXPIRADO);
            }
        }
    }

    // ============================================================ ponta a ponta com /invitations/register

    /**
     * Fluxo real do pedido: convite -> redeem -> register (cria a membership AGUARDANDO_APROVACAO
     * com approvalDeadline = agora + 24 h) -> relogio +24h -> job: a membership criada pela API fica
     * EXPIRADA, com retention_deadline = job + 30 d e 1 e-mail ao e-mail cadastrado. Garante que a
     * rotina enxerga os pedidos produzidos pelo caminho de producao, nao so os semeados por
     * {@code Membership.request}.
     */
    @Test
    @DisplayName("AC-006.5: pedido criado por /invitations/register expira pelo job com e-mail e retention_deadline")
    void ac_006_5_pedido_criado_pelo_register_expira_pelo_job() throws Exception {
        Grupo g = grupo();
        String email = "expira-e2e-" + UUID.randomUUID() + "@teste.com";
        semearConvite(g, email);
        String token = resgatar(email);
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpoDeRegistro(token))))
                .andExpect(status().isCreated());
        UUID membershipId = jdbc.queryForObject(
                "SELECT m.id FROM memberships m JOIN users u ON u.id = m.user_id WHERE u.email = ?", UUID.class, email);
        Pedido p = new Pedido(users.findByEmail(email).orElseThrow(), membershipId);
        assertThat(statusNoBanco(p)).as("pre-condicao: pedido real pendente").isEqualTo("AGUARDANDO_APROVACAO");
        clock.advance(PRAZO_PEDIDO);
        Instant instanteDoJob = clock.instant();
        mailpit.deleteAll();

        runner.runAll();

        assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
        assertThat(retentionDeadline(p)).isEqualTo(instanteDoJob.plus(RETENCAO));
        assertThat(umEmail(email).subject()).isEqualTo(ASSUNTO_EXPIRADO);
    }

    // ============================================================ apoio: concorrencia

    /** Largada simultanea numa barreira; devolve os resultados na ordem das acoes. */
    private List<Integer> largadaSimultanea(List<Callable<Integer>> acoes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acoes.size());
        try {
            CountDownLatch prontos = new CountDownLatch(acoes.size());
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> acao : acoes) {
                futuros.add(pool.submit(() -> {
                    prontos.countDown();
                    largada.await();
                    return acao.call();
                }));
            }
            assertThat(prontos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<Integer> res = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                res.add(f.get(60, TimeUnit.SECONDS));
            }
            return res;
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================================================ apoio: HTTP

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), clock);
    }

    private MockHttpServletRequestBuilder aprovar(Grupo g, Pedido p, User autor, Role role, long version) {
        try {
            return post(ELDERS + "/{e}/members/{m}/approve", g.elderId(), p.memberId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer(autor))
                    .content(json.writeValueAsString(Map.of("role", role.name(), "version", version)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequestBuilder recusar(Grupo g, Pedido p, User autor) {
        return post(ELDERS + "/{e}/members/{m}/reject", g.elderId(), p.memberId())
                .header(HttpHeaders.AUTHORIZATION, bearer(autor));
    }

    private MockHttpServletRequestBuilder decidir(String acao, Grupo g, Pedido p, User autor, long version) {
        return acao.equals("approve") ? aprovar(g, p, autor, Role.CUIDADOR, version) : recusar(g, p, autor);
    }

    private void esperar410(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andExpect(status().isGone()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo("REQUEST_EXPIRED");
        assertThat(body.path("detail").asText()).isEqualTo(MSG_EXPIRADO);
    }

    private JsonNode listar(Grupo g, String status) throws Exception {
        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members?status=" + status + "&size=100", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel())))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private List<String> idsDaLista(Grupo g, String status) throws Exception {
        List<String> ids = new ArrayList<>();
        listar(g, status).path("items").forEach(i -> ids.add(i.path("id").asText()));
        return ids;
    }

    private JsonNode itemDaLista(Grupo g, String status, Pedido p) throws Exception {
        for (JsonNode item : listar(g, status).path("items")) {
            if (p.memberId().toString().equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("pedido " + p.memberId() + " nao esta em ?status=" + status);
    }

    private int contagem(Grupo g) throws Exception {
        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members/pending-count", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel())))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("count").asInt(-1);
    }

    // ============================================================ apoio: e-mail

    /** Espera exatamente um e-mail para o endereco, confirma que nao chega um segundo e o devolve. */
    private MailpitClient.Detail umEmail(String email) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
        return mailpit.message(mailpit.messagesTo(email).getFirst().id());
    }

    /** Nenhum e-mail chega ao endereco durante uma janela de observacao. */
    private void semEmail(String email) {
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).isEmpty());
    }

    // ============================================================ apoio: dados

    private String statusNoBanco(Pedido p) {
        return jdbc.queryForObject("SELECT status FROM memberships WHERE id = ?", String.class, p.memberId());
    }

    private long versaoAtual(Pedido p) {
        return jdbc.queryForObject("SELECT version FROM memberships WHERE id = ?", Long.class, p.memberId());
    }

    /** Lida por JDBC (sem depender de getter na entidade); nula quando a coluna e NULL. */
    private Instant retentionDeadline(Pedido p) {
        Timestamp t = jdbc.queryForObject(
                "SELECT retention_deadline FROM memberships WHERE id = ?", Timestamp.class, p.memberId());
        return t == null ? null : t.toInstant();
    }

    private Instant expiresAt(Pedido p) {
        return jdbc.queryForObject("SELECT expires_at FROM memberships WHERE id = ?", Timestamp.class, p.memberId())
                .toInstant();
    }

    private UUID decidedBy(Pedido p) {
        return jdbc.queryForObject("SELECT decided_by FROM memberships WHERE id = ?", UUID.class, p.memberId());
    }

    private Timestamp decidedAt(Pedido p) {
        return jdbc.queryForObject("SELECT decided_at FROM memberships WHERE id = ?", Timestamp.class, p.memberId());
    }

    /** Leva o pedido recem-criado ao estado pedido, so por transicoes de dominio validas (D-07). */
    private void levarAoEstado(Grupo g, Pedido p, MembershipStatus estado) {
        Membership m = memberships.findById(p.memberId()).orElseThrow();
        UUID decisor = g.responsavel().getId();
        switch (estado) {
            case ATIVO -> m.approve(Role.CUIDADOR, decisor, clock);
            case RECUSADO -> m.reject(decisor, clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, decisor, clock);
                m.remove(clock);
            }
            case EXPIRADO -> {
                clock.advance(Duration.ofHours(25));
                m.expire(clock);
            }
            default -> throw new IllegalArgumentException(estado.name());
        }
        memberships.save(m);
    }

    // ============================================================ apoio: fabricas

    private Grupo grupo() {
        try {
            User responsavel = conta("Zélia Responsável", "hash-irrelevante");
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, bearer(responsavel))
                            .content(json.writeValueAsString(elderInput())))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID elderId = UUID.fromString(
                    json.readTree(criacao.getResponse().getContentAsString()).path("id").asText());
            UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
            return new Grupo(responsavel, groupId, elderId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pedido AGUARDANDO_APROVACAO vigente (recem-criado no relogio atual), como no dominio. */
    private Pedido pedido(Grupo g, String nome) {
        User user = conta(nome, "hash-irrelevante");
        Membership m = memberships.save(Membership.request(g.groupId(), user.getId(), clock));
        return new Pedido(user, m.getId());
    }

    private User conta(String nome, String hashDaSenha) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "expira-" + sufixo + "@teste.com", telefone, endereco, hashDaSenha, clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private Map<String, Object> elderInput() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
        req.put("address", enderecoJson());
        req.put("emergencyContacts", List.of());
        return req;
    }

    private static Map<String, Object> enderecoJson() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        return end;
    }

    /** Semeia convite ENVIADO vigente (7 dias) com o hash real do codigo (D-04), como nos testes de register. */
    private void semearConvite(Grupo g, String email) {
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                UUID.randomUUID(), g.groupId(), email, passwordEncoder.encode(CODIGO),
                Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                g.responsavel().getId(), Timestamp.from(clock.instant()));
    }

    /** Redeem real (IP distinto por chamada, para nao esbarrar no limite) devolvendo o registrationToken. */
    private String resgatar(String email) throws Exception {
        String ip = "10.88." + (int) (Math.random() * 250 + 1) + "." + (int) (Math.random() * 250 + 1);
        MvcResult r = mockMvc.perform(post(REDEEM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(json.writeValueAsString(Map.of("email", email, "code", CODIGO))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("registrationToken").asText();
    }

    private Map<String, Object> corpoDeRegistro(String token) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("registrationToken", token);
        c.put("name", "Joana Souza");
        c.put("phone", "21912345678");
        c.put("address", enderecoJson());
        c.put("password", "Convidada#2026");
        return c;
    }
}
