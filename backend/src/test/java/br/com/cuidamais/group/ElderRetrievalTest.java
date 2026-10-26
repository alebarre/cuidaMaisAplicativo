package br.com.cuidamais.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.JwtService;
import br.com.cuidamais.auth.User;
import br.com.cuidamais.auth.UserRepository;
import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.membership.Role;
import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.sql.Date;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * T-030 "Pronto quando" — {@code GET /api/v1/elders/{elderId}} (contrato {@code operationId:
 * getElder}): sem {@code x-required-account-state} nem {@code x-required-permission} declarados —
 * padrão do contrato (conta {@code ATIVO} com grupo, qualquer papel) — então qualquer membro ativo
 * do grupo, inclusive Cuidador, vê os dados completos do idoso.
 *
 * <p>ACs cobertos: AC-002.3 ("todos os membros ativos veem esses dados"), AC-011.7 (o endereço do
 * idoso e dos contatos de emergência é visível para todos os membros ativos, inclusive
 * Cuidadores — literalmente o "Pronto quando" desta tarefa), AC-015.3 (recurso de outro grupo ou
 * inexistente: resposta 404 idêntica nos dois casos, D-32).
 *
 * <p>Caixa-preta pela rota HTTP real. Como a entidade {@code Elder} e o repositório ainda não
 * existem em produção (nascem só nesta tarefa — ver Javadoc de {@code ElderIdLookup}), o cenário
 * ("grupo A com um idoso e um contato") é fabricado direto no banco por SQL puro
 * ({@code care_groups}/{@code elders}/{@code emergency_contacts}, tabelas já existentes desde
 * {@code V1__foundation.sql}, T-010) + a entidade JPA {@link Membership}, que já existe desde a
 * T-023 — o mesmo padrão de composição já usado em {@code RequestAuthorizationTest} e
 * {@code FoundationMigrationTest}. Isso deixa estes testes de leitura independentes de o
 * {@code POST /elders} já funcionar.
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta/grupo/idoso.
 */
@AutoConfigureMockMvc
class ElderRetrievalTest extends IntegrationTest {

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

    // ============================================================ AC-002.3 / AC-011.7 Responsável vê tudo

