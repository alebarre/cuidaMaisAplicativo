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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * T-041 — {@code POST /api/v1/invitations/redeem} (contrato {@code redeemInvitation}), endpoint
 * público (sem JWT): passo 1 de 2 do convidado ("Tenho um código de convite").
 *
 * <p>ACs cobertos: AC-003.2 (convite de 7 dias; vencido derivado pelo Clock), AC-003.3 / AC-004.7
 * (5 erros por e-mail =&gt; 423 por 30 min, mesmo com código certo), AC-003.9-003.11 (contador por
 * e-mail zera quando o código é aceito; convite só vira USADO ao concluir o cadastro/reinstatement),
 * AC-004.1/004.2 ({@code next=REGISTER} com token de 15 min e só o primeiro nome do idoso),
 * AC-004.5 (410 só com código conferido), AC-004.6 ({@code next=REINSTATED} mantendo dados e senha
 * antigos, prazo de 24 h novo, e-mail ao Responsável - AC-006.7) e AC-004.7 (400 idêntico, com ou
 * sem convite). Decisões: D-16 (rev. 2), D-34 (esclarecimentos 1-4: normalização do e-mail,
 * bloqueio por e-mail, 400 vs 410), D-07, D-13, R-03 (rate limit está em {@link
 * InvitationRedeemRateLimitTest}).
 *
 * <p>Caixa-preta pela rota HTTP real, validada contra o contrato. Os convites e o contador {@code
 * invite_code_attempts} são semeados por {@link JdbcTemplate} (hash do código gerado com o {@link
 * PasswordEncoder} real, D-04), para o teste não depender de classes novas de produção; a rota é
 * o único símbolo novo. {@link br.com.cuidamais.shared.testsupport.MutableClock} (D-10) fixa o
 * "agora". Cada teste usa um IP remoto próprio, para o rate limit por IP (R-03) nunca interferir.
 *
 * <p>Decisões assumidas (a confirmar com o implementador):
 * <ul>
 *   <li>Conta existente fora de RECUSADO/EXPIRADO/REMOVIDO do grupo do convite (ATIVO,
 *       AGUARDANDO_APROVACAO, usuário de outro grupo ou sem grupo) responde como código inválido
 *       (400 com {@code remainingAttempts}, conta tentativa), para não vazar existência da conta.
 *   <li>Durante o bloqueio, o 423 repete o mesmo {@code blockedUntil} (a janela não é estendida).
 *   <li>Regra de decisão: o código é comparado com os convites do e-mail; o ENVIADO vigente tem
 *       prioridade; senão, hash de outro status vale o 410 correspondente. 410 não conta como
 *       erro nem zera o contador.
 * </ul>
 */
