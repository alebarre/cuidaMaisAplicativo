package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-047 - {@code PATCH /elders/{elderId}/members/{memberId}} (contrato {@code changeMemberRole}) e
 * {@code DELETE /elders/{elderId}/members/{memberId}} (contrato {@code removeMember}), ambos com
 * {@code x-required-permission: admin}.
 *
 * <p>ACs cobertos: AC-009.1 (troca de papel entre CUIDADOR e FAMILIAR vale na hora, inclusive para
 * sessoes ja abertas), AC-009.2 (remover => REMOVIDO e TODAS as sessoes encerradas na hora),
 * AC-009.3 (os dados do removido continuam guardados, com o nome dele), AC-009.4 (o Responsavel nao
 * e removido nem tem o papel alterado; ninguem e promovido a Responsavel), AC-009.5 (so o Responsavel
 * ve contato do REMOVIDO, na lista filtrada por status), AC-014.2 (REMOVIDO nao e excluido pelo job
 * de retencao), AC-012.1 (so ATIVO sem administracao vai a REMOVIDO; qualquer outra transicao e
 * rejeitada) e AC-012.2 (remocao: sem e-mail para a pessoa). Decisoes: D-07 (transicoes na
 * entidade), D-08 (lock otimista: a corrida tem um unico vencedor), D-13 rev. 2 (o JWT nao carrega
 * papel nem status; o banco e lido a cada requisicao), D-29 (papel so CUIDADOR/FAMILIAR), D-32 (404
 * para outro grupo/inexistente, 403 para o proprio grupo sem permissao).
 *
 * <p>Decisoes do orquestrador fixadas nestes testes (a confirmar pelo dono da spec):
 * <ul>
 *   <li>Autorizacao (as duas rotas): sem token 401; Cuidador e Familiar comum 403 {@code
 *       NOT_ALLOWED}; Responsavel de outro grupo, {@code elderId} inexistente ou de outro grupo,
 *       membro inexistente ou {@code memberId} de outro grupo => 404 identico (AC-015.3, D-32).
 *   <li>PATCH, corpo {@code {role}}: {@code role} so {@code CUIDADOR}/{@code FAMILIAR}; ausente,
 *       nulo, {@code RESPONSAVEL}, {@code ADMIN}, minusculas ou corpo vazio => 400 {@code
 *       ValidationProblem} sem tocar no banco (ninguem vira Responsavel, AC-009.4). Sucesso => 200
 *       schema {@code Member} (role novo, {@code version} incrementada). Mesmo papel que o atual =>
 *       200 idempotente. A troca de papel NAO derruba a sessao (o refresh continua valendo).
 *   <li>Alvo e o Responsavel (inclusive o proprio Responsavel agindo sobre si) => 409 com {@code
 *       code = ADMIN_PROTECTED}, nas duas rotas.
 *   <li><b>Code novo a criar pelo implementador:</b> alvo que nao esta {@code ATIVO}
 *       (AGUARDANDO_APROVACAO, RECUSADO, EXPIRADO, REMOVIDO) => 409 com {@code code =
 *       MEMBER_NOT_ACTIVE} (valor novo em {@code MemberConflictCode}; {@code REQUEST_ALREADY_DECIDED}
 *       significa "pedido ja decidido" e nao serve aqui). Nao exige mudar o contrato: o schema
 *       {@code Problem} deixa {@code code} livre e a resposta {@code MemberConflict} ja cobre
 *       "transicao invalida para o status atual (AC-012.1)". Nas corridas (D-08) o perdedor pode
 *       ver {@code MEMBER_NOT_ACTIVE} (o vencedor ja commitou) ou {@code VERSION_CONFLICT} (o UPDATE
 *       com {@code version} falhou), conforme o entrelacamento; os dois sao aceitos, mas sempre 409.
 *   <li>DELETE: 204 sem corpo. Efeitos: status REMOVIDO; TODOS os refresh tokens do usuario com
 *       {@code revoked_at} preenchido (e {@code /auth/refresh} com o cookie antigo => 401); o access
 *       token ja emitido passa a dar 401 (interceptor, REMOVIDO => sessao invalida); o login do
 *       removido continua sendo o 403 {@code REMOVED} ja existente de AC-008.2 (nenhuma sessao
 *       nova); NENHUM e-mail e enviado (AC-012.2); a linha em {@code memberships} e a conta em
 *       {@code users} (nome, e-mail, telefone, endereco, senha) permanecem intactas e {@code
 *       retention_deadline} fica nulo (AC-014.2, AC-009.3).
 *   <li>O reconvite do removido (AC-004.6) ja e provado em {@code InvitationRedeemTest} (a linha
 *       REMOVIDO do teste parametrizado); nao e duplicado aqui. A matriz completa de visibilidade
 *       (AC-009.5 para Cuidador/Familiar comum, 403 no filtro) e de {@code MemberListTest}; aqui so
 *       se prova que o removido APARECE para o Responsavel com contato apos o DELETE de verdade.
 *   <li>Nao ha hoje rota exclusiva de Familiar/Responsavel alem da visibilidade de contato em {@code
 *       GET /members}; e ela que prova o "vale na hora" (AC-009.1) com um access token emitido
 *       ANTES da troca.
 *   <li>P5: corpos de erro e logs nunca levam nome, e-mail, telefone ou endereco do membro.
 * </ul>
 *
 * <p>Caixa-preta pela rota HTTP real (contexto Spring completo, PostgreSQL e Mailpit via
 * Testcontainers); efeitos conferidos por SQL. Todo 200/204 e validado contra o contrato ({@link
 * ContractValidation}) e todo erro contra o schema {@code Problem}/{@code ValidationProblem}. As
 * corridas usam threads reais com largada simultanea numa barreira e varias rodadas (padrao de
 * {@code MemberDecisionTest}). Tempo so pelo {@link MutableClock} (D-10).
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MemberManagementTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String COOKIE = "refresh_token";
    private static final String SENHA = "Senha@Forte123";
    private static final String MEMBER_NOT_ACTIVE = "MEMBER_NOT_ACTIVE";
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

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JobRunner jobRunner;

    private String senhaHash;

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    /** Um membro do grupo: a conta e o id do vinculo (memberId da rota). */
    private record Membro(User user, UUID memberId) {}

    /** O que um login real entrega: o JWT do corpo e o valor opaco do cookie. */
    private record Sessao(String accessToken, String refreshToken) {}

    // ============================================================ AC-009.1 PATCH: papel

    /**
     * Caminho feliz: o Responsavel troca um Cuidador para Familiar. O 200 tem o schema Member
     * (validado contra o contrato) com role FAMILIAR, status ATIVO, isAdmin=false e version
     * incrementada; o banco guarda o papel novo.
     */
    @Test
    @DisplayName("AC-009.1: Responsável troca CUIDADOR -> FAMILIAR => 200 Member, role novo, version+1")
    void ac_009_1_cuidador_para_familiar() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Cuidadora Promovida", Role.CUIDADOR);
        long versao = versaoNoBanco(alvo);

        JsonNode m = esperar200(alterarPapel(g, alvo, bearer(g.responsavel()), "FAMILIAR"));

        assertThat(m.path("id").asText()).isEqualTo(alvo.memberId().toString());
        assertThat(m.path("role").asText()).isEqualTo("FAMILIAR");
        assertThat(m.path("status").asText()).isEqualTo("ATIVO");
        assertThat(m.path("isAdmin").asBoolean(true)).isFalse();
        assertThat(m.path("version").asLong()).isGreaterThan(versao);
        assertThat(papelNoBanco(alvo)).isEqualTo("FAMILIAR");
    }

    /** O sentido inverso: FAMILIAR -> CUIDADOR, com o mesmo contrato de resposta. */
    @Test
    @DisplayName("AC-009.1: Responsável troca FAMILIAR -> CUIDADOR => 200 Member, role novo, version+1")
    void ac_009_1_familiar_para_cuidador() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Familiar Rebaixado", Role.FAMILIAR);
        long versao = versaoNoBanco(alvo);

        JsonNode m = esperar200(alterarPapel(g, alvo, bearer(g.responsavel()), "CUIDADOR"));

        assertThat(m.path("role").asText()).isEqualTo("CUIDADOR");
        assertThat(m.path("status").asText()).isEqualTo("ATIVO");
        assertThat(m.path("isAdmin").asBoolean(true)).isFalse();
        assertThat(m.path("version").asLong()).isGreaterThan(versao);
        assertThat(papelNoBanco(alvo)).isEqualTo("CUIDADOR");
    }

    /**
     * Idempotencia: pedir o papel que o membro ja tem nao e erro (200, mesmo papel, continua ATIVO).
     * Cada linha da tabela e o papel atual igual ao pedido.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("AC-009.1: pedir o papel que o membro já tem => 200 idempotente")
    void ac_009_1_mesmo_papel_e_idempotente(Role papel) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Mesmo Papel " + papel, papel);

        // Linha da tabela: o papel pedido e o papel atual (CUIDADOR ou FAMILIAR).
        JsonNode m = esperar200(alterarPapel(g, alvo, bearer(g.responsavel()), papel.name()));

        assertThat(m.path("role").asText()).isEqualTo(papel.name());
        assertThat(m.path("status").asText()).isEqualTo("ATIVO");
        assertThat(papelNoBanco(alvo)).isEqualTo(papel.name());
    }

    /**
     * "Vale imediatamente" (AC-009.1, D-13): o Cuidador usa um access token emitido ANTES da
     * promocao. Antes ele nao ve e-mail, telefone e endereco de outro membro; depois do PATCH, com
     * o MESMO token e sem novo login, passa a ver os tres (matriz de AC-011.5/011.8 para Familiar).
     */
    @Test
    @DisplayName("AC-009.1: Cuidador promovido a Familiar passa a ver os contatos com o token já emitido")
    void ac_009_1_promocao_vale_na_hora_para_token_ja_emitido() throws Exception {
        Grupo g = grupo();
        Membro cuidador = membroAtivo(g, "Cuidador Promovido", Role.CUIDADOR);
        Membro outro = membroAtivo(g, "Outro Familiar", Role.FAMILIAR);
        String tokenAntigo = bearer(cuidador.user());

        // Etapa 1: antes da troca, o contato do outro membro NAO aparece para o Cuidador.
        JsonNode antes = itemDe(listar(g, tokenAntigo), outro);
        assertThat(antes.has("email")).isFalse();
        assertThat(antes.has("phone")).isFalse();
        assertThat(antes.has("address")).isFalse();

        // Etapa 2: o Responsavel promove o Cuidador a Familiar.
        esperar200(alterarPapel(g, cuidador, bearer(g.responsavel()), "FAMILIAR"));

        // Etapa 3: o mesmo token, sem novo login, ja ve o contato completo do outro membro.
        JsonNode depois = itemDe(listar(g, tokenAntigo), outro);
        assertThat(depois.path("email").asText()).isEqualTo(outro.user().getEmail());
        assertThat(depois.path("phone").asText()).isEqualTo(outro.user().getPhone());
        assertThat(depois.path("address").path("street").asText()).isEqualTo(outro.user().getAddress().street());
    }

    /** O inverso de "vale na hora": Familiar rebaixado a Cuidador deixa de ver contatos com o token antigo. */
    @Test
    @DisplayName("AC-009.1: Familiar rebaixado a Cuidador deixa de ver os contatos com o token já emitido")
    void ac_009_1_rebaixamento_vale_na_hora_para_token_ja_emitido() throws Exception {
        Grupo g = grupo();
        Membro familiar = membroAtivo(g, "Familiar Rebaixado Agora", Role.FAMILIAR);
        Membro outro = membroAtivo(g, "Outro Cuidador", Role.CUIDADOR);
        String tokenAntigo = bearer(familiar.user());

        // Etapa 1: antes da troca, o Familiar ve o contato do outro membro.
        JsonNode antes = itemDe(listar(g, tokenAntigo), outro);
        assertThat(antes.path("email").asText()).isEqualTo(outro.user().getEmail());
        assertThat(antes.has("phone")).isTrue();
        assertThat(antes.has("address")).isTrue();

        // Etapa 2: o Responsavel rebaixa o Familiar a Cuidador.
        esperar200(alterarPapel(g, familiar, bearer(g.responsavel()), "CUIDADOR"));

        // Etapa 3: o mesmo token ja nao ve e-mail, telefone nem endereco do outro (o proprio contato segue visivel).
        JsonNode pagina = listar(g, tokenAntigo);
        JsonNode depois = itemDe(pagina, outro);
        assertThat(depois.has("email")).isFalse();
        assertThat(depois.has("phone")).isFalse();
        assertThat(depois.has("address")).isFalse();
        assertThat(itemDe(pagina, familiar).path("email").asText()).isEqualTo(familiar.user().getEmail());
    }

    /**
     * "Inclusive para sessoes ja abertas" (AC-009.1): trocar o papel NAO encerra a sessao. O membro,
     * logado de verdade, continua usando o access token e consegue renovar a sessao com o cookie
     * (nenhum refresh token e revogado).
     */
    @Test
    @DisplayName("AC-009.1: a troca de papel não derruba a sessão (token e refresh continuam valendo)")
    void ac_009_1_troca_de_papel_nao_derruba_a_sessao() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sessao Segue", Role.CUIDADOR);
        Sessao sessao = entrar(alvo.user());

        esperar200(alterarPapel(g, alvo, bearer(g.responsavel()), "FAMILIAR"));

        // Etapa 1: o access token ja emitido segue aceito.
        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessao.accessToken()))
                .andExpect(status().isOk());
        // Etapa 2: nenhum refresh token foi revogado e o cookie ainda renova a sessao.
        assertThat(tokensAtivos(alvo.user())).isEqualTo(totalDeTokens(alvo.user())).isPositive();
        mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, sessao.refreshToken())))
                .andExpect(status().isOk());
    }

    /** A troca de papel nao envia e-mail a ninguem (a spec nao a lista em AC-012.2 nem em AC-007). */
    @Test
    @DisplayName("AC-009.1: a troca de papel não envia e-mail ao membro")
    void ac_009_1_troca_de_papel_nao_envia_email() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sem Email Papel", Role.CUIDADOR);
        mailpit.deleteAll();

        esperar200(alterarPapel(g, alvo, bearer(g.responsavel()), "FAMILIAR"));

        semEmail(alvo.user().getEmail());
    }

    // ============================================================ AC-009.4 PATCH: corpo e admin

    /**
     * Papel e obrigatorio e restrito: cada linha e um corpo invalido (ausente, nulo, RESPONSAVEL,
     * ADMIN, minusculas, objeto vazio, corpo vazio) => 400 ValidationProblem. Nada muda no banco e
     * o grupo continua com exatamente um Responsavel (ninguem e promovido, AC-009.4).
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"foo\":1}", "{\"role\":null}", "{\"role\":\"RESPONSAVEL\"}", "{\"role\":\"ADMIN\"}",
        "{\"role\":\"familiar\"}", "{}", ""})
    @DisplayName("AC-009.4: role ausente ou fora de CUIDADOR/FAMILIAR => 400 ValidationProblem, ninguém vira Responsável")
    void ac_009_4_role_invalido_400(String corpoBruto) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Corpo Invalido", Role.CUIDADOR);

        // Cada linha da tabela de @ValueSource e um corpo invalido distinto.
        MvcResult r = mockMvc.perform(patch(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content(corpoBruto))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        assertThat(papelNoBanco(alvo)).isEqualTo("CUIDADOR");
        assertThat(statusNoBanco(alvo)).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE group_id = ? AND is_admin", Integer.class, g.groupId()))
                .isEqualTo(1);
    }

    /** O erro 400 de corpo sem role aponta o campo role (o frontend destaca o campo). */
    @Test
    @DisplayName("AC-009.1: corpo sem role => 400 com erro no campo role")
    void ac_009_1_sem_role_aponta_o_campo() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sem Role", Role.FAMILIAR);

        MvcResult r = mockMvc.perform(patch(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        List<String> campos = new ArrayList<>();
        body.path("errors").forEach(e -> campos.add(e.path("field").asText()));
        assertThat(campos).contains("role");
        assertThat(papelNoBanco(alvo)).isEqualTo("FAMILIAR");
    }

    /**
     * O Responsavel nunca tem o papel alterado, nem por ele mesmo: PATCH sobre o proprio vinculo de
     * admin => 409 ADMIN_PROTECTED. Cada linha e o papel pedido; o admin segue ATIVO, FAMILIAR e
     * admin.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("AC-009.4: alterar o papel do Responsável (inclusive o próprio) => 409 ADMIN_PROTECTED")
    void ac_009_4_responsavel_nao_tem_o_papel_alterado(Role pedido) throws Exception {
        Grupo g = grupo();
        Membro admin = new Membro(g.responsavel(), adminMemberId(g));

        // Linha da tabela: o Responsavel tenta (a si mesmo) CUIDADOR e FAMILIAR.
        JsonNode body = esperar409(alterarPapel(g, admin, bearer(g.responsavel()), pedido.name()));

        assertThat(body.path("code").asText()).isEqualTo("ADMIN_PROTECTED");
        assertThat(papelNoBanco(admin)).isEqualTo("FAMILIAR");
        assertThat(statusNoBanco(admin)).isEqualTo("ATIVO");
        assertThat(jdbc.queryForObject("SELECT is_admin FROM memberships WHERE id = ?", Boolean.class, admin.memberId()))
                .isTrue();
    }

    /**
     * AC-012.1: so ATIVO muda de papel. Cada linha e um status de vinculo que nao e ATIVO => 409
     * com o code MEMBER_NOT_ACTIVE (novo); o status e o papel continuam como estavam.
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1: alterar o papel de quem não está ATIVO => 409 MEMBER_NOT_ACTIVE")
    void ac_012_1_papel_de_alvo_nao_ativo_409(MembershipStatus status) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroEm(g, "Alvo " + status, status);
        String papelAntes = papelNoBanco(alvo);

        // Linha da tabela: um dos quatro status que nao sao ATIVO.
        JsonNode body = esperar409(alterarPapel(g, alvo, bearer(g.responsavel()), "CUIDADOR"));

        assertThat(body.path("code").asText()).isEqualTo(MEMBER_NOT_ACTIVE);
        assertThat(statusNoBanco(alvo)).isEqualTo(status.name());
        assertThat(papelNoBanco(alvo)).isEqualTo(papelAntes);
    }

    // ============================================================ AC-009.2 DELETE: remover

    /**
     * Caminho feliz: 204 sem corpo (validado contra o contrato). O vinculo passa a REMOVIDO, a
     * version sobe e o Responsavel continua ATIVO.
     */
    @Test
    @DisplayName("AC-009.2: remover um membro ATIVO => 204 sem corpo, status REMOVIDO, version+1")
    void ac_009_2_remove_o_membro() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sera Removido", Role.CUIDADOR);
        long versao = versaoNoBanco(alvo);

        MvcResult r = mockMvc.perform(remover(g, alvo, bearer(g.responsavel())))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(statusNoBanco(alvo)).isEqualTo("REMOVIDO");
        assertThat(versaoNoBanco(alvo)).isGreaterThan(versao);
        assertThat(statusNoBanco(new Membro(g.responsavel(), adminMemberId(g)))).isEqualTo("ATIVO");
    }

    /**
     * "Todas as sessoes sao encerradas imediatamente" (AC-009.2, D-13): o membro tem DUAS sessoes
     * (dois aparelhos = duas familias), uma delas ja rotacionada uma vez. Depois do DELETE nao resta
     * nenhuma linha de refresh token ativa no banco e o cookie mais recente de cada aparelho recebe
     * 401 em /auth/refresh.
     */
    @Test
    @DisplayName("AC-009.2: remover revoga todos os refresh tokens do membro (banco e /auth/refresh => 401)")
    void ac_009_2_revoga_todas_as_sessoes() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Duas Sessoes", Role.FAMILIAR);
        Sessao aparelhoA = entrar(alvo.user());
        Sessao aparelhoB = entrar(alvo.user());
        // Etapa 1: o aparelho A renova uma vez (a linha antiga ja nasce revogada; a nova e a ativa).
        String cookieAtualA = valorDoCookie(
                mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, aparelhoA.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn());
        assertThat(tokensAtivos(alvo.user())).isEqualTo(2);

        // Etapa 2: o Responsavel remove o membro.
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        // Etapa 3: o banco nao tem mais nenhuma linha ativa e nenhuma foi apagada.
        assertThat(totalDeTokens(alvo.user())).isGreaterThanOrEqualTo(3);
        assertThat(tokensAtivos(alvo.user())).isZero();
        // Etapa 4: os cookies mais recentes dos dois aparelhos nao renovam mais.
        for (String cookie : List.of(cookieAtualA, aparelhoB.refreshToken())) {
            MvcResult r = mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, cookie)))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertThat(ContractSchemas.schemaFor("Problem")
                            .validate(json.readTree(r.getResponse().getContentAsString())))
                    .isEmpty();
        }
    }

    /** O encerramento e so do removido: as sessoes do Responsavel e de outro membro seguem ativas e renovaveis. */
    @Test
    @DisplayName("AC-009.2: remover um membro não derruba as sessões de outras pessoas")
    void ac_009_2_nao_derruba_sessoes_de_outros() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Removida Sozinha", Role.CUIDADOR);
        Membro vizinho = membroAtivo(g, "Vizinho Fica", Role.FAMILIAR);
        entrar(alvo.user());
        Sessao sessaoVizinho = entrar(vizinho.user());
        Sessao sessaoResponsavel = entrar(g.responsavel());

        mockMvc.perform(remover(g, alvo, "Bearer " + sessaoResponsavel.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(tokensAtivos(vizinho.user())).isEqualTo(1);
        assertThat(tokensAtivos(g.responsavel())).isEqualTo(1);
        mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, sessaoVizinho.refreshToken())))
                .andExpect(status().isOk());
    }

    /**
     * "Encerradas imediatamente" para o access token ja emitido (D-13): antes do DELETE o JWT
     * funciona; logo depois, o mesmo JWT recebe 401 Problem (REMOVIDO => sessao invalida), sem
     * esperar os 15 minutos de validade.
     */
    @Test
    @DisplayName("AC-009.2: o access token já emitido do removido passa a dar 401 na hora")
    void ac_009_2_access_token_emitido_deixa_de_funcionar() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Token Morre", Role.FAMILIAR);
        String tokenAntigo = bearer(alvo.user());
        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId()).header(HttpHeaders.AUTHORIZATION, tokenAntigo))
                .andExpect(status().isOk());

        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, tokenAntigo))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    /**
     * O removido nao entra de novo: login com e-mail e senha certos => 403 AccountStateProblem com
     * code REMOVED (AC-008.2), sem cookie e sem nenhuma linha nova de refresh token.
     */
    @Test
    @DisplayName("AC-009.2: o removido que tenta entrar recebe 403 REMOVED e nenhuma sessão nova")
    void ac_009_2_login_do_removido_403_removed() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Nao Volta", Role.CUIDADOR);
        entrar(alvo.user());
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());
        int linhasAntes = totalDeTokens(alvo.user());

        MvcResult r = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", alvo.user().getEmail(), "password", SENHA))))
                .andExpect(status().isForbidden())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("AccountStateProblem").validate(body)).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo("REMOVED");
        assertThat(r.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(totalDeTokens(alvo.user())).isEqualTo(linhasAntes);
    }

    /** Remocao: a pessoa NAO recebe e-mail (AC-012.2, linha "remocao"); a mensagem so aparece ao tentar entrar. */
    @Test
    @DisplayName("AC-012.2: a remoção não envia e-mail ao removido")
    void ac_012_2_remocao_nao_envia_email() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sem Email Remocao", Role.CUIDADOR);
        mailpit.deleteAll();

        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        semEmail(alvo.user().getEmail());
    }

    // ============================================================ AC-009.3 / AC-014.2 dados preservados

    /**
     * AC-009.3 (nome preservado para os registros de cuidado) e "Pronto quando" da T-047: apos o
     * DELETE a linha de memberships continua (mesmo id, user_id e group_id, status REMOVIDO, sem
     * retention_deadline) e a conta em users mantem nome, e-mail, telefone, endereco completo e a
     * senha (AC-004.6 reaproveita a senha antiga) exatamente como antes.
     */
    @Test
    @DisplayName("AC-009.3/014.2: após remover, a linha continua com nome, telefone e endereço intactos")
    void ac_009_3_dados_do_removido_ficam_guardados() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Dados Guardados", Role.FAMILIAR);
        Map<String, Object> contaAntes = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", alvo.user().getId());
        Map<String, Object> vinculoAntes = jdbc.queryForMap("SELECT * FROM memberships WHERE id = ?", alvo.memberId());

        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        // Etapa 1: o vinculo continua, so o status muda (e a version sobe).
        Map<String, Object> vinculoDepois = jdbc.queryForMap("SELECT * FROM memberships WHERE id = ?", alvo.memberId());
        assertThat(vinculoDepois.get("status")).isEqualTo("REMOVIDO");
        assertThat(vinculoDepois.get("user_id")).isEqualTo(vinculoAntes.get("user_id"));
        assertThat(vinculoDepois.get("group_id")).isEqualTo(vinculoAntes.get("group_id"));
        assertThat(vinculoDepois.get("role")).isEqualTo(vinculoAntes.get("role"));
        assertThat(vinculoDepois.get("retention_deadline")).isNull();
        // Etapa 2: a conta nao foi tocada em nenhuma coluna (nome, telefone, endereco, senha...).
        Map<String, Object> contaDepois = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", alvo.user().getId());
        assertThat(contaDepois).isEqualTo(contaAntes);
        assertThat(contaDepois.get("name")).isEqualTo("Dados Guardados");
    }

    /**
     * AC-009.5: o Responsavel, na lista filtrada por status=REMOVIDO, ve o removido com nome, papel,
     * e-mail, telefone e endereco completos (validado contra o contrato).
     */
    @Test
    @DisplayName("AC-009.5: Responsável vê o removido com e-mail, telefone e endereço em GET /members?status=REMOVIDO")
    void ac_009_5_responsavel_ve_o_removido_com_contato() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Removido Visivel", Role.CUIDADOR);
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members?status=REMOVIDO", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel())))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode item = itemDe(json.readTree(r.getResponse().getContentAsString()), alvo);
        assertThat(item.path("name").asText()).isEqualTo("Removido Visivel");
        assertThat(item.path("status").asText()).isEqualTo("REMOVIDO");
        assertThat(item.path("role").asText()).isEqualTo("CUIDADOR");
        assertThat(item.path("email").asText()).isEqualTo(alvo.user().getEmail());
        assertThat(item.path("phone").asText()).isEqualTo(alvo.user().getPhone());
        assertThat(item.path("address").path("street").asText()).isEqualTo(alvo.user().getAddress().street());
        assertThat(item.path("address").path("zipCode").asText()).isEqualTo("22041001");
    }

    /**
     * AC-009.5: sem o filtro de status o removido some da lista, tanto para o Responsavel quanto para
     * um Familiar comum (a lista padrao so tem ATIVO).
     */
    @Test
    @DisplayName("AC-009.5: sem filtro de status o removido não aparece (Responsável nem Familiar comum)")
    void ac_009_5_sem_filtro_o_removido_nao_aparece() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Removido Oculto", Role.CUIDADOR);
        Membro familiar = membroAtivo(g, "Familiar Comum", Role.FAMILIAR);
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());

        // Etapa 1: a lista padrao do Responsavel nao traz o removido.
        assertThat(idsDe(listar(g, bearer(g.responsavel())))).doesNotContain(alvo.memberId().toString());
        // Etapa 2: a lista padrao do Familiar comum tambem nao.
        assertThat(idsDe(listar(g, bearer(familiar.user())))).doesNotContain(alvo.memberId().toString());
    }

    /**
     * AC-014.2: uma conta REMOVIDO nao e excluida automaticamente. Com o relogio 90 dias adiante e
     * todas as rotinas agendadas rodando, o vinculo e a conta seguem no banco com os dados e o
     * Responsavel ainda ve o removido com o contato.
     */
    @Test
    @DisplayName("AC-014.2: 90 dias depois e com os jobs rodando, o REMOVIDO continua guardado com seus dados")
    void ac_014_2_job_de_retencao_nao_toca_o_removido() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Retida Para Sempre", Role.CUIDADOR);
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());
        Map<String, Object> contaAntes = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", alvo.user().getId());

        // Etapa 1: avanca 90 dias e roda todas as rotinas agendadas (D-09, D-10).
        clock.advance(Duration.ofDays(90));
        jobRunner.runAll();

        // Etapa 2: vinculo e conta seguem no banco, intactos, sem prazo de exclusao.
        assertThat(statusNoBanco(alvo)).isEqualTo("REMOVIDO");
        assertThat(jdbc.queryForObject(
                        "SELECT retention_deadline FROM memberships WHERE id = ?", java.sql.Timestamp.class,
                        alvo.memberId()))
                .isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", alvo.user().getId())).isEqualTo(contaAntes);
        // Etapa 3: o Responsavel ainda ve o removido, com contato, pela lista filtrada.
        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members?status=REMOVIDO", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = itemDe(json.readTree(r.getResponse().getContentAsString()), alvo);
        assertThat(item.path("email").asText()).isEqualTo(alvo.user().getEmail());
    }

    // ============================================================ AC-009.4 / AC-012.1 DELETE: alvos invalidos

    /**
     * O Responsavel nao e removido, nem por ele mesmo: DELETE sobre o proprio vinculo => 409
     * ADMIN_PROTECTED; ele segue ATIVO, com a sessao aberta e os refresh tokens intactos.
     */
    @Test
    @DisplayName("AC-009.4: o Responsável não pode ser removido (nem por si mesmo) => 409 ADMIN_PROTECTED")
    void ac_009_4_responsavel_nao_e_removido() throws Exception {
        Grupo g = grupo();
        Membro admin = new Membro(g.responsavel(), adminMemberId(g));
        Sessao sessao = entrar(g.responsavel());

        JsonNode body = esperar409(remover(g, admin, "Bearer " + sessao.accessToken()));

        assertThat(body.path("code").asText()).isEqualTo("ADMIN_PROTECTED");
        assertThat(statusNoBanco(admin)).isEqualTo("ATIVO");
        assertThat(tokensAtivos(g.responsavel())).isEqualTo(1);
        mockMvc.perform(get(ELDERS + "/{e}/members", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessao.accessToken()))
                .andExpect(status().isOk());
    }

    /**
     * AC-012.1: so ATIVO vai a REMOVIDO. Cada linha e um status que nao e ATIVO (a remocao dupla
     * esta em outro teste) => 409 MEMBER_NOT_ACTIVE, status inalterado e nenhum refresh token
     * revogado.
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO"})
    @DisplayName("AC-012.1: remover quem não está ATIVO => 409 MEMBER_NOT_ACTIVE, nada muda")
    void ac_012_1_remover_alvo_nao_ativo_409(MembershipStatus status) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroEm(g, "Remocao Invalida " + status, status);

        // Linha da tabela: pendente, recusado ou expirado.
        JsonNode body = esperar409(remover(g, alvo, bearer(g.responsavel())));

        assertThat(body.path("code").asText()).isEqualTo(MEMBER_NOT_ACTIVE);
        assertThat(statusNoBanco(alvo)).isEqualTo(status.name());
    }

    /**
     * Remocao dupla (sequencial): a primeira e 204, a segunda e 409 MEMBER_NOT_ACTIVE; o vinculo
     * segue REMOVIDO e a version nao sobe de novo.
     */
    @Test
    @DisplayName("AC-012.1: remover duas vezes => 204 e depois 409 MEMBER_NOT_ACTIVE")
    void ac_012_1_remocao_dupla_409() throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Dupla Remocao", Role.CUIDADOR);
        mockMvc.perform(remover(g, alvo, bearer(g.responsavel()))).andExpect(status().isNoContent());
        long versaoApos = versaoNoBanco(alvo);

        JsonNode body = esperar409(remover(g, alvo, bearer(g.responsavel())));

        assertThat(body.path("code").asText()).isEqualTo(MEMBER_NOT_ACTIVE);
        assertThat(statusNoBanco(alvo)).isEqualTo("REMOVIDO");
        assertThat(versaoNoBanco(alvo)).isEqualTo(versaoApos);
    }

    // ============================================================ autorizacao (PATCH e DELETE)

    /**
     * Cuidador e Familiar comum => 403 NOT_ALLOWED nas duas rotas, sem alterar o alvo. Cada linha e
     * uma combinacao (rota, papel de quem pede).
     */
    @ParameterizedTest
    @CsvSource({"PATCH,CUIDADOR", "PATCH,FAMILIAR", "DELETE,CUIDADOR", "DELETE,FAMILIAR"})
    @DisplayName("AC-015.1: Cuidador e Familiar comum recebem 403 NOT_ALLOWED em PATCH e DELETE")
    void ac_015_1_nao_admin_403(String metodo, Role papelDeQuemPede) throws Exception {
        Grupo g = grupo();
        Membro comum = membroAtivo(g, "Comum " + papelDeQuemPede, papelDeQuemPede);
        Membro alvo = membroAtivo(g, "Alvo Protegido", Role.CUIDADOR);

        // Linha da tabela: a mesma rota/papel da linha corrente.
        MockHttpServletRequestBuilder req = metodo.equals("PATCH")
                ? alterarPapel(g, alvo, bearer(comum.user()), "FAMILIAR")
                : remover(g, alvo, bearer(comum.user()));
        MvcResult r = mockMvc.perform(req).andExpect(status().isForbidden()).andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(statusNoBanco(alvo)).isEqualTo("ATIVO");
        assertThat(papelNoBanco(alvo)).isEqualTo("CUIDADOR");
    }

    /** Sem token => 401 Problem, nas duas rotas; o alvo nao muda. */
    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("AC-015.1: sem token => 401 Problem em PATCH e DELETE")
    void ac_015_1_sem_token_401(String metodo) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Sem Token", Role.CUIDADOR);

        // Linha da tabela: PATCH (com corpo valido) e DELETE, ambos sem Authorization.
        MockHttpServletRequestBuilder req = metodo.equals("PATCH")
                ? patch(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"FAMILIAR\"}")
                : delete(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId());
        MvcResult r = mockMvc.perform(req).andExpect(status().isUnauthorized()).andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
        assertThat(statusNoBanco(alvo)).isEqualTo("ATIVO");
        assertThat(papelNoBanco(alvo)).isEqualTo("CUIDADOR");
    }

    /**
     * D-32: recurso de outro grupo ou inexistente responde 404. Cada linha cruza a rota (PATCH ou
     * DELETE) com um escopo invalido: membro de outro grupo na URL do proprio idoso; Responsavel de
     * outro grupo usando o idoso e o membro do primeiro; idoso inexistente; membro inexistente. O
     * alvo real nunca e tocado.
     */
    @ParameterizedTest
    @CsvSource({
        "PATCH,MEMBRO_DE_OUTRO_GRUPO", "PATCH,ELDER_DE_OUTRO_GRUPO", "PATCH,ELDER_INEXISTENTE", "PATCH,MEMBRO_INEXISTENTE",
        "DELETE,MEMBRO_DE_OUTRO_GRUPO", "DELETE,ELDER_DE_OUTRO_GRUPO", "DELETE,ELDER_INEXISTENTE", "DELETE,MEMBRO_INEXISTENTE"
    })
    @DisplayName("D-32: outro grupo ou recurso inexistente => 404 Problem em PATCH e DELETE")
    void d_32_escopo_invalido_404(String metodo, String caso) throws Exception {
        Grupo meu = grupo();
        Grupo outro = grupo();
        Membro alvoDoOutro = membroAtivo(outro, "Alvo Do Outro Grupo", Role.CUIDADOR);

        // Linha da tabela: monta (autor, elderId, memberId) conforme o escopo invalido do caso.
        User autor = meu.responsavel();
        UUID elderId = meu.elderId();
        UUID memberId = alvoDoOutro.memberId();
        switch (caso) {
            case "MEMBRO_DE_OUTRO_GRUPO" -> { }
            case "ELDER_DE_OUTRO_GRUPO" -> {
                autor = outro.responsavel();
                elderId = meu.elderId();
                memberId = alvoDoOutro.memberId();
            }
            case "ELDER_INEXISTENTE" -> {
                elderId = UUID.randomUUID();
                autor = outro.responsavel();
            }
            case "MEMBRO_INEXISTENTE" -> memberId = UUID.randomUUID();
            default -> throw new IllegalArgumentException(caso);
        }
        String token = bearer(autor);
        MockHttpServletRequestBuilder req = metodo.equals("PATCH")
                ? patch(ELDERS + "/{e}/members/{m}", elderId, memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .content("{\"role\":\"FAMILIAR\"}")
                : delete(ELDERS + "/{e}/members/{m}", elderId, memberId)
                        .header(HttpHeaders.AUTHORIZATION, token);

        MvcResult r = mockMvc.perform(req).andExpect(status().isNotFound()).andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
        assertThat(statusNoBanco(alvoDoOutro)).isEqualTo("ATIVO");
        assertThat(papelNoBanco(alvoDoOutro)).isEqualTo("CUIDADOR");
    }

    /**
     * AC-015.3: o 404 de "existe em outro grupo" e o de "nao existe" sao indistinguiveis (mesmo
     * type, title, detail, code e status), para nao revelar a existencia do membro.
     */
    @Test
    @DisplayName("AC-015.3: o 404 de membro de outro grupo é idêntico ao de membro inexistente")
    void ac_015_3_404_identico_para_outro_grupo_e_inexistente() throws Exception {
        Grupo meu = grupo();
        Grupo outro = grupo();
        Membro alvoDoOutro = membroAtivo(outro, "Existe Em Outro", Role.CUIDADOR);

        // Etapa 1: membro que existe, mas em outro grupo.
        JsonNode deOutroGrupo = corpoDe(mockMvc.perform(delete(ELDERS + "/{e}/members/{m}", meu.elderId(), alvoDoOutro.memberId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(meu.responsavel())))
                .andExpect(status().isNotFound())
                .andReturn());
        // Etapa 2: membro que nao existe em lugar nenhum.
        JsonNode inexistente = corpoDe(mockMvc.perform(delete(ELDERS + "/{e}/members/{m}", meu.elderId(), UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(meu.responsavel())))
                .andExpect(status().isNotFound())
                .andReturn());

        for (String campo : List.of("type", "title", "detail", "code", "status")) {
            assertThat(deOutroGrupo.path(campo)).as("campo %s", campo).isEqualTo(inexistente.path(campo));
        }
    }

    // ============================================================ P5: sem dados do membro

    /**
     * P5: corpos de erro e logs das rotas (403, 404, 409 ADMIN_PROTECTED, 409 MEMBER_NOT_ACTIVE e
     * 400) nunca contem nome, e-mail, telefone nem endereco do membro-alvo.
     */
    @Test
    @DisplayName("P5: erros de PATCH e DELETE não vazam dados do membro nos corpos nem nos logs")
    void p5_erros_nao_vazam_dados_do_membro(CapturedOutput saida) throws Exception {
        Grupo g = grupo();
        Membro alvo = membroAtivo(g, "Nome Sigiloso Zeta", Role.CUIDADOR);
        Membro comum = membroAtivo(g, "Comum P5", Role.CUIDADOR);
        Membro pendente = membroEm(g, "Pendente Sigiloso Ypsilon", MembershipStatus.AGUARDANDO_APROVACAO);
        Membro admin = new Membro(g.responsavel(), adminMemberId(g));
        List<Membro> sigilosos = List.of(alvo, pendente);

        // Etapa 1: coleta o corpo de cada tipo de erro sobre os membros sigilosos.
        List<String> corpos = new ArrayList<>();
        corpos.add(mockMvc.perform(alterarPapel(g, alvo, bearer(comum.user()), "FAMILIAR"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString());
        corpos.add(mockMvc.perform(remover(g, pendente, bearer(g.responsavel())))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        corpos.add(mockMvc.perform(alterarPapel(g, admin, bearer(g.responsavel()), "CUIDADOR"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        corpos.add(mockMvc.perform(patch(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g.responsavel()))
                        .content("{\"role\":\"RESPONSAVEL\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        corpos.add(mockMvc.perform(remover(g, new Membro(alvo.user(), UUID.randomUUID()), bearer(g.responsavel())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());

        // Etapa 2: nenhum corpo e nenhuma linha de log cita dados dos membros.
        for (Membro m : sigilosos) {
            for (String segredo : List.of(m.user().getName(), m.user().getEmail(), m.user().getPhone(),
                    m.user().getAddress().street())) {
                assertThat(corpos).as("corpos de erro sem '%s'", segredo).noneMatch(c -> c.contains(segredo));
                assertThat(saida.getAll()).as("logs sem '%s'", segredo).doesNotContain(segredo);
            }
        }
    }

    // ============================================================ concorrencia (D-08)

    /**
     * D-08: tres DELETE simultaneos sobre o mesmo membro, em 5 rodadas. Exatamente um 204 e os
     * demais 409 (nunca 500); o status final e REMOVIDO, a version sobe exatamente uma vez, nenhuma
     * linha de refresh token ativa sobra e nenhuma foi criada ou apagada.
     */
    @Test
    @DisplayName("AC-009.2/D-08: DELETE x DELETE simultâneos => um 204, os outros 409, sessões revogadas uma vez")
    void ac_009_2_delete_x_delete_concorrentes() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Membro alvo = membroAtivo(g, "CorridaDelete" + rodada, Role.CUIDADOR);
            entrar(alvo.user());
            entrar(alvo.user());
            long versao = versaoNoBanco(alvo);
            int linhas = totalDeTokens(alvo.user());
            String token = bearer(g.responsavel());

            // Etapa: tres remocoes largando juntas.
            List<Callable<MvcResult>> acoes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                acoes.add(() -> mockMvc.perform(remover(g, alvo, token)).andReturn());
            }
            List<MvcResult> res = largadaSimultanea(acoes);

            String ctx = "rodada " + rodada + ", status=" + statuses(res);
            List<Integer> st = statuses(res);
            assertThat(st.stream().filter(s -> s == 204).count()).as("um único 204 - %s", ctx).isEqualTo(1);
            assertThat(st.stream().filter(s -> s == 409).count()).as("os demais 409 - %s", ctx).isEqualTo(2);
            for (MvcResult perdedor : res) {
                if (perdedor.getResponse().getStatus() == 409) {
                    assertConflitoDeCorrida(perdedor, ctx);
                }
            }
            assertThat(statusNoBanco(alvo)).as("REMOVIDO - %s", ctx).isEqualTo("REMOVIDO");
            assertThat(versaoNoBanco(alvo)).as("version +1 - %s", ctx).isEqualTo(versao + 1);
            assertThat(tokensAtivos(alvo.user())).as("sem sessão ativa - %s", ctx).isZero();
            assertThat(totalDeTokens(alvo.user())).as("nenhuma linha criada/apagada - %s", ctx).isEqualTo(linhas);
        }
    }

    /**
     * D-08: PATCH (para FAMILIAR) x DELETE simultaneos sobre um Cuidador com duas sessoes, em 5
     * rodadas. Nunca 500, sempre ao menos um efeito, e o estado final e coerente em qualquer
     * entrelacamento: DELETE 204 <=> REMOVIDO com todas as sessoes revogadas (e DELETE 409 <=> ATIVO
     * com as sessoes intactas); PATCH 200 <=> papel FAMILIAR (e PATCH 409 <=> papel original, o que
     * so ocorre se o DELETE venceu).
     */
    @Test
    @DisplayName("AC-009.1/009.2/D-08: PATCH x DELETE simultâneos => sem 500 e estado final coerente")
    void ac_009_2_patch_x_delete_concorrentes() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Membro alvo = membroAtivo(g, "CorridaMista" + rodada, Role.CUIDADOR);
            entrar(alvo.user());
            entrar(alvo.user());
            String token = bearer(g.responsavel());

            // Etapa: PATCH e DELETE largando juntos.
            List<Callable<MvcResult>> acoes = List.of(
                    () -> mockMvc.perform(alterarPapel(g, alvo, token, "FAMILIAR")).andReturn(),
                    () -> mockMvc.perform(remover(g, alvo, token)).andReturn());
            List<MvcResult> res = largadaSimultanea(acoes);

            int sp = res.get(0).getResponse().getStatus();
            int sd = res.get(1).getResponse().getStatus();
            String ctx = "rodada " + rodada + ", PATCH=" + sp + " DELETE=" + sd;
            assertThat(sp).as("PATCH 200 ou 409 - %s", ctx).isIn(200, 409);
            assertThat(sd).as("DELETE 204 ou 409 - %s", ctx).isIn(204, 409);
            assertThat(sp == 200 || sd == 204).as("ao menos um efeito - %s", ctx).isTrue();
            if (sp == 409) {
                assertConflitoDeCorrida(res.get(0), ctx);
            }
            if (sd == 409) {
                assertConflitoDeCorrida(res.get(1), ctx);
            }
            // Coerencia do estado final com quem venceu.
            assertThat(statusNoBanco(alvo)).as("status - %s", ctx).isEqualTo(sd == 204 ? "REMOVIDO" : "ATIVO");
            assertThat(papelNoBanco(alvo)).as("papel - %s", ctx).isEqualTo(sp == 200 ? "FAMILIAR" : "CUIDADOR");
            if (sd == 204) {
                assertThat(tokensAtivos(alvo.user())).as("sessões revogadas - %s", ctx).isZero();
            } else {
                assertThat(tokensAtivos(alvo.user())).as("sessões intactas - %s", ctx).isEqualTo(2);
            }
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

    /** O 409 de um perdedor de corrida e um Problem com code de conflito conhecido (nunca 500). */
    private void assertConflitoDeCorrida(MvcResult perdedor, String ctx) throws Exception {
        JsonNode corpo = json.readTree(perdedor.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(corpo)).isEmpty();
        assertThat(corpo.path("code").asText()).as("code do perdedor - %s", ctx)
                .isIn(MEMBER_NOT_ACTIVE, "VERSION_CONFLICT");
    }

    // ============================================================ apoio: HTTP

    private String bearer(User u) {
        return "Bearer " + jwtService.issue(u.getId(), clock);
    }

    private MockHttpServletRequestBuilder alterarPapel(Grupo g, Membro alvo, String authorization, String role) {
        return patch(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .content("{\"role\":\"" + role + "\"}");
    }

    private MockHttpServletRequestBuilder remover(Grupo g, Membro alvo, String authorization) {
        return delete(ELDERS + "/{e}/members/{m}", g.elderId(), alvo.memberId())
                .header(HttpHeaders.AUTHORIZATION, authorization);
    }

    /** 200 validado contra o contrato (schema Member). */
    private JsonNode esperar200(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** 409 validado contra o schema Problem; devolve o corpo para conferir o code. */
    private JsonNode esperar409(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mockMvc.perform(req).andExpect(status().isConflict()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        return body;
    }

    private JsonNode corpoDe(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** {@code GET /members} (pagina unica, ate 100 itens) com o authorization dado. */
    private JsonNode listar(Grupo g, String authorization) throws Exception {
        MvcResult r = mockMvc.perform(get(ELDERS + "/{e}/members?size=100", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return corpoDe(r);
    }

    private List<String> idsDe(JsonNode pagina) {
        List<String> ids = new ArrayList<>();
        pagina.path("items").forEach(i -> ids.add(i.path("id").asText()));
        return ids;
    }

    /** O item da pagina que corresponde ao membro (falha se nao estiver na lista). */
    private JsonNode itemDe(JsonNode pagina, Membro membro) {
        for (JsonNode item : pagina.path("items")) {
            if (item.path("id").asText().equals(membro.memberId().toString())) {
                return item;
            }
        }
        throw new AssertionError("membro " + membro.memberId() + " ausente da lista: " + idsDe(pagina));
    }

    /** Login real por {@code /auth/login}: gera um access token e uma familia nova de refresh tokens. */
    private Sessao entrar(User user) throws Exception {
        MvcResult r = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", SENHA))))
                .andExpect(status().isOk())
                .andReturn();
        return new Sessao(corpoDe(r).path("accessToken").asText(), valorDoCookie(r));
    }

    /** Valor do {@code Set-Cookie refresh_token} de uma resposta de login/refresh. */
    private static String valorDoCookie(MvcResult r) {
        String setCookie = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("resposta sem Set-Cookie refresh_token"));
        String par = setCookie.split(";", 2)[0];
        return par.substring(par.indexOf('=') + 1);
    }

    // ============================================================ apoio: e-mail

    /** Nenhum e-mail chega ao endereco durante uma janela de observacao. */
    private void semEmail(String email) {
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).isEmpty());
    }

    // ============================================================ apoio: dados

    private String statusNoBanco(Membro m) {
        return jdbc.queryForObject("SELECT status FROM memberships WHERE id = ?", String.class, m.memberId());
    }

    private String papelNoBanco(Membro m) {
        return jdbc.queryForObject("SELECT role FROM memberships WHERE id = ?", String.class, m.memberId());
    }

    private long versaoNoBanco(Membro m) {
        return jdbc.queryForObject("SELECT version FROM memberships WHERE id = ?", Long.class, m.memberId());
    }

    private int tokensAtivos(User u) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, u.getId());
    }

    private int totalDeTokens(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, u.getId());
    }

    private UUID adminMemberId(Grupo g) {
        return jdbc.queryForObject(
                "SELECT id FROM memberships WHERE group_id = ? AND is_admin", UUID.class, g.groupId());
    }

    /** Grupo novo criado pela rota real {@code POST /elders}: o chamador vira o Responsavel. */
    private Grupo grupo() {
        try {
            User responsavel = conta("Zélia Responsável");
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, bearer(responsavel))
                            .content(json.writeValueAsString(elderInput())))
                    .andExpect(status().isCreated())
                    .andReturn();
            UUID elderId = UUID.fromString(corpoDe(criacao).path("id").asText());
            UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
            return new Grupo(responsavel, groupId, elderId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Membro ATIVO com o papel dado, nascido pela maquina de estados da entidade (D-07). */
    private Membro membroAtivo(Grupo g, String nome, Role papel) {
        return membroEm(g, nome, MembershipStatus.ATIVO, papel);
    }

    private Membro membroEm(Grupo g, String nome, MembershipStatus alvo) {
        return membroEm(g, nome, alvo, Role.CUIDADOR);
    }

    /** Cria o vinculo no status pedido percorrendo as transicoes validas da entidade. */
    private Membro membroEm(Grupo g, String nome, MembershipStatus alvo, Role papel) {
        User user = conta(nome);
        // Relogio no passado so para o EXPIRADO: o pedido nasce vencido sem mexer no relogio do contexto.
        Clock passado = new MutableClock(clock.instant().minus(Duration.ofDays(2)), ZoneOffset.UTC);
        Membership m;
        switch (alvo) {
            case AGUARDANDO_APROVACAO -> m = Membership.request(g.groupId(), user.getId(), clock);
            case RECUSADO -> {
                m = Membership.request(g.groupId(), user.getId(), clock);
                m.reject(g.responsavel().getId(), clock);
            }
            case EXPIRADO -> {
                m = Membership.request(g.groupId(), user.getId(), passado);
                m.expire(clock);
            }
            case ATIVO -> {
                m = Membership.request(g.groupId(), user.getId(), clock);
                m.approve(papel, g.responsavel().getId(), clock);
            }
            case REMOVIDO -> {
                m = Membership.request(g.groupId(), user.getId(), clock);
                m.approve(papel, g.responsavel().getId(), clock);
                m.remove(clock);
            }
            default -> throw new IllegalArgumentException(alvo.name());
        }
        return new Membro(user, memberships.save(m).getId());
    }

    /** Conta com e-mail confirmado, dados unicos reconheciveis e a senha {@link #SENHA} (permite login real). */
    private User conta(String nome) {
        if (senhaHash == null) {
            senhaHash = passwordEncoder.encode(SENHA);
        }
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "gestao-" + sufixo + "@teste.com", telefone, endereco, senhaHash, clock);
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
