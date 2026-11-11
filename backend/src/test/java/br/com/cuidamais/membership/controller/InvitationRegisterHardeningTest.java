package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-042 - enrijecimento (hardening) de {@code POST /api/v1/invitations/register}, tratando tres
 * ressalvas nao bloqueantes do verificador. Tres grupos:
 *
 * <ol>
 *   <li>Corrida register x cadastro comum ({@code POST /auth/signup}) pelo MESMO e-mail
 *       (AC-004.3, AC-003.10): no maximo uma conta; sem 500; se o register perde, 401 sem
 *       membership e convite continua ENVIADO; o signup sempre responde o 202 neutro (AC-001.4).
 *   <li>Tokens forjados por algoritmo/chave (AC-004.3, D-16): {@code alg=none}, HS384 com a mesma
 *       chave e HS256 com chave errada => 401 Problem, nada criado.
 *   <li>Ordem de validacao (AC-004.3/AC-004.4, R-07): token valido cujo convite ja nao serve (USADO,
 *       CANCELADO, vencido) ou e-mail que ja tem conta responde 401 SEM gastar o BCrypt da senha
 *       ({@code PasswordEncoder.encode} nunca chamado); no caminho feliz e chamado exatamente uma vez.
 * </ol>
 *
 * <p>Classe propria porque o {@link MockitoSpyBean} sobre o {@link PasswordEncoder} altera a
 * configuracao do contexto de teste. O limite de redeem por IP (R-03) e neutralizado por propriedade.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.invitations.redeem-rate-limit.max-requests=100000",
    "app.invitations.redeem-rate-limit.window=PT1M"
})
class InvitationRegisterHardeningTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String REGISTER = "/api/v1/invitations/register";
    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String ELDERS = "/api/v1/elders";
    private static final String SENHA = "Senha@Forte123";
    private static final String CODIGO = "123456";
    private static final int RODADAS = 8;

    private final ObjectMapper json = new ObjectMapper();

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

    @MockitoSpyBean
    PasswordEncoder encoder;

    // ============================================================ sanidade do spy

    /** Sanidade: o encoder injetado e um spy (senao as contagens do grupo 3 seriam vacuas). */
    @Test
    @DisplayName("AC-004.3: o PasswordEncoder do contexto e um spy do Mockito")
    void ac_004_3_encoder_e_spy() {
        assertThat(mockingDetails(encoder).isSpy()).isTrue();
    }

    // ============================================================ 1) corrida register x signup

    /**
     * Corrida register x signup pelo mesmo e-mail, repetida em {@value #RODADAS} rodadas com largada
     * simultanea. Invariantes por rodada: register so 201/401; signup sempre 202 (resposta neutra de
     * AC-001.4, inclusive quando perde a corrida); exatamente uma conta para o e-mail; se o register
     * venceu (201) ha 1 membership e convite USADO; se perdeu (401) NAO ha membership e o convite
     * segue ENVIADO.
     */
    @Test
    @DisplayName("AC-004.3/AC-003.10: register x signup simultâneos pelo mesmo e-mail => no máximo 1 conta, sem 500; register perdedor = 401 sem membership e convite ENVIADO")
    void ac_004_3_corrida_register_x_signup_cria_no_maximo_uma_conta() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            // Etapa: convite novo resgatado de verdade; o e-mail ainda nao tem conta.
            Grupo grupo = grupoComIdoso();
            String email = "corrida-" + UUID.randomUUID() + "@teste.com";
            UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofDays(7)));
            String token = resgatar(email);
            String corpoRegister = json.writeValueAsString(corpoRegister(token));
            String corpoSignup = json.writeValueAsString(corpoSignup(email));

            // Etapa: as duas requisicoes largam juntas numa barreira.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int statusRegister;
            int statusSignup;
            try {
                CountDownLatch pronto = new CountDownLatch(2);
                CountDownLatch largada = new CountDownLatch(1);
                Future<Integer> fRegister = pool.submit(() -> {
                    pronto.countDown();
                    largada.await();
                    return statusDe(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(corpoRegister));
                });
                Future<Integer> fSignup = pool.submit(() -> {
                    pronto.countDown();
                    largada.await();
                    return statusDe(post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(corpoSignup));
                });
                assertThat(pronto.await(30, TimeUnit.SECONDS)).isTrue();
                largada.countDown();
                statusRegister = fRegister.get(60, TimeUnit.SECONDS);
                statusSignup = fSignup.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            String ctx = "rodada " + rodada + ", register=" + statusRegister + ", signup=" + statusSignup;
            // Etapa: nenhum 500; o signup responde sempre o 202 neutro.
            assertThat(statusRegister).as("register sem 500 - %s", ctx).isIn(201, 401);
            assertThat(statusSignup).as("signup 202 neutro - %s", ctx).isEqualTo(202);
            // Etapa: no maximo (exatamente) uma conta para o e-mail.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                    .as("uma conta - %s", ctx).isEqualTo(1);
            int memberships = jdbc.queryForObject(
                    "SELECT count(*) FROM memberships m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                    Integer.class, email);
            String statusConvite =
                    jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, convite);
            if (statusRegister == 201) {
                // Etapa: register venceu => vinculo criado e convite consumido.
                assertThat(memberships).as("1 membership - %s", ctx).isEqualTo(1);
                assertThat(statusConvite).as("convite USADO - %s", ctx).isEqualTo("USADO");
            } else {
                // Etapa: register perdeu => nada vinculado e convite intacto.
                assertThat(memberships).as("sem membership - %s", ctx).isZero();
                assertThat(statusConvite).as("convite ENVIADO - %s", ctx).isEqualTo("ENVIADO");
            }
        }
    }

    // ============================================================ 2) tokens forjados por algoritmo/chave

    /**
     * JWT {@code alg=none} (sem assinatura, claims validas email/inv/typ): a biblioteca/servico nao
     * pode aceitar token nao assinado => 401 Problem e nada criado.
     */
    @Test
    @DisplayName("AC-004.3/D-16: registrationToken alg=none (sem assinatura) com claims válidas => 401 e nada criado")
    void ac_004_3_token_alg_none_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        long agora = clock.instant().getEpochSecond();
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64(claimsJson(c, agora));
        // Assinatura vazia, como no ataque classico de alg=none.
        String semAssinatura = header + "." + payload + ".";

        esperar401(registrar(semAssinatura));

        assertNadaCriado(c);
    }

    /**
     * Token HS384 assinado com a MESMA chave do servidor (HMAC-SHA384 montado a mao, pois a chave de
     * 32 bytes e fraca para o JJWT assinar HS384): o servico so aceita HS256 emitido por ele => 401.
     */
    @Test
    @DisplayName("AC-004.3/D-16: registrationToken HS384 com a mesma chave => 401 e nada criado")
    void ac_004_3_token_hs384_com_a_mesma_chave_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        long agora = clock.instant().getEpochSecond();
        String assinando = b64("{\"alg\":\"HS384\",\"typ\":\"JWT\"}") + "." + b64(claimsJson(c, agora));
        Mac mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(segredoJwt.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        String assinatura = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(assinando.getBytes(StandardCharsets.US_ASCII)));

        esperar401(registrar(assinando + "." + assinatura));

        assertNadaCriado(c);
    }

    /** Token HS256 assinado com chave errada (claims validas): assinatura nao confere => 401. */
    @Test
    @DisplayName("AC-004.3/D-16: registrationToken HS256 com chave errada => 401 e nada criado")
    void ac_004_3_token_hs256_com_chave_errada_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        String forjado = Jwts.builder()
                .claim("typ", "invite-registration")
                .claim("email", c.email())
                .claim("inv", c.convite().toString())
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(15))))
                .signWith(Keys.hmacShaKeyFor(
                        "chave-errada-qualquer-com-mais-de-32-bytes-1111".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();

        esperar401(registrar(forjado));

        assertNadaCriado(c);
    }

    // ============================================================ 3) validar antes do BCrypt

    /** Caminho feliz: exatamente uma chamada a {@code encode} (o hash da senha do novo usuario). */
    @Test
    @DisplayName("AC-004.4: register feliz chama PasswordEncoder.encode exatamente uma vez")
    void ac_004_4_caminho_feliz_gasta_um_encode() throws Exception {
        Cenario c = cenarioComTokenValido();
        clearInvocations(encoder);

        mockMvc.perform(registrar(c.token())).andExpect(status().isCreated());

        assertThat(chamadasAEncode()).isEqualTo(1);
    }

    /** Convite ja USADO (primeiro register consumiu): o segundo register e 401 sem BCrypt. */
    @Test
    @DisplayName("AC-003.10/AC-004.3: convite já USADO => 401 sem chamar encode")
    void ac_003_10_convite_usado_nao_gasta_encode() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(registrar(c.token())).andExpect(status().isCreated());
        clearInvocations(encoder);

        esperar401(registrar(c.token()));

        assertThat(chamadasAEncode()).as("encode antes da checagem do convite").isZero();
    }

    /** Convite CANCELADO pelo Responsavel entre redeem e register: 401 sem BCrypt. */
    @Test
    @DisplayName("AC-004.3: convite CANCELADO => 401 sem chamar encode")
    void ac_004_3_convite_cancelado_nao_gasta_encode() throws Exception {
        Cenario c = cenarioComTokenValido();
        mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/cancel", c.grupo().elderId(), c.convite())
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.issue(c.grupo().responsavel().getId(), clock)))
                .andExpect(status().is2xxSuccessful());
        clearInvocations(encoder);

        esperar401(registrar(c.token()));

        assertThat(chamadasAEncode()).as("encode antes da checagem do convite").isZero();
    }

    /** Convite vence (pelo Clock) com o token ainda valido: 401 sem BCrypt. */
    @Test
    @DisplayName("AC-004.3: convite VENCIDO (token ainda válido) => 401 sem chamar encode")
    void ac_004_3_convite_vencido_nao_gasta_encode() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearConvite(grupo, email, clock.instant().plus(Duration.ofMinutes(10)));
        String token = resgatar(email);
        clock.advance(Duration.ofMinutes(11));
        clearInvocations(encoder);

        esperar401(registrar(token));

        assertThat(chamadasAEncode()).as("encode antes da checagem do convite").isZero();
    }

    /** E-mail que ganhou conta entre redeem e register: 401 sem BCrypt. */
    @Test
    @DisplayName("AC-004.3: e-mail que já tem conta => 401 sem chamar encode")
    void ac_004_3_email_com_conta_nao_gasta_encode() throws Exception {
        Cenario c = cenarioComTokenValido();
        User existente = User.signup("Dona Original", c.email(), "21999990000", endereco(),
                encoder.encode("Original#123"), clock);
        existente.verifyEmail(clock);
        users.save(existente);
        clearInvocations(encoder);

        esperar401(registrar(c.token()));

        assertThat(chamadasAEncode()).as("encode antes da checagem da conta").isZero();
    }

    // ============================================================ 4) janela entre pre-checagem e lock

    /**
     * Grupo 4 (AC-004.3, AC-003.10): a pre-checagem sem lock passa e o estado muda DURANTE o BCrypt
     * (apos a pre-checagem, antes do lock do contador). O {@code doAnswer} em {@code encode} roda a
     * mudanca em conexao propria (autocommit/transacao do repositorio), portanto ja commitada no
     * banco quando o register chega ao lock. Prova que a releitura (refresh) + checagem sob lock e
     * a que vale: sem ela o register usaria o convite obsoleto do contexto de persistencia.
     */
    private void aoCodificarSenhaDoRegister(Runnable mudanca) {
        doAnswer(inv -> {
            // so o hash da senha do register dispara a mudanca (o setup usa outras senhas)
            if ("Convidada#2026".equals(inv.getArgument(0))) {
                emOutraThread(mudanca);
            }
            return inv.callRealMethod();
        }).when(encoder).encode(any(CharSequence.class));
    }

    /**
     * Executa a mudanca em OUTRA thread e espera. Necessario porque o register roda numa transacao
     * ligada a thread atual: JdbcTemplate/repositorios nesta thread entrariam NA transacao do
     * register (e seriam desfeitos com o 401), em vez de commitar de verdade antes do lock.
     */
    private static void emOutraThread(Runnable mudanca) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(mudanca).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("mudanca na janela falhou", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertSemEfeitos(Cenario c, int usuariosComEmail, String conviteEsperado) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, c.email()))
                .as("usuarios com o e-mail").isEqualTo(usuariosComEmail);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE group_id = ?", Integer.class, c.grupo().groupId()))
                .as("nenhuma membership nova (so a do Responsavel)").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, c.convite()))
                .as("convite no estado imposto pela janela").isEqualTo(conviteEsperado);
    }

    /** Convite cancelado por outro caminho (ja commitado) durante o BCrypt: 401, nada criado, segue CANCELADO. */
    @Test
    @DisplayName("AC-004.3/AC-003.10: convite cancelado durante o BCrypt (apos a pre-checagem) => 401, sem conta nem membership")
    void ac_004_3_convite_cancelado_na_janela_antes_do_lock_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cenario: pre-checagem ve ENVIADO; durante o encode o convite vira CANCELADO no banco.
        aoCodificarSenhaDoRegister(() -> jdbc.update(
                "UPDATE invitations SET status = 'CANCELADO', cancelled_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), c.convite()));

        esperar401(registrar(c.token()));

        assertSemEfeitos(c, 0, "CANCELADO");
    }

    /** Convite consumido (USADO) por outro caminho durante o BCrypt: 401, nada criado, segue USADO. */
    @Test
    @DisplayName("AC-004.3/AC-003.10: convite marcado USADO durante o BCrypt (apos a pre-checagem) => 401, sem conta nem membership")
    void ac_004_3_convite_usado_na_janela_antes_do_lock_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cenario: pre-checagem ve ENVIADO; durante o encode outro register consome o convite.
        aoCodificarSenhaDoRegister(() -> jdbc.update(
                "UPDATE invitations SET status = 'USADO', used_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), c.convite()));

        esperar401(registrar(c.token()));

        assertSemEfeitos(c, 0, "USADO");
    }

    /**
     * Convite vence pelo relogio durante o BCrypt (token de 15 min ainda valido, convite de 10 min
     * ja vencido): a checagem sob lock usa o Clock e recusa; convite continua ENVIADO no banco.
     */
    @Test
    @DisplayName("AC-004.3/AC-003.10: convite vence pelo relógio durante o BCrypt (apos a pre-checagem) => 401, sem conta nem membership")
    void ac_004_3_convite_vencido_pelo_relogio_na_janela_antes_do_lock_e_401() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofMinutes(10)));
        Cenario c = new Cenario(grupo, email, convite, resgatar(email));
        // Cenario: pre-checagem ve convite vigente; durante o encode o relogio passa de 10 min (< 15 do token).
        aoCodificarSenhaDoRegister(() -> clock.advance(Duration.ofMinutes(11)));

        esperar401(registrar(c.token()));

        assertSemEfeitos(c, 0, "ENVIADO");
    }

    /**
     * Conta com o mesmo e-mail criada (commitada) durante o BCrypt, ex.: cadastro comum: o register
     * perde, responde 401 sem membership, o convite segue ENVIADO e existe exatamente 1 usuario.
     */
    @Test
    @DisplayName("AC-004.3/AC-003.10: conta com o mesmo e-mail criada durante o BCrypt (apos a pre-checagem) => 401, sem duplicar usuário nem membership")
    void ac_003_10_conta_criada_na_janela_antes_do_lock_e_401() throws Exception {
        Cenario c = cenarioComTokenValido();
        // Cenario: pre-checagem ve e-mail livre; durante o encode outro caminho cria a conta (commit proprio).
        aoCodificarSenhaDoRegister(() -> {
            User existente = User.signup("Dona Original", c.email(), "21999990000", endereco(),
                    "hash-qualquer-nao-usado", clock);
            existente.verifyEmail(clock);
            users.saveAndFlush(existente);
        });

        esperar401(registrar(c.token()));

        assertSemEfeitos(c, 1, "ENVIADO");
    }

    // ============================================================ helpers

    private int chamadasAEncode() {
        return (int) mockingDetails(encoder).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("encode"))
                .count();
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String claimsJson(Cenario c, long agoraEpoch) {
        return "{\"typ\":\"invite-registration\",\"email\":\"" + c.email() + "\",\"inv\":\"" + c.convite()
                + "\",\"iat\":" + agoraEpoch + ",\"exp\":" + (agoraEpoch + 900) + "}";
    }

    private int statusDe(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private record Cenario(Grupo grupo, String email, UUID convite, String token) {}

    private Cenario cenarioComTokenValido() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, clock.instant().plus(Duration.ofDays(7)));
        return new Cenario(grupo, email, convite, resgatar(email));
    }

    private String resgatar(String email) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String ip = "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
        MvcResult res = mockMvc.perform(post(REDEEM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(json.writeValueAsString(Map.of("email", email, "code", CODIGO))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).path("registrationToken").asText();
    }

    private MockHttpServletRequestBuilder registrar(String token) throws Exception {
        return post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpoRegister(token)));
    }

    private void esperar401(MockHttpServletRequestBuilder req) throws Exception {
        mockMvc.perform(req)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    private static Map<String, Object> enderecoJson() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        return end;
    }

    private Map<String, Object> corpoRegister(String token) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("registrationToken", token);
        c.put("name", "Joana Souza");
        c.put("phone", "21912345678");
        c.put("address", enderecoJson());
        c.put("password", "Convidada#2026");
        return c;
    }

    private Map<String, Object> corpoSignup(String email) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Joana Cadastro Comum");
        c.put("email", email);
        c.put("phone", "21912345678");
        c.put("address", enderecoJson());
        c.put("password", "Comum#2026abc");
        return c;
    }

    private void assertNadaCriado(Cenario c) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, c.email()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE group_id = ?", Integer.class, c.grupo().groupId()))
                .as("só a membership do Responsável")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, c.convite()))
                .isEqualTo("ENVIADO");
    }

    private UUID semearConvite(Grupo grupo, String email, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, encoder.encode(CODIGO), Timestamp.from(expiresAt),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    private static String emailNovo() {
        return "convidada-" + UUID.randomUUID() + "@teste.com";
    }

    private static Endereco endereco() {
        return new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
    }

    /** Responsavel ATIVO que cria o idoso (e o grupo) pela API real. */
    private Grupo grupoComIdoso() throws Exception {
        User user = User.signup("Maria da Silva", "resp-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco(), encoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        User responsavel = users.save(user);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", "Francisca Oliveira");
        req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
        req.put("address", enderecoJson());
        req.put("emergencyContacts", new ArrayList<>(List.of()));
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
}
