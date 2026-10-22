package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
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
 * T-022 "Pronto quando": {@code POST /api/v1/auth/verify-email} (contrato {@code operationId:
 * verifyEmail}) ativa a conta e abre sessão, <strong>sem criar grupo</strong>. ACs: 001.2 (o
 * criador passa a {@code ATIVO}; o grupo só nasce no cadastro do idoso), 005.1 (código de 6
 * dígitos que expira em 15 min), 005.2 (5 tentativas erradas invalidam o código). Decisões: D-05
 * rev. 2 ({@code verify-email} só grava {@code email_verified_at}; nenhuma membership/grupo),
 * D-04 (o código só existe como hash no banco), D-10 (relógio controlado), D-11/D-12 (sessão:
 * JWT de 15 min no corpo + refresh token opaco de 7 dias em cookie {@code HttpOnly; Secure;
 * SameSite=Strict}), D-33 (estado derivado de {@code email_verified_at}).
 *
 * <p>Teste de caixa-preta pela rota HTTP real (contexto Spring completo, MockMvc), conferindo o
 * efeito no banco por SQL puro ({@link JdbcTemplate}). O cenário inicial — conta não confirmada
 * com um código {@code CONFIRMACAO_EMAIL} pendente — é montado com os serviços já existentes
 * ({@link User#signup}, {@link OtpService#issue}, repositórios), do mesmo jeito que o cadastro
 * real (T-020) faz, só para obter o código em texto puro sem ler o Mailpit.
 *
 * <p><strong>Desenho esperado da API</strong> (do {@code openapi.yaml}):
 *
 * <ul>
 *   <li>Rota pública ({@code security: []}), corpo {@code VerifyEmailRequest {email, code}} com
 *       {@code code} casando {@code ^\d{6}$}.
 *   <li>{@code 200} {@code SessionStarted}: corpo {@code AccessToken {accessToken, tokenType:
 *       "Bearer", expiresIn: 900}} e {@code Set-Cookie refresh_token=...; Max-Age=604800;
 *       HttpOnly; Secure; SameSite=Strict}.
 *   <li>{@code 400} {@code InvalidCodeProblem} ({@code Problem} + {@code remainingAttempts}) para
 *       código de formato válido mas errado. A tentativa errada fica gravada mesmo com a resposta
 *       de erro (senão o contador nunca andaria).
 *   <li>{@code 410} {@code Problem} genérico (sem {@code remainingAttempts}) para código expirado,
 *       esgotado, já consumido, conta sem código pendente ou e-mail sem conta.
 *   <li>{@code 400} {@code ValidationProblem} para corpo malformado (handler de validação já
 *       existente, T-007).
 * </ul>
 *
 * <p><strong>Decisão do orquestrador:</strong> o contrato não prevê {@code 404} nesta rota; e-mail
 * sem conta e conta sem código pendente respondem {@code 410}, iguais a um código expirado —
 * "nada a confirmar aqui, peça um código novo" — sem revelar se a conta existe (P5) e sem inventar
 * um contador de tentativas para um e-mail desconhecido.
 *
 * <p><strong>Leitura adotada para a 5ª tentativa errada</strong> (AC-005.2 + {@link
 * OtpCode#verify}): a 5ª errada ainda é respondida como código errado, {@code 400} com {@code
 * remainingAttempts = 0} (o schema permite {@code minimum: 0}); a partir da 6ª, com código certo ou
 * errado, o código está esgotado e a resposta é {@code 410}.
 *
 * <p>O banco não é limpo entre testes: cada teste usa um e-mail único.
 */
@AutoConfigureMockMvc
class VerifyEmailTest extends IntegrationTest {

    private static final String VERIFY_EMAIL = "/api/v1/auth/verify-email";

    /** D-11: access token de 15 min, informado em segundos no corpo. */
    private static final int EXPIRES_IN_SEGUNDOS = 900;

    /** D-11: refresh token novo vale 7 dias = 604800 s; o relógio não anda durante a requisição. */
    private static final long MAX_AGE_REFRESH_SEGUNDOS = Duration.ofDays(7).toSeconds();

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    OtpCodeRepository otpCodes;

    @Autowired
    OtpService otpService;

    @Autowired
    JwtService jwtService;

    // ============================================================ AC-001.2 código certo

    /**
     * Caminho feliz completo do ponto de vista do contrato: e-mail e código certos devolvem 200 e
     * tanto a requisição quanto a resposta (status, corpo {@code AccessToken}, cabeçalhos) casam
     * com {@code verifyEmail} no {@code openapi.yaml}.
     */
    @Test
    @DisplayName("AC-001.2 código certo responde 200 SessionStarted, conforme o contrato")
    void ac_001_2_codigo_certo_responde_200_conforme_contrato() throws Exception {
        Cenario c = contaComCodigoPendente();

        mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-001.2: confirmar o e-mail torna o criador {@code ATIVO}. Pela D-33 isso é exatamente
     * {@code users.email_verified_at} deixar de ser nulo — e o instante gravado é o "agora" do
     * relógio injetado (D-10), não o relógio da máquina.
     */
    @Test
    @DisplayName("AC-001.2 / D-33 código certo grava users.email_verified_at com o instante do relógio")
    void ac_001_2_codigo_certo_ativa_a_conta() throws Exception {
        Cenario c = contaComCodigoPendente();
        assertThat(emailVerifiedAtDe(c.userId())).as("antes: AGUARDANDO_CONFIRMACAO_EMAIL").isNull();

        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        java.sql.Timestamp verificadoEm = emailVerifiedAtDe(c.userId());
        assertThat(verificadoEm).as("depois: ATIVO (D-33)").isNotNull();
        assertThat(verificadoEm.toInstant()).as("D-10: instante do relógio injetado").isEqualTo(clock.instant());
    }

    /**
     * "Pronto quando" da T-022 / D-05 rev. 2: a confirmação NÃO cria grupo nem vínculo — o grupo só
     * nasce em {@code POST /elders} (tarefa futura). Confere que não existe nenhuma linha em
     * {@code memberships} para o usuário logo depois do 200.
     */
    @Test
    @DisplayName("AC-001.2 / D-05 rev. 2 código certo não cria membership (o grupo nasce só no cadastro do idoso)")
    void ac_001_2_codigo_certo_nao_cria_membership() throws Exception {
        Cenario c = contaComCodigoPendente();

        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE user_id = ?", Integer.class, c.userId()))
                .as("D-05 rev. 2: nenhuma membership para o criador recém-confirmado")
                .isZero();
    }

    /**
     * AC-001.2 "é levado ao cadastro do idoso" exige sessão aberta (D-11): o corpo traz um access
     * token Bearer de 15 min, e esse JWT é válido e identifica exatamente a conta confirmada
     * (subject = {@code users.id}), conferido pelo próprio {@link JwtService} da T-021.
     */
    @Test
    @DisplayName("AC-001.2 / D-11 código certo devolve AccessToken Bearer de 900 s cujo subject é a conta confirmada")
    void ac_001_2_codigo_certo_devolve_access_token_da_conta() throws Exception {
        Cenario c = contaComCodigoPendente();

        MvcResult result = mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode corpo = json.readTree(result.getResponse().getContentAsString());
        assertThat(corpo.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(corpo.path("expiresIn").asInt()).isEqualTo(EXPIRES_IN_SEGUNDOS);
        String accessToken = corpo.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(jwtService.parseSubject(accessToken, clock))
                .as("o JWT pertence à conta que acabou de confirmar o e-mail")
                .isEqualTo(c.userId());
    }

    /**
     * D-11/D-12: a sessão também ganha um refresh token opaco no cookie {@code refresh_token}, com
     * os atributos de segurança exigidos ({@code HttpOnly}, {@code Secure}, {@code
     * SameSite=Strict}) e {@code Max-Age} de 7 dias (604800 s, o mesmo do contrato).
     */
    @Test
    @DisplayName("AC-001.2 / D-12 código certo envia Set-Cookie refresh_token HttpOnly, Secure, SameSite=Strict, Max-Age=604800")
    void ac_001_2_codigo_certo_envia_cookie_de_refresh_token() throws Exception {
        Cenario c = contaComCodigoPendente();

        MvcResult result = mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isOk())
                .andReturn();

        String cookie = cookieRefreshToken(result);
        assertThat(cookie).as("Set-Cookie refresh_token presente").isNotNull();
        assertThat(valorDoCookie(cookie)).as("valor opaco não vazio").isNotBlank();
        assertThat(cookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .contains("Max-Age=" + MAX_AGE_REFRESH_SEGUNDOS);
    }

    /**
     * D-11: o cookie corresponde a um refresh token persistido para a conta — primeiro token de uma
     * família nova ({@code family_id = id}), não revogado. Prova que a sessão foi aberta pelo
     * {@link RefreshTokenService#issue} e não com um valor qualquer.
     */
    @Test
    @DisplayName("AC-001.2 / D-11 código certo grava exatamente um refresh token novo (família própria) para a conta")
    void ac_001_2_codigo_certo_grava_refresh_token_da_conta() throws Exception {
        Cenario c = contaComCodigoPendente();

        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        List<Map<String, Object>> tokens = jdbc.queryForList(
                "SELECT id, family_id, revoked_at FROM refresh_tokens WHERE user_id = ?", c.userId());
        assertThat(tokens).as("um refresh token para a conta").hasSize(1);
        assertThat(tokens.getFirst().get("family_id")).as("D-11: família nova").isEqualTo(tokens.getFirst().get("id"));
        assertThat(tokens.getFirst().get("revoked_at")).isNull();
    }

    /**
     * AC-005 / D-07: o código é de uso único — o acerto marca o {@code OtpCode} como consumido
     * ({@code consumed_at} preenchido), e isso fica persistido.
     */
    @Test
    @DisplayName("AC-005.1 código certo marca o código como consumido (uso único)")
    void ac_005_1_codigo_certo_marca_codigo_consumido() throws Exception {
        Cenario c = contaComCodigoPendente();

        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "SELECT consumed_at FROM otp_codes WHERE id = ?", java.sql.Timestamp.class, c.otpId()))
                .isNotNull();
    }

    /**
     * Decisão do orquestrador na T-020: o e-mail é gravado em minúsculas e comparado sem diferença
     * de caixa. Confirmar com o e-mail digitado em maiúsculas é a mesma conta e funciona.
     */
    @Test
    @DisplayName("AC-001.2 (decisão do orquestrador, T-020) e-mail em maiúsculas confirma a mesma conta")
    void ac_001_2_email_em_maiusculas_confirma_a_mesma_conta() throws Exception {
        Cenario c = contaComCodigoPendente();

        mockMvc.perform(verificar(c.email().toUpperCase(Locale.ROOT), c.codigo()))
                .andExpect(status().isOk());

        assertThat(emailVerifiedAtDe(c.userId())).isNotNull();
    }

    // ============================================================ AC-005.2 código errado

    /**
     * AC-005.2: a primeira tentativa errada responde 400 {@code InvalidCodeProblem} conforme o
     * contrato, com 4 tentativas restantes (5 no total, 1 gasta).
     */
    @Test
    @DisplayName("AC-005.2 código errado responde 400 InvalidCodeProblem com remainingAttempts=4, conforme o contrato")
    void ac_005_2_codigo_errado_responde_400_com_4_tentativas_restantes() throws Exception {
        Cenario c = contaComCodigoPendente();

        MvcResult result = mockMvc.perform(verificar(c.email(), codigoErrado(c.codigo())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode corpo = json.readTree(result.getResponse().getContentAsString());
        esperaInvalidCodeProblem(corpo);
        assertThat(corpo.path("remainingAttempts").isInt()).isTrue();
        assertThat(corpo.path("remainingAttempts").asInt()).isEqualTo(4);
    }

    /**
     * AC-005.2 / D-33: código errado não ativa a conta, não abre sessão (nenhum cookie, nenhum
     * refresh token gravado) e a tentativa fica gravada em {@code otp_codes.attempts} — mesmo
     * terminando em erro, senão o limite de 5 nunca seria atingido.
     */
    @Test
    @DisplayName("AC-005.2 código errado não ativa a conta, não abre sessão e grava a tentativa")
    void ac_005_2_codigo_errado_nao_ativa_nem_abre_sessao() throws Exception {
        Cenario c = contaComCodigoPendente();

        MvcResult result = mockMvc.perform(verificar(c.email(), codigoErrado(c.codigo())))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(emailVerifiedAtDe(c.userId())).as("continua AGUARDANDO_CONFIRMACAO_EMAIL").isNull();
        assertThat(cookieRefreshToken(result)).as("sem cookie de sessão").isNull();
        assertThat(refreshTokensDe(c.userId())).as("nenhuma sessão gravada").isZero();
        assertThat(tentativasDe(c.otpId())).as("a tentativa errada é persistida").isEqualTo(1);
    }

    /**
     * AC-005.2: o contador decresce a cada tentativa errada, de requisição em requisição (prova que
     * a tentativa é persistida, não só contada em memória), até 0 na 5ª errada.
     */
    @Test
    @DisplayName("AC-005.2 tentativas erradas seguidas decrescem remainingAttempts 4, 3, 2, 1, 0")
    void ac_005_2_tentativas_erradas_decrescem_o_contador() throws Exception {
        Cenario c = contaComCodigoPendente();
        String errado = codigoErrado(c.codigo());

        // Linha a linha: tentativa N errada -> remainingAttempts = 5 - N.
        int[] esperado = {
            4, // 1ª errada
            3, // 2ª errada
            2, // 3ª errada
            1, // 4ª errada
            0  // 5ª errada: ainda é "código errado" (400), mas não sobra nenhuma tentativa
        };
        for (int i = 0; i < esperado.length; i++) {
            MvcResult result = mockMvc.perform(verificar(c.email(), errado))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            JsonNode corpo = json.readTree(result.getResponse().getContentAsString());
            esperaInvalidCodeProblem(corpo);
            assertThat(corpo.path("remainingAttempts").asInt())
                    .as("%dª tentativa errada", i + 1)
                    .isEqualTo(esperado[i]);
        }

        assertThat(tentativasDe(c.otpId())).isEqualTo(5);
        assertThat(emailVerifiedAtDe(c.userId())).isNull();
    }

    // ============================================================ AC-005.2 código esgotado

    /**
     * AC-005.2 "depois de 5 tentativas erradas, o código é invalidado e é preciso pedir um novo":
     * uma 6ª tentativa, agora com o código CERTO, responde 410 (não 400, não 200) conforme o
     * contrato, e a conta continua sem confirmação e sem sessão.
     */
    @Test
    @DisplayName("AC-005.2 após 5 tentativas erradas, o código certo responde 410 e não ativa a conta")
    void ac_005_2_codigo_certo_apos_5_erradas_responde_410() throws Exception {
        Cenario c = contaComCodigoPendente();
        String errado = codigoErrado(c.codigo());

        // Etapa 1: esgota as 5 tentativas.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(verificar(c.email(), errado)).andExpect(status().isBadRequest());
        }

        // Etapa 2: o código certo já não vale mais.
        MvcResult result = mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        // Etapa 3: nada mudou na conta.
        assertThat(cookieRefreshToken(result)).isNull();
        assertThat(emailVerifiedAtDe(c.userId())).isNull();
        assertThat(refreshTokensDe(c.userId())).isZero();
    }

    /**
     * AC-005.2: código esgotado responde 410 também para um código errado (6ª errada), sem
     * {@code remainingAttempts} no corpo (é {@code Problem} genérico) e sem gastar outra tentativa
     * ({@link OtpCode#verify} recusa pelo estado antes de comparar).
     */
    @Test
    @DisplayName("AC-005.2 6ª tentativa errada responde 410 sem remainingAttempts e não conta tentativa")
    void ac_005_2_sexta_tentativa_errada_responde_410_sem_contador() throws Exception {
        Cenario c = contaComCodigoPendente();
        String errado = codigoErrado(c.codigo());
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(verificar(c.email(), errado)).andExpect(status().isBadRequest());
        }

        MvcResult result = mockMvc.perform(verificar(c.email(), errado))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode corpo = json.readTree(result.getResponse().getContentAsString());
        assertThat(corpo.has("remainingAttempts")).as("410 é Problem genérico").isFalse();
        assertThat(tentativasDe(c.otpId())).isEqualTo(5);
    }

    // ============================================================ AC-005.1 código expirado

    /**
     * AC-005.1: o código vale 15 minutos. Exatamente no 15º minuto ele já expirou ({@link
     * OtpCode#verify} recusa quando {@code agora >= expiresAt}); o código certo responde 410
     * conforme o contrato e não ativa a conta.
     */
    @Test
    @DisplayName("AC-005.1 código certo com 15 minutos exatos responde 410 e não ativa a conta")
    void ac_005_1_codigo_certo_aos_15_minutos_responde_410() throws Exception {
        Cenario c = contaComCodigoPendente();
        clock.advance(Duration.ofMinutes(15));

        MvcResult result = mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNull();
        assertThat(emailVerifiedAtDe(c.userId())).isNull();
    }

    /**
     * AC-005.1: bem depois do prazo (1 hora) continua 410, e um código errado num código expirado
     * também é 410 (o estado decide antes do valor) sem contar tentativa.
     */
    @Test
    @DisplayName("AC-005.1 código expirado responde 410 mesmo com código errado, sem contar tentativa")
    void ac_005_1_codigo_expirado_com_codigo_errado_responde_410() throws Exception {
        Cenario c = contaComCodigoPendente();
        clock.advance(Duration.ofHours(1));

        mockMvc.perform(verificar(c.email(), codigoErrado(c.codigo())))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());

        assertThat(tentativasDe(c.otpId())).isZero();
    }

    /**
     * AC-005.1, lado de dentro do prazo: 1 segundo antes dos 15 minutos o código certo ainda
     * confirma a conta (200). Garante que o 410 acima é o prazo, e não outra coisa.
     */
    @Test
    @DisplayName("AC-005.1 código certo 1 segundo antes de 15 minutos ainda confirma a conta")
    void ac_005_1_codigo_certo_antes_de_15_minutos_confirma() throws Exception {
        Cenario c = contaComCodigoPendente();
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));

        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        assertThat(emailVerifiedAtDe(c.userId())).isNotNull();
    }

    // ============================================================ decisão do orquestrador: nada a confirmar

    /**
     * Decisão do orquestrador (P5): e-mail sem conta nenhuma responde 410 {@code Problem} genérico
     * conforme o contrato — sem {@code remainingAttempts} e sem 404 (não previsto no contrato).
     */
    @Test
    @DisplayName("AC-005.1 (decisão do orquestrador, P5) e-mail sem conta responde 410 Problem genérico, conforme o contrato")
    void ac_005_1_email_sem_conta_responde_410() throws Exception {
        String email = emailUnico();

        MvcResult result = mockMvc.perform(verificar(email, "123456"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode corpo = json.readTree(result.getResponse().getContentAsString());
        assertThat(corpo.has("remainingAttempts")).isFalse();
        assertThat(cookieRefreshToken(result)).isNull();
    }

    /**
     * Decisão do orquestrador (P5): a resposta para e-mail sem conta é indistinguível da de um
     * código expirado de uma conta real — mesmo status e mesmo corpo (a rota, e portanto o {@code
     * instance}, é a mesma) —, para não revelar se o e-mail tem conta.
     */
    @Test
    @DisplayName("AC-005.1 (decisão do orquestrador, P5) 410 de e-mail sem conta é idêntico ao de código expirado")
    void ac_005_1_email_sem_conta_e_identico_a_codigo_expirado() throws Exception {
        Cenario c = contaComCodigoPendente();
        clock.advance(Duration.ofMinutes(15));

        MvcResult expirado = mockMvc.perform(verificar(c.email(), c.codigo())).andReturn();
        MvcResult semConta = mockMvc.perform(verificar(emailUnico(), c.codigo())).andReturn();

        assertThat(semConta.getResponse().getStatus()).isEqualTo(410).isEqualTo(expirado.getResponse().getStatus());
        assertThat(json.readTree(semConta.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(expirado.getResponse().getContentAsString()));
    }

    /**
     * Decisão do orquestrador: conta existente que nunca recebeu um código de confirmação não tem
     * nada a confirmar — 410, a conta não é ativada e nenhum código é criado por esta rota.
     */
    @Test
    @DisplayName("AC-005.1 (decisão do orquestrador) conta sem nenhum código emitido responde 410 e não ativa")
    void ac_005_1_conta_sem_codigo_emitido_responde_410() throws Exception {
        User user = users.save(novaConta(emailUnico()));

        mockMvc.perform(verificar(user.getEmail(), "123456"))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());

        assertThat(emailVerifiedAtDe(user.getId())).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM otp_codes WHERE user_id = ?", Integer.class, user.getId()))
                .isZero();
    }

    /**
     * AC-005 (uso único) + decisão do orquestrador: depois de uma confirmação bem-sucedida o código
     * já foi consumido; reapresentá-lo responde 410 e não abre uma segunda sessão.
     */
    @Test
    @DisplayName("AC-005.1 (decisão do orquestrador) reusar o código já confirmado responde 410 e não abre outra sessão")
    void ac_005_1_codigo_ja_usado_responde_410() throws Exception {
        Cenario c = contaComCodigoPendente();

        // Etapa 1: primeira confirmação, bem-sucedida.
        mockMvc.perform(verificar(c.email(), c.codigo())).andExpect(status().isOk());

        // Etapa 2: o mesmo código de novo — não há mais nada pendente.
        MvcResult result = mockMvc.perform(verificar(c.email(), c.codigo()))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        // Etapa 3: só a sessão da primeira confirmação existe.
        assertThat(cookieRefreshToken(result)).isNull();
        assertThat(refreshTokensDe(c.userId())).isEqualTo(1);
    }

    /**
     * Código de outra finalidade não serve para confirmar e-mail: uma conta só com código
     * {@code REDEFINICAO_SENHA} pendente não tem confirmação de e-mail pendente — 410, sem ativar.
     */
    @Test
    @DisplayName("AC-005.1 (decisão do orquestrador) código de REDEFINICAO_SENHA não confirma e-mail: 410")
    void ac_005_1_codigo_de_outra_finalidade_responde_410() throws Exception {
        User user = users.save(novaConta(emailUnico()));
        IssuedOtp emitido = otpService.issue(user.getId(), OtpPurpose.REDEFINICAO_SENHA);
        otpCodes.save(emitido.otp());

        mockMvc.perform(verificar(user.getEmail(), emitido.plainCode()))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());

        assertThat(emailVerifiedAtDe(user.getId())).isNull();
    }

    // ============================================================ corpo inválido

    /**
     * {@code VerifyEmailRequest.code} deve casar {@code ^\d{6}$} (AC-005.1: 6 dígitos). Fora do
     * padrão é erro de validação (400 {@code ValidationProblem} em {@code code}), não uma
     * tentativa errada: o contador do código pendente não anda e a conta não muda.
     */
    @ParameterizedTest(name = "AC-005.1 código \"{0}\" é recusado em code")
    @ValueSource(strings = {
        "12345",   // 5 dígitos
        "1234567", // 7 dígitos
        "abcdef",  // letras
        "12 345",  // espaço no meio
        ""         // vazio
    })
    @DisplayName("AC-005.1 código fora de 6 dígitos → 400 ValidationProblem em code, sem gastar tentativa")
    void ac_005_1_codigo_fora_do_padrao_e_recusado(String codigo) throws Exception {
        Cenario c = contaComCodigoPendente();

        esperaValidationProblem(corpo(c.email(), codigo), "code");

        assertThat(tentativasDe(c.otpId())).as("formato inválido não é tentativa").isZero();
        assertThat(emailVerifiedAtDe(c.userId())).isNull();
    }

    /** E-mail malformado ou ausente é erro de validação (400 {@code ValidationProblem} em {@code email}). */
    @ParameterizedTest(name = "AC-005.1 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {
        "sem-arroba", // sem @
        "@teste.com", // sem parte local
        ""            // vazio
    })
    @DisplayName("AC-005.1 e-mail malformado → 400 ValidationProblem em email")
    void ac_005_1_email_malformado_e_recusado(String email) throws Exception {
        esperaValidationProblem(corpo(email, "123456"), "email");
    }

    /** Campos obrigatórios do {@code VerifyEmailRequest}: sem {@code code} → 400 em {@code code}. */
    @Test
    @DisplayName("AC-005.1 corpo sem code → 400 ValidationProblem em code")
    void ac_005_1_corpo_sem_codigo_e_recusado() throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", emailUnico());

        esperaValidationProblem(corpo, "code");
    }

    /** Campos obrigatórios do {@code VerifyEmailRequest}: sem {@code email} → 400 em {@code email}. */
    @Test
    @DisplayName("AC-005.1 corpo sem email → 400 ValidationProblem em email")
    void ac_005_1_corpo_sem_email_e_recusado() throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("code", "123456");

        esperaValidationProblem(corpo, "email");
    }

    // ============================================================ apoio

    /** Conta não confirmada com um código {@code CONFIRMACAO_EMAIL} pendente e o código em texto. */
    private record Cenario(UUID userId, String email, UUID otpId, String codigo) {}

    /**
     * Monta o estado que o cadastro (T-020) deixa: conta em {@code AGUARDANDO_CONFIRMACAO_EMAIL}
     * e um código de confirmação recém-emitido (hash no banco, texto só em memória — D-04).
     */
    private Cenario contaComCodigoPendente() {
        User user = users.save(novaConta(emailUnico()));
        IssuedOtp emitido = otpService.issue(user.getId(), OtpPurpose.CONFIRMACAO_EMAIL);
        otpCodes.save(emitido.otp());
        return new Cenario(user.getId(), user.getEmail(), emitido.otp().getId(), emitido.plainCode());
    }

    private User novaConta(String email) {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        return User.signup("Maria da Silva", email, "21987654321", endereco, "hash-qualquer", clock);
    }

    private static String emailUnico() {
        return "verify-" + UUID.randomUUID() + "@teste.com";
    }

    /** Outro código de 6 dígitos, garantidamente diferente do certo. */
    private static String codigoErrado(String certo) {
        return String.format("%06d", (Integer.parseInt(certo) + 1) % 1_000_000);
    }

    private static Map<String, Object> corpo(String email, String codigo) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("code", codigo);
        return corpo;
    }

    private RequestBuilder verificar(String email, String codigo) throws Exception {
        return requisicao(corpo(email, codigo));
    }

    private RequestBuilder requisicao(Map<String, Object> corpo) throws Exception {
        return post(VERIFY_EMAIL).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    /** O cabeçalho {@code Set-Cookie} do {@code refresh_token}, ou {@code null} se não houver. */
    private static String cookieRefreshToken(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("refresh_token="))
                .findFirst()
                .orElse(null);
    }

    private static String valorDoCookie(String setCookie) {
        String par = setCookie.split(";", 2)[0];
        return par.substring("refresh_token=".length());
    }

    private java.sql.Timestamp emailVerifiedAtDe(UUID userId) {
        return jdbc.queryForObject("SELECT email_verified_at FROM users WHERE id = ?", java.sql.Timestamp.class, userId);
    }

    private int tentativasDe(UUID otpId) {
        return jdbc.queryForObject("SELECT attempts FROM otp_codes WHERE id = ?", Integer.class, otpId);
    }

    private int refreshTokensDe(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} e um item de {@code errors} com {@code field} igual a {@code
     * campoEsperado}. Não usa {@link ContractValidation#matchesContract()} porque a requisição é
     * propositalmente inválida (e o contrato desta rota só lista {@code InvalidCode} no 400 — ver
     * relatório); a resposta é conferida pelo schema extraído do mesmo {@code openapi.yaml}.
     */
    private void esperaValidationProblem(Map<String, Object> corpo, String campoEsperado) throws Exception {
        MvcResult result = mockMvc.perform(requisicao(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem do contrato: %s", violacoes).isEmpty();

        List<String> campos = new ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
        assertThat(cookieRefreshToken(result)).isNull();
    }

    /**
     * Confere que o corpo casa com {@code InvalidCodeProblem} do contrato, sem usar {@link
     * ContractValidation#matchesContract()}: a resposta real é válida contra {@code
     * InvalidCodeProblem} isoladamente, mas a resposta {@code InvalidCode} desta rota é um {@code
     * oneOf: [ValidationProblem, InvalidCodeProblem]} (ajuste da T-022 para cobrir também corpo
     * malformado), e a biblioteca de validação de contrato (`com.atlassian.oai`) tem uma
     * limitação conhecida ao combinar {@code allOf} dentro de {@code oneOf}: aplica {@code
     * additionalProperties} de cada ramo do {@code allOf} isoladamente, sem mesclar as
     * propriedades do {@code Problem} base primeiro, e rejeita os dois ramos do {@code oneOf}
     * por "propriedade não definida" mesmo quando o corpo é válido. Confirmamos a conformidade
     * validando direto contra o schema {@code InvalidCodeProblem} (fora do {@code oneOf}), via
     * {@link ContractSchemas}, igual ao padrão já usado para {@code ValidationProblem} acima.
     */
    private void esperaInvalidCodeProblem(JsonNode corpo) {
        List<Error> violacoes = ContractSchemas.schemaFor("InvalidCodeProblem").validate(corpo);
        assertThat(violacoes).as("corpo casa com InvalidCodeProblem do contrato: %s", violacoes).isEmpty();
    }
}
