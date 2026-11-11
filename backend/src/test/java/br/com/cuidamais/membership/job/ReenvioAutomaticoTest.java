package br.com.cuidamais.membership.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-048 (plan D-09 rev. 2, item 4) - rotina agendada de reenvio automatico de convite
 * ({@code ReenvioAutomaticoRoutine}, bean {@code ScheduledRoutine} de {@code membership.job}).
 *
 * <p>Regra provada: a cada rodada o job seleciona as linhas de {@code invite_code_attempts} com
 * {@code blocked_until <= agora} (SOMENTE esse predicado, borda fechada), reivindicadas com SKIP
 * LOCKED (D-42). Para cada linha: se ha convite ENVIADO VIGENTE para o e-mail, consome o reenvio
 * automatico; concedido (ate 3) => cancela o convite e cria outro com {@code origin = AUTO} (codigo
 * novo so em hash, prazo novo de 7 dias, mesmo grupo, {@code created_by} = criador do anterior) e
 * publica o e-mail do convite depois do commit (D-23); negado (o 4o) => nada e enviado e o convite
 * segue ENVIADO. Se NAO ha convite ENVIADO vigente => descarta o reenvio (nada enviado, nao gasta o
 * teto). Os dois caminhos limpam {@code blocked_until}.
 *
 * <p>ACs cobertos: AC-003.3 (reenvio automatico, teto de 3, sem convite nada e enviado), AC-003.1
 * (o e-mail e o do convite: codigo de 6 digitos, nome do Responsavel, so o primeiro nome do idoso),
 * AC-003.9 (convite criado/reenviado pelo Responsavel encerra o bloqueio e o reenvio automatico NAO
 * ocorre), AC-003.11 (o contador zera ao criar o convite automatico), AC-004.7 (o bloqueio de 5 erros
 * e provocado pelo POST /invitations/redeem real, 423). Decisoes: D-09 rev. 2 item 4, D-10 (tempo so
 * pelo {@code MutableClock}), D-23 (e-mail so apos o commit), D-34 (contador por e-mail), D-42 (SKIP
 * LOCKED e idempotencia), R-03/R-08 (ordem de locks invite_code_attempts -> invitations ->
 * memberships, sem deadlock), P5 (nada de dado de saude no e-mail nem no log).
 *
 * <p><b>Decisoes fixadas nestes testes (a confirmar pelo dono da spec / orquestrador):</b>
 * <ul>
 *   <li>Convite ENVIADO cujo prazo de 7 dias ja passou (VENCIDO derivado pelo relogio, borda
 *       fechada: {@code expires_at <= agora}) conta como "sem convite": o reenvio e descartado, nada
 *       e enviado e o job NAO o cancela (a linha de {@code invitations} segue ENVIADO).
 *   <li>Convite so USADO, CANCELADO ou VENCIDO (gravado) tambem conta como "sem convite".
 *   <li>O reenvio automatico concedido zera o contador de erros (AC-003.11b: "convite criado, envio,
 *       reenvio manual ou automatico"); erros do ciclo novo (depois do fim do bloqueio, antes do job)
 *       nao cancelam o reenvio devido (plan D-09 item 4) e tambem sao zerados pelo convite novo.
 *   <li>O e-mail do reenvio traz o nome do Responsavel que CRIOU o convite anterior (created_by).
 * </ul>
 *
 * <p>Testes negativos ("nada acontece") levam um CONTROLE POSITIVO (a "sentinela": outro e-mail com
 * convite bloqueado no mesmo instante, que o job precisa reenviar), para nao passarem sem a rotina.
 *
 * <p>Os testes usam {@link JobRunner#runAll()} e nao importam a classe da rotina (assim a falha antes
 * da implementacao e de comportamento, nao de compilacao). O banco, o Mailpit e o relogio sao
 * compartilhados entre classes, entao todo tempo e calculado relativo ao relogio atual.
 */
@AutoConfigureMockMvc
class ReenvioAutomaticoTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String REGISTER = "/api/v1/invitations/register";
    private static final String CODIGO = "123456";
    private static final String CODIGO_ERRADO = "654321";
    private static final String ASSUNTO_PREFIXO = "Cuida+: você foi convidado(a) para o grupo de cuidado de";
    private static final Duration BLOQUEIO = Duration.ofMinutes(30);
    private static final Duration PRAZO_CONVITE = Duration.ofDays(7);
    private static final int RODADAS = 4;
    private static final Pattern SEIS_DIGITOS = Pattern.compile("\\b(\\d{6})\\b");

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
            UUID id,
            String status,
            String origin,
            Instant expiresAt,
            Instant createdAt,
            String codeHash,
            UUID createdBy,
            UUID groupId,
            Instant cancelledAt) {}

    private record Contador(int falhas, Instant bloqueadoAte, int reenvios) {}

    /** Controle positivo: e-mail com convite ENVIADO bloqueado, que o job precisa reenviar. */
    private record Sentinela(String email, UUID conviteId) {}

    /**
     * O banco e o Mailpit sao compartilhados por todas as classes e o relogio nunca volta: linhas de
     * {@code invite_code_attempts} bloqueadas por outras classes seriam reenviadas (com e-mail) por
     * esta rotina, e pedidos vencidos seriam expirados pelas outras rotinas, inundando a caixa. Por
     * isso cada teste comeca (1) limpando o bloqueio das linhas legadas (sem e-mail), (2) drenando
     * o resto do legado com uma rodada e esperando a caixa estabilizar, (3) zerando o Mailpit. Os
     * dados do teste so sao criados depois.
     */
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

    // ============================================================ fluxo ponta a ponta (AC-003.3 / AC-004.7)

    /**
     * Pronto quando (1o reenvio, parte 1): 5 erros em POST /invitations/redeem => 423; com o relogio
     * +30 min EXATOS o job cancela o convite antigo (cancelled_at = agora) e cria exatamente um novo
     * ENVIADO com origin AUTO, prazo = agora + 7 dias, mesmo grupo, mesmo criador, hash diferente;
     * o contador fica com blocked_until nulo, failedAttempts 0 (AC-003.11b) e autoResends 1.
     */
    @Test
    @DisplayName("AC-003.3: 5 erros => 423; +30 min exatos, o job cancela o antigo e cria convite AUTO com prazo de 7 dias e contador zerado")
    void ac_003_3_job_cancela_o_antigo_e_cria_convite_auto() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        String hashAntigo = convite(antigo).codeHash();

        // Etapa 1: 5 erros reais no redeem => 423 e blocked_until = agora + 30 min (AC-004.7).
        Instant bloqueadoAte = bloquear(email, CODIGO);
        assertThat(contador(email)).isEqualTo(new Contador(5, bloqueadoAte, 0));
        assertThat(convite(antigo).status()).as("durante o bloqueio o convite segue ENVIADO").isEqualTo("ENVIADO");

        // Etapa 2: relogio +30 min exatos e uma rodada do job.
        clock.advance(BLOQUEIO);
        Instant agora = clock.instant();
        assertThat(agora).isEqualTo(bloqueadoAte);
        runner.runAll();

        // Etapa 3: o antigo foi cancelado agora; existe exatamente um ENVIADO, AUTO, com prazo novo.
        Convite velho = convite(antigo);
        assertThat(velho.status()).isEqualTo("CANCELADO");
        assertThat(velho.cancelledAt()).isEqualTo(agora);
        List<Convite> enviados = convitesDe(email, "ENVIADO");
        assertThat(enviados).hasSize(1);
        Convite novo = enviados.getFirst();
        assertThat(novo.id()).isNotEqualTo(antigo);
        assertThat(novo.origin()).isEqualTo("AUTO");
        assertThat(novo.expiresAt()).isEqualTo(agora.plus(PRAZO_CONVITE));
        assertThat(novo.createdAt()).isEqualTo(agora);
        assertThat(novo.groupId()).isEqualTo(g.groupId());
        assertThat(novo.createdBy()).isEqualTo(g.responsavel().getId());
        assertThat(novo.codeHash()).as("codigo novo: hash diferente").isNotEqualTo(hashAntigo);
        assertThat(convitesDe(email)).as("so o antigo e o novo").hasSize(2);

        // Etapa 4: contador - bloqueio encerrado, erros zerados, 1 reenvio gasto.
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));
    }

    /**
     * Pronto quando (1o reenvio, parte 2) / AC-003.3 + AC-003.1: o e-mail chega ao Mailpit (1 so),
     * traz um codigo de 6 digitos cujo hash e o do convite novo; o codigo novo resgata no redeem
     * (200 REGISTER) e o codigo ANTIGO nao serve mais (410 INVITATION_CANCELLED, nao conta erro).
     */
    @Test
    @DisplayName("AC-003.3: o e-mail do reenvio traz o código novo, que resgata (200 REGISTER); o código antigo não serve")
    void ac_003_3_codigo_novo_do_email_resgata_e_o_antigo_nao_serve() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);

        runner.runAll();

        // Etapa 1: exatamente um e-mail de convite, com codigo de 6 digitos compativel com o hash novo.
        MailpitClient.Detail mail = umEmailDeConvite(email);
        String codigoNovo = codigoDoEmail(mail);
        Convite novo = convitesDe(email, "ENVIADO").getFirst();
        assertThat(encoder.matches(codigoNovo, novo.codeHash())).as("o codigo do e-mail e o do convite novo").isTrue();
        assumeTrue(!CODIGO.equals(codigoNovo), "colisao de 1 em 1.000.000 com o codigo antigo: nada a provar");

        // Etapa 2: o codigo antigo bate com um convite CANCELADO => 410, sem consumir tentativa.
        MvcResult antigoResult = mockMvc.perform(resgatar(email, CODIGO)).andExpect(status().isGone()).andReturn();
        assertThat(json.readTree(antigoResult.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVITATION_CANCELLED");
        assertThat(convite(antigo).status()).isEqualTo("CANCELADO");

        // Etapa 3: o codigo novo resgata o convite AUTO.
        MvcResult novoResult = mockMvc.perform(resgatar(email, codigoNovo)).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(novoResult.getResponse().getContentAsString());
        assertThat(body.path("next").asText()).isEqualTo("REGISTER");
        assertThat(body.path("email").asText()).isEqualTo(email);
        assertThat(body.path("elderFirstName").asText()).isEqualTo(g.primeiroNomeIdoso());
    }

    /**
     * AC-003.1 + P5: o e-mail do reenvio automatico e IGUAL ao do convite normal (mesmo assunto e
     * mesmo texto, trocando so o codigo): nome do Responsavel, so o primeiro nome do idoso e as
     * instrucoes; nada de sobrenome, endereco, alergias nem condicoes do idoso (nem no HTML).
     */
    @Test
    @DisplayName("AC-003.1/P5: o e-mail do reenvio automático é igual ao do convite normal e não traz sobrenome nem dado de saúde do idoso")
    void ac_003_1_email_do_reenvio_automatico_segue_o_formato_do_convite() throws Exception {
        Grupo g = grupo();
        String automatico = emailNovo();
        String manual = emailNovo();
        semearConvite(g, automatico, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(automatico, CODIGO);
        // Etapa 1: o convite normal (manual) do mesmo grupo, para comparar os e-mails.
        criarConvite(g, manual);
        MailpitClient.Detail emailManual = umEmailDeConvite(manual);
        clock.advance(BLOQUEIO);

        runner.runAll();

        // Etapa 2: mesmo assunto e mesmo texto (normalizados), com o primeiro nome do idoso.
        MailpitClient.Detail emailAuto = umEmailDeConvite(automatico);
        assertThat(emailAuto.subject()).isEqualTo(emailManual.subject())
                .isEqualTo(ASSUNTO_PREFIXO + " " + g.primeiroNomeIdoso());
        assertThat(normalizado(emailAuto.text())).isEqualTo(normalizado(emailManual.text()));
        assertThat(emailAuto.text()).contains(g.nomeResponsavel() + " convidou você").contains(g.primeiroNomeIdoso());
        // Etapa 3: P5 - nada alem do primeiro nome do idoso (texto e HTML).
        for (String conteudo : List.of(emailAuto.text(), emailAuto.html() == null ? "" : emailAuto.html())) {
            assertThat(conteudo)
                    .doesNotContain("Oliveira")
                    .doesNotContain("Dipirona-Zq")
                    .doesNotContain("Diabetes-Zq")
                    .doesNotContain("Barata Ribeiro")
                    .doesNotContain("Copacabana");
        }
    }

    /**
     * AC-003.3 (mesmas regras de AC-003.4): o convite automatico mantem o created_by do convite
     * anterior e o e-mail nomeia esse criador, nao o Responsavel atual do grupo.
     */
    @Test
    @DisplayName("AC-003.3: o convite AUTO herda o created_by do convite anterior e o e-mail nomeia esse criador")
    void ac_003_3_convite_auto_herda_o_criador_do_anterior() throws Exception {
        Grupo g = grupo();
        // A criadora e uma Familiar ATIVA do mesmo grupo (criador diferente do Responsavel). Ela precisa
        // de membership real: uma conta verificada SEM grupo seria apagada pela retencao de AC-014.3
        // quando o relogio compartilhado avancar, e a FK invitations.created_by quebraria essa rotina.
        User criadora = familiarAtiva(g, "Ana Criadora");
        String email = emailNovo();
        semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, criadora.getId());
        bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);

        runner.runAll();

        // Etapa: created_by do novo = criadora; o e-mail diz o nome dela.
        Convite novo = convitesDe(email, "ENVIADO").getFirst();
        assertThat(novo.createdBy()).isEqualTo(criadora.getId());
        MailpitClient.Detail mail = umEmailDeConvite(email);
        assertThat(mail.text()).contains("Ana Criadora convidou você").doesNotContain(g.nomeResponsavel());
    }

    // ============================================================ bordas dos 30 minutos (AC-003.3)

    /**
     * Borda fechada (blocked_until <= agora): 29m59s e 29m59,999s ainda NAO disparam (convite
     * original ENVIADO, bloqueio intacto, nenhum e-mail); 30 min exatos e 30 min + 1 ms disparam.
     * Cada linha: tempo decorrido desde o 5o erro e se o job deve reenviar naquele instante. Nas
     * linhas "false" avanca-se o resto e o job dispara (prova que nada se perdeu).
     */
    @ParameterizedTest
    @CsvSource({"PT29M59S,false", "PT29M59.999S,false", "PT30M,true", "PT30M0.001S,true"})
    @DisplayName("AC-003.3: borda - 29m59 não dispara; 30 min exatos (e depois) dispara")
    void ac_003_3_bordas_dos_30_minutos(String decorrido, boolean deveReenviar) throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        Instant bloqueadoAte = bloquear(email, CODIGO);
        Duration passado = Duration.parse(decorrido);
        clock.advance(passado);

        runner.runAll();

        if (deveReenviar) {
            // Linhas 30 min exatos e 30 min + 1 ms: dispara, com 1 e-mail e o antigo cancelado.
            assertThat(convite(antigo).status()).isEqualTo("CANCELADO");
            assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
            assertThat(contador(email).bloqueadoAte()).isNull();
            umEmailDeConvite(email);
        } else {
            // Linhas 29m59 e 29m59,999s: nada acontece ainda.
            assertThat(convite(antigo).status()).isEqualTo("ENVIADO");
            assertThat(convitesDe(email)).hasSize(1);
            assertThat(contador(email).bloqueadoAte()).as("bloqueio intacto").isEqualTo(bloqueadoAte);
            aguardaTotalDeConvites(email, 0);
            // Etapa extra: cruzando o fim do bloqueio o job dispara normalmente.
            clock.advance(BLOQUEIO.minus(passado));
            runner.runAll();
            assertThat(convite(antigo).status()).isEqualTo("CANCELADO");
            assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
            umEmailDeConvite(email);
        }
    }

    // ============================================================ teto de 3 (AC-003.3)

    /**
     * Pronto quando (4o negado): ciclos repetidos de bloqueio. O 1o, 2o e 3o reenvios sao concedidos
     * (cada um troca o convite e manda 1 e-mail, autoResends 1, 2, 3). No 4o (e no 5o) ciclo o job
     * NEGA: nenhum e-mail, o convite continua o mesmo ENVIADO (AUTO do 3o reenvio), autoResends fica
     * 3 e blocked_until e limpo.
     */
    @Test
    @DisplayName("AC-003.3: ciclos repetidos - 1º, 2º e 3º reenvios concedidos; o 4º é NEGADO (sem e-mail, convite igual, autoResends 3)")
    void ac_003_3_quarto_reenvio_e_negado() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        String codigoVigente = CODIGO;
        UUID conviteVigente = convitesDe(email, "ENVIADO").getFirst().id();

        // Etapas 1 a 3: cada ciclo (bloqueio de 5 erros + 30 min + job) concede um reenvio.
        for (int ciclo = 1; ciclo <= 3; ciclo++) {
            bloquear(email, codigoVigente);
            clock.advance(BLOQUEIO);
            runner.runAll();

            String ctx = "ciclo " + ciclo;
            assertThat(convite(conviteVigente).status()).as(ctx).isEqualTo("CANCELADO");
            List<Convite> enviados = convitesDe(email, "ENVIADO");
            assertThat(enviados).as(ctx).hasSize(1);
            assertThat(enviados.getFirst().origin()).as(ctx).isEqualTo("AUTO");
            assertThat(contador(email)).as(ctx).isEqualTo(new Contador(0, null, ciclo));
            aguardaTotalDeConvites(email, ciclo);
            conviteVigente = enviados.getFirst().id();
            codigoVigente = codigoDoEmail(ultimoEmailDeConvite(email));
        }

        // Etapas 4 e 5: o teto foi atingido - o job nega (duas vezes seguidas), sem e-mail novo.
        for (int ciclo = 4; ciclo <= 5; ciclo++) {
            Instant bloqueadoAte = bloquear(email, codigoVigente);
            assertThat(contador(email).bloqueadoAte()).isEqualTo(bloqueadoAte);
            clock.advance(BLOQUEIO);
            runner.runAll();

            String ctx = "ciclo " + ciclo + " (negado)";
            assertThat(convite(conviteVigente).status()).as(ctx).isEqualTo("ENVIADO");
            assertThat(convitesDe(email, "ENVIADO")).as(ctx).extracting(Convite::id).containsExactly(conviteVigente);
            assertThat(convitesDe(email)).as("so os 4 da cadeia: seed + 3 AUTO").hasSize(4);
            assertThat(contador(email)).as(ctx).isEqualTo(new Contador(0, null, 3));
            aguardaTotalDeConvites(email, 3);
        }
    }

    /**
     * AC-003.3 (atalho do teto): contador ja com autoResends = 3 (semeado) e convite ENVIADO: o job
     * nega o reenvio, sem e-mail, convite intacto, blocked_until limpo e autoResends continua 3.
     */
    @Test
    @DisplayName("AC-003.3: com autoResends já em 3 o job nega: sem e-mail, convite ENVIADO igual, bloqueio limpo")
    void ac_003_3_teto_ja_atingido_nega_o_reenvio() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID convite = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        semearContador(email, 0, null, 3);
        Instant bloqueadoAte = bloquear(email, CODIGO);
        assertThat(contador(email)).isEqualTo(new Contador(5, bloqueadoAte, 3));
        clock.advance(BLOQUEIO);

        runner.runAll();

        assertThat(convite(convite).status()).isEqualTo("ENVIADO");
        assertThat(convitesDe(email)).hasSize(1);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 3));
        aguardaTotalDeConvites(email, 0);
    }

    // ============================================================ e-mail sem convite (AC-003.3 / AC-004.7)

    /**
     * Pronto quando (e-mail sem convite): 5 erros para um e-mail SEM convite (423 igual, P5); ao fim
     * dos 30 min o job nao envia nada, nao cria convite, nao muda autoResends e limpa blocked_until
     * (dismiss). Rodadas repetidas depois tambem nao fazem nada. A sentinela prova que o job rodou.
     */
    @Test
    @DisplayName("AC-003.3: e-mail bloqueado sem convite - o job não envia nada, não cria convite, autoResends não muda, bloqueio limpo")
    void ac_003_3_email_sem_convite_nada_acontece() throws Exception {
        Grupo g = grupo();
        Sentinela sentinela = sentinela(g);
        String email = emailNovo();
        bloquear(email, null);
        assertThat(contador(email).falhas()).isEqualTo(5);
        clock.advance(BLOQUEIO);

        runner.runAll();
        runner.runAll();
        clock.advance(Duration.ofMinutes(5));
        runner.runAll();

        assertThat(convitesDe(email)).isEmpty();
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 0));
        aguardaTotalDeConvites(email, 0);
        confirmaSentinelaReenviada(sentinela);
    }

    /**
     * AC-003.3 (nao gasta o teto): e-mail sem convite com autoResends = 2 (semeado) bloqueado e
     * descartado pelo job mantem autoResends em 2; depois que o Responsavel convida e o e-mail volta
     * a ser bloqueado, o job ainda concede um reenvio (o 3o, autoResends passa a 3).
     */
    @Test
    @DisplayName("AC-003.3: o descarte de e-mail sem convite não gasta o teto - ainda cabe o 3º reenvio depois")
    void ac_003_3_descarte_sem_convite_nao_gasta_o_teto() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        semearContador(email, 0, null, 2);
        bloquear(email, null);
        clock.advance(BLOQUEIO);

        // Etapa 1: sem convite - descartado, autoResends continua 2.
        runner.runAll();
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 2));
        aguardaTotalDeConvites(email, 0);

        // Etapa 2: o Responsavel convida; novo bloqueio; o job concede o 3o reenvio.
        criarConvite(g, email);
        aguardaTotalDeConvites(email, 1);
        bloquear(email, null);
        clock.advance(BLOQUEIO);
        runner.runAll();
        assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
        assertThat(convitesDe(email, "ENVIADO").getFirst().origin()).isEqualTo("AUTO");
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 3));
        aguardaTotalDeConvites(email, 2);
    }

    /**
     * Decisao do orquestrador: ENVIADO cujo prazo de 7 dias ja passou pelo relogio (VENCIDO derivado)
     * conta como "sem convite". O convite expira 20 min depois do 5o erro (antes do fim do
     * bloqueio), ou no instante exato do job (borda fechada: vencido), ou 1 s DEPOIS dele (ainda
     * vigente: reenvia). Cada linha: instante do vencimento desde o 5o erro e se o job reenvia.
     */
    @ParameterizedTest
    @CsvSource({"PT20M,false", "PT30M,false", "PT30M1S,true"})
    @DisplayName("AC-003.3: convite ENVIADO vencido pelo relógio conta como sem convite (sem e-mail, sem cancelar); vigente no instante do job reenvia")
    void ac_003_3_convite_enviado_vencido_pelo_relogio_e_tratado_como_sem_convite(
            String vencimentoDepois, boolean deveReenviar) throws Exception {
        Grupo g = grupo();
        Sentinela sentinela = deveReenviar ? null : sentinela(g);
        String email = emailNovo();
        Instant vencimento = clock.instant().plus(Duration.parse(vencimentoDepois));
        UUID convite = semearConvite(g, email, "ENVIADO", vencimento, CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);

        runner.runAll();

        if (deveReenviar) {
            // Linha PT30M1S: ainda vigente no instante do job (1 s de folga) => reenvio concedido.
            assertThat(convite(convite).status()).isEqualTo("CANCELADO");
            assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
            assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));
            umEmailDeConvite(email);
        } else {
            // Linhas PT20M e PT30M: vencido (borda fechada) => descarte; o job nao cancela nem cria nada.
            assertThat(convite(convite).status()).as("a rotina nao cancela o vencido").isEqualTo("ENVIADO");
            assertThat(convite(convite).cancelledAt()).isNull();
            assertThat(convitesDe(email)).hasSize(1);
            assertThat(contador(email)).isEqualTo(new Contador(0, null, 0));
            aguardaTotalDeConvites(email, 0);
            confirmaSentinelaReenviada(sentinela);
        }
    }

    /**
     * AC-003.3: o e-mail so tem convite USADO, CANCELADO ou VENCIDO (gravado): nao ha ENVIADO, entao
     * nada e enviado, nenhum convite e criado e os convites existentes ficam como estavam. Cada
     * linha: o status gravado do unico convite do e-mail.
     */
    @ParameterizedTest
    @ValueSource(strings = {"USADO", "CANCELADO", "VENCIDO"})
    @DisplayName("AC-003.3: e-mail só com convite USADO, CANCELADO ou VENCIDO - nada é enviado nem criado")
    void ac_003_3_sem_convite_enviado_nada_acontece(String statusGravado) throws Exception {
        Grupo g = grupo();
        Sentinela sentinela = sentinela(g);
        String email = emailNovo();
        UUID convite = semearConvite(g, email, statusGravado, clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, null);
        clock.advance(BLOQUEIO);

        runner.runAll();

        assertThat(convite(convite).status()).isEqualTo(statusGravado);
        assertThat(convitesDe(email)).hasSize(1);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 0));
        aguardaTotalDeConvites(email, 0);
        confirmaSentinelaReenviada(sentinela);
    }

    /**
     * AC-003.3/AC-003.4: o Responsavel cancela o convite durante o bloqueio; ao fim dos 30 min nao ha
     * mais convite ENVIADO, entao o job descarta o reenvio (nada enviado, convite segue CANCELADO).
     */
    @Test
    @DisplayName("AC-003.3: convite cancelado pelo Responsável durante o bloqueio não é reenviado pelo job")
    void ac_003_3_convite_cancelado_durante_o_bloqueio_nao_e_reenviado() throws Exception {
        Grupo g = grupo();
        Sentinela sentinela = sentinela(g);
        String email = emailNovo();
        UUID convite = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        mockMvc.perform(cancelar(g, convite)).andExpect(status().isOk());
        clock.advance(BLOQUEIO);

        runner.runAll();

        assertThat(convite(convite).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(email)).hasSize(1);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 0));
        aguardaTotalDeConvites(email, 0);
        confirmaSentinelaReenviada(sentinela);
    }

    // ============================================================ AC-003.9 convite manual cancela o reenvio

    /**
     * AC-003.9: criar o convite DURANTE o bloqueio (ou depois do fim dele e antes do job, plan D-34
     * esclarecimento 3) encerra o bloqueio na hora e o reenvio automatico NAO ocorre. Cada linha:
     * (como o Responsavel convida) x (quando). Depois do job: um unico convite ENVIADO (o manual),
     * nenhum convite AUTO, autoResends 0, bloqueio limpo e so o e-mail do convite manual.
     * Linhas: CONVIDAR_DE_NOVO (AC-003.12: POST do mesmo e-mail), REENVIAR (AC-003.4: resend do
     * convite) e SEM_CONVITE (e-mail bloqueado sem convite, depois convidado).
     */
    @ParameterizedTest
    @CsvSource({
        "CONVIDAR_DE_NOVO,DURANTE",
        "REENVIAR,DURANTE",
        "SEM_CONVITE,DURANTE",
        "CONVIDAR_DE_NOVO,ENTRE_FIM_E_JOB",
        "REENVIAR,ENTRE_FIM_E_JOB",
        "SEM_CONVITE,ENTRE_FIM_E_JOB"
    })
    @DisplayName("AC-003.9: convite criado/reenviado durante o bloqueio (ou entre o fim e o job) cancela o reenvio automático")
    void ac_003_9_convite_manual_cancela_o_reenvio_automatico(String modo, String quando) throws Exception {
        Grupo g = grupo();
        Sentinela sentinela = sentinela(g);
        String email = emailNovo();
        UUID antigo = null;
        if (!modo.equals("SEM_CONVITE")) {
            antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        }
        bloquear(email, CODIGO);
        if (quando.equals("ENTRE_FIM_E_JOB")) {
            // Linhas ENTRE_FIM_E_JOB: o bloqueio ja terminou, o job ainda nao rodou.
            clock.advance(BLOQUEIO);
        }

        // Etapa 1: o Responsavel cria o convite manual.
        JsonNode manual = modo.equals("REENVIAR") ? reenviarConvite(g, antigo) : criarConvite(g, email);
        UUID conviteManual = UUID.fromString(manual.path("id").asText());
        assertThat(contador(email).bloqueadoAte()).as("o bloqueio (e o reenvio devido) acabou na hora").isNull();
        MailpitClient.Detail emailManual = umEmailDeConvite(email);
        assertThat(emailManual.subject()).startsWith(ASSUNTO_PREFIXO);

        // Etapa 2: passa o fim do bloqueio original (se ainda nao passou) e roda o job (e de novo).
        if (quando.equals("DURANTE")) {
            clock.advance(BLOQUEIO);
        }
        runner.runAll();
        runner.runAll();

        // Etapa 3: so o convite manual; o job nao reenviou nada.
        assertThat(convitesDe(email, "ENVIADO")).extracting(Convite::id).containsExactly(conviteManual);
        assertThat(convitesDe(email, "ENVIADO").getFirst().origin()).isEqualTo("MANUAL");
        assertThat(convitesDe(email).stream().filter(c -> c.origin().equals("AUTO"))).isEmpty();
        assertThat(contador(email).reenvios()).isZero();
        assertThat(contador(email).bloqueadoAte()).isNull();
        aguardaTotalDeConvites(email, 1);
        confirmaSentinelaReenviada(sentinela);
    }

    // ============================================================ bloqueio sobrescrito (D-09 item 4)

    /**
     * D-09 item 4: 5 erros novos DEPOIS do fim do bloqueio e ANTES do job rodar sobrescrevem
     * blocked_until (agora + 30 min). O job nao dispara no novo bloqueio (nada em 29m59), dispara
     * UMA vez no novo fim (nunca duas: uma rodada extra e outra 30 min depois nao mudam nada).
     */
    @Test
    @DisplayName("D-09/AC-003.3: 5 erros novos antes do job sobrescrevem o bloqueio - sai um só reenvio, no bloqueio mais novo")
    void ac_003_3_cinco_erros_novos_antes_do_job_sobrescrevem_o_bloqueio() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);

        // Etapa 1: o primeiro bloqueio termina, o job nao roda, e 5 erros novos bloqueiam de novo.
        clock.advance(BLOQUEIO.plusMinutes(1));
        Instant novoFim = bloquear(email, CODIGO);
        assertThat(novoFim).isEqualTo(clock.instant().plus(BLOQUEIO));

        // Etapa 2: o job roda DENTRO do bloqueio novo: nao seleciona a linha.
        runner.runAll();
        assertThat(convite(antigo).status()).isEqualTo("ENVIADO");
        assertThat(contador(email).bloqueadoAte()).isEqualTo(novoFim);
        aguardaTotalDeConvites(email, 0);

        // Etapa 3: 1 s antes do novo fim ainda nada.
        clock.advance(BLOQUEIO.minusSeconds(1));
        runner.runAll();
        assertThat(convite(antigo).status()).isEqualTo("ENVIADO");

        // Etapa 4: no novo fim sai exatamente um reenvio.
        clock.advance(Duration.ofSeconds(1));
        runner.runAll();
        assertThat(convite(antigo).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));

        // Etapa 5: mais rodadas depois nao geram o segundo reenvio.
        runner.runAll();
        clock.advance(BLOQUEIO);
        runner.runAll();
        assertThat(convitesDe(email)).hasSize(2);
        assertThat(contador(email).reenvios()).isEqualTo(1);
        aguardaTotalDeConvites(email, 1);
    }

    /**
     * D-09 item 4 + AC-003.11b: 2 erros do ciclo novo (depois do fim do bloqueio, antes do job) NAO
     * cancelam o reenvio devido: o job reenvia e o convite novo zera o contador de erros.
     */
    @Test
    @DisplayName("D-09/AC-003.11: erros do ciclo novo antes do job não cancelam o reenvio devido; o convite novo zera o contador")
    void ac_003_11_erros_do_ciclo_novo_nao_cancelam_o_reenvio_devido() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        Instant bloqueadoAte = bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);

        // Etapa 1: dois erros do ciclo novo (400, nao 423); a pendencia do job continua.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(resgatar(email, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        }
        assertThat(contador(email)).isEqualTo(new Contador(2, bloqueadoAte, 0));

        // Etapa 2: o job ainda reenvia e zera tudo.
        runner.runAll();
        assertThat(convite(antigo).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(email, "ENVIADO")).hasSize(1);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));
        umEmailDeConvite(email);
    }

    // ============================================================ idempotencia (D-42)

    /**
     * D-42: depois do reenvio concedido, rodar o job de novo (no mesmo instante, +5 min, +1 dia) nunca
     * reenvia: 1 so e-mail, autoResends continua 1, o mesmo convite AUTO segue como unico ENVIADO.
     */
    @Test
    @DisplayName("AC-003.3/D-42: rodar o job de novo não reenvia - sem e-mail extra, autoResends não sobe")
    void ac_003_3_job_idempotente_em_rodadas_repetidas() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);

        // Etapa 1: a rodada que reenvia.
        runner.runAll();
        UUID auto = convitesDe(email, "ENVIADO").getFirst().id();
        umEmailDeConvite(email);

        // Etapa 2: rodada imediata, +5 min, +1 dia - todas no-op.
        runner.runAll();
        clock.advance(Duration.ofMinutes(5));
        runner.runAll();
        clock.advance(Duration.ofDays(1));
        runner.runAll();

        assertThat(convitesDe(email, "ENVIADO")).extracting(Convite::id).containsExactly(auto);
        assertThat(convitesDe(email)).hasSize(2);
        assertThat(contador(email)).isEqualTo(new Contador(0, null, 1));
        aguardaTotalDeConvites(email, 1);
    }

    // ============================================================ varios e-mails de uma vez

    /**
     * Varios e-mails em uma so rodada, de dois grupos: a1 (convite A, reenvia), a2 (sem convite,
     * descarta), b1 (convite B com autoResends 3, NEGA), b2 (convite B, reenvia), c (convite B que
     * vence antes do job, descarta sem cancelar), f (convite A bloqueado 10 min depois, ainda nao
     * vence) e n (convite A com 3 erros, sem bloqueio, intocado). Cada um e tratado corretamente, 1
     * e-mail por reenvio concedido (com o primeiro nome do idoso do proprio grupo); f dispara 10 min
     * depois.
     */
    @Test
    @DisplayName("AC-003.3: vários e-mails bloqueados de grupos diferentes - cada um tratado corretamente, 1 e-mail por reenvio concedido")
    void ac_003_3_varios_emails_bloqueados_cada_um_tratado_corretamente() throws Exception {
        Grupo a = grupo("Zélia Responsável", "Francisca Oliveira");
        Grupo b = grupo("Rita Responsável", "Josefa Santos");
        Instant t0 = clock.instant();
        String a1 = emailNovo();
        String a2 = emailNovo();
        String b1 = emailNovo();
        String b2 = emailNovo();
        String c = emailNovo();
        String f = emailNovo();
        String n = emailNovo();
        UUID ca1 = semearConvite(a, a1, "ENVIADO", t0.plus(PRAZO_CONVITE), CODIGO, a.responsavel().getId());
        UUID cb1 = semearConvite(b, b1, "ENVIADO", t0.plus(PRAZO_CONVITE), CODIGO, b.responsavel().getId());
        UUID cb2 = semearConvite(b, b2, "ENVIADO", t0.plus(PRAZO_CONVITE), CODIGO, b.responsavel().getId());
        UUID cc = semearConvite(b, c, "ENVIADO", t0.plus(Duration.ofMinutes(20)), CODIGO, b.responsavel().getId());
        UUID cf = semearConvite(a, f, "ENVIADO", t0.plus(PRAZO_CONVITE), CODIGO, a.responsavel().getId());
        UUID cn = semearConvite(a, n, "ENVIADO", t0.plus(PRAZO_CONVITE), CODIGO, a.responsavel().getId());
        semearContador(b1, 0, null, 3);
        semearContador(n, 3, null, 0);

        // Etapa 1: bloqueia (pelo redeem real) os quatro que vencem em t0 + 30 e o b1 (teto).
        for (String email : List.of(a1, a2, b1, b2, c)) {
            bloquear(email, CODIGO);
        }
        // Etapa 2: f e bloqueado 10 min depois (vence em t0 + 40).
        clock.advance(Duration.ofMinutes(10));
        Instant fimDeF = bloquear(f, CODIGO);
        clock.advance(Duration.ofMinutes(20));
        assertThat(clock.instant()).isEqualTo(t0.plus(BLOQUEIO));

        runner.runAll();

        // a1: reenvio concedido no grupo A.
        assertThat(convite(ca1).status()).isEqualTo("CANCELADO");
        Convite autoA1 = convitesDe(a1, "ENVIADO").getFirst();
        assertThat(autoA1.origin()).isEqualTo("AUTO");
        assertThat(autoA1.groupId()).isEqualTo(a.groupId());
        assertThat(contador(a1)).isEqualTo(new Contador(0, null, 1));
        assertThat(umEmailDeConvite(a1).text()).contains("Francisca").contains("Zélia Responsável convidou você");
        // a2: sem convite, descartado.
        assertThat(convitesDe(a2)).isEmpty();
        assertThat(contador(a2)).isEqualTo(new Contador(0, null, 0));
        aguardaTotalDeConvites(a2, 0);
        // b1: teto atingido, negado.
        assertThat(convite(cb1).status()).isEqualTo("ENVIADO");
        assertThat(convitesDe(b1)).hasSize(1);
        assertThat(contador(b1)).isEqualTo(new Contador(0, null, 3));
        aguardaTotalDeConvites(b1, 0);
        // b2: reenvio concedido no grupo B, com o idoso e o Responsavel de B.
        assertThat(convite(cb2).status()).isEqualTo("CANCELADO");
        Convite autoB2 = convitesDe(b2, "ENVIADO").getFirst();
        assertThat(autoB2.origin()).isEqualTo("AUTO");
        assertThat(autoB2.groupId()).isEqualTo(b.groupId());
        assertThat(contador(b2)).isEqualTo(new Contador(0, null, 1));
        assertThat(umEmailDeConvite(b2).text()).contains("Josefa").contains("Rita Responsável convidou você")
                .doesNotContain("Francisca");
        // c: convite vencido pelo relogio, tratado como sem convite e nao cancelado.
        assertThat(convite(cc).status()).isEqualTo("ENVIADO");
        assertThat(convitesDe(c)).hasSize(1);
        assertThat(contador(c)).isEqualTo(new Contador(0, null, 0));
        aguardaTotalDeConvites(c, 0);
        // f: ainda bloqueado, intocado.
        assertThat(convite(cf).status()).isEqualTo("ENVIADO");
        assertThat(contador(f).bloqueadoAte()).isEqualTo(fimDeF);
        aguardaTotalDeConvites(f, 0);
        // n: sem bloqueio, nao e selecionado (3 erros preservados).
        assertThat(convite(cn).status()).isEqualTo("ENVIADO");
        assertThat(contador(n)).isEqualTo(new Contador(3, null, 0));
        aguardaTotalDeConvites(n, 0);

        // Etapa 3: 10 min depois f chega ao fim do bloqueio e e reenviado; os demais nao mudam.
        clock.advance(Duration.ofMinutes(10));
        runner.runAll();
        assertThat(convite(cf).status()).isEqualTo("CANCELADO");
        assertThat(convitesDe(f, "ENVIADO")).hasSize(1);
        umEmailDeConvite(f);
        aguardaTotalDeConvites(a1, 1);
        aguardaTotalDeConvites(b2, 1);
        assertThat(contador(a1).reenvios()).isEqualTo(1);
    }

    // ============================================================ P5 (logs)

    /**
     * P5: durante o reenvio automatico nenhum log (da aplicacao, nivel DEBUG) contem o e-mail da
     * pessoa, o codigo novo, o sobrenome/nome do idoso nem o nome do Responsavel; o JobRunner nao
     * registra falha da rotina.
     */
    @Test
    @DisplayName("P5: o reenvio automático não escreve e-mail, código nem dado do idoso nos logs")
    void p5_logs_do_reenvio_automatico_sem_dado_pessoal() throws Exception {
        Grupo g = grupo();
        String email = emailNovo();
        semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        clock.advance(BLOQUEIO);
        List<String> logs;
        String codigoNovo;

        try (CapturaDeLogs captura = new CapturaDeLogs("br.com.cuidamais", Level.DEBUG)) {
            runner.runAll();
            codigoNovo = codigoDoEmail(umEmailDeConvite(email));
            logs = captura.mensagens();
        }

        assertThat(convitesDe(email, "ENVIADO")).as("pre-condicao: o reenvio ocorreu").hasSize(1);
        assertThat(logs).as("nenhuma falha de rotina").noneMatch(m -> m.contains("falhou"));
        for (String linha : logs) {
            assertThat(linha).doesNotContain(email)
                    .doesNotContain("Oliveira")
                    .doesNotContain("Francisca")
                    .doesNotContain(g.nomeResponsavel())
                    .doesNotContain("Dipirona-Zq");
            assertThat(SEIS_DIGITOS.matcher(linha).results().map(r -> r.group(1)))
                    .as("o codigo novo nao aparece no log").doesNotContain(codigoNovo);
        }
    }

    // ============================================================ concorrencia (D-42 / R-08)

    /**
     * D-42/R-08: job x REENVIO MANUAL do mesmo convite, largados juntos, em varias rodadas. Qualquer
     * ordem e valida (manual primeiro: o job nao ve mais a linha; job primeiro: o manual espera o
     * lock do contador), mas o estado final e coerente: reenvio manual 201, sem 500 nem falha de
     * rotina (deadlock), UM unico convite ENVIADO, no maximo 1 AUTO, e exatamente 1 e-mail por convite
     * criado. A sentinela garante que o job realmente rodou.
     */
    @Test
    @DisplayName("D-42/R-08: job x reenvio manual do mesmo convite => sem 500, sem deadlock, um único ENVIADO e 1 e-mail por convite criado")
    void d42_job_x_reenvio_manual_do_mesmo_convite() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            String email = emailNovo();
            UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
            bloquearRapido(email, 0);
            Sentinela sentinela = sentinelaRapida(g);
            clock.advance(BLOQUEIO);
            MockHttpServletRequestBuilder reenvio = reenviar(g, antigo);

            List<Integer> res;
            try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunnerLogger.NOME, null)) {
                res = largadaSimultanea(List.of(
                        () -> { runner.runAll(); return 0; },
                        () -> statusHttp(reenvio)));
                runner.runAll(); // a proxima rodada reavalia o que o SKIP LOCKED pulou
                runner.runAll();
                assertThat(falhas.mensagens()).as("rodada %d: rotina sem falha", rodada).isEmpty();
            }

            String ctx = "rodada " + rodada + ", reenvio=" + res.get(1);
            assertThat(res.get(1)).as(ctx).isEqualTo(201);
            assertThat(convitesDe(email, "ENVIADO")).as("um unico ENVIADO - " + ctx).hasSize(1);
            long autos = convitesDe(email).stream().filter(c -> c.origin().equals("AUTO")).count();
            long criados = convitesDe(email).size() - 1;
            assertThat(autos).as(ctx).isLessThanOrEqualTo(1);
            assertThat(criados).as(ctx).isBetween(1L, 2L);
            assertThat(contador(email).bloqueadoAte()).as(ctx).isNull();
            assertThat(contador(email).reenvios()).as(ctx).isEqualTo((int) autos);
            aguardaTotalDeConvites(email, (int) criados);
            confirmaSentinelaReenviada(sentinela);
        }
    }

    /**
     * D-42/R-08: job x CANCELAMENTO do mesmo convite. Cancelamento primeiro: 200, o job nao acha
     * ENVIADO e descarta (0 AUTO, 0 e-mail). Job primeiro: o cancelamento ve o convite ja CANCELADO
     * e responde 409, e fica o AUTO ENVIADO com 1 e-mail. Nunca 500, nunca falha de rotina, nunca
     * cancelamento 200 junto com um AUTO.
     */
    @Test
    @DisplayName("D-42/R-08: job x cancelamento do mesmo convite => 200 sem reenvio OU 409 com um AUTO; nunca 500")
    void d42_job_x_cancelamento_do_mesmo_convite() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            String email = emailNovo();
            UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
            bloquearRapido(email, 0);
            Sentinela sentinela = sentinelaRapida(g);
            clock.advance(BLOQUEIO);
            MockHttpServletRequestBuilder cancelamento = cancelar(g, antigo);

            List<Integer> res;
            try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunnerLogger.NOME, null)) {
                res = largadaSimultanea(List.of(
                        () -> { runner.runAll(); return 0; },
                        () -> statusHttp(cancelamento)));
                runner.runAll();
                runner.runAll();
                assertThat(falhas.mensagens()).as("rodada %d: rotina sem falha", rodada).isEmpty();
            }

            int cancelamentoStatus = res.get(1);
            String ctx = "rodada " + rodada + ", cancelamento=" + cancelamentoStatus;
            assertThat(cancelamentoStatus).as(ctx).isIn(200, 409);
            long autos = convitesDe(email).stream().filter(c -> c.origin().equals("AUTO")).count();
            assertThat(autos).as(ctx).isEqualTo(cancelamentoStatus == 200 ? 0 : 1);
            assertThat(convitesDe(email, "ENVIADO")).as(ctx).hasSize((int) autos);
            assertThat(convite(antigo).status()).as(ctx).isEqualTo("CANCELADO");
            assertThat(contador(email).bloqueadoAte()).as(ctx).isNull();
            aguardaTotalDeConvites(email, (int) autos);
            confirmaSentinelaReenviada(sentinela);
        }
    }

    /**
     * D-42/R-08/AC-004.7: job x RESGATE (redeem) do codigo antigo no instante em que o bloqueio
     * termina. Resultados validos: (a) redeem primeiro => 200 REGISTER, o contador e zerado
     * (blocked_until nulo, autoResends 0), o job nao reenvia e o convite antigo segue ENVIADO;
     * (b) job primeiro => o AUTO existe com 1 e-mail e o redeem responde 410 (leu o antigo ja
     * cancelado) ou 200 (leu antes do commit). Sempre: sem 500, sem falha de rotina e um unico
     * ENVIADO.
     */
    @Test
    @DisplayName("D-42/R-08: job x resgate (redeem) do mesmo e-mail => estado coerente, sem 500, um único ENVIADO, 1 e-mail por reenvio")
    void d42_job_x_resgate_no_mesmo_email() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            String email = emailNovo();
            UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
            bloquearRapido(email, 0);
            Sentinela sentinela = sentinelaRapida(g);
            clock.advance(BLOQUEIO);

            List<Integer> res;
            try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunnerLogger.NOME, null)) {
                res = largadaSimultanea(List.of(
                        () -> { runner.runAll(); return 0; },
                        () -> statusHttp(resgatar(email, CODIGO))));
                runner.runAll();
                runner.runAll();
                assertThat(falhas.mensagens()).as("rodada %d: rotina sem falha", rodada).isEmpty();
            }

            int redeemStatus = res.get(1);
            String ctx = "rodada " + rodada + ", redeem=" + redeemStatus;
            long autos = convitesDe(email).stream().filter(c -> c.origin().equals("AUTO")).count();
            assertThat(redeemStatus).as(ctx).isIn(200, 410);
            assertThat(convitesDe(email, "ENVIADO")).as("um unico ENVIADO - " + ctx).hasSize(1);
            assertThat(contador(email).bloqueadoAte()).as(ctx).isNull();
            assertThat(contador(email).falhas()).as(ctx).isZero();
            aguardaTotalDeConvites(email, (int) autos);
            if (autos == 0) {
                // (a) o resgate venceu: ele zerou o contador e o job nao tinha o que reenviar.
                assertThat(redeemStatus).as(ctx).isEqualTo(200);
                assertThat(convite(antigo).status()).as(ctx).isEqualTo("ENVIADO");
                assertThat(contador(email).reenvios()).as(ctx).isZero();
            } else {
                // (b) o job venceu: o antigo foi cancelado e existe o AUTO.
                assertThat(convite(antigo).status()).as(ctx).isEqualTo("CANCELADO");
                assertThat(contador(email).reenvios()).as(ctx).isIn(0, 1);
            }
            confirmaSentinelaReenviada(sentinela);
        }
    }

    /**
     * D-42/R-08: job x REGISTER (conclusao do cadastro com o token do resgate, valido 15 min) no
     * mesmo e-mail, no instante em que o bloqueio termina. O bloqueio e semeado para durar 5 min
     * (para o token do resgate ainda valer). Register primeiro => 201, convite USADO, conta criada,
     * nada reenviado; job primeiro => convite cancelado e AUTO ENVIADO com 1 e-mail, register
     * recusado (nunca 201 junto com AUTO), nunca 500 nem falha de rotina.
     */
    @Test
    @DisplayName("D-42/R-08: job x register (cadastro) do mesmo e-mail => ou conta criada sem reenvio, ou AUTO sem conta; nunca 500")
    void d42_job_x_register_no_mesmo_email() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            String email = emailNovo();
            UUID antigo = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
            // Etapa 1: resgata (token de 15 min) e so depois bloqueia por 5 min (semeado), para o token valer no instante da corrida.
            String token = resgatarToken(email, CODIGO);
            semearContador(email, 5, clock.instant().plus(Duration.ofMinutes(5)), 0);
            Sentinela sentinela = sentinelaVencendoEm(g, Duration.ofMinutes(5));
            clock.advance(Duration.ofMinutes(5));
            MockHttpServletRequestBuilder cadastro = registrar(token);

            List<Integer> res;
            try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunnerLogger.NOME, null)) {
                res = largadaSimultanea(List.of(
                        () -> { runner.runAll(); return 0; },
                        () -> statusHttp(cadastro)));
                runner.runAll();
                runner.runAll();
                assertThat(falhas.mensagens()).as("rodada %d: rotina sem falha", rodada).isEmpty();
            }

            int registerStatus = res.get(1);
            String ctx = "rodada " + rodada + ", register=" + registerStatus;
            assertThat(registerStatus).as("sem 5xx - " + ctx).isLessThan(500);
            long autos = convitesDe(email).stream().filter(c -> c.origin().equals("AUTO")).count();
            int contas = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
            assertThat(contador(email).bloqueadoAte()).as(ctx).isNull();
            aguardaTotalDeConvites(email, (int) autos);
            if (registerStatus == 201) {
                // O cadastro venceu: convite USADO, conta criada, nenhum AUTO, nenhum ENVIADO.
                assertThat(autos).as(ctx).isZero();
                assertThat(contas).as(ctx).isEqualTo(1);
                assertThat(convite(antigo).status()).as(ctx).isEqualTo("USADO");
                assertThat(convitesDe(email, "ENVIADO")).as(ctx).isEmpty();
            } else {
                // O job venceu: AUTO ENVIADO, antigo CANCELADO, nenhuma conta.
                assertThat(autos).as(ctx).isEqualTo(1);
                assertThat(contas).as(ctx).isZero();
                assertThat(convite(antigo).status()).as(ctx).isEqualTo("CANCELADO");
                assertThat(convitesDe(email, "ENVIADO")).as(ctx).hasSize(1);
            }
            confirmaSentinelaReenviada(sentinela);
        }
    }

    /**
     * D-42: tres execucoes CONCORRENTES do job sobre varios e-mails devidos (3 com convite e 3 sem
     * convite): SKIP LOCKED faz cada linha ser tratada uma vez so. Os 3 com convite ganham 1 AUTO e
     * 1 e-mail cada (autoResends 1); os 3 sem convite nada (autoResends 0); sem falha de rotina.
     * Uma rodada sequencial depois nao muda nada. Repetido em varias rodadas.
     */
    @Test
    @DisplayName("D-42: três execuções concorrentes do job => cada e-mail devido é tratado uma vez, 1 e-mail por reenvio, sem falha")
    void d42_execucoes_concorrentes_do_job_nao_duplicam_reenvio() throws Exception {
        for (int rodada = 1; rodada <= 3; rodada++) {
            Grupo g = grupo();
            List<String> comConvite = new ArrayList<>();
            List<String> semConvite = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String com = emailNovo();
                semearConvite(g, com, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
                bloquearRapido(com, 0);
                comConvite.add(com);
                String sem = emailNovo();
                bloquearRapido(sem, 0);
                semConvite.add(sem);
            }
            clock.advance(BLOQUEIO);

            try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunnerLogger.NOME, null)) {
                // Etapa 1: tres threads largam juntas rodando o job.
                List<Callable<Integer>> acoes = new ArrayList<>();
                for (int t = 0; t < 3; t++) {
                    acoes.add(() -> { runner.runAll(); return 0; });
                }
                largadaSimultanea(acoes);
                // Etapa 2: rodada sequencial - nada pode mudar.
                runner.runAll();
                assertThat(falhas.mensagens()).as("rodada %d: rotina sem falha", rodada).isEmpty();
            }

            for (String email : comConvite) {
                String ctx = "rodada " + rodada;
                assertThat(convitesDe(email)).as(ctx).hasSize(2);
                assertThat(convitesDe(email, "ENVIADO")).as(ctx).hasSize(1);
                assertThat(convitesDe(email, "ENVIADO").getFirst().origin()).as(ctx).isEqualTo("AUTO");
                assertThat(contador(email)).as(ctx).isEqualTo(new Contador(0, null, 1));
                umEmailDeConvite(email);
            }
            for (String email : semConvite) {
                assertThat(convitesDe(email)).as("rodada " + rodada).isEmpty();
                assertThat(contador(email)).as("rodada " + rodada).isEqualTo(new Contador(0, null, 0));
                aguardaTotalDeConvites(email, 0);
            }
        }
    }

    // ============================================================ apoio: concorrencia e logs

    /** Nome do logger do JobRunner (a rotina que lanca excecao so deixa rastro la, sem dado pessoal). */
    private static final class JobRunnerLogger {
        static final String NOME = JobRunner.class.getName();
    }

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
                res.add(f.get(90, TimeUnit.SECONDS));
            }
            return res;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Status HTTP da requisicao; excecao nao tratada pelo MockMvc conta como 500. */
    private int statusHttp(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    /** Captura as mensagens de log de um logger durante um bloco try-with-resources (restaura o nivel ao fechar). */
    private static final class CapturaDeLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level nivelAnterior;

        CapturaDeLogs(String nomeDoLogger, Level nivel) {
            this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(nomeDoLogger);
            this.nivelAnterior = logger.getLevel();
            appender.start();
            logger.addAppender(appender);
            if (nivel != null) {
                logger.setLevel(nivel);
            }
        }

        /** Mensagem formatada (mais a mensagem da excecao, se houver) de cada evento capturado ate agora. */
        List<String> mensagens() {
            List<ILoggingEvent> copia;
            synchronized (appender) {
                copia = new ArrayList<>(appender.list);
            }
            List<String> res = new ArrayList<>();
            for (ILoggingEvent e : copia) {
                String extra = e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage();
                res.add(e.getFormattedMessage() + extra);
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

    private static String ipAleatorio() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    /** Redeem real, com IP remoto distinto por chamada (o limite por IP, R-03, nunca interfere). */
    private MockHttpServletRequestBuilder resgatar(String email, String codigo) throws Exception {
        String ip = ipAleatorio();
        return post(REDEEM)
                .contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content(json.writeValueAsString(Map.of("email", email, "code", codigo)));
    }

    private String resgatarToken(String email, String codigo) throws Exception {
        MvcResult r = mockMvc.perform(resgatar(email, codigo)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("registrationToken").asText();
    }

    private MockHttpServletRequestBuilder registrar(String token) {
        try {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("registrationToken", token);
            c.put("name", "Joana Souza");
            c.put("phone", "21912345678");
            c.put("address", enderecoJson());
            c.put("password", "Convidada#2026");
            return post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(c));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode criarConvite(Grupo g, String email) throws Exception {
        MvcResult r = mockMvc.perform(post(ELDERS + "/{e}/invitations", g.elderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g))
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder reenviar(Grupo g, UUID convite) {
        return post(ELDERS + "/{e}/invitations/{i}/resend", g.elderId(), convite)
                .header(HttpHeaders.AUTHORIZATION, bearer(g));
    }

    private JsonNode reenviarConvite(Grupo g, UUID convite) throws Exception {
        MvcResult r = mockMvc.perform(reenviar(g, convite)).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder cancelar(Grupo g, UUID convite) {
        return post(ELDERS + "/{e}/invitations/{i}/cancel", g.elderId(), convite)
                .header(HttpHeaders.AUTHORIZATION, bearer(g));
    }

    /**
     * Provoca o bloqueio de verdade: 4 erros (400) e a 5a (423) em POST /invitations/redeem com um
     * codigo errado (diferente do vigente, se conhecido). Confere o fim do bloqueio no banco
     * (agora + 30 min) e o devolve.
     */
    private Instant bloquear(String email, String codigoVigente) throws Exception {
        String errado = CODIGO_ERRADO.equals(codigoVigente) ? "654322" : CODIGO_ERRADO;
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(resgatar(email, errado)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(resgatar(email, errado)).andExpect(status().isLocked());
        Instant fim = contador(email).bloqueadoAte();
        assertThat(fim).as("pre-condicao: 5o erro bloqueia por 30 min").isEqualTo(clock.instant().plus(BLOQUEIO));
        return fim;
    }

    /** Estado equivalente ao do 5o erro (contador 5, bloqueado 30 min), semeado - para as corridas, onde o bloqueio nao e o objeto. */
    private Instant bloquearRapido(String email, int reenvios) {
        Instant fim = clock.instant().plus(BLOQUEIO);
        semearContador(email, 5, fim, reenvios);
        return fim;
    }

    // ============================================================ apoio: sentinela (controle positivo)

    /** E-mail com convite ENVIADO bloqueado pelo redeem real, no instante atual. */
    private Sentinela sentinela(Grupo g) throws Exception {
        String email = emailNovo();
        UUID convite = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        bloquear(email, CODIGO);
        return new Sentinela(email, convite);
    }

    /** Sentinela com o bloqueio semeado (mesmo fim que {@link #bloquearRapido}). */
    private Sentinela sentinelaRapida(Grupo g) {
        return sentinelaVencendoEm(g, BLOQUEIO);
    }

    private Sentinela sentinelaVencendoEm(Grupo g, Duration ate) {
        String email = emailNovo();
        UUID convite = semearConvite(g, email, "ENVIADO", clock.instant().plus(PRAZO_CONVITE), CODIGO, g.responsavel().getId());
        semearContador(email, 5, clock.instant().plus(ate), 0);
        return new Sentinela(email, convite);
    }

    /** Depois do job: a sentinela foi reenviada (antigo CANCELADO, 1 AUTO ENVIADO, autoResends 1, 1 e-mail). */
    private void confirmaSentinelaReenviada(Sentinela s) {
        assertThat(convite(s.conviteId()).status()).as("controle positivo: o job rodou e reenviou a sentinela").isEqualTo("CANCELADO");
        List<Convite> enviados = convitesDe(s.email(), "ENVIADO");
        assertThat(enviados).hasSize(1);
        assertThat(enviados.getFirst().origin()).isEqualTo("AUTO");
        assertThat(contador(s.email()).reenvios()).isEqualTo(1);
        aguardaTotalDeConvites(s.email(), 1);
    }

    // ============================================================ apoio: e-mail

    private List<MailpitClient.Summary> emailsDeConvite(String email) {
        return mailpit.messagesTo(email).stream().filter(m -> m.subject() != null && m.subject().startsWith(ASSUNTO_PREFIXO)).toList();
    }

    /** Espera exatamente {@code esperado} e-mails de convite para o endereco e confirma que o total nao muda. */
    private void aguardaTotalDeConvites(String email, int esperado) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(emailsDeConvite(email)).hasSize(esperado));
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeConvite(email)).hasSize(esperado));
    }

    /** Espera exatamente um e-mail de convite (e que nao chegue um segundo) e o devolve completo. */
    private MailpitClient.Detail umEmailDeConvite(String email) {
        aguardaTotalDeConvites(email, 1);
        return mailpit.message(emailsDeConvite(email).getFirst().id());
    }

    /** O e-mail de convite mais recente do endereco (o Mailpit lista do mais novo ao mais antigo). */
    private MailpitClient.Detail ultimoEmailDeConvite(String email) {
        return mailpit.message(emailsDeConvite(email).getFirst().id());
    }

    private String codigoDoEmail(MailpitClient.Detail mail) {
        Matcher m = SEIS_DIGITOS.matcher(mail.text());
        assertThat(m.find()).as("o e-mail traz um codigo de 6 digitos").isTrue();
        return m.group(1);
    }

    /** Texto sem espacos repetidos e com o codigo trocado por marcador, para comparar e-mails. */
    private static String normalizado(String texto) {
        return SEIS_DIGITOS.matcher(texto).replaceAll("######").replaceAll("\\s+", " ").strip();
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

    /** Contador do e-mail lido cru do banco; zerado quando nao ha linha. */
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

    /** Semeia convite com o hash real do codigo (D-04), no status e com o criador pedidos. */
    private UUID semearConvite(Grupo g, String email, String status, Instant expiresAt, String codigo, UUID criadoPor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                id, g.groupId(), email, encoder.encode(codigo), status, Timestamp.from(expiresAt), criadoPor,
                Timestamp.from(clock.instant()));
        return id;
    }

    // ============================================================ apoio: fabricas

    private Grupo grupo() {
        return grupo("Zélia Responsável", "Francisca Oliveira");
    }

    /** Responsavel ATIVO que cria o idoso (e o grupo) pela API real; o idoso tem alergias e condicoes (para o P5). */
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
        User user = User.signup(nome, "reenvio-resp-" + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    /** Conta verificada com membership ATIVA (Familiar) no grupo: nunca "sem grupo" para as rotinas de retencao. */
    private User familiarAtiva(Grupo g, String nome) {
        User user = conta(nome);
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        m.approve(Role.FAMILIAR, g.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private static String emailNovo() {
        return "reenvio-" + UUID.randomUUID() + "@teste.com";
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