@AutoConfigureMockMvc
class InvitationRedeemTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String ELDERS = "/api/v1/elders";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/me";
    private static final String SENHA = "Senha@Forte123";
    private static final String CODIGO = "123456";
    private static final String CODIGO_ERRADO = "654321";
    private static final String ASSUNTO_PEDIDO = "Cuida+: pedido de entrada no grupo aguarda sua decisão";
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    /** IP remoto exclusivo do teste corrente (R-03): evita que o limite por IP afete outro teste. */
    private String ip;

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

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void preparaTeste() {
        mailpit.deleteAll();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ip = "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    // ============================================================ AC-004.1 / AC-004.2 REGISTER

    /**
     * Caminho feliz do e-mail sem conta: código certo de convite ENVIADO, e-mail enviado em caixa
     * alta. Prova o 200 conforme o contrato, {@code next=REGISTER}, token não nulo de 900 s, o
     * e-mail normalizado e só o primeiro nome do idoso (P5). Nenhuma conta nem membership nasce
     * (T-042), o convite segue ENVIADO (só vira USADO ao concluir o cadastro, AC-003.10) e o
     * contador do e-mail, que estava em 3, volta a zero (AC-003.11c).
     */
    @Test
    @DisplayName("AC-004.1: e-mail sem conta + código certo => 200 REGISTER com token de 900 s, e-mail normalizado e só o primeiro nome do idoso")
    void ac_004_1_email_sem_conta_codigo_certo_devolve_register() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);
        semearTentativas(email, 3, null);

        MvcResult result = mockMvc.perform(resgatar(email.toUpperCase(), CODIGO))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("next").asText()).isEqualTo("REGISTER");
        assertThat(body.path("registrationToken").asText()).isNotBlank();
        assertThat(body.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(body.path("email").asText()).as("e-mail normalizado (D-34)").isEqualTo(email);
        assertThat(body.path("elderFirstName").asText()).isEqualTo("Francisca");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Oliveira");

        // Etapa: nenhuma conta nem membership foi criada (isso é a T-042).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        // Etapa: o convite continua ENVIADO e o contador por e-mail voltou a zero (AC-003.11c).
        assertThat(statusDe(convite)).isEqualTo("ENVIADO");
        assertThat(falhasDe(email)).isZero();
    }

    /**
     * AC-004.2 / D-16: o {@code registrationToken} é um JWT de 15 min (exp - iat = 900), mas NÃO
     * é um access token: usado como Bearer em rota protegida ({@code GET /me}) é rejeitado com 401.
     */
    @Test
    @DisplayName("AC-004.2: registrationToken vale 15 min e não autentica rotas protegidas (GET /me => 401)")
    void ac_004_2_registration_token_de_15_min_nao_e_access_token() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);

        MvcResult result = mockMvc.perform(resgatar(email, CODIGO)).andExpect(status().isOk()).andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).path("registrationToken").asText();

        // Etapa: decodifica o payload do JWT (sem validar a semântica da T-042) e confere o prazo.
        String[] partes = token.split("\\.");
        assertThat(partes).as("token é um JWT de 3 partes").hasSize(3);
        JsonNode payload = json.readTree(new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8));
        assertThat(payload.path("exp").asLong() - payload.path("iat").asLong()).isEqualTo(900L);

        // Etapa: como Bearer numa rota protegida, o token de resgate não autentica.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ AC-004.6 REINSTATED

    /**
     * Conta já existente no grupo em RECUSADO, EXPIRADO ou REMOVIDO + código certo: 200 {@code
     * REINSTATED} sem token; a membership volta a AGUARDANDO_APROVACAO com prazo novo de 24 h
     * contado pelo Clock; nome, telefone, endereço e hash da senha continuam os de antes; o convite
     * vira USADO; o login com a senha antiga é aceito (403 AWAITING_APPROVAL, e não 401); e o
     * Responsável recebe o e-mail de pedido pendente (AC-006.7).
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-004.6: conta RECUSADO/EXPIRADO/REMOVIDO do grupo + código certo => REINSTATED, senha e dados antigos, prazo novo de 24 h e e-mail ao Responsável")
    void ac_004_6_reinstated_mantem_dados_e_senha_antigos(MembershipStatus statusAnterior) throws Exception {
        Grupo grupo = grupoComIdoso();
        // Cada linha da tabela = o status da membership antes do reconvite (RECUSADO, EXPIRADO, REMOVIDO).
        User antes = membro(grupo, statusAnterior);
        String hashAntes = antes.getPasswordHash();
        UUID convite = semearConvite(
                grupo, antes.getEmail(), "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);

        MvcResult result = mockMvc.perform(resgatar(antes.getEmail(), CODIGO))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("next").asText()).isEqualTo("REINSTATED");
        assertThat(body.path("registrationToken").isNull() || body.path("registrationToken").isMissingNode())
                .as("REINSTATED não devolve registrationToken").isTrue();
        assertThat(OffsetDateTime.parse(body.path("approvalDeadline").asText()).toInstant())
                .isCloseTo(clock.instant().plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));

        // Etapa: a membership voltou a AGUARDANDO_APROVACAO com prazo novo de 24 h.
        Membership m = memberships.findByUserId(antes.getId()).orElseThrow();
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
        assertThat(m.getExpiresAt()).isCloseTo(clock.instant().plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));

        // Etapa: dados e hash da senha antigos mantidos; convite consumido.
        User depois = users.findById(antes.getId()).orElseThrow();
        assertThat(depois.getPasswordHash()).as("hash da senha inalterado (AC-004.6)").isEqualTo(hashAntes);
        assertThat(depois.getName()).isEqualTo(antes.getName());
        assertThat(depois.getPhone()).isEqualTo(antes.getPhone());
        assertThat(depois.getAddress()).usingRecursiveComparison().isEqualTo(antes.getAddress());
        assertThat(statusDe(convite)).isEqualTo("USADO");

        // Etapa: o Responsável é avisado do novo pedido (PendingRequestEmail, AC-006.7).
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(grupo.responsavel().getEmail()))
                .anyMatch(s -> ASSUNTO_PEDIDO.equals(s.subject())));
    }

    /**
     * AC-004.6, prova explícita da senha antiga: depois do REINSTATED o login com a senha de antes
     * é aceito como credencial (responde 403 {@code AWAITING_APPROVAL}, que só ocorre com a senha
     * certa) e uma senha qualquer continua 401.
     */
    @Test
    @DisplayName("AC-004.6: após REINSTATED a senha antiga ainda autentica (403 AWAITING_APPROVAL) e outra senha dá 401")
    void ac_004_6_login_com_a_senha_antiga_continua_valido() throws Exception {
        Grupo grupo = grupoComIdoso();
        User antes = membro(grupo, MembershipStatus.RECUSADO);
        semearConvite(grupo, antes.getEmail(), "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);
        mockMvc.perform(resgatar(antes.getEmail(), CODIGO)).andExpect(status().isOk());

        // Etapa: senha antiga => credencial aceita; o estado da conta é que bloqueia o acesso.
        MvcResult ok = mockMvc.perform(login(antes.getEmail(), SENHA)).andExpect(status().isForbidden()).andReturn();
        assertThat(json.readTree(ok.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("AWAITING_APPROVAL");
        // Etapa: senha diferente continua sendo rejeitada.
        mockMvc.perform(login(antes.getEmail(), "Outra@Senha999")).andExpect(status().isUnauthorized());
    }

    // ============================================================ AC-004.7 400 idêntico

    /**
     * AC-004.7 / D-34: código errado para e-mail COM convite e para e-mail SEM convite produzem
     * corpo igual byte a byte (exceto {@code instance}), mesmo status e Content-Type, e o {@code
     * remainingAttempts} desce 4, 3, 2, 1 em cada e-mail, contado por e-mail exista convite ou não.
     */
    @Test
    @DisplayName("AC-004.7: código errado com convite e e-mail sem convite => 400 byte a byte igual (exceto instance), remainingAttempts 4,3,2,1")
    void ac_004_7_400_identico_com_e_sem_convite() throws Exception {
        Grupo grupo = grupoComIdoso();
        String comConvite = emailNovo();
        String semConvite = emailNovo();
        semearConvite(grupo, comConvite, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);

        for (int tentativa = 1; tentativa <= 4; tentativa++) {
            // Etapa: a tentativa N errada dos dois e-mails deve devolver respostas indistinguíveis.
            MvcResult a = mockMvc.perform(resgatar(comConvite, CODIGO_ERRADO)).andReturn();
            MvcResult b = mockMvc.perform(resgatar(semConvite, CODIGO_ERRADO)).andReturn();

            assertThat(a.getResponse().getStatus()).isEqualTo(400);
            assertThat(b.getResponse().getStatus()).isEqualTo(400);
            assertThat(a.getResponse().getContentType()).isEqualTo(b.getResponse().getContentType());
            assertThat(a.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            JsonNode corpoA = json.readTree(a.getResponse().getContentAsString());
            JsonNode corpoB = json.readTree(b.getResponse().getContentAsString());
            assertThat(corpoA.path("remainingAttempts").asInt()).isEqualTo(5 - tentativa);
            assertThat(json.writeValueAsString(semInstance(corpoA)))
                    .as("bytes iguais exceto instance, tentativa %d", tentativa)
                    .isEqualTo(json.writeValueAsString(semInstance(corpoB)));
            assertThat(ContractSchemas.schemaFor("InvalidCodeProblem").validate(corpoA)).isEmpty();
        }
    }

    /**
     * AC-004.7 / D-34 esclarecimento 3: o código errado contra convite que não está ENVIADO vigente
     * (VENCIDO gravado, ENVIADO já vencido pelo Clock, CANCELADO, USADO) responde 400 com
     * remainingAttempts=4, nunca 410 - o 410 só aparece com o código conferido.
     */
    @ParameterizedTest
    @CsvSource({"VENCIDO,false", "ENVIADO,true", "CANCELADO,false", "USADO,false"})
    @DisplayName("AC-004.7: código errado contra convite VENCIDO/ENVIADO-vencido/CANCELADO/USADO => 400 (não 410)")
    void ac_004_7_codigo_errado_em_convite_indisponivel_e_400(String statusGravado, boolean vencidoPeloClock)
            throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        // Cada linha: status gravado e, na linha 2, ENVIADO cujo prazo já passou (derivado pelo Clock).
        Instant expira = vencidoPeloClock ? clock.instant().minusSeconds(1) : clock.instant().plus(Duration.ofDays(7));
        semearConvite(grupo, email, statusGravado, expira, CODIGO);

        MvcResult result = mockMvc.perform(resgatar(email, CODIGO_ERRADO))
                .andExpect(status().isBadRequest())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("remainingAttempts").asInt()).isEqualTo(4);
        assertThat(ContractSchemas.schemaFor("InvalidCodeProblem").validate(body)).isEmpty();
    }

    // ============================================================ AC-004.7 / AC-003.3 423

    /**
     * AC-004.7 / AC-003.3: as 4 primeiras tentativas erradas respondem 400 e a 5ª responde 423 com
     * {@code code=INVITE_CODE_BLOCKED} e {@code blockedUntil} = agora + 30 min, conforme o contrato.
     */
    @Test
    @DisplayName("AC-004.7: a 5ª tentativa errada responde 423 INVITE_CODE_BLOCKED com blockedUntil = agora + 30 min")
    void ac_004_7_quinta_tentativa_errada_responde_423() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);

        // Etapa: tentativas 1 a 4 => 400.
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(resgatar(email, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        }
        // Etapa: a 5ª => 423.
        MvcResult result = mockMvc.perform(resgatar(email, CODIGO_ERRADO))
                .andExpect(status().isLocked())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("INVITE_CODE_BLOCKED");
        assertThat(ContractSchemas.schemaFor("InviteBlockedProblem").validate(body)).isEmpty();
        assertThat(OffsetDateTime.parse(body.path("blockedUntil").asText()).toInstant())
                .isCloseTo(clock.instant().plus(Duration.ofMinutes(30)), within(1, ChronoUnit.SECONDS));
    }

    /**
     * AC-004.7 / D-34: durante os 30 min, o código CERTO também recebe 423 (igual ao anterior) e
     * NÃO é usado: o convite segue ENVIADO. Um código errado no meio do bloqueio também é 423.
     */
    @Test
    @DisplayName("AC-004.7: durante o bloqueio, código certo e errado recebem 423 igual e o convite segue ENVIADO")
    void ac_004_7_durante_bloqueio_codigo_certo_tambem_423() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);
        JsonNode primeiro423 = bloquear(email);

        clock.advance(Duration.ofMinutes(10));
        // Etapa: código certo no meio do bloqueio.
        JsonNode certo = esperar423(resgatar(email, CODIGO));
        // Etapa: código errado no meio do bloqueio.
        JsonNode errado = esperar423(resgatar(email, CODIGO_ERRADO));

        assertThat(semInstance(certo)).isEqualTo(semInstance(primeiro423));
        assertThat(semInstance(errado)).isEqualTo(semInstance(primeiro423));
        assertThat(statusDe(convite)).as("código certo durante o bloqueio não é consumido").isEqualTo("ENVIADO");
    }

    /**
     * AC-004.7 / P5: e-mail SEM convite também vira 423 na 5ª errada, com corpo igual ao de um
     * e-mail com convite (exceto instance), então o bloqueio não revela se o convite existe.
     */
    @Test
    @DisplayName("AC-004.7: e-mail sem convite recebe o mesmo 423 que e-mail com convite (P5)")
    void ac_004_7_423_identico_sem_convite() throws Exception {
        Grupo grupo = grupoComIdoso();
        String comConvite = emailNovo();
        String semConvite = emailNovo();
        semearConvite(grupo, comConvite, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);

        JsonNode a = bloquear(comConvite);
        JsonNode b = bloquear(semConvite);

        assertThat(semInstance(a)).isEqualTo(semInstance(b));
        // Etapa: qualquer tentativa seguinte do e-mail sem convite continua em 423.
        esperar423(resgatar(semConvite, CODIGO));
    }

    /**
     * AC-003.3 / AC-004.7: passados 30 min do bloqueio (Clock avançado) o contador voltou a zero e
     * o código certo é aceito (200 REGISTER), zerando de novo o contador.
     */
    @Test
    @DisplayName("AC-003.3: após 30 min o contador volta a zero e o código certo é aceito")
    void ac_003_3_apos_30_minutos_codigo_certo_e_aceito() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);
        bloquear(email);

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        MvcResult result = mockMvc.perform(resgatar(email, CODIGO)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("next").asText())
                .isEqualTo("REGISTER");
        assertThat(falhasDe(email)).isZero();
    }

    // ============================================================ D-34 normalização

    /**
     * D-34 esclarecimento 1: e-mail com caixa mista e espaços é o mesmo e-mail para convite,
     * contador e bloqueio. Quatro erros com "  Ana-x@X.com " e o 5º com a forma minúscula => 423.
     */
    @Test
    @DisplayName("AC-004.7 / D-34: e-mail com caixa mista/espaços compartilha contador e bloqueio com a forma normalizada")
    void ac_004_7_normalizacao_do_email_compartilha_contador() throws Exception {
        String base = "ana-" + UUID.randomUUID() + "@x.com";
        String misto = "  " + base.replace("ana-", "Ana-").replace("@x", "@X") + " ";

        // Etapa: 4 erros com a forma suja.
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(resgatar(misto, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        }
        // Etapa: o 5º erro, com a forma normalizada, cai no mesmo contador e bloqueia.
        mockMvc.perform(resgatar(base, CODIGO_ERRADO)).andExpect(status().isLocked());
    }

    // ============================================================ AC-004.5 410

    /**
     * AC-004.5: código CERTO de convite indisponível => 410 com o {@code code} do status. Cada
     * linha: VENCIDO gravado, ENVIADO vencido pelo Clock (também INVITATION_EXPIRED), CANCELADO e
     * USADO. O 410 não conta como erro nem zera o contador (que estava em 2).
     */
    @ParameterizedTest
    @CsvSource({
        "VENCIDO,false,INVITATION_EXPIRED",
        "ENVIADO,true,INVITATION_EXPIRED",
        "CANCELADO,false,INVITATION_CANCELLED",
        "USADO,false,INVITATION_USED"
    })
    @DisplayName("AC-004.5: código certo de convite VENCIDO/CANCELADO/USADO => 410 com o code do status, sem mexer no contador")
    void ac_004_5_codigo_certo_de_convite_indisponivel_e_410(
            String statusGravado, boolean vencidoPeloClock, String codeEsperado) throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        Instant expira = vencidoPeloClock ? clock.instant().minusSeconds(1) : clock.instant().plus(Duration.ofDays(7));
        semearConvite(grupo, email, statusGravado, expira, CODIGO);
        semearTentativas(email, 2, null);

        MvcResult result = mockMvc.perform(resgatar(email, CODIGO))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(codeEsperado);
        assertThat(ContractSchemas.schemaFor("InvitationUnavailableProblem").validate(body)).isEmpty();
        assertThat(falhasDe(email)).as("410 não conta como erro nem zera o contador").isEqualTo(2);
    }

    /**
     * AC-004.5 / D-34: com um convite ENVIADO vigente (código A) e um CANCELADO antigo do mesmo
     * e-mail (código B), o código A é aceito (prioridade do vigente) e o código B, que bate com o
     * hash do cancelado, vale 410 INVITATION_CANCELLED.
     */
    @Test
    @DisplayName("AC-004.5: ENVIADO vigente tem prioridade; código de um CANCELADO antigo do mesmo e-mail => 410")
    void ac_004_5_vigente_tem_prioridade_e_codigo_do_cancelado_da_410() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearConvite(grupo, email, "CANCELADO", clock.instant().plus(Duration.ofDays(7)), "111111");
        semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(7)), "222222");

        // Etapa: código do cancelado => 410.
        MvcResult gone = mockMvc.perform(resgatar(email, "111111")).andExpect(status().isGone()).andReturn();
        assertThat(json.readTree(gone.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVITATION_CANCELLED");
        // Etapa: código do vigente => 200.
        mockMvc.perform(resgatar(email, "222222")).andExpect(status().isOk());
    }

    // ============================================================ bordas

    /**
     * Borda: corpo que viola o contrato (e-mail mal formado, código fora de ^\d{6}$ ou ausente) =>
     * 400 ValidationProblem padrão, sem tocar no contador do e-mail (que estava em 2).
     */
    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "abcdef", "12 456", ""})
    @DisplayName("AC-004.7: código fora de ^\\d{6}$ => 400 ValidationProblem sem tocar no contador")
    void ac_004_7_codigo_mal_formado_e_400_de_validacao_sem_contar(String codigoInvalido) throws Exception {
        String email = emailNovo();
        semearTentativas(email, 2, null);

        // Cada linha da tabela = um formato de código inválido.
        MvcResult result = mockMvc.perform(resgatar(email, codigoInvalido))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        assertThat(body.has("remainingAttempts")).isFalse();
        assertThat(falhasDe(email)).isEqualTo(2);
    }

    /** Borda: e-mail mal formado => 400 ValidationProblem (nenhum contador criado para o texto inválido). */
    @Test
    @DisplayName("AC-004.7: e-mail mal formado => 400 ValidationProblem sem remainingAttempts")
    void ac_004_7_email_mal_formado_e_400_de_validacao() throws Exception {
        MvcResult result = mockMvc.perform(resgatar("isto-nao-e-email", CODIGO))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("ValidationProblem").validate(body)).isEmpty();
        assertThat(body.has("remainingAttempts")).isFalse();
    }

    /**
     * Borda (P5): conta existente em estado que não admite reingresso por este convite - ATIVO ou
     * AGUARDANDO_APROVACAO no mesmo grupo, usuário de OUTRO grupo ou sem grupo - mesmo com o código
     * certo responde como código inválido (400 com remainingAttempts=4, conta tentativa); o convite
     * continua ENVIADO e a membership não muda.
     */
    @ParameterizedTest
    @CsvSource({"ATIVO", "AGUARDANDO_APROVACAO", "OUTRO_GRUPO", "SEM_GRUPO"})
    @DisplayName("AC-004.7: conta existente fora de RECUSADO/EXPIRADO/REMOVIDO do grupo responde como código inválido (não vaza existência)")
    void ac_004_7_conta_em_estado_incompativel_responde_como_codigo_invalido(String situacao) throws Exception {
        Grupo grupo = grupoComIdoso();
        // Cada linha da tabela = uma situação da conta que tem convite antigo/vigente deste grupo.
        User conta = switch (situacao) {
            case "ATIVO" -> membro(grupo, MembershipStatus.ATIVO);
            case "AGUARDANDO_APROVACAO" -> membro(grupo, MembershipStatus.AGUARDANDO_APROVACAO);
            case "OUTRO_GRUPO" -> membro(grupoComIdoso(), MembershipStatus.ATIVO);
            default -> contaAtivaSemGrupo();
        };
        UUID convite = semearConvite(
                grupo, conta.getEmail(), "ENVIADO", clock.instant().plus(Duration.ofDays(7)), CODIGO);
        Object statusAntes = jdbc.queryForList("SELECT status FROM memberships WHERE user_id = ?", conta.getId());

        MvcResult result = mockMvc.perform(resgatar(conta.getEmail(), CODIGO))
                .andExpect(status().isBadRequest())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("remainingAttempts").asInt()).isEqualTo(4);
        assertThat(ContractSchemas.schemaFor("InvalidCodeProblem").validate(body)).isEmpty();
        assertThat(statusDe(convite)).isEqualTo("ENVIADO");
        assertThat(jdbc.queryForList("SELECT status FROM memberships WHERE user_id = ?", conta.getId()))
                .isEqualTo(statusAntes);
    }

    // ============================================================ helpers de requisição

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private MockHttpServletRequestBuilder resgatar(String email, String codigo) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("code", codigo);
        return post(REDEEM)
                .contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content(json.writeValueAsString(corpo));
    }

    private MockHttpServletRequestBuilder login(String email, String senha) throws Exception {
        return post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", senha)));
    }

    /** Faz 4 erros (400) e a 5ª (423) e devolve o corpo do 423, validado contra o contrato. */
    private JsonNode bloquear(String email) throws Exception {
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(resgatar(email, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        }
        return esperar423(resgatar(email, CODIGO_ERRADO));
    }

    private JsonNode esperar423(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mockMvc.perform(req)
                .andExpect(status().isLocked())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("InviteBlockedProblem").validate(body);
        assertThat(violacoes).as("corpo casa com InviteBlockedProblem: %s", violacoes).isEmpty();
        return body;
    }

    private JsonNode semInstance(JsonNode body) {
        ObjectNode copia = body.deepCopy();
        copia.remove("instance");
        return copia;
    }

    // ============================================================ helpers de banco

    private String statusDe(UUID invitationId) {
        return jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
    }

    /** Contador de erros do e-mail; 0 quando não há linha. */
    private int falhasDe(String email) {
        List<Integer> v = jdbc.queryForList(
                "SELECT failed_attempts FROM invite_code_attempts WHERE email = ?", Integer.class, email);
        return v.isEmpty() ? 0 : v.getFirst();
    }

    /** Semeia um convite no status pedido, com o hash real (PasswordEncoder, D-04) do código dado. */
    private UUID semearConvite(Grupo grupo, String email, String status, Instant expiresAt, String codigo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, encoder.encode(codigo), status, Timestamp.from(expiresAt),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    private void semearTentativas(String email, int falhas, Instant bloqueadoAte) {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, ?, 0, ?)",
                email, falhas, bloqueadoAte == null ? null : Timestamp.from(bloqueadoAte),
                Timestamp.from(clock.instant()));
    }

    // ============================================================ fábricas de cenário

    private Grupo grupoComIdoso() {
        try {
            User responsavel = contaAtivaSemGrupo();
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(responsavel.getId(), clock))
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

    /** Conta com membership no grupo, no status pedido, e senha real {@link #SENHA} (hash BCrypt). */
    private User membro(Grupo grupo, MembershipStatus statusFinal) {
        User user = contaAtivaSemGrupo();
        UUID responsavel = grupo.responsavel().getId();
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        switch (statusFinal) {
            case ATIVO -> m.approve(Role.CUIDADOR, responsavel, clock);
            case AGUARDANDO_APROVACAO -> { /* fica pendente */ }
            case RECUSADO -> m.reject(responsavel, clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, responsavel, clock);
                m.remove(clock);
            }
            case EXPIRADO -> {
                memberships.save(m);
                clock.advance(Duration.ofHours(25));
                m.expire(clock);
            }
        }
        memberships.save(m);
        return user;
    }

    private User contaAtivaSemGrupo() {
        User user = User.signup(
                "Maria da Silva", emailNovo(), "21987654321", endereco(), encoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return "resgate-" + UUID.randomUUID() + "@teste.com";
    }

    private static Endereco endereco() {
        return new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
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
