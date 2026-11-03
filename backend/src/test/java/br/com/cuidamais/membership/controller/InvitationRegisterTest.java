package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-042 - {@code POST /api/v1/invitations/register} (contrato {@code registerWithInvitation}),
 * endpoint publico (sem JWT): passo 2 de 2 do convidado, que cria o usuario (com {@code
 * email_verified_at} preenchido, pois o codigo de convite comprovou o e-mail - AC-004.3, D-33) e
 * a membership {@code AGUARDANDO_APROVACAO} com prazo de 24 h, e avisa o Responsavel por e-mail.
 *
 * <p>ACs cobertos: AC-004.2 (o {@code registrationToken} de 15 min e o unico credencial; e-mail
 * vem dele), AC-004.3 (conta + vinculo ao grupo do convite, convite ENVIADO -&gt; USADO, AC-003.10),
 * AC-004.4 (resposta 201 com {@code approvalDeadline}), AC-006.7 (e-mail de pedido pendente sem
 * dado de saude, P5), AC-001.3 (senha), AC-011.1/011.2 (endereco). Decisoes: D-16 rev. 2 (token
 * de uso unico, valida {@code typ}/{@code email}/{@code inv} e o estado atual do convite), D-23
 * (e-mail depois do commit), D-25 (endereco), D-33, D-34, R-08 (ordem global de locks; a
 * concorrencia esta em {@link InvitationRegisterConcurrencyTest}).
 *
 * <p>Caixa-preta pela rota HTTP real, validada contra o contrato. O token e sempre o de uma chamada
 * real a {@code POST /invitations/redeem}; os convites sao semeados por {@link JdbcTemplate} (hash
 * real do codigo, D-04) e o {@link br.com.cuidamais.shared.testsupport.MutableClock} (D-10) fixa
 * o "agora". Cada teste usa um IP remoto proprio para o rate limit do redeem (R-03) nunca
 * interferir. Tokens forjados (outra chave, sem claims) sao montados com JJWT e o segredo de teste.
 *
 * <p>Estrategia para "convite VENCIDO entre redeem e register": o token vale 15 min e o convite so
 * pode ser resgatado vigente; entao o convite e semeado com {@code expires_at} = agora + 10 min,
 * resgatado, e o Clock avanca 11 min (token ainda valido, convite ja vencido pelo Clock).
 *
 * <p>Decisoes assumidas (a confirmar com o implementador): todo motivo de token/convite invalido
 * (incluindo e-mail que ja ganhou conta) responde 401 {@code application/problem+json}; nenhuma
 * validacao de corpo consome o token (convite segue ENVIADO e o mesmo token funciona de novo); o
 * e-mail ao Responsavel e enviado so apos o commit; o campo {@code email} no corpo e ignorado.
 */
