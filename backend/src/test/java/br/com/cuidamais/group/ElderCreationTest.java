package br.com.cuidamais.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.JwtService;
import br.com.cuidamais.auth.User;
import br.com.cuidamais.auth.UserRepository;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * T-030 "Pronto quando" — {@code POST /api/v1/elders} (contrato {@code operationId: createElder}):
 * numa única transação cria {@code care_groups} + {@code elders} (+ {@code emergency_contacts}
 * opcionais) e a {@code membership} do criador como {@code FAMILIAR} administrador (D-05 rev. 2),
 * só para uma conta {@code ATIVO} ainda sem grupo (uma vez por conta — a 2ª tentativa é conflito de
 * negócio do serviço, não do filtro de segurança, ver {@code AccountStateRequirement}).
 *
 * <p>ACs cobertos aqui: AC-001.2 (o grupo nasce com o cadastro do idoso), AC-002.1 (grupo 1:1 com o
 * idoso), AC-002.2 (campos obrigatórios/opcionais do idoso), AC-002.5 (contatos de emergência
 * obrigatórios, zero ou mais), AC-002.7 (nascimento válido de calendário e não futuro — inclui o
 * gap real documentado no relatório da tarefa: hoje não existe {@code @ExceptionHandler} para
 * {@code HttpMessageNotReadableException}, então uma data sintaticamente malformada vira 500, não
 * 400), AC-002.8 (alergias/condições até 500 caracteres, D-36).
 *
 * <p>Caixa-preta pela rota HTTP real (contexto Spring completo, MockMvc): a conta de teste nasce
 * {@code ATIVO} direto no banco (mesmo padrão de {@code RequestAuthorizationTest}, sem depender do
 * fluxo completo de {@code signup}/{@code verify-email}) e o token Bearer é emitido direto por
 * {@link JwtService#issue}, sem passar por {@code /auth/login} — o filtro de segurança só confia no
 * {@code sub} do JWT e lê o resto do banco a cada requisição (D-13 rev. 2), então isso é equivalente
 * a um login real para efeito deste teste. Nenhuma entidade/repositório/controller de produção é
 * referenciado: a rota ainda não existe, então a maioria destes testes falha hoje por 404 (rota
 * inexistente) em vez do status esperado — motivo correto nesta fase (funcionalidade ausente).
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta (e-mail único) e as
 * contagens no banco são sempre escopadas por {@code user_id}/{@code group_id}, nunca por contagem
 * global da tabela.
 */
@AutoConfigureMockMvc
class ElderCreationTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";

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

    // ============================================================ AC-001.2 / AC-002.1 / D-05 caminho feliz

    /**
     * Cenário central do "Pronto quando": uma conta ATIVO sem grupo cadastra o idoso com os três
     * campos obrigatórios (nome, nascimento passado, endereço) e, numa única transação, nascem
     * exatamente um {@code care_groups}, um {@code elders} vinculado a ele e uma
     * {@code membership} do próprio criador como FAMILIAR administrador ATIVO (D-05 rev. 2).
     */
    @Test
    @DisplayName("AC-001.2 / AC-002.1 / D-05 conta ATIVO sem grupo cadastra o idoso e cria grupo + membership admin")
    void ac_001_2_conta_ativa_sem_grupo_cadastra_idoso_e_cria_grupo_com_responsavel_admin() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());

        MvcResult result = mockMvc.perform(criar(elderInputValido(), criador))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .as("Location do idoso criado")
                .isNotBlank()
                .contains("/elders/");
        UUID elderId = idDoCorpo(result);

        assertThat(countCareGroupsCriadosPor(criador.getId())).as("exatamente 1 care_groups").isEqualTo(1);
        assertThat(countEldersDoCriador(criador.getId())).as("exatamente 1 elders").isEqualTo(1);

        Map<String, Object> membership = jdbc.queryForMap(
                "SELECT role, is_admin, status FROM memberships WHERE user_id = ?", criador.getId());
        assertThat(membership)
                .as("membership única do criador: FAMILIAR, is_admin, ATIVO")
                .containsEntry("role", "FAMILIAR")
                .containsEntry("is_admin", true)
                .containsEntry("status", "ATIVO");

        assertThat(jdbc.queryForObject(
                        "SELECT group_id FROM elders WHERE id = ?", UUID.class, elderId))
                .as("o idoso criado pertence ao grupo recém-criado deste criador")
                .isEqualTo(jdbc.queryForObject(
                        "SELECT id FROM care_groups WHERE created_by = ?", UUID.class, criador.getId()));
    }

    // ============================================================ AC-002.2 / AC-002.5 cadastro completo

    /**
     * Com todos os campos opcionais preenchidos (alergias, condições e 2 contatos de emergência),
     * a resposta devolve tudo de volta certo e o banco persiste os 2 contatos vinculados ao idoso.
     */
    @Test
    @DisplayName("AC-002.2 / AC-002.5 cadastro completo com alergias, condições e 2 contatos de emergência")
    void ac_002_2_cadastro_completo_com_alergias_condicoes_e_contatos() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        Map<String, Object> corpo = elderInputValido();
        corpo.put("allergies", "Penicilina");
        corpo.put("conditions", "Hipertensão");
        corpo.put("emergencyContacts", List.of(
                contato("Joana Oliveira", "Filha", "21988887777"),
                contato("Pedro Oliveira", "Filho", "21977776666")));

        MvcResult result = mockMvc.perform(criar(corpo, criador))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("allergies").asText()).isEqualTo("Penicilina");
        assertThat(body.path("conditions").asText()).isEqualTo("Hipertensão");
        assertThat(body.path("emergencyContacts")).hasSize(2);

        UUID elderId = idDoCorpo(result);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM emergency_contacts WHERE elder_id = ?", Integer.class, elderId))
                .as("os 2 contatos ficam persistidos vinculados ao idoso")
                .isEqualTo(2);
    }

    /**
     * AC-002.5 "o idoso pode ter zero ou mais contatos": omitir {@code emergencyContacts} ainda é
     * {@code 201}, com a lista vazia (default {@code []} do contrato) — zero contatos é válido.
     */
    @Test
    @DisplayName("AC-002.5 emergencyContacts omitido é aceito com lista vazia (zero contatos é válido)")
    void ac_002_5_contatos_omitidos_e_aceito_com_lista_vazia() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());

        MvcResult result = mockMvc.perform(criar(elderInputValido(), criador))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("emergencyContacts").isArray()).isTrue();
        assertThat(body.path("emergencyContacts")).isEmpty();

        UUID elderId = idDoCorpo(result);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM emergency_contacts WHERE elder_id = ?", Integer.class, elderId))
                .isZero();
    }

    // ============================================================ AC-002.1 / D-02 segunda tentativa

    /**
     * AC-002.1 "cada grupo tem exatamente um idoso e um Responsável": uma conta que já é
     * Responsável de um grupo chamando {@code POST /elders} de novo recebe 409 (conflito de
     * negócio do serviço — o filtro de segurança {@code active-without-group} não olha isso) e
     * nada novo é criado: continua existindo exatamente 1 {@code care_groups} e 1 {@code elders}
     * para esta conta.
     */
    @Test
    @DisplayName("AC-002.1 / D-02 segunda tentativa da mesma conta (já Responsável) recebe 409, nada novo é criado")
    void ac_002_1_segunda_tentativa_da_mesma_conta_recebe_409() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        mockMvc.perform(criar(elderInputValido(), criador)).andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(criar(elderInputValido(), criador))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("code")).as("409 é Problem genérico, sem code (contrato)").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();

        assertThat(countCareGroupsCriadosPor(criador.getId())).as("ainda exatamente 1 care_groups").isEqualTo(1);
        assertThat(countEldersDoCriador(criador.getId())).as("ainda exatamente 1 elders").isEqualTo(1);
    }

    // ============================================================ AC-002.7 nascimento

    /** AC-002.7 "não pode ser futura": nascimento amanhã (relativo ao relógio do teste) → 400. */
    @Test
    @DisplayName("AC-002.7 nascimento no futuro é recusado com 400 em birthDate")
    void ac_002_7_nascimento_futuro_e_recusado() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());

        esperaValidationProblem(com(elderInputValido(), "birthDate", dataFutura()), "birthDate", criador);

        assertThat(countEldersDoCriador(criador.getId())).isZero();
    }

    /**
     * AC-002.7 "deve ser uma data válida do calendário" — gap real documentado no relatório da
     * tarefa: {@code "2024-02-30"} é sintaticamente uma data ISO, mas 30 de fevereiro não existe.
     * Hoje não há {@code @ExceptionHandler} para {@code HttpMessageNotReadableException} em
     * {@code GlobalExceptionHandler}, então esse corpo cairia no handler genérico de
     * {@code Exception} → 500. Este teste espera 400 (o contrato promete {@code ValidationError}
     * para este endpoint) — deve falhar hoje porque a resposta observada é 500 (ou porque a rota
     * nem existe ainda), não porque o 400 "não teria o campo certo".
     */
    @Test
    @DisplayName("AC-002.7 (gap do GlobalExceptionHandler) data de calendário inválida (30 de fevereiro) → 400, não 500")
    void ac_002_7_data_de_calendario_invalida_e_recusada_com_400() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        Map<String, Object> corpo = com(elderInputValido(), "birthDate", "2024-02-30");

        MvcResult result = mockMvc.perform(criar(corpo, criador)).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("AC-002.7: data de calendário inválida deve virar 400, nunca 500 (P5: nenhum erro de"
                        + " entrada do usuário pode ser um erro interno)")
                .isEqualTo(400);
        assertThat(countEldersDoCriador(criador.getId())).isZero();
    }

    // ============================================================ AC-002.8 limite de 500 caracteres

    /** AC-002.8 / D-36: alergia com 501 caracteres excede o limite → 400 em allergies. */
    @Test
    @DisplayName("AC-002.8 alergia com 501 caracteres é recusada com 400 em allergies")
    void ac_002_8_alergia_com_501_caracteres_e_recusada() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());

        esperaValidationProblem(com(elderInputValido(), "allergies", "a".repeat(501)), "allergies", criador);

        assertThat(countEldersDoCriador(criador.getId())).isZero();
    }

    /** AC-002.8 / D-36: exatamente 500 caracteres ainda está dentro do limite → 201 aceito. */
    @Test
    @DisplayName("AC-002.8 alergia com exatamente 500 caracteres é aceita (201)")
    void ac_002_8_alergia_com_500_caracteres_e_aceita() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        String alergia500 = "a".repeat(500);

        MvcResult result = mockMvc.perform(criar(com(elderInputValido(), "allergies", alergia500), criador))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("allergies").asText()).hasSize(500);
    }

    // ============================================================ AC-002.2 campos obrigatórios do idoso

    /**
     * AC-002.2 "nome, data de nascimento e endereço, obrigatórios": cada um dos três, ausente do
     * corpo, é recusado com 400 no campo certo e nada é criado.
     *
     * <p>Cada valor da lista é uma etapa própria do mesmo cenário: remove um campo obrigatório
     * diferente do {@code ElderInput} e confere o erro de validação correspondente.
     */
    @ParameterizedTest(name = "AC-002.2 campo obrigatório \"{0}\" ausente é recusado")
    @ValueSource(strings = {"name", "birthDate", "address"})
    @DisplayName("AC-002.2 campo obrigatório do idoso (name/birthDate/address) ausente → 400 no campo, nada criado")
    void ac_002_2_campo_obrigatorio_ausente_e_recusado(String campo) throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        Map<String, Object> corpo = elderInputValido();
        corpo.remove(campo);

        esperaValidationProblem(corpo, campo, criador);

        assertThat(countEldersDoCriador(criador.getId())).isZero();
    }

    // ============================================================ AC-002.5 contato de emergência obrigatório

    /**
     * AC-002.5 "cada contato tem nome, parentesco, telefone e endereço, todos obrigatórios": um
     * contato sem telefone é recusado com 400, sem criar nada.
     */
    @Test
    @DisplayName("AC-002.5 contato de emergência sem telefone é recusado com 400")
    void ac_002_5_contato_sem_telefone_e_recusado() throws Exception {
        User criador = users.save(contaAtivaSemGrupo());
        Map<String, Object> contatoSemTelefone = contato("Joana Oliveira", "Filha", "21988887777");
        contatoSemTelefone.remove("phone");
        Map<String, Object> corpo = com(elderInputValido(), "emergencyContacts", List.of(contatoSemTelefone));

        esperaValidationProblem(corpo, "emergencyContacts[0].phone", criador);

        assertThat(countEldersDoCriador(criador.getId())).isZero();
    }

    // ============================================================ autenticação

    /** Controle básico: a rota é autenticada — sem token nenhum, 401 (exaustão é de T-024). */
    @Test
    @DisplayName("sem sessão (sem token) responde 401")
    void sem_sessao_responde_401() throws Exception {
        mockMvc.perform(
                        post(ELDERS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(elderInputValido())))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ apoio

    /** Conta {@code ATIVO} sem grupo, pronta para chamar {@code POST /elders} (mesmo padrão de T-024). */
    private User contaAtivaSemGrupo() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "elder-criador-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private String dataPassada() {
        return LocalDate.now(clock).minusYears(80).toString();
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

    private static Map<String, Object> contato(String nome, String parentesco, String telefone) {
        Map<String, Object> contato = new LinkedHashMap<>();
        contato.put("name", nome);
        contato.put("relationship", parentesco);
        contato.put("phone", telefone);
        contato.put("address", enderecoValido());
        return contato;
    }

    /** Corpo válido mínimo de {@code ElderInput}: só os 3 campos obrigatórios. */
    private Map<String, Object> elderInputValido() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", dataPassada());
        req.put("address", enderecoValido());
        return req;
    }

    private static Map<String, Object> com(Map<String, Object> req, String campo, Object valor) {
        req.put(campo, valor);
        return req;
    }

    private RequestBuilder criar(Map<String, Object> corpo, User autor) throws Exception {
        return post(ELDERS)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(corpo));
    }

    private UUID idDoCorpo(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private int countCareGroupsCriadosPor(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM care_groups WHERE created_by = ?", Integer.class, userId);
    }

    private int countEldersDoCriador(UUID userId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM elders e
                 JOIN care_groups g ON g.id = e.group_id
                 WHERE g.created_by = ?
                """,
                Integer.class,
                userId);
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} do contrato e um item de {@code errors} com {@code field}
     * exatamente igual a {@code campoEsperado} (mesmo padrão de {@code SignupTest}/{@code
     * VerifyEmailTest}). Não usa {@link ContractValidation#matchesContract()} porque a requisição
     * em si é propositalmente inválida.
     */
    private JsonNode esperaValidationProblem(Map<String, Object> corpo, String campoEsperado, User autor)
            throws Exception {
        MvcResult result = mockMvc.perform(criar(corpo, autor))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem do contrato: %s", violacoes).isEmpty();

        List<String> campos = new ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
        return body;
    }
}
