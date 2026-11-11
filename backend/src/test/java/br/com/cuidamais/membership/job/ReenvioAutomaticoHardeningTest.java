package br.com.cuidamais.membership.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pacote de endurecimento das ressalvas da T-048 (reenvio automatico de convite), fora do tasks.md,
 * pedido pelo dono da spec. Complementa (sem editar) {@code ReenvioAutomaticoTest}.
 *
 * <p>Cobre quatro pontos:
 * <ol>
 *   <li><b>R-04 (log do teto)</b>: quando o 4o reenvio automatico e NEGADO (teto de 3, AC-003.3) a
 *       aplicacao registra UM log (INFO ou WARN) do logger da {@code ReenvioAutomaticoRoutine}, com a
 *       expressao fixa {@code teto de reenvios automáticos atingido} e SEM dado pessoal (nem e-mail,
 *       nem codigo/hash, nem nome - P5). Nos reenvios concedidos (1o a 3o) e no descarte "sem convite"
 *       esse log nao aparece; e nenhuma mensagem daquele logger, em nenhum caso, traz o e-mail.
 *   <li><b>Passo 4b de {@code InvitationService.resend} (D-42)</b>: apos o job (A vira CANCELADO, nasce
 *       B ENVIADO/AUTO), o reenvio manual de A deixa exatamente UM ENVIADO vigente para grupo+e-mail
 *       (o novo; B vira CANCELADO). Variante com dois ENVIADO artificiais. Estes testes JA PASSAM hoje:
 *       sao protecao contra regressao do passo 4b (validada por mutacao manual).
 *   <li><b>Dois grupos com ENVIADO para o mesmo e-mail</b>: o job reenvia apenas o ENVIADO vigente
 *       MAIS RECENTE do e-mail (decisao ja tomada); o do outro grupo fica intacto; 1 e-mail e 1
 *       reenvio consumido.
 * </ol>
 * (O gerador de codigo tem teste proprio, {@code InvitationCodeGeneratorTest}.)
 *
 * <p>Ambiente: banco, Mailpit e {@code MutableClock} sao compartilhados entre classes e o relogio nunca
 * volta; todo tempo e relativo ao relogio atual e o {@code @BeforeEach} drena o legado e o Mailpit,
 * como em {@code ReenvioAutomaticoTest}. Os testes usam {@link JobRunner#runAll()}.
 */
@AutoConfigureMockMvc
class ReenvioAutomaticoHardeningTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String CODIGO = "123456";
    private static final String ASSUNTO_PREFIXO = "Cuida+: você foi convidado(a) para o grupo de cuidado de";
    private static final String MENSAGEM_TETO = "teto de reenvios automáticos atingido";
    private static final Duration PRAZO_CONVITE = Duration.ofDays(7);
    private static final String LOGGER_DA_ROTINA = ReenvioAutomaticoRoutine.class.getName();

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
    PasswordEncoder encoder;

    private record Grupo(User responsavel, UUID groupId, UUID elderId, String nomeResponsavel, String primeiroNomeIdoso) {}

    private record Convite(
            UUID id, String status, String origin, Instant expiresAt, Instant createdAt, String codeHash,
            UUID createdBy, UUID groupId, Instant cancelledAt) {}

    private record Contador(int falhas, Instant bloqueadoAte, int reenvios) {}

    /** Um evento de log capturado: nivel e mensagem formatada (mais a mensagem da excecao, se houver). */
    private record Registro(Level nivel, String mensagem) {}

    /** Drena o legado (bloqueios e pedidos de outras classes) e zera a caixa, como em ReenvioAutomaticoTest. */
    @BeforeEach
    void drenaLegadoEZeraCaixa() {
        jdbc.update("UPDATE invite_code_attempts SET blocked_until = NULL WHERE blocked_until IS NOT NULL");
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

    // ============================================================ 1. R-04: log do teto, sem dado pessoal

    /**
     * R-04 / AC-003.3: o e-mail ja usou os 3 reenvios automaticos e tem convite ENVIADO vigente; o
     * bloqueio terminou. O job NEGA o 4o: registra exatamente UM log INFO/WARN com a expressao fixa
     * (aplicacao, sem dado pessoal) e nada mais carrega e-mail, codigo, hash nem nomes. O convite
     * segue ENVIADO e nenhum e-mail sai. Hoje nenhum log e emitido (falha por funcionalidade ausente).
     */
    @Test
    @DisplayName("R-04/AC-003.3: o 4º reenvio negado registra UM log INFO/WARN 'teto de reenvios automáticos atingido', sem e-mail, código nem nome")
    void r04_quarto_reenvio_negado_registra_log_sem_dado_pessoal() throws Exception {
        Grupo g = grupo("Zélia Responsável", "Francisca Oliveira");
        String email = emailNovo();
        UUID convite = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        semearContador(email, 5, clock.instant(), 3); // bloqueio terminado (borda fechada) e teto ja gasto

        List<Registro> logs;
        try (CapturaDeLogs captura = new CapturaDeLogs(LOGGER_DA_ROTINA)) {
            runner.runAll();
            logs = captura.registros();
        }

        // Etapa 1: exatamente um log com a expressao fixa, em nivel INFO ou WARN.
        List<Registro> doTeto = logs.stream().filter(r -> r.mensagem().contains(MENSAGEM_TETO)).toList();
        assertThat(doTeto).as("um unico log de teto atingido").hasSize(1);
        assertThat(doTeto.getFirst().nivel()).isIn(Level.INFO, Level.WARN);
        // Etapa 2: nenhuma mensagem do logger traz dado pessoal (e-mail, parte local, codigo, hash, nomes).
        assertSemDadoPessoal(logs, email, g);
        // Etapa 3: o 4o foi mesmo negado: convite intacto, nenhum e-mail, teto preservado, bloqueio limpo.
        assertThat(convite(convite).status()).isEqualTo("ENVIADO");
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 3));
        semEmailDeConvite(email);
    }

    /**
     * R-04: nos reenvios CONCEDIDOS (o 1o, 2o e 3o: autoResends previo 0, 1 e 2) o log do teto nao
     * aparece, e nenhuma mensagem do logger traz o e-mail. Controle positivo: o reenvio de fato
     * ocorreu (antigo CANCELADO, um AUTO ENVIADO, contador +1), entao o job rodou e escolheu o caminho "concedido".
     *
     * @param reenviosPrevios quantos reenvios automaticos o e-mail ja consumiu antes desta rodada
     */
    @ParameterizedTest(name = "reenvios previos = {0}")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("R-04: nos reenvios concedidos (1º a 3º) o log do teto NÃO aparece e nenhuma mensagem traz o e-mail")
    void r04_reenvios_concedidos_nao_registram_o_log_do_teto(int reenviosPrevios) throws Exception {
        Grupo g = grupo("Zélia Responsável", "Francisca Oliveira");
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        // Linha da tabela: reenvios previos 0 => 1o reenvio; 1 => 2o; 2 => 3o (todos concedidos).
        semearContador(email, 5, clock.instant(), reenviosPrevios);

        List<Registro> logs;
        try (CapturaDeLogs captura = new CapturaDeLogs(LOGGER_DA_ROTINA)) {
            runner.runAll();
            logs = captura.registros();
        }

        // Etapa 1: controle positivo - o reenvio foi concedido.
        assertThat(convite(antigo).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
        assertThat(contador(email).reenvios()).isEqualTo(reenviosPrevios + 1);
        // Etapa 2: sem o log do teto e sem e-mail nas mensagens.
        assertThat(logs).noneMatch(r -> r.mensagem().contains(MENSAGEM_TETO));
        assertSemDadoPessoal(logs, email, g);
    }

    /**
     * R-04: o descarte "sem convite" (bloqueio terminado, nenhum convite ENVIADO) nao gasta o teto e
     * nao deve registrar o log do teto, mesmo com o teto ja em 3. Controle positivo: o job tratou a
     * linha (blocked_until limpo) e o contador de reenvios segue 3.
     */
    @Test
    @DisplayName("R-04: o descarte 'sem convite' não registra o log do teto (mesmo com 3 reenvios já gastos) e não traz o e-mail")
    void r04_descarte_sem_convite_nao_registra_o_log_do_teto() throws Exception {
        String email = emailNovo();
        semearContador(email, 5, clock.instant(), 3); // sem nenhum convite para o e-mail

        List<Registro> logs;
        try (CapturaDeLogs captura = new CapturaDeLogs(LOGGER_DA_ROTINA)) {
            runner.runAll();
            logs = captura.registros();
        }

        // Etapa 1: controle positivo - a linha foi tratada (bloqueio limpo) sem gastar o teto.
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 3));
        // Etapa 2: nenhum log do teto e nenhum e-mail nas mensagens.
        assertThat(logs).noneMatch(r -> r.mensagem().contains(MENSAGEM_TETO));
        assertThat(logs).noneMatch(r -> r.mensagem().contains(email));
        semEmailDeConvite(email);
    }

    /**
     * R-04: na mesma rodada, um e-mail no teto (negado) e outro com reenvio concedido: so o negado gera
     * o log do teto (exatamente um), e a mensagem nao identifica nenhum dos dois e-mails.
     */
    @Test
    @DisplayName("R-04: na mesma rodada só o e-mail negado gera o log do teto (um), sem identificar nenhum dos dois e-mails")
    void r04_so_o_negado_gera_log_na_rodada_mista() throws Exception {
        Grupo g = grupo("Zélia Responsável", "Francisca Oliveira");
        String negado = emailNovo();
        String concedido = emailNovo();
        semearConvite(g, negado, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        UUID antigoConcedido = semearConvite(g, concedido, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        semearContador(negado, 5, clock.instant(), 3);
        semearContador(concedido, 5, clock.instant(), 0);

        List<Registro> logs;
        try (CapturaDeLogs captura = new CapturaDeLogs(LOGGER_DA_ROTINA)) {
            runner.runAll();
            logs = captura.registros();
        }

        // Etapa 1: controle positivo - o outro e-mail foi reenviado.
        assertThat(convite(antigoConcedido).status()).isEqualTo("CANCELADO");
        // Etapa 2: um unico log do teto; nenhum dos e-mails aparece em nenhuma mensagem.
        assertThat(logs.stream().filter(r -> r.mensagem().contains(MENSAGEM_TETO))).hasSize(1);
        assertSemDadoPessoal(logs, negado, g);
        assertSemDadoPessoal(logs, concedido, g);
    }

    // ============================================================ 2. passo 4b do resend (D-42), regressao

    /**
     * D-42 / passo 4b (JA PASSA hoje; protecao contra regressao): o job reenvia (A vira CANCELADO e nasce
     * B ENVIADO/AUTO); depois o Responsavel reenvia manualmente o CANCELADO A. O passo 4b cancela os
     * OUTROS ENVIADO vigentes do grupo+e-mail: ao final so o convite novo (C) esta ENVIADO e B ficou
     * CANCELADO. Sem o passo 4b, B e C ficariam ambos ENVIADO.
     */
    @Test
    @DisplayName("D-42/passo 4b: resend manual do convite A (cancelado pelo job) deixa só o novo ENVIADO; o AUTO B vira CANCELADO")
    void d42_resend_manual_apos_o_job_cancela_o_convite_auto() throws Exception {
        Grupo g = grupo("Zélia Responsável", "Francisca Oliveira");
        String email = emailNovo();
        UUID a = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        semearContador(email, 5, clock.instant(), 0);

        // Etapa 1: o job reenvia: A CANCELADO, nasce B ENVIADO/AUTO.
        runner.runAll();
        assertThat(convite(a).status()).isEqualTo("CANCELADO");
        List<Convite> aposJob = convitesDe(email, "ENVIADO");
        assertThat(aposJob).hasSize(1);
        assertThat(aposJob.getFirst().origin()).isEqualTo("AUTO");
        UUID b = aposJob.getFirst().id();

        // Etapa 2: o Responsavel reenvia manualmente o CANCELADO A (HTTP real).
        UUID c = reenviarConvite(g, a);

        // Etapa 3: exatamente um ENVIADO vigente (o novo C, MANUAL); B ficou CANCELADO; A segue CANCELADO.
        List<Convite> enviados = convitesDe(email, "ENVIADO");
        assertThat(enviados).as("nao pode haver dois ENVIADO para grupo+e-mail").hasSize(1);
        assertThat(enviados.getFirst().id()).isEqualTo(c);
        assertThat(enviados.getFirst().origin()).isEqualTo("MANUAL");
        assertThat(convite(b).status()).isEqualTo("CANCELADO");
        assertThat(convite(a).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(email)).hasSize(3);
    }

    /**
     * D-42 / passo 4b (JA PASSA hoje; regressao), variante: dois ENVIADO vigentes artificiais do mesmo
     * grupo+e-mail inseridos direto no banco; o resend de um deles deixa so o novo ENVIADO (o outro
     * ENVIADO, que o dominio nao cancelaria sozinho, e cancelado pelo passo 4b).
     */
    @Test
    @DisplayName("D-42/passo 4b: com dois ENVIADO vigentes do mesmo grupo e e-mail, o resend de um deixa só o novo ENVIADO")
    void d42_resend_cancela_o_outro_enviado_vigente_do_mesmo_grupo_e_email() throws Exception {
        Grupo g = grupo("Zélia Responsável", "Francisca Oliveira");
        String email = emailNovo();
        UUID um = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        UUID outro = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g.responsavel().getId());
        assertThat(convitesDe(email, "ENVIADO")).as("pre-condicao: dois ENVIADO").hasSize(2);

        UUID novo = reenviarConvite(g, um);

        List<Convite> enviados = convitesDe(email, "ENVIADO");
        assertThat(enviados).hasSize(1);
        assertThat(enviados.getFirst().id()).isEqualTo(novo);
        assertThat(convite(um).status()).isEqualTo("CANCELADO");
        assertThat(convite(outro).status()).as("o OUTRO ENVIADO e cancelado pelo passo 4b").isEqualTo("CANCELADO");
    }

    // ============================================================ 4. dois grupos com ENVIADO para o mesmo e-mail

    /**
     * Decisao ja tomada: o mesmo e-mail com convite ENVIADO vigente em dois grupos; o job reenvia SO o
     * MAIS RECENTE (G2). O de G2 vira CANCELADO e nasce um AUTO em G2 (created_by do Responsavel de
     * G2); o de G1 (mais antigo) permanece ENVIADO e intacto (todas as colunas iguais). Exatamente 1
     * e-mail, com o primeiro nome do idoso de G2, e o contador consome 1 reenvio.
     */
    @Test
    @DisplayName("AC-003.3: com ENVIADO em dois grupos para o mesmo e-mail, o job reenvia só o mais recente (G2) e deixa o de G1 intacto")
    void ac_003_3_reenvia_so_o_convite_mais_recente_entre_dois_grupos() throws Exception {
        Grupo g1 = grupo("Marta Primeira", "Antonia Alves");
        Grupo g2 = grupo("Josefa Segunda", "Benedita Prado");
        String email = emailNovo();
        // Etapa 1: G1 mais antigo; o relogio avanca 1 min e G2 nasce depois (created_at maior).
        UUID emG1 = semearConvite(g1, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g1.responsavel().getId());
        clock.advance(Duration.ofMinutes(1));
        UUID emG2 = semearConvite(g2, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), g2.responsavel().getId());
        semearContador(email, 5, clock.instant(), 0); // bloqueio vencido agora (borda fechada)
        Convite g1Antes = convite(emG1);
        assertThat(convite(emG2).createdAt()).as("pre-condicao: G2 e o mais recente").isAfter(g1Antes.createdAt());

        runner.runAll();

        // Etapa 2: G2 foi cancelado e nasceu um AUTO ENVIADO em G2, criado pelo Responsavel de G2.
        assertThat(convite(emG2).status()).isEqualTo("CANCELADO");
        List<Convite> doG2Enviados = convitesDe(email, "ENVIADO").stream()
                .filter(c -> c.groupId().equals(g2.groupId())).toList();
        assertThat(doG2Enviados).hasSize(1);
        assertThat(doG2Enviados.getFirst().origin()).isEqualTo("AUTO");
        assertThat(doG2Enviados.getFirst().createdBy()).isEqualTo(g2.responsavel().getId());
        // Etapa 3: G1 intacto (todas as colunas) e ainda o unico ENVIADO fora de G2.
        assertThat(convite(emG1)).isEqualTo(g1Antes);
        assertThat(convite(emG1).status()).isEqualTo("ENVIADO");
        assertThat(convitesDe(email)).as("G1 + G2 antigo + G2 novo").hasSize(3);
        // Etapa 4: um unico reenvio consumido e um unico e-mail, com o primeiro nome do idoso de G2.
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(emailsDeConvite(email)).hasSize(1));
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeConvite(email)).hasSize(1));
        assertThat(emailsDeConvite(email).getFirst().subject()).isEqualTo(ASSUNTO_PREFIXO + " " + g2.primeiroNomeIdoso());
    }

    // ============================================================ apoio: logs

    /** Nenhuma mensagem pode trazer e-mail, parte local, codigo semeado, hash BCrypt nem nomes do grupo (P5). */
    private static void assertSemDadoPessoal(List<Registro> logs, String email, Grupo g) {
        String parteLocal = email.substring(0, email.indexOf('@'));
        for (Registro r : logs) {
            assertThat(r.mensagem())
                    .doesNotContain(email)
                    .doesNotContain(parteLocal)
                    .doesNotContain(CODIGO)
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$")
                    .doesNotContain(g.nomeResponsavel())
                    .doesNotContain(g.primeiroNomeIdoso());
        }
    }

    /** Captura os eventos de um logger (nivel INFO ou mais detalhado) em memoria; restaura o nivel ao fechar. */
    private static final class CapturaDeLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level nivelAnterior;

        CapturaDeLogs(String nomeDoLogger) {
            this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(nomeDoLogger);
            this.nivelAnterior = logger.getLevel();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
        }

        List<Registro> registros() {
            List<ILoggingEvent> copia;
            synchronized (appender) {
                copia = new ArrayList<>(appender.list);
            }
            List<Registro> res = new ArrayList<>();
            for (ILoggingEvent e : copia) {
                String extra = e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage();
                res.add(new Registro(e.getLevel(), e.getFormattedMessage() + extra));
            }
            return res;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(nivelAnterior);
            appender.stop();
        }
    }

    // ============================================================ apoio: HTTP

    private String bearer(Grupo g) {
        return "Bearer " + jwtService.issue(g.responsavel().getId(), clock);
    }

    /** Reenvio manual real (POST resend); devolve o id do convite novo. */
    private UUID reenviarConvite(Grupo g, UUID convite) throws Exception {
        MvcResult r = mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/resend", g.elderId(), convite)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).path("id").asText());
    }

    // ============================================================ apoio: e-mail

    private List<MailpitClient.Summary> emailsDeConvite(String email) {
        return mailpit.messagesTo(email).stream().filter(m -> m.subject() != null && m.subject().startsWith(ASSUNTO_PREFIXO)).toList();
    }

    /** Nenhum e-mail de convite chega ao endereco (espera uma janela curta para dar chance a um envio indevido). */
    private void semEmailDeConvite(String email) {
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeConvite(email)).isEmpty());
    }

    // ============================================================ apoio: banco

    private Convite convite(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, status, origin, expires_at, created_at, code_hash, created_by, group_id, cancelled_at"
                        + " FROM invitations WHERE id = ?",
                (rs, i) -> mapa(rs), id);
    }

    private List<Convite> convitesDe(String email) {
        return jdbc.query(
                "SELECT id, status, origin, expires_at, created_at, code_hash, created_by, group_id, cancelled_at"
                        + " FROM invitations WHERE email = ?",
                (rs, i) -> mapa(rs), email);
    }

    private List<Convite> convitesDe(String email, String status) {
        return convitesDe(email).stream().filter(c -> c.status().equals(status)).toList();
    }

    private static Convite mapa(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp cancelado = rs.getTimestamp("cancelled_at");
        return new Convite(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("origin"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("code_hash"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("group_id", UUID.class),
                cancelado == null ? null : cancelado.toInstant());
    }

    private Contador contador(String email) {
        List<Contador> v = jdbc.query(
                "SELECT failed_attempts, blocked_until, auto_resends FROM invite_code_attempts WHERE email = ?",
                (rs, i) -> {
                    Timestamp ate = rs.getTimestamp("blocked_until");
                    return new Contador(rs.getInt("failed_attempts"), ate == null ? null : ate.toInstant(), rs.getInt("auto_resends"));
                },
                email);
        return v.isEmpty() ? new Contador(0, null, 0) : v.getFirst();
    }

    private void semearContador(String email, int falhas, Instant bloqueadoAte, int reenvios) {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                email, falhas, bloqueadoAte == null ? null : Timestamp.from(bloqueadoAte), reenvios,
                Timestamp.from(clock.instant()));
    }

    /** Semeia convite MANUAL com o hash real do codigo (D-04), no status e criador pedidos, created_at = agora. */
    private UUID semearConvite(Grupo g, String email, String status, Instant expiresAt, UUID criadoPor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                id, g.groupId(), email, encoder.encode(CODIGO), status, Timestamp.from(expiresAt), criadoPor,
                Timestamp.from(clock.instant()));
        return id;
    }

    // ============================================================ apoio: fabricas

    /** Responsavel ATIVO que cria o idoso (e o grupo) pela API real. */
    private Grupo grupo(String nomeResponsavel, String nomeIdoso) {
        try {
            User responsavel = conta(nomeResponsavel);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("name", nomeIdoso);
            req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
            req.put("address", enderecoJson());
            req.put("allergies", "Dipirona-Zq");
            req.put("conditions", "Diabetes-Zq");
            req.put("emergencyContacts", List.of());
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(responsavel.getId(), clock))
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID elderId = UUID.fromString(json.readTree(criacao.getResponse().getContentAsString()).path("id").asText());
            UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
            return new Grupo(responsavel, groupId, elderId, nomeResponsavel, nomeIdoso.split(" ")[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private User conta(String nome) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "reenvio-hard-" + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return "reenvio-hard-" + UUID.randomUUID() + "@teste.com";
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
}
