package br.com.cuidamais.membership.controller;

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
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-045 - {@code POST /elders/{elderId}/members/{memberId}/approve} e {@code .../reject}
 * (contrato {@code approveMember} / {@code rejectMember}, {@code x-required-permission: admin}).
 *
 * <p>ACs cobertos: AC-006.3 (aprovar exige escolher CUIDADOR ou FAMILIAR e leva a ATIVO), AC-006.4
 * (recusar leva a RECUSADO), AC-006.6 (Cuidador/Familiar comum nao decidem), AC-006.8 (pedido
 * vencido falha com a mensagem exata, mesmo antes do job), AC-007.1 (e-mail do resultado), AC-007.2
 * (e-mail de recusa orienta pedir novo convite), AC-009.4 (o Responsavel nao e alvo), AC-003.6
 * (recusado pode ser convidado de novo). Decisoes: D-07, D-08 (lock otimista), D-09 rev.2 (410
 * {@code REQUEST_EXPIRED} vs 409), D-23 (e-mail so apos o commit), D-32 (404 para outro grupo).
 *
 * <p>Decisoes do orquestrador fixadas nestes testes:
 * <ul>
 *   <li>approve: corpo {@code {role, version}}; {@code role} so {@code CUIDADOR}/{@code FAMILIAR}
 *       (o enum {@link Role} e o schema {@code Role} do contrato nao tem outro valor; ausente ou
 *       invalido => 400 {@code ValidationProblem}); {@code version} ausente => 400; diferente da
 *       atual => 409. reject: sem corpo.
 *   <li>Sucesso => 200 com o schema {@code Member} (ATIVO/RECUSADO, {@code decidedAt}, {@code
 *       version} incrementada, {@code isAdmin=false}); {@code memberships.decided_by} = o
 *       Responsavel.
 *   <li>Autorizacao: 401 sem token; 403 {@code NOT_ALLOWED} para nao admin; 404 para Responsavel
 *       de outro grupo, elder inexistente, membro inexistente ou membro de outro elder (D-32).
 *   <li>Estado/prazo: so {@code AGUARDANDO_APROVACAO} com {@code agora < expiresAt} e decidivel.
 *       Vencido (>= 24h, inclusive 24h exatos e 24h+1s) ou {@code EXPIRADO} => 410 {@code
 *       REQUEST_EXPIRED} com o detail de AC-006.8; o 410 tem precedencia sobre o 409 de version
 *       obsoleta. ATIVO/RECUSADO/REMOVIDO => 409.
 *   <li>Codes estaveis do 409 (enum de motivos, no estilo de {@code InvitationConflictCode}):
 *       {@code REQUEST_ALREADY_DECIDED} (status nao pendente), {@code VERSION_CONFLICT} (version
 *       obsoleta no approve) e {@code ADMIN_PROTECTED} (alvo e o Responsavel, AC-009.4). Na
 *       corrida, o perdedor pode ver o status ja decidido ou a version obsoleta, conforme o
 *       entrelacamento; os testes de concorrencia aceitam qualquer dos dois codes, mas sempre 409.
 *   <li>E-mail: exatamente um por decisao bem-sucedida, so apos o commit; 400/403/404/409/410 nao
 *       enviam; o corpo nao leva dado do idoso (P5).
 * </ul>
 *
 * <p>Caixa-preta pela rota HTTP real. Todo 200 e validado contra o contrato ({@link
 * ContractValidation}) e todo erro contra o schema {@code Problem}. As corridas usam threads com
 * largada simultanea numa barreira e varias rodadas (padrao de {@code
 * InvitationRegisterConcurrencyTest}).
 */
