package br.com.cuidamais.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * T-030 "Pronto quando" — {@code PUT /api/v1/elders/{elderId}} (contrato {@code operationId:
 * updateElder}, {@code x-required-permission: admin}): substitui todos os dados do idoso,
 * inclusive a lista completa de contatos de emergência, sem gerar histórico (D-36: é um
 * {@code UPDATE} simples).
 *
 * <p>ACs cobertos: AC-002.3 (só o Responsável edita), AC-002.9 (edição não gera histórico — a
 * tabela {@code elders} continua com exatamente uma linha para o grupo, e {@code
 * emergency_contacts} reflete a <strong>substituição</strong> completa da lista, não um
 * acréscimo), AC-015.3 (404 idêntico para outro grupo/inexistente, D-32). Reaproveita 2 cenários
 * de validação do {@code POST} (AC-002.7 nascimento futuro, AC-002.8 alergia de 501 caracteres)
 * para confirmar que o {@code PUT} valida igual ao {@code POST}, sem repetir a bateria inteira.
 *
 * <p>Caixa-preta pela rota HTTP real. O cenário inicial (grupo com idoso e 2 contatos) é fabricado
 * direto no banco por SQL puro, do mesmo jeito que {@code ElderRetrievalTest} — a entidade
 * {@code Elder} e o {@code ElderController} ainda não existem, então a maioria destes testes falha
 * hoje por 404 de rota inexistente em vez do status esperado.
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta/grupo/idoso.
 */
@AutoConfigureMockMvc
class ElderUpdateTest extends IntegrationTest {

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

    // ============================================================ AC-002.3 / AC-002.9 edição pelo Responsável