    /**
     * O próprio Responsável (criador do grupo) vê os dados completos do idoso, inclusive o
     * endereço dele e o endereço do contato de emergência cadastrado.
     */
    @Test
    @DisplayName("AC-002.3 / AC-011.7 Responsável vê os dados completos do idoso, inclusive endereços")
    void ac_002_3_responsavel_ve_dados_completos_do_idoso_inclusive_enderecos() throws Exception {
        Grupo grupo = grupoComIdosoEUmContato();

        MvcResult result = mockMvc.perform(buscar(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertDadosCompletosDoIdoso(json.readTree(result.getResponse().getContentAsString()));
    }

    /**
     * AC-011.7, literalmente o "Pronto quando" da tarefa: um Cuidador do mesmo grupo (fabricado
     * direto no banco via {@link Membership#request}/{@link Membership#approve}, já que o fluxo
     * HTTP de convite é da Fase 4) também vê o endereço completo do idoso e do contato — o
     * endereço não é restrito ao Responsável.
     */
    @Test
    @DisplayName("AC-011.7 Cuidador do grupo também vê o endereço completo do idoso e dos contatos")
    void ac_011_7_cuidador_do_grupo_tambem_ve_enderecos_completos() throws Exception {
        Grupo grupo = grupoComIdosoEUmContato();
        User cuidador = cuidadorDoGrupo(grupo);

        MvcResult result = mockMvc.perform(buscar(grupo.elderId(), cuidador))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertDadosCompletosDoIdoso(json.readTree(result.getResponse().getContentAsString()));
    }

    // ============================================================ AC-015.3 outro grupo / inexistente

    /**
     * AC-015.3 / D-32: uma conta Responsável de OUTRO grupo (grupo B) chamando
     * {@code GET /elders/{idDoGrupoA}} recebe 404 — o recurso existe, mas não pertence ao grupo de
     * quem pergunta, e isso não pode ser distinguido de "não existe" na resposta.
     */
    @Test
    @DisplayName("AC-015.3 Responsável de outro grupo recebe 404 ao buscar o idoso do grupo A")
    void ac_015_3_responsavel_de_outro_grupo_recebe_404_para_idoso_do_grupo_a() throws Exception {
        Grupo grupoA = grupoComIdosoEUmContato();
        Grupo grupoB = grupoComIdosoEUmContato();

        esperaNotFound(buscar(grupoA.elderId(), grupoB.responsavel()));
    }

    /**
     * AC-015.3, o ponto central do critério: o 404 de "recurso de outro grupo" é indistinguível do
     * 404 de "recurso inexistente" — comparados campo a campo ({@code type}, {@code title},
     * {@code status}, sem {@code code}). {@code instance} naturalmente contém o id pedido em cada
     * requisição (o próprio chamador já sabe qual id pediu) e por isso é o único campo que varia
     * entre os dois corpos; não é ele que revelaria qual dos dois motivos ocorreu.
     */
    @Test
    @DisplayName("AC-015.3 404 de outro grupo e 404 de id inexistente são indistinguíveis campo a campo")
    void ac_015_3_404_de_outro_grupo_e_de_id_inexistente_sao_indistinguiveis() throws Exception {
        Grupo grupoA = grupoComIdosoEUmContato();
        Grupo grupoB = grupoComIdosoEUmContato();

        // Etapa 1: grupo B pedindo o idoso do grupo A — existe, mas não é dele.
        MvcResult outroGrupo = mockMvc.perform(buscar(grupoA.elderId(), grupoB.responsavel()))
                .andExpect(status().isNotFound())
                .andReturn();

        // Etapa 2: grupo A pedindo um id que nunca existiu.
        MvcResult inexistente = mockMvc.perform(buscar(UUID.randomUUID(), grupoA.responsavel()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode corpoOutroGrupo = json.readTree(outroGrupo.getResponse().getContentAsString());
        JsonNode corpoInexistente = json.readTree(inexistente.getResponse().getContentAsString());
        assertProblemsEquivalentes(corpoOutroGrupo, corpoInexistente);
    }

    // ============================================================ apoio

    /** Conta + grupo + idoso fabricados para um cenário. */
    private record Grupo(User responsavel, UUID groupId, UUID elderId) {
    }

    /** Conta {@code ATIVO} sem grupo, pronta para receber uma membership (mesmo padrão de T-024). */
    private User contaAtivaSemGrupo() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "elder-leitura-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    /** Grupo novo com Responsável admin ATIVO, um idoso e um contato de emergência, tudo persistido. */
    private Grupo grupoComIdosoEUmContato() {
        User responsavel = users.save(contaAtivaSemGrupo());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        memberships.save(Membership.admin(groupId, responsavel.getId(), clock));

        UUID elderId = inserirIdoso(groupId);
        inserirContato(elderId, "Joana Oliveira", "Filha", "21988887777");
        return new Grupo(responsavel, groupId, elderId);
    }

    /** Cuidador ATIVO do mesmo grupo, aprovado pelo Responsável (AC-006.3), sem administração. */
    private User cuidadorDoGrupo(Grupo grupo) {
        User user = users.save(contaAtivaSemGrupo());
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        m.approve(Role.CUIDADOR, grupo.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private UUID inserirIdoso(UUID groupId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO elders (id, group_id, name, birth_date, address_zip_code, address_street,
                    address_number, address_complement, address_district, address_city, address_state,
                    allergies, conditions)
                VALUES (?, ?, 'Francisca Oliveira', ?, '22041001', 'Rua Barata Ribeiro', '370', 'Apto 802',
                    'Copacabana', 'Rio de Janeiro', 'RJ', 'Penicilina', 'Hipertensão')
                """,
                id,
                groupId,
                Date.valueOf(LocalDate.now(clock).minusYears(80)));
        return id;
    }

    private void inserirContato(UUID elderId, String nome, String parentesco, String telefone) {
        jdbc.update(
                """
                INSERT INTO emergency_contacts (id, elder_id, name, relationship, phone, address_zip_code,
                    address_street, address_number, address_complement, address_district, address_city,
                    address_state, position)
                VALUES (?, ?, ?, ?, ?, '04567000', 'Rua Vergueiro', '1000', 'Bloco B', 'Vila Mariana',
                    'São Paulo', 'SP', 0)
                """,
                UUID.randomUUID(),
                elderId,
                nome,
                parentesco,
                telefone);
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private RequestBuilder buscar(UUID elderId, User autor) {
        return get("/api/v1/elders/{elderId}", elderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    /**
     * Confere nome, endereço do próprio idoso e endereço do contato de emergência — exatamente o
     * que {@link #grupoComIdosoEUmContato()} gravou (AC-011.7).
     */
    private void assertDadosCompletosDoIdoso(JsonNode body) {
        assertThat(body.path("name").asText()).isEqualTo("Francisca Oliveira");
        assertThat(body.path("allergies").asText()).isEqualTo("Penicilina");
        assertThat(body.path("conditions").asText()).isEqualTo("Hipertensão");

        JsonNode endereco = body.path("address");
        assertThat(endereco.path("zipCode").asText()).isEqualTo("22041001");
        assertThat(endereco.path("street").asText()).isEqualTo("Rua Barata Ribeiro");
        assertThat(endereco.path("city").asText()).isEqualTo("Rio de Janeiro");

        JsonNode contatos = body.path("emergencyContacts");
        assertThat(contatos).hasSize(1);
        JsonNode contato = contatos.get(0);
        assertThat(contato.path("name").asText()).isEqualTo("Joana Oliveira");
        assertThat(contato.path("phone").asText()).isEqualTo("21988887777");
        JsonNode enderecoContato = contato.path("address");
        assertThat(enderecoContato.path("zipCode").asText()).isEqualTo("04567000");
        assertThat(enderecoContato.path("street").asText()).isEqualTo("Rua Vergueiro");
        assertThat(enderecoContato.path("city").asText()).isEqualTo("São Paulo");
    }

    /**
     * Executa a requisição e confere: 404, {@code application/problem+json}, corpo conforme
     * {@code #/components/schemas/Problem} do contrato.
     */
    private void esperaNotFound(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();
    }

    /**
     * AC-015.3: os dois corpos de erro 404 são indistinguíveis em todo campo que poderia revelar o
     * motivo ({@code type}, {@code title}, {@code status}, {@code detail}, ausência de
     * {@code code}) — só {@code instance} varia, porque carrega o id efetivamente pedido em cada
     * chamada (dado que o próprio chamador já conhece).
     */
    private void assertProblemsEquivalentes(JsonNode a, JsonNode b) {
        assertThat(a.get("type")).as("type idêntico").isEqualTo(b.get("type"));
        assertThat(a.get("title")).as("title idêntico").isEqualTo(b.get("title"));
        assertThat(a.get("status")).as("status idêntico").isEqualTo(b.get("status"));
        assertThat(a.get("detail")).as("detail idêntico").isEqualTo(b.get("detail"));
        assertThat(a.has("code")).as("sem code (não revela o motivo)").isFalse();
        assertThat(b.has("code")).as("sem code (não revela o motivo)").isFalse();
    }
}
