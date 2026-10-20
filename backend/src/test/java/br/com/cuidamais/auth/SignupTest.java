package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * T-020 "Pronto quando": {@code POST /api/v1/auth/signup} (contrato {@code operationId: signup})
 * com cadastro novo, e-mail repetido (mesma resposta, nada criado, aviso no Mailpit), senha fraca
 * e endereço inválido. ACs: 001.1, 001.3, 001.4, 011.1, 011.2. Decisões: D-15 (resposta neutra
 * 202), D-25 (endereço), D-04 (código só como hash), D-14 (senha BCrypt), D-23 (e-mail depois do
 * commit).
 *
 * <p>Teste de caixa-preta: entra pela rota HTTP real (contexto Spring completo, com Spring
 * Security, MockMvc via {@code @AutoConfigureMockMvc}) e confere o efeito no banco por SQL puro
 * ({@link JdbcTemplate}, mesmo padrão de {@code FoundationMigrationTest}) e no Mailpit real
 * ({@link MailpitClient}). Não depende de nenhuma classe de produção nova — o desenho interno
 * (entidade, repositório, serviço) fica livre para o implementador.
 *
 * <p><strong>Desenho esperado da API</strong> (do {@code openapi.yaml}):
 *
 * <ul>
 *   <li>Rota pública ({@code security: []}): sem token e sem CSRF (API stateless), a requisição
 *       chega ao controller.
 *   <li>Corpo JSON {@code SignupRequest}: {@code name}, {@code email}, {@code phone},
 *       {@code password} e um objeto aninhado {@code address} com {@code zipCode}, {@code street},
 *       {@code number}, {@code complement} (opcional), {@code district}, {@code city},
 *       {@code state}.
 *   <li>Sucesso — sempre {@code 202} sem corpo ({@code AcceptedNeutral}), exista o e-mail ou não.
 *   <li>Erro de validação — {@code 400 application/problem+json} {@code ValidationProblem} com
 *       {@code errors[].field} no caminho do campo, ex. {@code "password"},
 *       {@code "address.zipCode"} (validação Bean Validation no DTO, com {@code @Valid} em cascata
 *       no {@code address}).
 * </ul>
 *
 * <p><strong>Decisão do orquestrador:</strong> o e-mail é normalizado para minúsculas antes de
 * gravar e de comparar; {@code PESSOA@TESTE.COM} é o mesmo e-mail que {@code pessoa@teste.com}.
 *
 * <p>O banco não é limpo entre testes: cada teste usa um e-mail único, e as consultas ao Mailpit
 * filtram pelo destinatário.
 */
@AutoConfigureMockMvc
class SignupTest extends IntegrationTest {

    private static final String SIGNUP = "/api/v1/auth/signup";