    /**
     * Cenário central do "Pronto quando": o Responsável substitui nome, nascimento, endereço,
     * alergias/condições e troca a lista de 2 contatos por 1 contato diferente. A resposta reflete
     * os novos dados; no banco, {@code emergency_contacts} do idoso passa a ter só o novo contato
     * (prova de substituição completa, não acréscimo — AC-002.3) e {@code elders} continua com
     * exatamente 1 linha para o grupo (prova de "sem histórico", AC-002.9: é um {@code UPDATE}
     * simples, nenhuma linha extra de versão).
     */
    @Test
    @DisplayName("AC-002.3 / AC-002.9 Responsável edita o idoso e substitui a lista de contatos (2 → 1), sem histórico")
    void ac_002_3_ac_002_9_responsavel_edita_idoso_e_substitui_contatos() throws Exception {
        Grupo grupo = grupoComIdosoEDoisContatos();
        Map<String, Object> corpo = elderInputAtualizado();

        MvcResult result = mockMvc.perform(atualizar(grupo.elderId(), corpo, grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("name").asText()).isEqualTo("Francisca Oliveira Souza");
        assertThat(body.path("allergies").asText()).isEqualTo("Dipirona");
        assertThat(body.path("conditions").asText()).isEqualTo("Diabetes tipo 2");
        assertThat(body.path("emergencyContacts")).hasSize(1);
        assertThat(body.path("emergencyContacts").get(0).path("name").asText()).isEqualTo("Maria Nova");

        // AC-002.3: substituição completa — sobra só o contato novo, os 2 antigos não existem mais.
        List<String> nomesDosContatos = jdbc.queryForList(
                "SELECT name FROM emergency_contacts WHERE elder_id = ?", String.class, grupo.elderId());
        assertThat(nomesDosContatos).as("lista de contatos substituída, não somada").containsExactly("Maria Nova");

        // AC-002.9: nenhuma linha extra de histórico — o idoso do grupo continua sendo exatamente 1 linha.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM elders WHERE group_id = ?", Integer.class, grupo.groupId()))
                .as("edição não gera histórico: UPDATE simples, sem versão extra")
                .isEqualTo(1);
    }

    // ============================================================ AC-002.3 Cuidador não pode editar

    /**
     * AC-002.3 "só o Responsável edita": um Cuidador do grupo (sem administração) tentando
     * {@code PUT} recebe 403 {@code NOT_ALLOWED} — mesmo código já usado em outras rotas
     * admin-only do projeto ({@code x-required-permission: admin}).
     */
    @Test
    @DisplayName("AC-002.3 Cuidador (sem administração) tentando editar o idoso recebe 403 NOT_ALLOWED")
    void ac_002_3_cuidador_tentando_editar_recebe_403_not_allowed() throws Exception {
        Grupo grupo = grupoComIdosoEDoisContatos();
        User cuidador = cuidadorDoGrupo(grupo);

        MvcResult result = mockMvc.perform(atualizar(grupo.elderId(), elderInputAtualizado(), cuidador))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");

        // O idoso não muda: o nome continua o original.
        assertThat(jdbc.queryForObject(
                        "SELECT name FROM elders WHERE id = ?", String.class, grupo.elderId()))
                .isEqualTo("Francisca Oliveira");
    }

    // ============================================================ AC-015.3 outro grupo

    /**
     * AC-015.3 / D-32: conta B (Responsável de outro grupo) tentando {@code PUT} no idoso do
     * grupo A recebe 404 — mesmo padrão (corpo {@code Problem} genérico, sem {@code code}) dos
     * testes de {@code GET} ({@code ElderRetrievalTest}, cenários 13/14): o recurso existe, mas
     * não pertence a quem pergunta, e isso é indistinguível de "não existe".
     */
    @Test
    @DisplayName("AC-015.3 Responsável de outro grupo tentando editar o idoso do grupo A recebe 404")
    void ac_015_3_responsavel_de_outro_grupo_tentando_editar_recebe_404() throws Exception {
        Grupo grupoA = grupoComIdosoEDoisContatos();
        Grupo grupoB = grupoComIdosoEDoisContatos();

        MvcResult result = mockMvc.perform(atualizar(grupoA.elderId(), elderInputAtualizado(), grupoB.responsavel()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("code")).as("404 é Problem genérico, sem code").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();

        // Nada do idoso do grupo A foi alterado pela tentativa de outro grupo.
        assertThat(jdbc.queryForObject(
                        "SELECT name FROM elders WHERE id = ?", String.class, grupoA.elderId()))
                .isEqualTo("Francisca Oliveira");
    }

    // ============================================================ validações (reaproveitadas do POST)

    /** AC-002.7: o {@code PUT} valida nascimento futuro igual ao {@code POST} → 400 em birthDate. */
    @Test
    @DisplayName("AC-002.7 PUT com nascimento futuro é recusado com 400 em birthDate, idoso não muda")
    void ac_002_7_put_com_nascimento_futuro_e_recusado() throws Exception {
        Grupo grupo = grupoComIdosoEDoisContatos();
        Map<String, Object> corpo = com(elderInputAtualizado(), "birthDate", dataFutura());

        esperaValidationProblem(grupo.elderId(), corpo, "birthDate", grupo.responsavel());

        assertThat(jdbc.queryForObject(
                        "SELECT name FROM elders WHERE id = ?", String.class, grupo.elderId()))
                .as("corpo inválido não altera o idoso existente")
                .isEqualTo("Francisca Oliveira");
    }

    /** AC-002.8 / D-36: o {@code PUT} valida o limite de 500 caracteres igual ao {@code POST}. */
    @Test
    @DisplayName("AC-002.8 PUT com alergia de 501 caracteres é recusado com 400 em allergies")
    void ac_002_8_put_com_alergia_de_501_caracteres_e_recusado() throws Exception {
        Grupo grupo = grupoComIdosoEDoisContatos();
        Map<String, Object> corpo = com(elderInputAtualizado(), "allergies", "a".repeat(501));

        esperaValidationProblem(grupo.elderId(), corpo, "allergies", grupo.responsavel());
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
                "Maria da Silva", "elder-edicao-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    /** Grupo novo com Responsável admin ATIVO, um idoso e 2 contatos de emergência, tudo persistido. */
    private Grupo grupoComIdosoEDoisContatos() {
        User responsavel = users.save(contaAtivaSemGrupo());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        memberships.save(Membership.admin(groupId, responsavel.getId(), clock));

        UUID elderId = inserirIdoso(groupId);
        inserirContato(elderId, "Joana Oliveira", "Filha", "21988887777", 0);
        inserirContato(elderId, "Pedro Oliveira", "Filho", "21977776666", 1);
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

    private void inserirContato(UUID elderId, String nome, String parentesco, String telefone, int posicao) {
        jdbc.update(
                """
                INSERT INTO emergency_contacts (id, elder_id, name, relationship, phone, address_zip_code,
                    address_street, address_number, address_complement, address_district, address_city,
                    address_state, position)
                VALUES (?, ?, ?, ?, ?, '04567000', 'Rua Vergueiro', '1000', 'Bloco B', 'Vila Mariana',
                    'São Paulo', 'SP', ?)
                """,
                UUID.randomUUID(),
                elderId,
                nome,
                parentesco,
                telefone,
                posicao);
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private String dataFutura() {
        return LocalDate.now(clock).plusDays(1).toString();
    }

    private static Map<String, Object> enderecoValido() {
        Map<String, Object> endereco = new LinkedHashMap<>();
        endereco.put("zipCode", "22041001");
        endereco.put("street", "Rua Barata Ribeiro");
        endereco.put("number", "370");
        endereco.put("complement", "Apto 802");
        endereco.put("district", "Copacabana");
        endereco.put("city", "Rio de Janeiro");
        endereco.put("state", "RJ");
        return endereco;
    }

    private static Map<String, Object> contatoNovo(String nome, String parentesco, String telefone) {
        Map<String, Object> contato = new LinkedHashMap<>();
        contato.put("name", nome);
        contato.put("relationship", parentesco);
        contato.put("phone", telefone);
        contato.put("address", enderecoValido());
        return contato;
    }

    /**
     * Corpo de substituição completa: novo nome, novo nascimento, novo endereço, novas
     * alergias/condições e a lista de contatos trocada de 2 (Joana/Pedro) para 1 (Maria Nova).
     */
    private Map<String, Object> elderInputAtualizado() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira Souza");
        req.put("birthDate", LocalDate.now(clock).minusYears(81).toString());
        req.put("address", enderecoValido());
        req.put("allergies", "Dipirona");
        req.put("conditions", "Diabetes tipo 2");
        req.put("emergencyContacts", List.of(contatoNovo("Maria Nova", "Neta", "21966665555")));
        return req;
    }

    private static Map<String, Object> com(Map<String, Object> req, String campo, Object valor) {
        req.put(campo, valor);
        return req;
    }

    private RequestBuilder atualizar(UUID elderId, Map<String, Object> corpo, User autor) throws Exception {
        return put("/api/v1/elders/{elderId}", elderId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(corpo));
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} do contrato e um item de {@code errors} com {@code field}
     * exatamente igual a {@code campoEsperado} (mesmo padrão de {@code ElderCreationTest}).
     */
    private void esperaValidationProblem(UUID elderId, Map<String, Object> corpo, String campoEsperado, User autor)
            throws Exception {
        MvcResult result = mockMvc.perform(atualizar(elderId, corpo, autor))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem do contrato: %s", violacoes).isEmpty();

        List<String> campos = new java.util.ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
    }
}