@AutoConfigureMockMvc
class InvitationRegisterTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String REGISTER = "/api/v1/invitations/register";
    private static final String ELDERS = "/api/v1/elders";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME_ACCOUNT = "/api/v1/me/account";
    private static final String SENHA = "Senha@Forte123";
    private static final String SENHA_NOVA = "Convidada#2026";
    private static final String CODIGO = "123456";
    private static final String ASSUNTO_PEDIDO = "Cuida+: pedido de entrada no grupo aguarda sua decisão";
    private static final String NOME = "Joana Souza";
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    /** IP remoto exclusivo do teste corrente (R-03). */
    private String ip;

    @Value("${app.jwt.secret}")
    String segredoJwt;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void preparaTeste() {
        mailpit.deleteAll();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ip = "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    // ============================================================ AC-004.2/004.3/004.4 caminho feliz

    /**
     * Caminho feliz: redeem real devolve o token e o register o consome. Prova o 201 conforme o
     * contrato com {@code status=AGUARDANDO_APROVACAO} e {@code approvalDeadline} = agora + 24 h
     * (AC-004.4), sem exigir Authorization (rota publica) e sem {@code Set-Cookie}/token de sessao.
     */
    @Test
    @DisplayName("AC-004.4: register com token valido => 201 AGUARDANDO_APROVACAO com approvalDeadline = agora + 24 h, conforme o contrato")
    void ac_004_4_register_responde_201_com_prazo_de_24h() throws Exception {
        Cenario c = cenarioComTokenValido();

        MvcResult result = mockMvc.perform(registrar(c.token(), corpoValido()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(OffsetDateTime.parse(body.path("approvalDeadline").asText()).toInstant())
                .isCloseTo(clock.instant().plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));
    }

    /**
     * AC-004.3 / D-33: o usuario nasce com o e-mail do token (normalizado), nome/telefone/endereco
     * enviados, {@code email_verified_at} = agora (o codigo de convite comprovou o e-mail) e a
     * senha so como hash BCrypt, nunca em claro.
     */
    @Test
    @DisplayName("AC-004.3: usuário criado com e-mail do token, dados gravados, email_verified_at = agora e senha só como hash BCrypt (D-33)")
    void ac_004_3_usuario_criado_verificado_e_senha_so_como_hash() throws Exception {
        Cenario c = cenarioComTokenValido();

        mockMvc.perform(registrar(c.token(), corpoValido())).andExpect(status().isCreated());

        Map<String, Object> u = jdbc.queryForMap("SELECT * FROM users WHERE email = ?", c.email());
        // Etapa: dados cadastrais gravados.
        assertThat(u.get("name")).isEqualTo(NOME);
        assertThat(u.get("phone")).isEqualTo("21912345678");
        assertThat(u.get("address_zip_code")).isEqualTo("22041001");
        assertThat(u.get("address_street")).isEqualTo("Rua Barata Ribeiro");
        assertThat(u.get("address_number")).isEqualTo("370");
        assertThat(u.get("address_complement")).isEqualTo("Apto 802");
        assertThat(u.get("address_district")).isEqualTo("Copacabana");
        assertThat(u.get("address_city")).isEqualTo("Rio de Janeiro");
        assertThat(u.get("address_state")).isEqualTo("RJ");
        // Etapa: e-mail ja verificado, sem passar pelo codigo de confirmacao.
        assertThat(u.get("email_verified_at")).as("email_verified_at preenchido").isNotNull();
        Instant verificado = instante(u.get("email_verified_at"));
        assertThat(verificado).isCloseTo(clock.instant(), within(2, ChronoUnit.SECONDS));
        // Etapa: senha so como hash BCrypt.
        String hash = (String) u.get("password_hash");
        assertThat(hash).startsWith("$2").doesNotContain(SENHA_NOVA);
        assertThat(encoder.matches(SENHA_NOVA, hash)).isTrue();
    }

    /**
     * AC-004.3: a membership nasce no grupo do convite, AGUARDANDO_APROVACAO, com {@code
     * expires_at} = agora + 24 h; existe exatamente uma membership para o novo usuario.
     */
    @Test
    @DisplayName("AC-004.3: membership do grupo do convite em AGUARDANDO_APROVACAO com expires_at = agora + 24 h")
    void ac_004_3_membership_aguardando_aprovacao_com_prazo_de_24h() throws Exception {
        Cenario c = cenarioComTokenValido();

        mockMvc.perform(registrar(c.token(), corpoValido())).andExpect(status().isCreated());

        UUID userId = userIdDe(c.email());
        List<Map<String, Object>> ms = jdbc.queryForList("SELECT * FROM memberships WHERE user_id = ?", userId);
        assertThat(ms).hasSize(1);
        assertThat(ms.getFirst().get("group_id")).isEqualTo(c.grupo().groupId());
        assertThat(ms.getFirst().get("status")).isEqualTo("AGUARDANDO_APROVACAO");
        Instant expira = instante(ms.getFirst().get("expires_at"));
        assertThat(expira).isCloseTo(clock.instant().plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));
    }

    /**
     * AC-004.3 / AC-003.10: o convite do token passa de ENVIADO a USADO com {@code used_at}
     * preenchido, e o contador por e-mail volta a 0 (falhas e reenvios automaticos,
     * {@code registerCodeUsed}). Semeia 3 falhas e 2 reenvios antes do redeem.
     */
    @Test
    @DisplayName("AC-004.3: convite ENVIADO vira USADO (used_at) e o contador do e-mail volta a 0 (falhas e auto_resends)")
    void ac_004_3_convite_usado_e_contador_zerado() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofDays(7)));
        semearTentativas(email, 3, 2);
        String token = resgatar(email);

        mockMvc.perform(registrar(token, corpoValido())).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, convite))
                .isEqualTo("USADO");
        assertThat(jdbc.queryForObject("SELECT used_at FROM invitations WHERE id = ?", Object.class, convite))
                .isNotNull();
        assertThat(jdbc.queryForList("SELECT failed_attempts FROM invite_code_attempts WHERE email = ?",
                        Integer.class, email))
                .allMatch(v -> v == 0);
        assertThat(jdbc.queryForList("SELECT auto_resends FROM invite_code_attempts WHERE email = ?",
                        Integer.class, email))
                .allMatch(v -> v == 0);
    }

    /**
     * AC-004.3 / AC-004.4: com a senha cadastrada, o login e aceito como credencial mas a conta
     * ainda nao esta ativa => 403 {@code AWAITING_APPROVAL} (senha errada continuaria 401), e
     * {@code GET /me/account} reflete o estado AGUARDANDO_APROVACAO com grupo.
     */
    @Test
    @DisplayName("AC-004.4: login com a senha cadastrada => 403 AWAITING_APPROVAL e GET /me/account => AGUARDANDO_APROVACAO")
    void ac_004_4_login_e_estado_da_conta_refletem_aguardando_aprovacao() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(registrar(c.token(), corpoValido())).andExpect(status().isCreated());

        // Etapa: login com a senha certa => 403 AWAITING_APPROVAL.
        MvcResult login = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", c.email(), "password", SENHA_NOVA))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(json.readTree(login.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("AWAITING_APPROVAL");

        // Etapa: estado da conta pelo access token do novo usuario.
        MvcResult conta = mockMvc.perform(get(ME_ACCOUNT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(userIdDe(c.email()), clock)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(conta.getResponse().getContentAsString());
        assertThat(body.path("state").asText()).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(body.path("hasGroup").asBoolean()).isTrue();
    }

    /**
     * AC-004.2 / D-16: o e-mail da conta vem sempre do token. Mesmo que o corpo traga {@code email}
     * de outra pessoa, o usuario criado usa o do token e nenhum usuario nasce com o e-mail extra.
     */
    @Test
    @DisplayName("AC-004.2: campo email extra no corpo é ignorado; o usuário usa o e-mail do token")
    void ac_004_2_email_do_corpo_e_ignorado() throws Exception {
        Cenario c = cenarioComTokenValido();
        Map<String, Object> corpo = corpoValido();
        corpo.put("email", "outra-" + UUID.randomUUID() + "@x.com");

        mockMvc.perform(registrar(c.token(), corpo)).andExpect(status().isCreated());

        assertThat(contasCom(c.email())).isEqualTo(1);
        assertThat(contasCom((String) corpo.get("email"))).isZero();
    }

    // ============================================================ AC-006.7 e-mail ao Responsavel

    /**
     * AC-006.7 / P5: o Responsavel (admin do grupo) recebe exatamente um e-mail de pedido pendente
     * com o nome de quem pediu e o prazo (data e hora de Brasilia). O texto nao contem dado do
     * idoso (sobrenome "Oliveira", nome "Francisca") nem termos de saude.
     */
    @Test
    @DisplayName("AC-006.7: Responsável recebe 1 e-mail com nome do solicitante e prazo, sem dado do idoso nem de saúde (P5)")
    void ac_006_7_email_ao_responsavel_sem_dado_do_idoso() throws Exception {
        Cenario c = cenarioComTokenValido();
        String responsavel = c.grupo().responsavel().getEmail();

        MvcResult result = mockMvc.perform(registrar(c.token(), corpoValido()))
                .andExpect(status().isCreated())
                .andReturn();
        Instant prazo = OffsetDateTime.parse(
                        json.readTree(result.getResponse().getContentAsString()).path("approvalDeadline").asText())
                .toInstant();

        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(responsavel))
                .filteredOn(s -> ASSUNTO_PEDIDO.equals(s.subject()))
                .hasSize(1));
        MailpitClient.Summary resumo = mailpit.messagesTo(responsavel).stream()
                .filter(s -> ASSUNTO_PEDIDO.equals(s.subject()))
                .findFirst()
                .orElseThrow();
        String texto = mailpit.message(resumo.id()).text();
        assertThat(texto).contains(NOME);
        assertThat(texto).contains(DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(SAO_PAULO).format(prazo));
        assertThat(texto).contains(DateTimeFormatter.ofPattern("HH:mm").withZone(SAO_PAULO).format(prazo));
        // Etapa: P5 - nada do idoso nem de saude.
        assertThat(texto.toLowerCase())
                .doesNotContain("oliveira")
                .doesNotContain("francisca")
                .doesNotContain("alergia")
                .doesNotContain("condiç")
                .doesNotContain("medica");
        // Etapa: nao chega um segundo e-mail de pedido para o mesmo pedido.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(mailpit.messagesTo(responsavel))
                        .filteredOn(s -> ASSUNTO_PEDIDO.equals(s.subject()))
                        .hasSize(1));
    }

    // ============================================================ D-16 token invalido => 401

    /** Token que nao e um JWT: 401 Problem e nada e criado. */
    @Test
    @DisplayName("D-16: registrationToken malformado => 401 e nada criado")
    void d_16_token_malformado_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();

        esperar401(registrar("isto.nao-e-um-jwt", corpoValido()));

        assertNadaCriado(c);
    }

    /**
     * Token expirado: o Clock avanca 15 min e 1 s apos o redeem (AC-004.2, exp exclusivo) => 401,
     * nada criado e o convite continua ENVIADO.
     */
    @Test
    @DisplayName("AC-004.2: registrationToken expirado (15 min e 1 s) => 401 e nada criado")
    void ac_004_2_token_expirado_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        esperar401(registrar(c.token(), corpoValido()));

        assertNadaCriado(c);
    }

    /** Token assinado com outra chave (mesmos claims validos): a assinatura nao confere => 401. */
    @Test
    @DisplayName("D-16: registrationToken assinado com outra chave => 401 e nada criado")
    void d_16_token_de_outra_chave_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        String forjado = Jwts.builder()
                .claim("typ", "invite-registration")
                .claim("email", c.email())
                .claim("inv", c.convite().toString())
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(15))))
                .signWith(Keys.hmacShaKeyFor("outra-chave-qualquer-com-mais-de-32-bytes-0000".getBytes(StandardCharsets.UTF_8)))
                .compact();

        esperar401(registrar(forjado, corpoValido()));

        assertNadaCriado(c);
    }

    /**
     * Um access token comum (assinatura valida, sem {@code typ}, com {@code sub}) usado como
     * registrationToken nao serve: falta a finalidade {@code invite-registration} => 401.
     */
    @Test
    @DisplayName("D-16: access token comum (sem typ) usado como registrationToken => 401 e nada criado")
    void d_16_access_token_comum_nao_serve_como_registration_token() throws Exception {
        Cenario c = cenarioComTokenValido();
        String accessToken = jwtService.issue(c.grupo().responsavel().getId(), clock);

        esperar401(registrar(accessToken, corpoValido()));

        assertNadaCriado(c);
    }

    /**
     * Tokens com assinatura e prazo validos mas claims incompletos ou de outra finalidade: cada
     * linha omite ou troca um claim exigido por D-16 (typ, email, inv) => 401, nada criado.
     */
    @ParameterizedTest
    @CsvSource({"SEM_EMAIL", "SEM_INV", "SEM_TYP", "TYP_ERRADO", "INV_NAO_UUID"})
    @DisplayName("D-16: token assinado sem os claims typ/email/inv (ou com typ de outra finalidade) => 401 e nada criado")
    void d_16_token_sem_claims_exigidos_e_401(String defeito) throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cada linha da tabela = um defeito nos claims do token forjado com a chave certa.
        var builder = Jwts.builder()
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(15))));
        if (!defeito.equals("SEM_TYP")) {
            builder.claim("typ", defeito.equals("TYP_ERRADO") ? "outra-finalidade" : "invite-registration");
        }
        if (!defeito.equals("SEM_EMAIL")) {
            builder.claim("email", c.email());
        }
        if (!defeito.equals("SEM_INV")) {
            builder.claim("inv", defeito.equals("INV_NAO_UUID") ? "nao-e-uuid" : c.convite().toString());
        }
        String token = builder.signWith(Keys.hmacShaKeyFor(segredoJwt.getBytes(StandardCharsets.UTF_8))).compact();

        esperar401(registrar(token, corpoValido()));

        assertNadaCriado(c);
    }

    /** Campo {@code registrationToken} ausente ou vazio => 400 de validacao no campo, nada criado. */
    @ParameterizedTest
    @ValueSource(strings = {"AUSENTE", "VAZIO"})
    @DisplayName("D-16: registrationToken ausente ou vazio => 400 ValidationProblem em registrationToken")
    void d_16_token_ausente_e_400(String variante) throws Exception {
        Cenario c = cenarioComTokenValido();
        Map<String, Object> corpo = corpoValido();
        // Cada linha = uma forma de nao informar o token.
        if (variante.equals("VAZIO")) {
            corpo.put("registrationToken", "");
        }

        JsonNode body = esperar400(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));

        assertThat(campos(body)).contains("registrationToken");
        assertNadaCriado(c);
    }

    // ============================================================ D-16 uso unico

    /**
     * Uso unico (D-16, AC-003.2): o primeiro register consome o token; o segundo, com o mesmo
     * token e corpo, recebe 401, nao cria segundo usuario nem membership, e o convite segue USADO.
     */
    @Test
    @DisplayName("D-16: segundo register com o mesmo token => 401, sem segundo usuário/membership; convite segue USADO")
    void d_16_token_e_de_uso_unico() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(registrar(c.token(), corpoValido())).andExpect(status().isCreated());

        esperar401(registrar(c.token(), corpoValido()));

        assertThat(contasCom(c.email())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id = ?", Integer.class,
                        userIdDe(c.email())))
                .isEqualTo(1);
        assertThat(statusConvite(c.convite())).isEqualTo("USADO");
    }

    // ============================================================ D-16 convite mudou

    /** O Responsavel cancela o convite entre o redeem e o register: 401, nada criado, convite CANCELADO. */
    @Test
    @DisplayName("D-16: convite CANCELADO entre redeem e register => 401 e nada criado")
    void d_16_convite_cancelado_entre_redeem_e_register_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/cancel", c.grupo().elderId(), c.convite())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(c.grupo().responsavel().getId(), clock)))
                .andExpect(status().is2xxSuccessful());

        esperar401(registrar(c.token(), corpoValido()));

        assertThat(contasCom(c.email())).isZero();
        assertThat(statusConvite(c.convite())).isEqualTo("CANCELADO");
    }

    /**
     * O Responsavel reenvia o convite entre o redeem e o register: o antigo vira CANCELADO e um
     * novo ENVIADO nasce; o token aponta para o antigo => 401 e nada criado (o novo segue ENVIADO).
     */
    @Test
    @DisplayName("D-16: convite reenviado (antigo CANCELADO) entre redeem e register => 401 e nada criado")
    void d_16_convite_reenviado_entre_redeem_e_register_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/resend", c.grupo().elderId(), c.convite())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(c.grupo().responsavel().getId(), clock)))
                .andExpect(status().is2xxSuccessful());

        esperar401(registrar(c.token(), corpoValido()));

        assertThat(contasCom(c.email())).isZero();
        assertThat(statusConvite(c.convite())).isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM invitations WHERE email = ? AND status = 'ENVIADO'", Integer.class,
                        c.email()))
                .isEqualTo(1);
    }

    /**
     * Convite vence entre o redeem e o register: semeado com {@code expires_at} = agora + 10 min,
     * resgatado, e o Clock avanca 11 min (token de 15 min ainda valido) => 401, nada criado.
     */
    @Test
    @DisplayName("D-16 / AC-003.2: convite VENCIDO (pelo Clock) entre redeem e register, com token ainda válido => 401 e nada criado")
    void d_16_convite_vencido_entre_redeem_e_register_e_401() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofMinutes(10)));
        String token = resgatar(email);
        clock.advance(Duration.ofMinutes(11));

        esperar401(registrar(token, corpoValido()));

        assertThat(contasCom(email)).isZero();
        assertThat(statusConvite(convite)).isNotEqualTo("USADO");
    }

    // ============================================================ e-mail ja tem conta

    /**
     * Entre o redeem e o register alguem criou a conta com o mesmo e-mail (ex.: signup normal).
     * O register responde 401, nao altera a conta existente (nome, hash) nem cria membership.
     */
    @Test
    @DisplayName("AC-004.3: e-mail que ganhou conta entre redeem e register => 401, conta existente intacta e nada criado")
    void ac_004_3_email_que_ja_tem_conta_e_401_sem_alterar_a_existente() throws Exception {
        Cenario c = cenarioComTokenValido();
        User existente = User.signup("Dona Original", c.email(), "21999990000", endereco(),
                encoder.encode("Original#123"), clock);
        existente.verifyEmail(clock);
        existente = users.save(existente);
        String hashAntes = existente.getPasswordHash();

        esperar401(registrar(c.token(), corpoValido()));

        Map<String, Object> u = jdbc.queryForMap("SELECT name, phone, password_hash FROM users WHERE email = ?", c.email());
        assertThat(u.get("name")).isEqualTo("Dona Original");
        assertThat(u.get("phone")).isEqualTo("21999990000");
        assertThat(u.get("password_hash")).isEqualTo(hashAntes);
        assertThat(contasCom(c.email())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id = ?", Integer.class,
                        existente.getId()))
                .isZero();
    }

    // ============================================================ validacao 400

    /** AC-001.3: senha fora da regra (menos de 8, sem letra, sem numero, longa demais) => 400 em password. */
    @ParameterizedTest
    @ValueSource(strings = {"abc1234", "12345678", "somenteletras", "a1"})
    @DisplayName("AC-001.3: senha fraca => 400 em password, nada criado e a senha não é ecoada")
    void ac_001_3_senha_fraca_e_400(String senha) throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cada linha = uma violacao: <8 caracteres, sem letra, sem numero, curta demais.
        JsonNode body = esperar400(registrar(c.token(), com(corpoValido(), "password", senha)));

        assertThat(campos(body)).contains("password");
        assertThat(body.toString()).doesNotContain(senha);
        assertNadaCriado(c);
    }

    /** AC-001.1/AC-001.3: nome vazio, de 1 caractere ou acima de 120 e senha acima de 128 => 400 no campo. */
    @ParameterizedTest
    @CsvSource({"name,''", "name,A", "name,TAMANHO_121", "password,SENHA_129"})
    @DisplayName("AC-001.1: nome vazio/curto/longo demais e senha longa demais => 400 no campo, nada criado")
    void ac_001_1_nome_e_tamanhos_invalidos_sao_400(String campo, String valor) throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cada linha = um limite de tamanho violado (nome <2, nome >120, senha >128).
        String v = switch (valor) {
            case "TAMANHO_121" -> "A".repeat(121);
            case "SENHA_129" -> "a1".repeat(64) + "b";
            default -> valor;
        };

        JsonNode body = esperar400(registrar(c.token(), com(corpoValido(), campo, v)));

        assertThat(campos(body)).contains(campo);
        assertNadaCriado(c);
    }

    /** AC-001.1: telefone fora de DDD + 8/9 digitos => 400 em phone. */
    @ParameterizedTest
    @ValueSource(strings = {"123456789", "219876543210", "(21)98765-4321", ""})
    @DisplayName("AC-001.1: telefone inválido => 400 em phone, nada criado")
    void ac_001_1_telefone_invalido_e_400(String telefone) throws Exception {
        Cenario c = cenarioComTokenValido();

        JsonNode body = esperar400(registrar(c.token(), com(corpoValido(), "phone", telefone)));

        assertThat(campos(body)).contains("phone");
        assertNadaCriado(c);
    }

    /** AC-011.2: CEP sem exatamente 8 digitos => 400 em address.zipCode. */
    @ParameterizedTest
    @ValueSource(strings = {"2204100", "220410012", "22041-001", "2204100A", ""})
    @DisplayName("AC-011.2: CEP sem 8 dígitos numéricos => 400 em address.zipCode, nada criado")
    void ac_011_2_cep_invalido_e_400(String cep) throws Exception {
        Cenario c = cenarioComTokenValido();

        JsonNode body = esperar400(registrar(c.token(), comEndereco(corpoValido(), "zipCode", cep)));

        assertThat(campos(body)).contains("address.zipCode");
        assertNadaCriado(c);
    }

    /** AC-011.2: UF fora das 27 unidades => 400 em address.state. */
    @ParameterizedTest
    @ValueSource(strings = {"XX", "rj", "RJX", ""})
    @DisplayName("AC-011.2: UF inválida => 400 em address.state, nada criado")
    void ac_011_2_uf_invalida_e_400(String uf) throws Exception {
        Cenario c = cenarioComTokenValido();

        JsonNode body = esperar400(registrar(c.token(), comEndereco(corpoValido(), "state", uf)));

        assertThat(campos(body)).contains("address.state");
        assertNadaCriado(c);
    }

    /** AC-011.1: cada campo obrigatorio do endereco ausente => 400 naquele campo. */
    @ParameterizedTest
    @ValueSource(strings = {"zipCode", "street", "number", "district", "city", "state"})
    @DisplayName("AC-011.1: campo obrigatório do endereço ausente => 400 no campo, nada criado")
    void ac_011_1_campo_obrigatorio_do_endereco_ausente_e_400(String campo) throws Exception {
        Cenario c = cenarioComTokenValido();
        Map<String, Object> corpo = corpoValido();
        // Cada linha = um campo do endereco removido do corpo.
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) corpo.get("address");
        end.remove(campo);

        JsonNode body = esperar400(registrar(c.token(), corpo));

        assertThat(campos(body)).contains("address." + campo);
        assertNadaCriado(c);
    }

    /** AC-011.1: endereco inteiro ausente, telefone ausente, nome ausente e senha ausente => 400 no campo. */
    @ParameterizedTest
    @ValueSource(strings = {"address", "phone", "name", "password"})
    @DisplayName("AC-011.1/AC-001.3: campo obrigatório de topo ausente => 400 no campo, nada criado")
    void ac_011_1_campo_de_topo_ausente_e_400(String campo) throws Exception {
        Cenario c = cenarioComTokenValido();
        Map<String, Object> corpo = corpoValido();
        corpo.remove(campo);

        JsonNode body = esperar400(registrar(c.token(), corpo));

        assertThat(campos(body)).contains(campo);
        assertNadaCriado(c);
    }

    /**
     * A validacao nao consome o token: depois de um 400 (senha fraca) o mesmo token, com o corpo
     * corrigido, ainda funciona (201) - o convite seguiu ENVIADO ate a tentativa correta.
     */
    @Test
    @DisplayName("D-16: após 400 de validação o convite segue ENVIADO e o mesmo token funciona na tentativa correta")
    void d_16_apos_400_o_mesmo_token_ainda_funciona() throws Exception {
        Cenario c = cenarioComTokenValido();
        esperar400(registrar(c.token(), com(corpoValido(), "password", "fraca")));
        assertThat(statusConvite(c.convite())).isEqualTo("ENVIADO");

        mockMvc.perform(registrar(c.token(), corpoValido())).andExpect(status().isCreated());

        assertThat(contasCom(c.email())).isEqualTo(1);
        assertThat(statusConvite(c.convite())).isEqualTo("USADO");
    }

    // ============================================================ rota publica

    /**
     * Rota publica: sem nenhum header Authorization o register nao e barrado pela seguranca
     * (nao e 401/403 de filtro) e chega ao caso de uso, respondendo o 201 do caso feliz.
     */
    @Test
    @DisplayName("T-042: register é rota pública e não exige Authorization")
    void t_042_register_nao_exige_authorization() throws Exception {
        Cenario c = cenarioComTokenValido();

        MvcResult result = mockMvc.perform(registrar(c.token(), corpoValido()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(result.getRequest().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
    }

    // ============================================================ helpers de requisicao

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    /** Cenario pronto: grupo, convite ENVIADO, e-mail sem conta e registrationToken real do redeem. */
    private record Cenario(Grupo grupo, String email, UUID convite, String token) {}

    private Cenario cenarioComTokenValido() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofDays(7)));
        return new Cenario(grupo, email, convite, resgatar(email));
    }

    /** Chama o redeem real e devolve o {@code registrationToken}. */
    private String resgatar(String email) throws Exception {
        MvcResult r = mockMvc.perform(post(REDEEM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(json.writeValueAsString(Map.of("email", email, "code", CODIGO))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("next").asText()).isEqualTo("REGISTER");
        return body.path("registrationToken").asText();
    }

    /** POST /invitations/register sem Authorization, com o corpo dado (o token entra no corpo). */
    private MockHttpServletRequestBuilder registrar(String token, Map<String, Object> corpo) throws Exception {
        Map<String, Object> completo = new LinkedHashMap<>();
        completo.put("registrationToken", token);
        completo.putAll(corpo);
        return post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(completo));
    }

    private Map<String, Object> corpoValido() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("name", NOME);
        corpo.put("phone", "21912345678");
        corpo.put("address", end);
        corpo.put("password", SENHA_NOVA);
        return corpo;
    }

    private static Map<String, Object> com(Map<String, Object> corpo, String campo, Object valor) {
        corpo.put(campo, valor);
        return corpo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> comEndereco(Map<String, Object> corpo, String campo, Object valor) {
        ((Map<String, Object>) corpo.get("address")).put(campo, valor);
        return corpo;
    }

    /** Espera 401 application/problem+json conforme o contrato. */
    private void esperar401(MockHttpServletRequestBuilder req) throws Exception {
        mockMvc.perform(req)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    /** Espera 400 ValidationProblem conforme o contrato e devolve o corpo. */
    private JsonNode esperar400(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mockMvc.perform(req)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        return body;
    }

    private static List<String> campos(JsonNode problem) {
        List<String> campos = new ArrayList<>();
        problem.path("errors").forEach(e -> campos.add(e.path("field").asText()));
        return campos;
    }

    // ============================================================ helpers de banco

    /** Nada foi criado: sem usuario do e-mail, sem membership nova no grupo e convite ainda ENVIADO. */
    private void assertNadaCriado(Cenario c) {
        assertThat(contasCom(c.email())).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE group_id = ?", Integer.class, c.grupo().groupId()))
                .as("só a membership do Responsável")
                .isEqualTo(1);
        assertThat(statusConvite(c.convite())).isEqualTo("ENVIADO");
    }

    /** Converte o TIMESTAMPTZ devolvido pelo driver (Timestamp ou OffsetDateTime) em Instant. */
    private static Instant instante(Object valor) {
        return valor instanceof Timestamp t ? t.toInstant() : ((OffsetDateTime) valor).toInstant();
    }

    private int contasCom(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private UUID userIdDe(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String statusConvite(UUID id) {
        return jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, id);
    }

    /** Semeia convite ENVIADO com o hash real (D-04) de {@link #CODIGO}. */
    private UUID semearConvite(Grupo grupo, String email, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, encoder.encode(CODIGO), Timestamp.from(expiresAt),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    private void semearTentativas(String email, int falhas, int autoResends) {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, NULL, ?, ?)",
                email, falhas, autoResends, Timestamp.from(clock.instant()));
    }

    // ============================================================ fabricas de cenario

    /** Responsavel ATIVO que cria o idoso (e o grupo) pela API real. */
    private Grupo grupoComIdoso() throws Exception {
        User user = User.signup("Maria da Silva", "resp-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco(), encoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        User responsavel = users.save(user);

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
        MvcResult criacao = mockMvc.perform(post(ELDERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(responsavel.getId(), clock))
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID elderId = UUID.fromString(json.readTree(criacao.getResponse().getContentAsString()).path("id").asText());
        UUID groupId = jdbc.queryForObject(
                "SELECT group_id FROM memberships WHERE user_id = ?", UUID.class, responsavel.getId());
        return new Grupo(responsavel, groupId, elderId);
    }

    private static String emailNovo() {
        return "convidada-" + UUID.randomUUID() + "@teste.com";
    }

    private static Endereco endereco() {
        return new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
    }
}