    private static final String ASSUNTO_CONFIRMACAO = "Cuida+: seu código de confirmação";
    private static final String ASSUNTO_DUPLICADO = "Cuida+: tentativa de cadastro com o seu e-mail";

    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);
    private static final Pattern CODIGO_SEIS_DIGITOS = Pattern.compile("\\b(\\d{6})\\b");

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpaCaixa() {
        mailpit.deleteAll();
    }

    // ============================================================ AC-001.1 cadastro novo

    @Test
    @DisplayName("AC-001.1 cadastro novo responde 202 sem corpo, conforme o contrato")
    void ac_001_1_cadastro_novo_responde_202_conforme_contrato() throws Exception {
        mockMvc.perform(signup(requisicaoValida(emailUnico()))).andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andExpect(ContractValidation.matchesContract());
    }

    @Test
    @DisplayName("AC-001.1 cadastro novo cria exatamente uma conta em AGUARDANDO_CONFIRMACAO_EMAIL (email_verified_at nulo)")
    void ac_001_1_cadastro_novo_cria_conta_aguardando_confirmacao() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        assertThat(contasCom(email)).as("exatamente uma conta com o e-mail").isEqualTo(1);
        Integer naoConfirmadas = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ? AND email_verified_at IS NULL", Integer.class, email);
        assertThat(naoConfirmadas).as("D-33: email_verified_at nulo => AGUARDANDO_CONFIRMACAO_EMAIL").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-001.1 cadastro novo grava nome, telefone e endereço informados")
    void ac_001_1_cadastro_novo_grava_dados_informados() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        Map<String, Object> linha = jdbc.queryForMap(
                """
                SELECT name, phone, address_zip_code, address_street, address_number, address_complement,
                       address_district, address_city, address_state
                  FROM users WHERE email = ?
                """,
                email);
        assertThat(linha)
                .containsEntry("name", "Maria da Silva")
                .containsEntry("phone", "21987654321")
                .containsEntry("address_zip_code", "22041001")
                .containsEntry("address_street", "Rua Barata Ribeiro")
                .containsEntry("address_number", "370")
                .containsEntry("address_complement", "Apto 802")
                .containsEntry("address_district", "Copacabana")
                .containsEntry("address_city", "Rio de Janeiro")
                .containsEntry("address_state", "RJ");
    }

    @Test
    @DisplayName("AC-001.1 / D-14 a senha é guardada só como hash BCrypt, nunca em texto")
    void ac_001_1_senha_guardada_como_hash_bcrypt() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = requisicaoValida(email);

        mockMvc.perform(signup(req)).andExpect(status().isAccepted());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).isNotEqualTo(req.get("password")).doesNotContain((String) req.get("password"));
        assertThat(hash).as("BCrypt (D-14)").matches("^\\$2[aby]?\\$\\d{2}\\$.{53}$");
    }

    @Test
    @DisplayName("AC-001.1 cadastro novo gera um código CONFIRMACAO_EMAIL para a conta")
    void ac_001_1_cadastro_novo_gera_codigo_de_confirmacao() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        assertThat(codigosDeConfirmacaoDe(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-001.1 cadastro novo envia ao e-mail informado a mensagem com o código de confirmação")
    void ac_001_1_cadastro_novo_envia_email_com_codigo() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO)).hasSize(1));
        MailpitClient.Detail detalhe = mailpit.message(mensagensPara(email, ASSUNTO_CONFIRMACAO).getFirst().id());
        assertThat(CODIGO_SEIS_DIGITOS.matcher(detalhe.text()).find())
                .as("corpo traz um código de 6 dígitos")
                .isTrue();
    }

    @Test
    @DisplayName("AC-001.1 / D-04 o código enviado por e-mail não fica gravado em texto no banco")
    void ac_001_1_codigo_guardado_so_como_hash() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO)).hasSize(1));
        String texto = mailpit.message(mensagensPara(email, ASSUNTO_CONFIRMACAO).getFirst().id()).text();
        Matcher m = CODIGO_SEIS_DIGITOS.matcher(texto);
        assertThat(m.find()).isTrue();
        String codigo = m.group(1);

        String hash = jdbc.queryForObject(
                """
                SELECT o.code_hash FROM otp_codes o JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.purpose = 'CONFIRMACAO_EMAIL'
                """,
                String.class,
                email);
        assertThat(hash).isNotEqualTo(codigo).doesNotContain(codigo);
    }

    @Test
    @DisplayName("AC-001.1 (decisão do orquestrador) e-mail é gravado em minúsculas")
    void ac_001_1_email_gravado_em_minusculas() throws Exception {
        String email = emailUnico();

        mockMvc.perform(signup(requisicaoValida(email.toUpperCase(Locale.ROOT)))).andExpect(status().isAccepted());

        assertThat(jdbc.queryForList("SELECT email FROM users WHERE lower(email) = ?", String.class, email))
                .containsExactly(email);
    }

    // ============================================================ AC-011.1 casos válidos de endereço

    @Test
    @DisplayName("AC-011.1 número \"S/N\" é aceito")
    void ac_011_1_numero_sem_numero_e_aceito() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = comEndereco(requisicaoValida(email), "number", "S/N");

        mockMvc.perform(signup(req)).andExpect(status().isAccepted()).andExpect(ContractValidation.matchesContract());

        assertThat(jdbc.queryForObject("SELECT address_number FROM users WHERE email = ?", String.class, email))
                .isEqualTo("S/N");
    }

    @Test
    @DisplayName("AC-011.1 complemento é opcional (ausente)")
    void ac_011_1_complemento_ausente_e_aceito() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = semCampoDoEndereco(requisicaoValida(email), "complement");

        mockMvc.perform(signup(req)).andExpect(status().isAccepted()).andExpect(ContractValidation.matchesContract());

        assertThat(contasCom(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-001.3 senha com exatamente 8 caracteres, letra e número é aceita")
    void ac_001_3_senha_com_8_caracteres_letra_e_numero_e_aceita() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = com(requisicaoValida(email), "password", "abcdef12");

        mockMvc.perform(signup(req)).andExpect(status().isAccepted());

        assertThat(contasCom(email)).isEqualTo(1);
    }

    // ============================================================ AC-001.4 e-mail repetido

    @Test
    @DisplayName("AC-001.4 / D-15 segunda tentativa com o mesmo e-mail recebe resposta idêntica à primeira")
    void ac_001_4_email_repetido_recebe_resposta_identica() throws Exception {
        String email = emailUnico();

        MvcResult primeira = mockMvc.perform(signup(requisicaoValida(email)))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        MvcResult segunda = mockMvc.perform(signup(requisicaoValida(email)))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(primeira.getResponse().getStatus()).isEqualTo(202);
        assertThat(segunda.getResponse().getStatus()).isEqualTo(primeira.getResponse().getStatus());
        assertThat(segunda.getResponse().getContentAsString()).isEqualTo(primeira.getResponse().getContentAsString());
        assertThat(segunda.getResponse().getHeader(HttpHeaders.CONTENT_TYPE))
                .isEqualTo(primeira.getResponse().getHeader(HttpHeaders.CONTENT_TYPE));
        assertThat(segunda.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(primeira.getResponse().getHeaderNames());
    }

    @Test
    @DisplayName("AC-001.4 segunda tentativa com o mesmo e-mail não cria conta nem código novo")
    void ac_001_4_email_repetido_nao_cria_nada() throws Exception {
        String email = emailUnico();
        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        Map<String, Object> outraPessoa = com(requisicaoValida(email), "name", "Outra Pessoa");
        outraPessoa.put("password", "outraSenha99");
        mockMvc.perform(signup(outraPessoa)).andExpect(status().isAccepted());

        assertThat(contasCom(email)).as("continua existindo só uma conta").isEqualTo(1);
        assertThat(codigosDeConfirmacaoDe(email)).as("nenhum código novo").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE email = ?", String.class, email))
                .as("a conta existente não é alterada")
                .isEqualTo("Maria da Silva");
    }

    @Test
    @DisplayName("AC-001.4 segunda tentativa com o mesmo e-mail avisa o dono por e-mail, sem novo código")
    void ac_001_4_email_repetido_avisa_o_dono() throws Exception {
        String email = emailUnico();
        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO)).hasSize(1));

        mockMvc.perform(signup(requisicaoValida(email))).andExpect(status().isAccepted());

        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_DUPLICADO)).hasSize(1));
        assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO))
                .as("a segunda tentativa não manda outro código")
                .hasSize(1);
    }

    @Test
    @DisplayName("AC-001.4 (decisão do orquestrador) e-mail repetido com maiúsculas diferentes é o mesmo e-mail: 202 idêntico, nada criado, aviso enviado")
    void ac_001_4_email_repetido_ignora_maiusculas_e_minusculas() throws Exception {
        String email = emailUnico();
        MvcResult primeira = mockMvc.perform(signup(requisicaoValida(email))).andReturn();
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO)).hasSize(1));

        MvcResult segunda = mockMvc.perform(signup(requisicaoValida(email.toUpperCase(Locale.ROOT))))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(segunda.getResponse().getStatus()).isEqualTo(202).isEqualTo(primeira.getResponse().getStatus());
        assertThat(segunda.getResponse().getContentAsString()).isEqualTo(primeira.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?", Integer.class, email))
                .as("nenhuma conta nova por diferença de caixa")
                .isEqualTo(1);
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_DUPLICADO)).hasSize(1));
    }

    @Test
    @DisplayName("AC-001.4 e-mail de conta já ATIVA (outro status): 202, nada criado nem alterado, dono avisado")
    void ac_001_4_email_de_conta_ativa_nao_cria_nada_e_avisa() throws Exception {
        String email = emailUnico();
        UUID existente = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO users (id, name, email, phone, address_zip_code, address_street, address_number,
                                   address_district, address_city, address_state, password_hash, email_verified_at)
                VALUES (?, 'Dono Original', ?, '21900000000', '01001000', 'Praça da Sé', '1',
                        'Sé', 'São Paulo', 'SP', 'hash-original', now())
                """,
                existente,
                email);

        mockMvc.perform(signup(requisicaoValida(email)))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andExpect(ContractValidation.matchesContract());

        assertThat(contasCom(email)).isEqualTo(1);
        Map<String, Object> linha =
                jdbc.queryForMap("SELECT id, name, password_hash, email_verified_at FROM users WHERE email = ?", email);
        assertThat(linha.get("id")).isEqualTo(existente);
        assertThat(linha).containsEntry("name", "Dono Original").containsEntry("password_hash", "hash-original");
        assertThat(linha.get("email_verified_at")).as("status da conta não muda").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_codes WHERE user_id = ?", Integer.class, existente))
                .isZero();
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mensagensPara(email, ASSUNTO_DUPLICADO)).hasSize(1));
        assertThat(mensagensPara(email, ASSUNTO_CONFIRMACAO)).isEmpty();
    }

    // ============================================================ AC-001.3 senha fraca

    @ParameterizedTest(name = "AC-001.3 senha \"{0}\" é recusada com erro em password")
    @ValueSource(strings = {"somenteletras", "12345678", "abc1234", "a1"})
    @DisplayName("AC-001.3 senha fraca (sem número, sem letra ou com menos de 8 caracteres) → 400 em password")
    void ac_001_3_senha_fraca_e_recusada(String senha) throws Exception {
        String email = emailUnico();

        JsonNode corpo = esperaValidationProblem(com(requisicaoValida(email), "password", senha), "password");

        assertThat(corpo.toString()).as("P5: a senha não ecoa no erro").doesNotContain(senha);
        assertThat(contasCom(email)).isZero();
    }

    @Test
    @DisplayName("AC-001.3 senha ausente → 400 em password, nada criado")
    void ac_001_3_senha_ausente_e_recusada() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = requisicaoValida(email);
        req.remove("password");

        esperaValidationProblem(req, "password");

        assertThat(contasCom(email)).isZero();
    }

    @Test
    @DisplayName("AC-001.3 senha fraca não envia e-mail algum")
    void ac_001_3_senha_fraca_nao_envia_email() throws Exception {
        String email = emailUnico();

        esperaValidationProblem(com(requisicaoValida(email), "password", "somenteletras"), "password");

        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(mensagensPara(email, null)).isEmpty());
    }

    // ============================================================ AC-011.2 CEP e UF

    @ParameterizedTest(name = "AC-011.2 CEP \"{0}\" é recusado em address.zipCode")
    @ValueSource(strings = {"2204100", "220410012", "22041-001", "2204100A", ""})
    @DisplayName("AC-011.2 CEP sem exatamente 8 dígitos numéricos → 400 em address.zipCode, nada criado")
    void ac_011_2_cep_invalido_e_recusado(String cep) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(comEndereco(requisicaoValida(email), "zipCode", cep), "address.zipCode");

        assertThat(contasCom(email)).isZero();
    }

    @ParameterizedTest(name = "AC-011.2 UF \"{0}\" é recusada em address.state")
    @ValueSource(strings = {"XX", "rj", "RJX", ""})
    @DisplayName("AC-011.2 UF fora das 27 unidades da federação → 400 em address.state, nada criado")
    void ac_011_2_uf_invalida_e_recusada(String uf) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(comEndereco(requisicaoValida(email), "state", uf), "address.state");

        assertThat(contasCom(email)).isZero();
    }

    // ============================================================ AC-011.1 campos obrigatórios e número

    @ParameterizedTest(name = "AC-011.1 address.{0} ausente é recusado")
    @ValueSource(strings = {"zipCode", "street", "number", "district", "city", "state"})
    @DisplayName("AC-011.1 campo obrigatório do endereço ausente → 400 no campo, nada criado")
    void ac_011_1_campo_obrigatorio_do_endereco_ausente_e_recusado(String campo) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(semCampoDoEndereco(requisicaoValida(email), campo), "address." + campo);

        assertThat(contasCom(email)).isZero();
    }

    @ParameterizedTest(name = "AC-011.1 address.{0} em branco é recusado")
    @ValueSource(strings = {"street", "district", "city"})
    @DisplayName("AC-011.1 campo obrigatório do endereço em branco → 400 no campo, nada criado")
    void ac_011_1_campo_obrigatorio_do_endereco_em_branco_e_recusado(String campo) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(comEndereco(requisicaoValida(email), campo, "   "), "address." + campo);

        assertThat(contasCom(email)).isZero();
    }

    @ParameterizedTest(name = "AC-011.1 número \"{0}\" é recusado em address.number")
    @ValueSource(strings = {"abc", "s/n", "SN", "1234567", "12-A", ""})
    @DisplayName("AC-011.1 número que não é dígitos (com letra opcional) nem \"S/N\" → 400 em address.number")
    void ac_011_1_numero_invalido_e_recusado(String numero) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(comEndereco(requisicaoValida(email), "number", numero), "address.number");

        assertThat(contasCom(email)).isZero();
    }

    @Test
    @DisplayName("AC-011.1 endereço ausente → 400 em address, nada criado")
    void ac_011_1_endereco_ausente_e_recusado() throws Exception {
        String email = emailUnico();
        Map<String, Object> req = requisicaoValida(email);
        req.remove("address");

        esperaValidationProblem(req, "address");

        assertThat(contasCom(email)).isZero();
    }

    @Test
    @DisplayName("AC-011.1 complemento com mais de 80 caracteres → 400 em address.complement")
    void ac_011_1_complemento_longo_demais_e_recusado() throws Exception {
        String email = emailUnico();

        esperaValidationProblem(
                comEndereco(requisicaoValida(email), "complement", "x".repeat(81)), "address.complement");

        assertThat(contasCom(email)).isZero();
    }

    // ============================================================ AC-001.1 demais campos obrigatórios

    @ParameterizedTest(name = "AC-001.1 nome \"{0}\" é recusado em name")
    @ValueSource(strings = {"", "A"})
    @DisplayName("AC-001.1 nome vazio ou com menos de 2 caracteres → 400 em name")
    void ac_001_1_nome_invalido_e_recusado(String nome) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(com(requisicaoValida(email), "name", nome), "name");

        assertThat(contasCom(email)).isZero();
    }

    @ParameterizedTest(name = "AC-001.1 telefone \"{0}\" é recusado em phone")
    @ValueSource(strings = {"123456789", "219876543210", "(21)98765-4321", ""})
    @DisplayName("AC-001.1 telefone fora de DDD + 8/9 dígitos (10-11 dígitos) → 400 em phone")
    void ac_001_1_telefone_invalido_e_recusado(String telefone) throws Exception {
        String email = emailUnico();

        esperaValidationProblem(com(requisicaoValida(email), "phone", telefone), "phone");

        assertThat(contasCom(email)).isZero();
    }

    @ParameterizedTest(name = "AC-001.1 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {"sem-arroba", "@teste.com", ""})
    @DisplayName("AC-001.1 e-mail malformado → 400 em email")
    void ac_001_1_email_malformado_e_recusado(String email) throws Exception {
        Integer antes = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        esperaValidationProblem(requisicaoValida(email), "email");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(antes);
    }

    // ============================================================ apoio

    private static String emailUnico() {
        return "signup-" + UUID.randomUUID() + "@teste.com";
    }

    /** Corpo válido segundo {@code SignupRequest} do contrato. */
    private static Map<String, Object> requisicaoValida(String email) {
        Map<String, Object> endereco = new LinkedHashMap<>();
        endereco.put("zipCode", "22041001");
        endereco.put("street", "Rua Barata Ribeiro");
        endereco.put("number", "370");
        endereco.put("complement", "Apto 802");
        endereco.put("district", "Copacabana");
        endereco.put("city", "Rio de Janeiro");
        endereco.put("state", "RJ");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Maria da Silva");
        req.put("email", email);
        req.put("phone", "21987654321");
        req.put("address", endereco);
        req.put("password", "senhaForte1");
        return req;
    }

    private static Map<String, Object> com(Map<String, Object> req, String campo, Object valor) {
        req.put(campo, valor);
        return req;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> comEndereco(Map<String, Object> req, String campo, Object valor) {
        ((Map<String, Object>) req.get("address")).put(campo, valor);
        return req;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> semCampoDoEndereco(Map<String, Object> req, String campo) {
        ((Map<String, Object>) req.get("address")).remove(campo);
        return req;
    }

    private org.springframework.test.web.servlet.RequestBuilder signup(Map<String, Object> corpo) throws Exception {
        return post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} do contrato e um item de {@code errors} com {@code field}
     * exatamente igual a {@code campoEsperado}.
     *
     * <p>Aqui não se usa {@link ContractValidation#matchesContract()} porque ele também valida a
     * <em>requisição</em>, que é propositalmente inválida; a resposta é conferida pelo schema
     * {@code ValidationProblem} extraído do mesmo {@code openapi.yaml} ({@link ContractSchemas}).
     */
    private JsonNode esperaValidationProblem(Map<String, Object> corpo, String campoEsperado) throws Exception {
        MvcResult result = mockMvc.perform(signup(corpo))
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

    private int contasCom(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private int codigosDeConfirmacaoDe(String email) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM otp_codes o JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.purpose = 'CONFIRMACAO_EMAIL'
                """,
                Integer.class,
                email);
    }

    /** Mensagens do Mailpit para {@code email} (sem diferenciar caixa), filtradas pelo assunto se não nulo. */
    private List<MailpitClient.Summary> mensagensPara(String email, String assunto) {
        return mailpit.messages().stream()
                .filter(m -> m.to().stream().anyMatch(to -> to.equalsIgnoreCase(email)))
                .filter(m -> assunto == null || assunto.equals(m.subject()))
                .toList();
    }
}
