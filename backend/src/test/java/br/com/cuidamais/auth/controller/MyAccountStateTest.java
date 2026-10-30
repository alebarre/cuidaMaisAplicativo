package br.com.cuidamais.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.AccountState;
import br.com.cuidamais.auth.domain.AccountStateValue;
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
import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-027 — {@code GET /api/v1/me/account} (operationId {@code getMyAccountState}): devolve o estado
 * derivado da conta (AC-012.1, D-33) mais {@code hasGroup}, {@code elderId}, {@code role} e
 * {@code isAdmin}, que é o que o guard do frontend lê para decidir a tela (D-20). O caso central é
 * AC-001.7: o criador {@code ATIVO} que ainda não cadastrou o idoso recebe {@code hasGroup=false}
 * e é preso no cadastro do idoso.
 *
 * <p>"Pronto quando: um teste por estado devolvido" — há um teste para cada um dos seis valores de
 * {@code AccountStateValue} (o {@code ATIVO} desdobrado em sem grupo, Responsável e Cuidador), mais
 * os 401 do contrato. A rota é {@code x-required-account-state: any}: até {@code REMOVIDO} recebe
 * 200 (diferente da rota padrão, que dá 401 para {@code REMOVIDO} — T-024).
 *
 * <p>Os cenários são montados direto no banco: {@link User}/{@link Membership} pelos repositórios
 * (como em {@code RequestAuthorizationTest}, T-024) e o idoso por SQL em {@code elders} (não há
 * entidade {@code Elder} até a T-030; mesmo padrão de {@code FoundationMigrationTest}, T-010). O
 * token é emitido por {@link JwtService#issue} — o mesmo JWT que o login devolveria, mas sem
 * depender do login, que só aceita contas {@code ATIVO} (T-023).
 *
 * <p><strong>Desenho esperado</strong> (a ser criado pelo implementador):
 *
 * <ul>
 *   <li>Um controller com {@code @GetMapping("/me/account")} (context-path {@code /api/v1}, T-020),
 *       anotado {@code @RequiredAccountState(AccountStateRequirement.ANY)}, recebendo
 *       {@code @AuthenticationPrincipal AccountPrincipal principal}.
 *   <li>Resposta {@code 200 application/json} no schema {@code AccountState}: {@code state} =
 *       {@code principal.state().state().name()} (enum em português, idêntico a
 *       {@link AccountStateValue} — sem tradução), {@code hasGroup}, {@code role} (nome do enum ou
 *       {@code null}) e {@code isAdmin} vindos de {@link AccountState} (T-017, sem alteração).
 *   <li>{@code elderId}: {@link AccountState} não o deriva (Javadoc da T-017). É resolvido à parte,
 *       só quando {@code hasGroup()} é verdadeiro, com uma consulta mínima pelo {@code group_id} da
 *       membership da conta (ex. {@code SELECT id FROM elders WHERE group_id = ?}), "nenhuma linha"
 *       → {@code null}. Sem grupo → {@code null}. Nenhuma entidade/repositório {@code Elder}
 *       (escopo da T-030).
 *   <li>Sem sessão válida → 401 {@code Problem} (já garantido pelo filtro da T-024).
 * </ul>
 *
 * <p>O banco não é limpo entre testes: cada teste cria suas próprias contas (e-mail único), grupos
 * e idosos.
 */
@AutoConfigureMockMvc
class MyAccountStateTest extends IntegrationTest {

    private static final String ME_ACCOUNT = "/api/v1/me/account";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    JwtService jwtService;

    // ============================================================ sem grupo

    /**
     * AC-012.1 linha "(não existe) → AGUARDANDO_CONFIRMACAO_EMAIL" / D-33: conta recém-criada, sem
     * e-mail confirmado e sem membership. A rota é {@code any}, então mesmo esta conta (que nenhuma
     * outra rota autenticada aceita) consegue ler o próprio estado: {@code hasGroup=false}, sem
     * idoso, sem papel, sem administração.
     */
    @Test
    @DisplayName("AC-012.1/D-33 conta não confirmada → AGUARDANDO_CONFIRMACAO_EMAIL, sem grupo")
    void ac_012_1_conta_nao_confirmada_devolve_aguardando_confirmacao_email() throws Exception {
        User user = users.save(contaNova());

        JsonNode body = consultar(user);

        assertThat(body.get("state").asText()).isEqualTo("AGUARDANDO_CONFIRMACAO_EMAIL");
        assertThat(body.get("hasGroup").asBoolean()).isFalse();
        assertThat(body.get("isAdmin").asBoolean()).isFalse();
        assertNulo(body, "elderId");
        assertNulo(body, "role");
    }

    /**
     * AC-001.7 (caso central) / AC-012.1 linha "AGUARDANDO_CONFIRMACAO_EMAIL → ATIVO (criador sem
     * grupo)": e-mail confirmado e nenhuma membership. O guard do frontend (D-20) precisa receber
     * {@code state=ATIVO} com {@code hasGroup=false} para prender a conta no cadastro do idoso —
     * por isso {@code elderId}, {@code role} e {@code isAdmin} vêm vazios/falsos.
     */
    @Test
    @DisplayName("AC-001.7 criador ATIVO sem idoso → ATIVO com hasGroup=false")
    void ac_001_7_ativo_sem_grupo_devolve_hasgroup_falso() throws Exception {
        User user = users.save(contaConfirmada());

        JsonNode body = consultar(user);

        assertThat(body.get("state").asText()).isEqualTo("ATIVO");
        assertThat(body.get("hasGroup").asBoolean()).isFalse();
        assertThat(body.get("isAdmin").asBoolean()).isFalse();
        assertNulo(body, "elderId");
        assertNulo(body, "role");
    }

    // ============================================================ ATIVO com grupo

    /**
     * AC-012.1 linha "ATIVO (criador sem grupo; vira Responsável ao cadastrar o idoso)" / D-20: o
     * Responsável ({@code FAMILIAR}, {@code is_admin}) de um grupo com idoso recebe
     * {@code hasGroup=true} e o {@code elderId} do idoso <strong>do próprio grupo</strong> (o banco
     * tem idosos de outros grupos criados por outros testes; só o do {@code group_id} da membership
     * serve) — é o id que o frontend usa nas rotas {@code /elders/{elderId}} e que libera a área
     * logada.
     */
    @Test
    @DisplayName("AC-012.1/D-20 Responsável ATIVO → hasGroup, elderId do próprio grupo, FAMILIAR, isAdmin")
    void ac_012_1_responsavel_ativo_devolve_grupo_idoso_e_administracao() throws Exception {
        Grupo grupo = grupoComIdoso();

        JsonNode body = consultar(grupo.responsavel());

        assertThat(body.get("state").asText()).isEqualTo("ATIVO");
        assertThat(body.get("hasGroup").asBoolean()).isTrue();
        assertThat(body.get("elderId").asText()).isEqualTo(grupo.elderId().toString());
        assertThat(body.get("role").asText()).isEqualTo("FAMILIAR");
        assertThat(body.get("isAdmin").asBoolean()).isTrue();
    }

    /**
     * AC-012.1 linha "AGUARDANDO_APROVACAO → ATIVO (Cuidador ou Familiar)": um Cuidador aprovado no
     * mesmo grupo vê o <strong>mesmo</strong> {@code elderId} do Responsável, papel
     * {@code CUIDADOR} e {@code isAdmin=false} (só o Responsável administra, D-29).
     */
    @Test
    @DisplayName("AC-012.1 Cuidador ATIVO → hasGroup, mesmo elderId do grupo, CUIDADOR, sem administração")
    void ac_012_1_cuidador_ativo_devolve_mesmo_idoso_sem_administracao() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membroAprovado(grupo, Role.CUIDADOR);

        JsonNode body = consultar(cuidador);

        assertThat(body.get("state").asText()).isEqualTo("ATIVO");
        assertThat(body.get("hasGroup").asBoolean()).isTrue();
        assertThat(body.get("elderId").asText()).isEqualTo(grupo.elderId().toString());
        assertThat(body.get("role").asText()).isEqualTo("CUIDADOR");
        assertThat(body.get("isAdmin").asBoolean()).isFalse();
    }

    // ============================================================ vínculo fora de ATIVO

    /**
     * AC-012.1 linha "(não existe) → AGUARDANDO_APROVACAO" (AC-004.3): o convidado tem vínculo
     * pendente, então {@code hasGroup=true} ({@code AccountState.of} marca grupo sempre que há
     * membership, seja qual for o status); papel e administração só valem em {@code ATIVO}, logo
     * {@code role=null} e {@code isAdmin=false} — o {@code FAMILIAR} provisório da membership
     * pendente não pode vazar. {@code elderId} segue a decisão do orquestrador (resolvido sempre que
     * {@code hasGroup}).
     */
    @Test
    @DisplayName("AC-012.1 convidado pendente → AGUARDANDO_APROVACAO, hasGroup, sem papel nem administração")
    void ac_012_1_pendente_devolve_aguardando_aprovacao() throws Exception {
        Grupo grupo = grupoComIdoso();
        User convidado = membroPendente(grupo);

        JsonNode body = consultar(convidado);

        assertThat(body.get("state").asText()).isEqualTo("AGUARDANDO_APROVACAO");
        assertForaDeAtivo(body, grupo);
    }

    /**
     * AC-012.1 linha "AGUARDANDO_APROVACAO → RECUSADO" (AC-006.4): conta recusada ainda tem sessão
     * válida e, pela rota {@code any}, lê o próprio estado ({@code RECUSADO}) para o frontend
     * mostrar a mensagem certa (AC-012.2); sem papel nem administração.
     */
    @Test
    @DisplayName("AC-012.1 convidado recusado → RECUSADO, hasGroup, sem papel nem administração")
    void ac_012_1_recusado_devolve_recusado() throws Exception {
        Grupo grupo = grupoComIdoso();
        User convidado = membroPendente(grupo);
        Membership m = memberships.findByUserId(convidado.getId()).orElseThrow();
        m.reject(grupo.responsavel().getId(), clock);
        memberships.save(m);

        JsonNode body = consultar(convidado);

        assertThat(body.get("state").asText()).isEqualTo("RECUSADO");
        assertForaDeAtivo(body, grupo);
    }

    /**
     * AC-012.1 linha "AGUARDANDO_APROVACAO → EXPIRADO" (AC-006.5): o pedido é criado 25 h no
     * passado (relógio próprio, para não mexer no {@link MutableClock} compartilhado) e expirado
     * agora, como o job de D-09 faria. Estado {@code EXPIRADO}, sem papel nem administração.
     */
    @Test
    @DisplayName("AC-012.1 pedido vencido → EXPIRADO, hasGroup, sem papel nem administração")
    void ac_012_1_expirado_devolve_expirado() throws Exception {
        Grupo grupo = grupoComIdoso();
        User convidado = users.save(contaConfirmada());
        MutableClock ontem = new MutableClock(clock.instant().minus(Duration.ofHours(25)), ZoneOffset.UTC);
        Membership m = Membership.request(grupo.groupId(), convidado.getId(), ontem);
        m.expire(clock);
        memberships.save(m);

        JsonNode body = consultar(convidado);

        assertThat(body.get("state").asText()).isEqualTo("EXPIRADO");
        assertForaDeAtivo(body, grupo);
    }

    /**
     * AC-012.1 linha "ATIVO → REMOVIDO" (AC-009.2) + {@code x-required-account-state: any}: um
     * membro removido recebe 401 em qualquer rota padrão (T-024), mas aqui recebe
     * <strong>200</strong> com {@code state=REMOVIDO} — é assim que o frontend descobre o motivo e
     * mostra a mensagem de AC-015 em vez de só "sessão expirada". O papel que ele tinha
     * ({@code CUIDADOR}) não aparece mais.
     */
    @Test
    @DisplayName("AC-012.1/AC-015 membro REMOVIDO ainda consulta o próprio estado (200, não 401)")
    void ac_012_1_removido_ainda_consegue_consultar_o_proprio_estado() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membroAprovado(grupo, Role.CUIDADOR);
        Membership m = memberships.findByUserId(cuidador.getId()).orElseThrow();
        m.remove(clock);
        memberships.save(m);
        assertThat(memberships.findByUserId(cuidador.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REMOVIDO);

        JsonNode body = consultar(cuidador);

        assertThat(body.get("state").asText()).isEqualTo("REMOVIDO");
        assertForaDeAtivo(body, grupo);
    }

    // ============================================================ sem sessão → 401

    /**
     * Contrato {@code '401': Unauthorized}: {@code any} dispensa exigência de estado, não de
     * sessão. Sem header {@code Authorization} → 401 {@code Problem}. Validado contra o schema
     * {@code Problem} via {@link ContractSchemas} (não {@code matchesContract()}, que recusaria a
     * própria requisição por falta do bearer exigido — mesmo padrão de {@code RefreshAndLogoutTest}).
     * Hoje a rota não existe e a resposta é 404, por isso este teste também falha.
     */
    @Test
    @DisplayName("Contrato getMyAccountState: sem token → 401 Problem")
    void contrato_sem_token_responde_401() throws Exception {
        MvcResult result = mockMvc.perform(get(ME_ACCOUNT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violations = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violations).as("corpo deve casar com #/components/schemas/Problem: %s", violations).isEmpty();
        assertThat(body.get("status").asInt()).isEqualTo(401);
    }

    /**
     * Contrato {@code '401'} com bearer presente mas inválido: tratado como "sem sessão" (D-13),
     * 401 validado pelo {@code matchesContract()} (a requisição tem o bearer exigido, então o
     * validador pode conferir requisição e resposta inteiras).
     */
    @Test
    @DisplayName("Contrato getMyAccountState: token inválido → 401 Problem")
    void contrato_token_invalido_responde_401() throws Exception {
        mockMvc.perform(get(ME_ACCOUNT).header(HttpHeaders.AUTHORIZATION, "Bearer isto-nao-e-um-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    // ============================================================ apoio

    /** Grupo de cuidado montado no banco: Responsável, id do grupo e id do idoso. */
    record Grupo(User responsavel, UUID groupId, UUID elderId) {
    }

    /**
     * Grupo completo (AC-002.1: não existe grupo sem idoso): Responsável {@code ATIVO}
     * ({@code FAMILIAR}, {@code is_admin}, D-05/D-29), linha em {@code care_groups} e idoso
     * inserido por SQL em {@code elders} (sem entidade até a T-030).
     */
    private Grupo grupoComIdoso() {
        User responsavel = users.save(contaConfirmada());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        memberships.save(Membership.admin(groupId, responsavel.getId(), clock));
        UUID elderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO elders (id, group_id, name, birth_date,
                    address_zip_code, address_street, address_number, address_complement,
                    address_district, address_city, address_state,
                    photo_path, allergies, conditions, updated_at)
                VALUES (?, ?, 'Idoso Teste', CURRENT_DATE - INTERVAL '80 years',
                    '01310100', 'Avenida Paulista', '1000', NULL,
                    'Bela Vista', 'São Paulo', 'SP',
                    NULL, NULL, NULL, now())
                """, elderId, groupId);
        return new Grupo(responsavel, groupId, elderId);
    }

    /** Convidado com pedido {@code AGUARDANDO_APROVACAO} no grupo (AC-004.3). */
    private User membroPendente(Grupo grupo) {
        User user = users.save(contaConfirmada());
        memberships.save(Membership.request(grupo.groupId(), user.getId(), clock));
        return user;
    }

    /** Convidado aprovado pelo Responsável com o papel pedido, sem administração (AC-006.3). */
    private User membroAprovado(Grupo grupo, Role papel) {
        User user = users.save(contaConfirmada());
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        m.approve(papel, grupo.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    /** Conta recém-criada (AC-001.1), ainda sem e-mail confirmado. */
    private User contaNova() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        return User.signup(
                "Maria da Silva", "me-account-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
    }

    /** Conta com e-mail confirmado (AC-001.2). */
    private User contaConfirmada() {
        User user = contaNova();
        user.verifyEmail(clock);
        return user;
    }

    /**
     * Chama {@code GET /me/account} com um JWT válido da conta e confere: 200,
     * {@code application/json}, requisição e resposta conformes ao {@code openapi.yaml}
     * ({@code matchesContract()}). Devolve o corpo.
     */
    private JsonNode consultar(User user) throws Exception {
        MockHttpServletRequestBuilder request = get(ME_ACCOUNT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(user.getId(), clock));
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Partes comuns dos estados com vínculo fora de {@code ATIVO} (pendente, recusado, expirado,
     * removido): {@code hasGroup=true}, sem papel, sem administração e — pela decisão de desenho
     * desta tarefa ("{@code elderId} buscado quando {@code hasGroup()}") — o idoso do grupo do
     * vínculo.
     */
    private void assertForaDeAtivo(JsonNode body, Grupo grupo) {
        // hasGroup: há membership, qualquer que seja o status (AccountState.of, T-017).
        assertThat(body.get("hasGroup").asBoolean()).isTrue();
        // role: só vale em ATIVO; o papel provisório/histórico não aparece.
        assertNulo(body, "role");
        // isAdmin: só vale em ATIVO.
        assertThat(body.get("isAdmin").asBoolean()).isFalse();
        // elderId: resolvido pelo group_id da membership, como em ATIVO.
        assertThat(body.get("elderId").asText()).isEqualTo(grupo.elderId().toString());
    }

    /** Campo anulável do contrato: aceita {@code null} explícito ou ausente, nunca um valor. */
    private static void assertNulo(JsonNode body, String campo) {
        assertThat(body.path(campo).isNull() || body.path(campo).isMissingNode())
                .as("%s deve ser null (ou ausente), mas veio %s", campo, body.path(campo))
                .isTrue();
    }
}