@AutoConfigureMockMvc
class MemberDecisionTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String MSG_EXPIRADO = "Este pedido expirou. Envie um novo convite.";
    private static final String ASSUNTO_APROVADO = "Cuida+: seu pedido foi aprovado";
    private static final String ASSUNTO_RECUSADO = "Cuida+: seu pedido foi recusado";
    private static final int RODADAS = 5;

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate jdbc;

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private record Pedido(User user, UUID memberId) {}

    // ============================================================ AC-006.3 aprovar

    /**
     * Caminho feliz: o Responsavel aprova escolhendo CUIDADOR. O 200 tem o schema Member com status
     * ATIVO, o papel escolhido, decidedAt preenchido, version incrementada e isAdmin=false; o banco
     * guarda decided_by = Responsavel.
     */
    @Test
    @DisplayName("AC-006.3: aprovar com CUIDADOR => 200 Member ATIVO, papel escolhido, decidedAt, version+1, isAdmin=false")
    void ac_006_3_aprova_como_cuidador() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Joana");
        long versao = versaoAtual(p);

        JsonNode m = esperar200(aprovar(g, p, g.responsavel(), Role.CUIDADOR, versao));

        assertThat(m.path("id").asText()).isEqualTo(p.memberId().toString());
        assertThat(m.path("status").asText()).isEqualTo("ATIVO");
        assertThat(m.path("role").asText()).isEqualTo("CUIDADOR");
        assertThat(m.path("isAdmin").asBoolean(true)).isFalse();
        assertThat(m.path("decidedAt").asText()).isNotBlank();
        assertThat(m.path("version").asLong()).isGreaterThan(versao);
        assertThat(statusNoBanco(p)).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM memberships WHERE id = ?", UUID.class, p.memberId()))
                .isEqualTo(g.responsavel().getId());
    }

    /** O outro papel valido: FAMILIAR e gravado como escolhido (a escolha do Responsavel vale). */
    @Test
    @DisplayName("AC-006.3: aprovar com FAMILIAR => 200 e role FAMILIAR")
    void ac_006_3_aprova_como_familiar() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Marta");

        JsonNode m = esperar200(aprovar(g, p, g.responsavel(), Role.FAMILIAR, versaoAtual(p)));

        assertThat(m.path("role").asText()).isEqualTo("FAMILIAR");
        assertThat(m.path("status").asText()).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject("SELECT role FROM memberships WHERE id = ?", String.class, p.memberId()))
                .isEqualTo("FAMILIAR");
    }

    /**
     * Prova de que o aprovado e membro ATIVO de verdade (nao so um campo): com o proprio token ele
     * passa a acessar o grupo (GET /members => 200), o que antes da aprovacao era 403.
     */
    @Test
    @DisplayName("AC-006.3: apos aprovar, a pessoa acessa o grupo (GET /members => 200)")
    void ac_006_3_aprovado_passa_a_acessar_o_grupo() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Rita");
        // Etapa 1: antes de aprovar, o pendente nao acessa o grupo.
        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId()).header(HttpHeaders.AUTHORIZATION, bearer(p.user())))
                .andExpect(status().isForbidden());

        esperar200(aprovar(g, p, g.responsavel(), Role.CUIDADOR, versaoAtual(p)));

        // Etapa 2: depois, o acesso funciona.
        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId()).header(HttpHeaders.AUTHORIZATION, bearer(p.user())))
                .andExpect(status().isOk());
    }

    /**
     * Papel e obrigatorio e restrito (AC-006.3): corpo sem role, role invalido (inclusive
     * RESPONSAVEL, que nao existe no enum) e role nulo => 400 ValidationProblem; nada muda no
     * banco e nenhum e-mail sai.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"version\":0}", "{\"role\":null,\"version\":0}", "{\"role\":\"RESPONSAVEL\",\"version\":0}",
        "{\"role\":\"ADMIN\",\"version\":0}", "{\"role\":\"cuidador\",\"version\":0}", "{}", ""})
    @DisplayName("AC-006.3: role ausente ou fora de CUIDADOR/FAMILIAR => 400 ValidationProblem")
    void ac_006_3_role_invalido_400(String corpoBruto) throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Invalida");
        mailpit.deleteAll();

        // Cada linha da tabela de @ValueSource e um corpo invalido distinto (ausente/nulo/desconhecido/minusculo/vazio).
        MvcResult r = mockMvc.perform(post(ELDERS + "/{e}/members/{m}/approve", g.elderId(), p.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content(corpoBruto))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
        semEmail(p.user().getEmail());
    }

    /** version e obrigatoria no approve (D-08): corpo so com role => 400 com erro no campo version. */
    @Test
    @DisplayName("AC-006.3: approve sem version => 400 ValidationProblem no campo version")
    void ac_006_3_sem_version_400() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "SemVersao");

        MvcResult r = mockMvc.perform(post(ELDERS + "/{e}/members/{m}/approve", g.elderId(), p.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content("{\"role\":\"CUIDADOR\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        List<String> campos = new ArrayList<>();
        body.path("errors").forEach(e -> campos.add(e.path("field").asText()));
        assertThat(campos).contains("version");
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    // ============================================================ AC-006.4 recusar

    /** Recusa: 200 Member com status RECUSADO, decidedAt, version+1; decided_by = Responsavel; sem corpo na requisicao. */
    @Test
    @DisplayName("AC-006.4: recusar => 200 Member RECUSADO, decidedAt, version+1, decided_by do Responsável")
    void ac_006_4_recusa_o_pedido() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Recusada");
        long versao = versaoAtual(p);

        JsonNode m = esperar200(recusar(g, p, g.responsavel()));

        assertThat(m.path("status").asText()).isEqualTo("RECUSADO");
        assertThat(m.path("isAdmin").asBoolean(true)).isFalse();
        assertThat(m.path("decidedAt").asText()).isNotBlank();
        assertThat(m.path("version").asLong()).isGreaterThan(versao);
        assertThat(statusNoBanco(p)).isEqualTo("RECUSADO");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM memberships WHERE id = ?", UUID.class, p.memberId()))
                .isEqualTo(g.responsavel().getId());
    }

    /** Recusado nao ganha acesso ao grupo: o GET /members dele continua negado (403 ACCOUNT_STATE). */
    @Test
    @DisplayName("AC-006.4: recusado continua sem acesso ao grupo")
    void ac_006_4_recusado_sem_acesso() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "SemAcesso");
        esperar200(recusar(g, p, g.responsavel()));

        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId()).header(HttpHeaders.AUTHORIZATION, bearer(p.user())))
                .andExpect(status().is4xxClientError());
    }

    /**
     * AC-003.6 (barato): depois da recusa o Responsavel consegue criar novo convite para o mesmo
     * e-mail (201), em vez de ALREADY_MEMBER/EMAIL_UNAVAILABLE.
     */
    @Test
    @DisplayName("AC-003.6: após recusar, o Responsável pode convidar o mesmo e-mail de novo (201)")
    void ac_003_6_recusado_pode_ser_convidado_de_novo() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Reconvidada");
        esperar200(recusar(g, p, g.responsavel()));

        mockMvc.perform(post(ELDERS + "/{e}/invitations", g.elderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content(json.writeValueAsString(Map.of("email", p.user().getEmail()))))
                .andExpect(status().isCreated());
    }

    // ============================================================ AC-006.6 autorizacao

    /**
     * Cuidador e Familiar comum => 403 NOT_ALLOWED nas duas rotas, sem alterar o pedido e sem
     * e-mail. Cada combinacao (rota x papel) e uma linha: approve/reject com Cuidador e Familiar.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve:CUIDADOR", "approve:FAMILIAR", "reject:CUIDADOR", "reject:FAMILIAR"})
    @DisplayName("AC-006.6: Cuidador e Familiar comum recebem 403 NOT_ALLOWED em approve e reject")
    void ac_006_6_nao_admin_403(String caso) throws Exception {
        String[] partes = caso.split(":");
        Grupo g = grupo();
        User comum = membroAtivo(g, "Comum", Role.valueOf(partes[1]));
        Pedido p = pedido(g, "Alvo");
        mailpit.deleteAll();

        // Etapa: a mesma rota/papel da linha corrente da tabela.
        MvcResult r = mockMvc.perform(partes[0].equals("approve")
                        ? aprovar(g, p, comum, Role.CUIDADOR, versaoAtual(p))
                        : recusar(g, p, comum))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
        semEmail(p.user().getEmail());
    }

    /** Sem token => 401 Problem em ambas as rotas (linha approve e linha reject). */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.6: sem token => 401 Problem")
    void ac_006_6_sem_token_401(String acao) throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "SemToken");

        MvcResult r = mockMvc.perform(post(ELDERS + "/{e}/members/{m}/" + acao, g.elderId(), p.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CUIDADOR\",\"version\":0}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    /**
     * D-32: Responsavel de OUTRO grupo no elder do grupo A => 404 (nao 403), sem decidir nada.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.6: Responsável de outro grupo recebe 404 (D-32)")
    void ac_006_6_outro_grupo_404(String acao) throws Exception {
        Grupo a = grupo();
        Grupo b = grupo();
        Pedido p = pedido(a, "DeA");

        esperar404(decidir(acao, a, p, b.responsavel(), versaoAtual(p)));

        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    /** memberId inexistente, ou de membro de OUTRO elder usado na URL do elder A => 404 (D-32). */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.6: membro inexistente ou de outro elder => 404")
    void ac_006_6_membro_inexistente_ou_de_outro_elder_404(String acao) throws Exception {
        Grupo a = grupo();
        Grupo b = grupo();
        Pedido deB = pedido(b, "DeB");
        // Linha 1: memberId que nao existe.
        esperar404(decidir(acao, a, new Pedido(deB.user(), UUID.randomUUID()), a.responsavel(), 0));
        // Linha 2: memberId existente, mas pertencente ao elder B, na URL do elder A.
        esperar404(decidir(acao, a, deB, a.responsavel(), versaoAtual(deB)));
        // Linha 3: elderId inexistente.
        mockMvc.perform(post(ELDERS + "/{e}/members/{m}/" + acao, UUID.randomUUID(), deB.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(a.responsavel()))
                        .content("{\"role\":\"CUIDADOR\",\"version\":0}"))
                .andExpect(status().isNotFound());

        assertThat(statusNoBanco(deB)).isEqualTo("AGUARDANDO_APROVACAO");
    }

    // ============================================================ AC-006.8 prazo / D-09 rev.2

    /**
     * Pedido vencido, AINDA persistido como AGUARDANDO_APROVACAO (o job nao rodou): 410
     * REQUEST_EXPIRED com a mensagem exata, o status no banco nao muda e nenhum e-mail sai. Cada
     * linha: acao x tempo decorrido (24h exatos, 24h+1s, 25h).
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve:PT24H", "approve:PT24H1S", "approve:PT25H", "reject:PT24H", "reject:PT24H1S",
        "reject:PT25H"})
    @DisplayName("AC-006.8: pedido vencido (24h, 24h+1s, 25h) antes do job => 410 REQUEST_EXPIRED com a mensagem exata")
    void ac_006_8_vencido_antes_do_job_410(String caso) throws Exception {
        String[] partes = caso.split(":");
        Grupo g = grupo();
        Pedido p = pedido(g, "Vencida");
        long versao = versaoAtual(p);
        clock.advance(Duration.parse(partes[1]));
        mailpit.deleteAll();

        // Etapa: a decisao (approve ou reject conforme a linha) falha com 410.
        esperar410(decidir(partes[0], g, p, g.responsavel(), versao));

        // Etapa: o estado persistido continua AGUARDANDO (quem expira e o job, T-046) e nada foi enviado.
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
        semEmail(p.user().getEmail());
    }

    /** Pedido ja marcado EXPIRADO (job rodou) tambem responde 410 com a mesma mensagem. */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-006.8: pedido EXPIRADO => 410 REQUEST_EXPIRED")
    void ac_006_8_expirado_410(String acao) throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "JaExpirada");
        clock.advance(Duration.ofHours(25));
        Membership m = memberships.findById(p.memberId()).orElseThrow();
        m.expire(clock);
        memberships.save(m);
        long versao = versaoAtual(p);

        esperar410(decidir(acao, g, p, g.responsavel(), versao));

        assertThat(statusNoBanco(p)).isEqualTo("EXPIRADO");
    }

    /** Precedencia (D-09 rev.2): vencido E com version obsoleta => 410, nao 409. */
    @Test
    @DisplayName("AC-006.8: 410 tem precedência sobre 409 de version obsoleta")
    void ac_006_8_410_precede_409_de_version() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "VencidaObsoleta");
        long obsoleta = versaoAtual(p);
        jdbc.update("UPDATE memberships SET version = version + 1 WHERE id = ?", p.memberId());
        clock.advance(Duration.ofHours(25));

        esperar410(aprovar(g, p, g.responsavel(), Role.CUIDADOR, obsoleta));
    }

    /** Fronteira oposta: aos 23h59 o pedido ainda e vigente e a aprovacao funciona (200). */
    @Test
    @DisplayName("AC-006.8: aos 23h59 ainda aprova (200)")
    void ac_006_8_aos_23h59_aprova() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Limite");
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        esperar200(aprovar(g, p, g.responsavel(), Role.FAMILIAR, versaoAtual(p)));

        assertThat(statusNoBanco(p)).isEqualTo("ATIVO");
    }

    /** O mesmo vale para recusar aos 23h59 (200 RECUSADO). */
    @Test
    @DisplayName("AC-006.8: aos 23h59 ainda recusa (200)")
    void ac_006_8_aos_23h59_recusa() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "LimiteRecusa");
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        esperar200(recusar(g, p, g.responsavel()));

        assertThat(statusNoBanco(p)).isEqualTo("RECUSADO");
    }

    // ============================================================ 409: estado / version / admin

    /**
     * Pedido que nao esta mais pendente (ATIVO, RECUSADO, REMOVIDO) => 409 REQUEST_ALREADY_DECIDED
     * em approve e em reject, sem mudar nada e sem e-mail. Mesmo ATIVO ha mais de 24h e 409, nao 410.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve:ATIVO", "approve:RECUSADO", "approve:REMOVIDO", "reject:ATIVO", "reject:RECUSADO",
        "reject:REMOVIDO"})
    @DisplayName("AC-006.3/006.4: pedido já decidido => 409 REQUEST_ALREADY_DECIDED")
    void ac_006_3_ja_decidido_409(String caso) throws Exception {
        String[] partes = caso.split(":");
        Grupo g = grupo();
        Pedido p = pedido(g, "JaDecidida");
        Membership m = memberships.findById(p.memberId()).orElseThrow();
        // Etapa: leva o pedido ao estado da linha corrente.
        switch (MembershipStatus.valueOf(partes[1])) {
            case ATIVO -> m.approve(Role.CUIDADOR, g.responsavel().getId(), clock);
            case RECUSADO -> m.reject(g.responsavel().getId(), clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, g.responsavel().getId(), clock);
                m.remove(clock);
            }
            default -> throw new IllegalStateException();
        }
        memberships.save(m);
        long versao = versaoAtual(p);
        clock.advance(Duration.ofHours(30));
        mailpit.deleteAll();

        JsonNode problema = esperar409(decidir(partes[0], g, p, g.responsavel(), versao));

        assertThat(problema.path("code").asText()).isEqualTo("REQUEST_ALREADY_DECIDED");
        assertThat(statusNoBanco(p)).isEqualTo(partes[1]);
        semEmail(p.user().getEmail());
    }

    /**
     * D-08: version obsoleta (alguem mexeu no pedido depois que o Responsavel o viu) mesmo com o
     * pedido ainda pendente => 409 VERSION_CONFLICT; estado inalterado e nenhum e-mail.
     */
    @Test
    @DisplayName("AC-006.3: approve com version obsoleta => 409 VERSION_CONFLICT e estado inalterado")
    void ac_006_3_version_obsoleta_409() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "Obsoleta");
        long vista = versaoAtual(p);
        jdbc.update("UPDATE memberships SET version = version + 1 WHERE id = ?", p.memberId());
        mailpit.deleteAll();

        JsonNode problema = esperar409(aprovar(g, p, g.responsavel(), Role.CUIDADOR, vista));

        assertThat(problema.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(statusNoBanco(p)).isEqualTo("AGUARDANDO_APROVACAO");
        semEmail(p.user().getEmail());
    }

    /**
     * AC-009.4: o proprio Responsavel (membership admin) como alvo => 409 ADMIN_PROTECTED em
     * approve e reject; continua ATIVO e admin.
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    @DisplayName("AC-009.4: decidir sobre o próprio Responsável => 409 ADMIN_PROTECTED")
    void ac_009_4_alvo_e_o_responsavel_409(String acao) throws Exception {
        Grupo g = grupo();
        Membership adm = memberships.findByUserId(g.responsavel().getId()).orElseThrow();
        Pedido alvo = new Pedido(g.responsavel(), adm.getId());

        JsonNode problema = esperar409(decidir(acao, g, alvo, g.responsavel(), adm.getVersion()));

        assertThat(problema.path("code").asText()).isEqualTo("ADMIN_PROTECTED");
        assertThat(statusNoBanco(alvo)).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject("SELECT is_admin FROM memberships WHERE id = ?", Boolean.class, adm.getId()))
                .isTrue();
    }

    // ============================================================ AC-007 e-mail do resultado

    /** AC-007.1: aprovar envia exatamente 1 e-mail ao e-mail da pessoa, com o assunto de aprovado. */
    @Test
    @DisplayName("AC-007.1: aprovar envia 1 e-mail de aprovação para a pessoa")
    void ac_007_1_email_de_aprovacao() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "EmailAprovada");
        mailpit.deleteAll();

        esperar200(aprovar(g, p, g.responsavel(), Role.CUIDADOR, versaoAtual(p)));

        MailpitClient.Detail mail = umEmail(p.user().getEmail());
        assertThat(mail.subject()).isEqualTo(ASSUNTO_APROVADO);
        assertThat(mail.text()).contains("aprovou");
        assertThat(mail.text()).doesNotContain("Francisca Oliveira"); // P5: nada do idoso
    }

    /**
     * AC-007.1 / AC-007.2: recusar envia exatamente 1 e-mail, que orienta pedir novo convite ao
     * Responsavel; sem dados do idoso (P5).
     */
    @Test
    @DisplayName("AC-007.1/007.2: recusar envia 1 e-mail que orienta pedir novo convite")
    void ac_007_2_email_de_recusa_orienta_novo_convite() throws Exception {
        Grupo g = grupo();
        Pedido p = pedido(g, "EmailRecusada");
        mailpit.deleteAll();

        esperar200(recusar(g, p, g.responsavel()));

        MailpitClient.Detail mail = umEmail(p.user().getEmail());
        assertThat(mail.subject()).isEqualTo(ASSUNTO_RECUSADO);
        assertThat(mail.text()).contains("recusou").contains("peça um novo convite ao Responsável");
        assertThat(mail.text()).doesNotContain("Francisca Oliveira");
    }

    // ============================================================ concorrencia (D-08)

    /**
     * D-08: 3 approves simultaneos com a MESMA version (papeis alternados) sobre o mesmo pedido,
     * em 5 rodadas. Exatamente um 200, os demais 409 (nunca 500); um unico status final ATIVO com o
     * papel do vencedor; um unico e-mail de aprovacao.
     */
    @Test
    @DisplayName("AC-006.3/D-08: approve x approve simultâneos (mesma version) => um 200, os outros 409, 1 e-mail")
    void ac_006_3_approve_x_approve_concorrentes() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Pedido p = pedido(g, "Corrida" + rodada);
            long versao = versaoAtual(p);
            mailpit.deleteAll();

            // Etapa: tres tentativas de aprovar com papeis diferentes e a mesma version.
            List<Role> papeis = List.of(Role.CUIDADOR, Role.FAMILIAR, Role.CUIDADOR);
            List<Callable<MvcResult>> acoes = new ArrayList<>();
            for (Role papel : papeis) {
                acoes.add(() -> mockMvc.perform(aprovar(g, p, g.responsavel(), papel, versao)).andReturn());
            }
            List<MvcResult> res = largadaSimultanea(acoes);

            String ctx = "rodada " + rodada + ", status=" + statuses(res);
            int vencedor = verificaUmVencedor(res, ctx);
            assertThat(statusNoBanco(p)).as("ATIVO - %s", ctx).isEqualTo("ATIVO");
            assertThat(jdbc.queryForObject("SELECT role FROM memberships WHERE id = ?", String.class, p.memberId()))
                    .as("papel do vencedor - %s", ctx).isEqualTo(papeis.get(vencedor).name());
            assertThat(umEmail(p.user().getEmail()).subject()).isEqualTo(ASSUNTO_APROVADO);
        }
    }

    /**
     * D-08: approve x reject simultaneos. Exatamente um vence; o status final bate com quem venceu
     * (ATIVO se foi o approve, RECUSADO se foi o reject) e so o e-mail do vencedor sai.
     */
    @Test
    @DisplayName("AC-006.3/006.4/D-08: approve x reject simultâneos => um 200, outro 409, estado e e-mail do vencedor")
    void ac_006_4_approve_x_reject_concorrentes() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Pedido p = pedido(g, "Disputa" + rodada);
            long versao = versaoAtual(p);
            mailpit.deleteAll();

            List<Callable<MvcResult>> acoes = List.of(
                    () -> mockMvc.perform(aprovar(g, p, g.responsavel(), Role.CUIDADOR, versao)).andReturn(),
                    () -> mockMvc.perform(recusar(g, p, g.responsavel())).andReturn());
            List<MvcResult> res = largadaSimultanea(acoes);

            String ctx = "rodada " + rodada + ", status=" + statuses(res);
            int vencedor = verificaUmVencedor(res, ctx);
            // Indice 0 = approve => ATIVO / aprovado; indice 1 = reject => RECUSADO / recusado.
            assertThat(statusNoBanco(p)).as("status do vencedor - %s", ctx)
                    .isEqualTo(vencedor == 0 ? "ATIVO" : "RECUSADO");
            assertThat(umEmail(p.user().getEmail()).subject()).as("e-mail do vencedor - %s", ctx)
                    .isEqualTo(vencedor == 0 ? ASSUNTO_APROVADO : ASSUNTO_RECUSADO);
        }
    }

    /** D-08: 3 rejects simultaneos => um 200, dois 409, RECUSADO uma vez, um unico e-mail. */
    @Test
    @DisplayName("AC-006.4/D-08: reject x reject simultâneos => um 200, os outros 409, 1 e-mail")
    void ac_006_4_reject_x_reject_concorrentes() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Pedido p = pedido(g, "DuplaRecusa" + rodada);
            mailpit.deleteAll();

            List<Callable<MvcResult>> acoes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                acoes.add(() -> mockMvc.perform(recusar(g, p, g.responsavel())).andReturn());
            }
            List<MvcResult> res = largadaSimultanea(acoes);

            String ctx = "rodada " + rodada + ", status=" + statuses(res);
            verificaUmVencedor(res, ctx);
            assertThat(statusNoBanco(p)).as("RECUSADO - %s", ctx).isEqualTo("RECUSADO");
            assertThat(umEmail(p.user().getEmail()).subject()).isEqualTo(ASSUNTO_RECUSADO);
        }
    }

    // ============================================================ apoio: concorrencia

    /** Executa as acoes em threads que largam juntas numa barreira e devolve os resultados na ordem. */
    private List<MvcResult> largadaSimultanea(List<Callable<MvcResult>> acoes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acoes.size());
        try {
            CountDownLatch prontos = new CountDownLatch(acoes.size());
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (Callable<MvcResult> acao : acoes) {
                futuros.add(pool.submit(() -> {
                    prontos.countDown();
                    largada.await();
                    return acao.call();
                }));
            }
            assertThat(prontos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<MvcResult> res = new ArrayList<>();
            for (Future<MvcResult> f : futuros) {
                res.add(f.get(60, TimeUnit.SECONDS));
            }
            return res;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Integer> statuses(List<MvcResult> res) {
        return res.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    /**
     * Invariante das corridas: exatamente um 200 e todos os outros 409 (nunca 500); cada 409 e um
     * Problem com code de conflito. Devolve o indice do vencedor.
     */
    private int verificaUmVencedor(List<MvcResult> res, String ctx) throws Exception {
        List<Integer> st = statuses(res);
        assertThat(st.stream().filter(s -> s == 200).count()).as("um único 200 - %s", ctx).isEqualTo(1);
        assertThat(st.stream().filter(s -> s == 409).count()).as("os demais 409 - %s", ctx).isEqualTo(st.size() - 1);
        int vencedor = -1;
        for (int i = 0; i < res.size(); i++) {
            JsonNode corpo = json.readTree(res.get(i).getResponse().getContentAsString());
            if (st.get(i) == 200) {
                vencedor = i;
            } else {
                assertThat(ContractSchemas.schemaFor("Problem").validate(corpo)).isEmpty();
                assertThat(corpo.path("code").asText()).as("code do perdedor - %s", ctx)
                        .isIn("REQUEST_ALREADY_DECIDED", "VERSION_CONFLICT");
            }
        }
        return vencedor;
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

    /** 200 validado contra o contrato (schema Member). */
    private JsonNode esperar200(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void esperar404(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andExpect(status().isNotFound()).andReturn();
        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    private JsonNode esperar409(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andExpect(status().isConflict()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        return body;
    }

    private void esperar410(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andExpect(status().isGone()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo("REQUEST_EXPIRED");
        assertThat(body.path("detail").asText()).isEqualTo(MSG_EXPIRADO);
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

    private Grupo grupo() {
        try {
            User responsavel = conta("Zélia Responsável");
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

    /** Pedido AGUARDANDO_APROVACAO vigente (recem-criado no relogio atual). */
    private Pedido pedido(Grupo g, String nome) {
        User user = conta(nome);
        Membership m = memberships.save(Membership.request(g.groupId(), user.getId(), clock));
        return new Pedido(user, m.getId());
    }

    private User membroAtivo(Grupo g, String nome, Role papel) {
        User user = conta(nome);
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        m.approve(papel, g.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private User conta(String nome) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "decisao-" + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private Map<String, Object> elderInput() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
        req.put("address", end);
        req.put("emergencyContacts", List.of());
        return req;
    }
}
