package br.com.cuidamais.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.auth.service.RefreshTokenService;
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
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-025 — {@code POST /api/v1/auth/refresh} (contrato {@code operationId: refreshSession}) e
 * {@code POST /api/v1/auth/logout} ({@code operationId: logout}), caixa-preta pela rota HTTP real
 * (contexto Spring completo, MockMvc com a cadeia de filtros do Spring Security, PostgreSQL via
 * Testcontainers), conferindo o efeito na tabela {@code refresh_tokens} por SQL puro.
 *
 * <p>Cobre: AC-008.3 (a sessão é renovada pelo cookie {@code refresh_token}; 7 dias sem uso ou 30
 * dias desde a entrada exigem entrar de novo), AC-008.5 ("Sair" encerra a sessão <strong>naquele
 * aparelho</strong>, na hora) e o "Pronto quando" da tarefa: depois do logout, o refresh token
 * daquele aparelho não renova mais e os de outros aparelhos (outros logins do mesmo usuário =
 * outras famílias, D-11 rev. 3) continuam válidos. Decisões: D-11 (rotação de uso único, família,
 * detecção de reuso revoga a família), D-12 (cookie {@code HttpOnly; Secure; SameSite=Strict;
 * Path=/api/v1/auth}), D-13 ({@code /auth/logout} usa o bearer normal, com {@code
 * x-required-account-state: any}), D-10 (relógio controlado pelo {@link MutableClock}).
 *
 * <p><strong>Desenho esperado</strong> (a ser criado pelo implementador; os testes só observam o
 * HTTP e o banco, então os nomes internos abaixo são a proposta do orquestrador, não uma exigência
 * de compilação):
 *
 * <ul>
 *   <li>{@code AuthController#refresh}: {@code @PublicEndpoint} (o contrato usa só {@code
 *       security: [refreshCookie]}, nunca o bearer — o access token pode estar vencido, é por isso
 *       que se chama refresh), {@code @PostMapping("/refresh")}, lê o cookie {@code refresh_token}
 *       (ausente → 401), chama {@link RefreshTokenService#rotate}, emite um JWT novo e responde 200
 *       {@code SessionStarted} com o cookie rotacionado (mesmo formato de login/verify-email).
 *       Qualquer {@code IllegalStateException} de {@code rotate} (desconhecido, expirado, reuso) →
 *       401 {@code Problem} (sugestão: reaproveitar {@code UnauthorizedSessionException}, já
 *       mapeada para 401 em {@code GlobalExceptionHandler}).
 *   <li>{@code AuthController#logout}: {@code @RequiredAccountState(ANY)} (bearer obrigatório,
 *       qualquer estado, inclusive {@code REMOVIDO}), {@code @PostMapping("/logout")}, lê o cookie
 *       {@code refresh_token} opcional; se presente e conhecido <strong>e</strong> dono do token
 *       for a mesma conta do bearer, revoga a <strong>família inteira</strong> daquele token
 *       (sugestão: {@code RefreshTokenService#revokeDeviceByPlainToken(String, UUID)}) — não só a
 *       linha apresentada, porque o aparelho pode apresentar um token antigo já rotacionado e a
 *       sessão precisa mesmo parar de renovar; cookie ausente, desconhecido ou de outra conta não é
 *       erro e não revoga nada (idempotente, silencioso). Sempre 204 com o cookie de limpeza
 *       {@code refresh_token=; Max-Age=0; Path=/api/v1/auth; HttpOnly; Secure;
 *       SameSite=Strict} (sugestão: {@code SessionCookies#clearRefreshTokenCookie()}).
 * </ul>
 *
 * <p>Respostas 401 de requisições que já violam o próprio contrato (sem cookie em {@code refresh},
 * sem bearer em {@code logout}) são validadas contra o schema {@code Problem} via {@link
 * ContractSchemas} em vez de {@link ContractValidation#matchesContract()}, porque o validador
 * recusaria a <em>requisição</em> por falta da credencial exigida (mesmo padrão de {@code
 * LoginTest}/{@code RequestAuthorizationTest}).
 *
 * <p>O banco não é limpo entre testes: cada teste cria suas próprias contas (e-mail único). O
 * {@link MutableClock} é compartilhado pelo contexto; os testes de prazo só o avançam e medem
 * sempre a partir de {@code clock.instant()}.
 */
@AutoConfigureMockMvc
class RefreshAndLogoutTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String COOKIE = "refresh_token";
    private static final String SENHA = "Senha@Forte123";
    private static final long MAX_AGE_7_DIAS = Duration.ofDays(7).toSeconds();
    private static final int EXPIRES_IN_SEGUNDOS = 900;

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
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtService jwtService;

    @Autowired
    RefreshTokenService refreshTokenService;

    // ============================================================ AC-008.3 /auth/refresh renova

    /**
     * AC-008.3 / D-11 caminho feliz: com o cookie que o login real entregou, {@code /auth/refresh}
     * responde 200 {@code SessionStarted} conforme o contrato (requisição com o cookie exigido por
     * {@code refreshCookie} e resposta com {@code AccessToken} no corpo).
     */
    @Test
    @DisplayName("AC-008.3 refresh com o cookie do login → 200 SessionStarted, conforme o contrato")
    void ac_008_3_refresh_com_cookie_valido_responde_200_conforme_contrato() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));

        mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-008.3 / D-11 "rotaciona o cookie": o 200 traz um {@code Set-Cookie refresh_token} com um
     * valor opaco <strong>diferente</strong> do apresentado (uso único), com os atributos de D-12
     * e a nova janela de 7 dias ({@code Max-Age=604800}), já que o login acabou de acontecer e o
     * teto de 30 dias está longe.
     */
    @Test
    @DisplayName("AC-008.3 / D-12 refresh rotaciona o cookie: valor novo, HttpOnly/Secure/SameSite=Strict, Path, Max-Age 7 dias")
    void ac_008_3_refresh_rotaciona_o_cookie() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));

        MvcResult result = mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = setCookieRefreshToken(result);
        assertThat(setCookie).as("Set-Cookie refresh_token presente").isNotNull();
        assertThat(valorDoCookie(setCookie))
                .as("token rotacionado é outro valor")
                .isNotBlank()
                .isNotEqualTo(sessao.refreshToken());
        assertThat(setCookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=" + MAX_AGE_7_DIAS);
    }

    /**
     * AC-008.3 / D-11: o corpo do 200 traz um access token Bearer de 900 s cujo {@code sub} é a
     * própria conta dona do refresh token (conferido pelo {@link JwtService} da T-021).
     */
    @Test
    @DisplayName("AC-008.3 / D-11 refresh devolve novo AccessToken Bearer de 900 s da própria conta")
    void ac_008_3_refresh_devolve_novo_access_token_da_conta() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);

        MvcResult result = mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode corpo = corpo(result);
        assertThat(corpo.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(corpo.path("expiresIn").asInt()).isEqualTo(EXPIRES_IN_SEGUNDOS);
        assertThat(jwtService.parseSubject(corpo.path("accessToken").asText(), clock)).isEqualTo(user.getId());
    }

    /**
     * D-11 rotação persistida: depois do refresh, a família do login tem duas linhas — a original,
     * agora revogada (uso único), e a nova, ativa e com o mesmo {@code family_id}. Prova que o
     * controller usa {@link RefreshTokenService#rotate} (mesma família), e não um {@code issue}
     * novo (família nova), o que quebraria a detecção de reuso.
     */
    @Test
    @DisplayName("D-11 refresh grava a rotação: token antigo revogado, novo ativo na mesma família")
    void d_11_refresh_grava_rotacao_na_mesma_familia() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);

        mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk());

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT family_id, revoked_at FROM refresh_tokens WHERE user_id = ? ORDER BY created_at, revoked_at NULLS LAST",
                user.getId());
        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0).get("family_id")).isEqualTo(linhas.get(1).get("family_id"));
        assertThat(linhas.stream().filter(l -> l.get("revoked_at") == null).count())
                .as("exatamente uma linha ativa (a nova)")
                .isEqualTo(1);
    }

    /**
     * AC-008.3 "permanece ativa ... com uso": o cookie rotacionado é por sua vez aceito num
     * segundo refresh (200) — a cadeia de renovações continua funcionando enquanto o app é usado.
     */
    @Test
    @DisplayName("AC-008.3 o cookie rotacionado renova de novo (cadeia de refresh)")
    void ac_008_3_cookie_rotacionado_renova_de_novo() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        String segundo = valorDoCookie(setCookieRefreshToken(
                mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk()).andReturn()));

        mockMvc.perform(renovar(segundo))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * D-11 / contrato {@code security: [refreshCookie]} ({@code @PublicEndpoint}): o motivo de
     * chamar refresh é o access token ter vencido. Um header {@code Authorization} com um JWT
     * vencido há 1 minuto não pode transformar o refresh em 401 — o cookie válido basta (200).
     */
    @Test
    @DisplayName("D-11 refresh ignora o bearer: com access token vencido e cookie válido → 200")
    void d_11_refresh_aceita_access_token_vencido() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);
        Clock emissao = Clock.fixed(clock.instant().minus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        String accessVencido = jwtService.issue(user.getId(), emissao);

        mockMvc.perform(renovar(sessao.refreshToken()).header(HttpHeaders.AUTHORIZATION, bearer(accessVencido)))
                .andExpect(status().isOk());
    }

    // ============================================================ AC-008.3 prazos (7 dias / 30 dias)

    /**
     * AC-008.3 "até 7 dias sem uso": 6 dias e 23 horas depois do login, sem nenhum uso no meio, o
     * refresh ainda renova (200). Lado de dentro da janela, para o teste seguinte provar o prazo.
     */
    @Test
    @DisplayName("AC-008.3 refresh 1 hora antes dos 7 dias sem uso ainda renova (200)")
    void ac_008_3_refresh_dentro_da_janela_de_7_dias_renova() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        clock.advance(Duration.ofDays(7).minusHours(1));

        mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk());
    }

    /**
     * AC-008.3 "depois disso é preciso entrar de novo": exatamente 7 dias sem uso (limite
     * exclusivo, D-11), o refresh é recusado com 401 {@code Problem} conforme o contrato e não
     * devolve cookie novo.
     */
    @Test
    @DisplayName("AC-008.3 7 dias sem uso → refresh 401 Problem, conforme o contrato, sem cookie novo")
    void ac_008_3_sete_dias_sem_uso_responde_401() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        clock.advance(Duration.ofDays(7));

        MvcResult result = mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(valorDoCookieOuNulo(result)).as("nenhum token novo emitido").isNullOrEmpty();
    }

    /**
     * AC-008.3 "no máximo 30 dias desde a entrada" / D-12 Max-Age coerente: com uso a cada 6 dias,
     * o refresh feito no dia 24 devolve um cookie cujo {@code Max-Age} é o que falta para o teto
     * (6 dias), não 7 — a janela nova nunca ultrapassa os 30 dias do login original. Cada etapa é
     * um uso do app.
     */
    @Test
    @DisplayName("AC-008.3 / D-12 refresh no dia 24 devolve Max-Age de 6 dias (limitado ao teto de 30)")
    void ac_008_3_max_age_limitado_ao_teto_de_30_dias() throws Exception {
        String token = entrar(users.save(contaAtiva())).refreshToken();

        // Etapas 1 a 3: usos nos dias 6, 12 e 18 — todos renovam com a janela cheia.
        for (int uso = 1; uso <= 3; uso++) {
            clock.advance(Duration.ofDays(6));
            token = valorDoCookie(setCookieRefreshToken(
                    mockMvc.perform(renovar(token)).andExpect(status().isOk()).andReturn()));
        }

        // Etapa 4: uso no dia 24 — faltam 6 dias para o teto, então o Max-Age é 6 dias.
        clock.advance(Duration.ofDays(6));
        MvcResult dia24 = mockMvc.perform(renovar(token)).andExpect(status().isOk()).andReturn();

        assertThat(setCookieRefreshToken(dia24)).contains("Max-Age=" + Duration.ofDays(6).toSeconds());
    }

    /**
     * AC-008.3 "no máximo 30 dias desde a entrada, mesmo com uso": renovando a cada 6 dias (nunca
     * 7 dias sem uso), no dia 30 o refresh é recusado com 401 — o teto absoluto vale mesmo para
     * quem usa o app todo dia. Cada etapa é um uso.
     */
    @Test
    @DisplayName("AC-008.3 mesmo usando a cada 6 dias, no dia 30 o refresh responde 401")
    void ac_008_3_teto_de_30_dias_mesmo_com_uso_responde_401() throws Exception {
        String token = entrar(users.save(contaAtiva())).refreshToken();

        // Etapas 1 a 4: usos nos dias 6, 12, 18 e 24 — todos renovam.
        for (int uso = 1; uso <= 4; uso++) {
            clock.advance(Duration.ofDays(6));
            token = valorDoCookie(setCookieRefreshToken(
                    mockMvc.perform(renovar(token)).andExpect(status().isOk()).andReturn()));
        }

        // Etapa 5: dia 30 — teto absoluto atingido (limite exclusivo), é preciso entrar de novo.
        clock.advance(Duration.ofDays(6));
        mockMvc.perform(renovar(token))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    // ============================================================ AC-008.3 refresh recusado

    /**
     * AC-008.3 / contrato 401: um valor de cookie que nunca foi emitido (nenhuma linha com esse
     * hash) → 401 {@code Problem} conforme o contrato — não 500, não 403.
     */
    @Test
    @DisplayName("AC-008.3 refresh com token desconhecido → 401 Problem, conforme o contrato")
    void ac_008_3_refresh_com_token_desconhecido_responde_401() throws Exception {
        mockMvc.perform(renovar("token-que-nunca-foi-emitido-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-008.3 / D-04: um token real com um caractere trocado é outro hash, portanto desconhecido
     * → 401, e nada é gravado para a conta (continua só a linha do login, ativa).
     */
    @Test
    @DisplayName("AC-008.3 refresh com token adulterado → 401 e nada muda no banco")
    void ac_008_3_refresh_com_token_adulterado_responde_401() throws Exception {
        User user = users.save(contaAtiva());
        String real = entrar(user).refreshToken();
        char ultimo = real.charAt(real.length() - 1);
        String adulterado = real.substring(0, real.length() - 1) + (ultimo == 'A' ? 'B' : 'A');

        mockMvc.perform(renovar(adulterado))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());

        assertThat(linhasAtivasDe(user.getId())).as("o token do login continua o único, ativo").isEqualTo(1);
        assertThat(totalDeLinhasDe(user.getId())).isEqualTo(1);
    }

    /**
     * Contrato 401 de {@code refreshSession}: sem cookie nenhum → 401 {@code Problem} (não o 400
     * nem o 500 que um {@code @CookieValue} obrigatório daria por padrão). Validado contra o schema
     * {@code Problem}, porque a própria requisição viola o contrato (falta a credencial).
     */
    @Test
    @DisplayName("AC-008.3 refresh sem cookie → 401 Problem")
    void ac_008_3_refresh_sem_cookie_responde_401() throws Exception {
        esperaProblem401(post(REFRESH));
    }

    // ============================================================ D-11 reuso

    /**
     * D-11 detecção de reuso, propagada pelo controller: depois de um refresh bem-sucedido,
     * reapresentar o token <strong>antigo</strong> (já usado) → 401 {@code Problem} conforme o
     * contrato.
     */
    @Test
    @DisplayName("D-11 reapresentar um refresh token já usado → 401, conforme o contrato")
    void d_11_reuso_do_token_antigo_responde_401() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk());

        mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * D-11 "reapresentar um token já usado revoga a família inteira": o reuso do token antigo
     * derruba também o token novo que a rotação legítima tinha entregado — ele deixa de renovar
     * (401). Prova que a revogação em transação isolada de {@code rotate} sobrevive ao 401 que o
     * controller devolve (não é desfeita junto com a resposta de erro).
     */
    @Test
    @DisplayName("D-11 depois do reuso, o token novo da mesma família também deixa de renovar (401)")
    void d_11_reuso_revoga_a_familia_inteira() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);
        // Etapa 1: rotação legítima entrega o token novo.
        String novo = valorDoCookie(setCookieRefreshToken(
                mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk()).andReturn()));
        // Etapa 2: alguém reapresenta o token antigo (reuso).
        mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isUnauthorized());

        // Etapa 3: o token novo também foi revogado — banco e HTTP confirmam.
        assertThat(linhasAtivasDe(user.getId())).as("família inteira revogada").isZero();
        mockMvc.perform(renovar(novo)).andExpect(status().isUnauthorized());
    }

    // ============================================================ AC-008.5 /auth/logout

    /**
     * AC-008.5 / contrato {@code logout} 204: com sessão (bearer) e o cookie do aparelho, "Sair"
     * responde 204 conforme o contrato e devolve o cookie de limpeza exatamente como o contrato
     * descreve: valor vazio, {@code Max-Age=0}, {@code Path=/api/v1/auth}, {@code HttpOnly},
     * {@code Secure}, {@code SameSite=Strict}.
     */
    @Test
    @DisplayName("AC-008.5 logout → 204 conforme o contrato, com Set-Cookie de limpeza (vazio, Max-Age=0)")
    void ac_008_5_logout_responde_204_e_limpa_o_cookie() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));

        MvcResult result = mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken()))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        esperaCookieDeLimpeza(result);
    }

    /**
     * AC-008.5 "encerra a sessão ... na hora": logo depois do 204, a linha do refresh token daquele
     * aparelho já está revogada no banco ({@code revoked_at} preenchido com o "agora").
     */
    @Test
    @DisplayName("AC-008.5 logout revoga na hora o refresh token daquele aparelho no banco")
    void ac_008_5_logout_revoga_o_token_no_banco() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);

        mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken())).andExpect(status().isNoContent());

        assertThat(linhasAtivasDe(user.getId())).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT revoked_at FROM refresh_tokens WHERE user_id = ?", java.sql.Timestamp.class, user.getId())
                        .toInstant())
                .isEqualTo(clock.instant());
    }

    /**
     * "Pronto quando" da T-025 (primeira metade) / AC-008.5: depois do logout, o refresh token
     * daquele aparelho não renova mais — {@code /auth/refresh} com o mesmo cookie → 401.
     */
    @Test
    @DisplayName("AC-008.5 depois do logout, o refresh token daquele aparelho não renova mais (401)")
    void ac_008_5_apos_logout_o_token_do_aparelho_nao_renova() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken())).andExpect(status().isNoContent());

        mockMvc.perform(renovar(sessao.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * "Pronto quando" da T-025 (prova central) / AC-008.5 "naquele aparelho": o mesmo usuário entra
     * em dois aparelhos (dois logins = duas famílias, D-11 rev. 3 — conferido por SQL); sair no
     * aparelho A não afeta o aparelho B, cujo refresh token continua ativo no banco e continua
     * renovando (200).
     */
    @Test
    @DisplayName("AC-008.5 / Pronto quando: logout no aparelho A não afeta o aparelho B, que continua renovando")
    void ac_008_5_logout_revoga_so_o_token_daquele_aparelho() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: dois logins do mesmo usuário — duas famílias diferentes.
        Aparelho aparelhoA = entrarComoAparelho(user);
        Aparelho aparelhoB = entrarComoAparelho(user);
        assertThat(aparelhoA.familia()).as("cada aparelho tem sua própria família").isNotEqualTo(aparelhoB.familia());

        // Etapa 2: sai no aparelho A.
        mockMvc.perform(sair(aparelhoA.sessao().accessToken(), aparelhoA.sessao().refreshToken()))
                .andExpect(status().isNoContent());

        // Etapa 3: a família A foi encerrada, a família B continua ativa no banco...
        assertThat(ativasNaFamilia(aparelhoA.familia())).as("família A sem token ativo").isZero();
        assertThat(ativasNaFamilia(aparelhoB.familia())).as("família B intacta").isEqualTo(1);
        // Etapa 4: ...e o aparelho B continua renovando a sessão.
        mockMvc.perform(renovar(aparelhoB.sessao().refreshToken()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-008.5 depois de rotações: "Sair" num aparelho que já renovou a sessão revoga o token
     * <strong>atual</strong> daquele aparelho (o último entregue pelo refresh), que deixa de
     * renovar — o logout não fica preso ao token do login original.
     */
    @Test
    @DisplayName("AC-008.5 logout com o token rotacionado encerra a sessão do aparelho (refresh 401)")
    void ac_008_5_logout_apos_rotacao_encerra_a_sessao() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);
        MvcResult renovado = mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk()).andReturn();
        String atual = valorDoCookie(setCookieRefreshToken(renovado));
        String accessAtual = corpo(renovado).path("accessToken").asText();

        mockMvc.perform(sair(accessAtual, atual)).andExpect(status().isNoContent());

        assertThat(linhasAtivasDe(user.getId())).isZero();
        mockMvc.perform(renovar(atual)).andExpect(status().isUnauthorized());
    }

    /**
     * D-11 / AC-008.5 (decisão do orquestrador na T-025: logout revoga a <strong>família
     * inteira</strong> do token apresentado): o aparelho apresenta, por engano ou corrida, o token
     * <em>antigo</em> já rotacionado (token1, revogado). Mesmo assim "Sair" precisa matar a sessão
     * daquele aparelho de verdade — o token atual da cadeia (token2, ainda ativo) também tem que
     * ficar com {@code revoked_at} preenchido e deixar de renovar. Revogar só a linha apresentada
     * não teria efeito nenhum aqui (ela já estava revogada), e o aparelho continuaria renovando.
     */
    @Test
    @DisplayName("AC-008.5 / D-11 logout com o token antigo (já rotacionado) revoga a família inteira, inclusive o token atual")
    void ac_008_5_logout_com_token_antigo_da_mesma_familia_revoga_tudo() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: login (família A, token1).
        Aparelho aparelho = entrarComoAparelho(user);
        String token1 = aparelho.sessao().refreshToken();

        // Etapa 2: refresh com token1 → token2; token1 revogado, token2 é a única linha ativa da família.
        String token2 = valorDoCookie(setCookieRefreshToken(
                mockMvc.perform(renovar(token1)).andExpect(status().isOk()).andReturn()));
        assertThat(ativasNaFamilia(aparelho.familia())).as("só token2 ativo antes do logout").isEqualTo(1);

        // Etapa 3: logout apresentando o cookie ANTIGO (token1, já revogado) → 204.
        mockMvc.perform(sair(aparelho.sessao().accessToken(), token1))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract());

        // Etapa 4: por SQL, a família inteira (token1 e token2) está com revoked_at preenchido.
        assertThat(ativasNaFamilia(aparelho.familia())).as("token2 também revogado").isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NOT NULL",
                        Integer.class, aparelho.familia()))
                .as("as duas linhas da família revogadas")
                .isEqualTo(2);

        // Etapa 5: token2 não renova mais.
        mockMvc.perform(renovar(token2))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-008.5 / D-13 (decisão do orquestrador na T-025: logout só revoga se o dono do refresh
     * token for o mesmo {@code userId} do bearer): quem está logado como A e apresenta, por engano
     * ou de propósito, o cookie de refresh de B não pode derrubar a sessão de B. A resposta é o
     * mesmo 204 silencioso do cookie desconhecido (não revela que o token existe nem de quem é), e
     * nada é revogado: o token de B continua com {@code revoked_at} nulo e continua renovando (200).
     */
    @Test
    @DisplayName("AC-008.5 / D-11 logout com bearer de A e refresh token de B → 204 e nada é revogado")
    void ac_008_5_logout_com_refresh_token_de_outra_conta_nao_revoga_nada() throws Exception {
        // Etapa 1: duas contas diferentes, cada uma com seu login (sua família).
        User userA = users.save(contaAtiva());
        User userB = users.save(contaAtiva());
        Sessao sessaoA = entrar(userA);
        Sessao sessaoB = entrar(userB);

        // Etapa 2: logout com o bearer de A e o cookie de B → 204 silencioso, conforme o contrato.
        mockMvc.perform(sair(sessaoA.accessToken(), sessaoB.refreshToken()))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract());

        // Etapa 3: por SQL, o token de B continua ativo (revoked_at nulo)...
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NOT NULL",
                        Integer.class, userB.getId()))
                .as("nenhuma linha de B revogada")
                .isZero();
        assertThat(linhasAtivasDe(userB.getId())).as("token de B ativo").isEqualTo(1);
        // ...e o de A também não foi tocado (o cookie apresentado não era dele).
        assertThat(linhasAtivasDe(userA.getId())).as("token de A intacto").isEqualTo(1);

        // Etapa 4: B continua renovando a sessão.
        mockMvc.perform(renovar(sessaoB.refreshToken()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract());
    }

    // ============================================================ logout idempotente

    /**
     * Decisão de desenho da T-025 (idempotência): com sessão válida mas <strong>sem</strong> cookie
     * {@code refresh_token} (ex. o cookie já tinha sido limpo), "Sair" ainda responde 204 com o
     * cookie de limpeza — não falha nem revela nada.
     */
    @Test
    @DisplayName("AC-008.5 logout sem cookie refresh_token → ainda 204 com cookie de limpeza")
    void ac_008_5_logout_sem_cookie_responde_204() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));

        MvcResult result = mockMvc.perform(post(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer(sessao.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        esperaCookieDeLimpeza(result);
    }

    /**
     * Decisão de desenho da T-025 (idempotência): cookie com um valor que não corresponde a nenhum
     * token → 204 com cookie de limpeza, e o refresh token real da conta (o do login) não é tocado
     * — continua ativo.
     */
    @Test
    @DisplayName("AC-008.5 logout com cookie desconhecido → 204 e nenhum token da conta é revogado")
    void ac_008_5_logout_com_cookie_desconhecido_responde_204() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);

        MvcResult result = mockMvc.perform(sair(sessao.accessToken(), "token-desconhecido-" + UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        esperaCookieDeLimpeza(result);
        assertThat(linhasAtivasDe(user.getId())).isEqualTo(1);
    }

    /**
     * Decisão de desenho da T-025 (idempotência): sair duas vezes com o mesmo cookie — a segunda
     * chamada, com o token já revogado, também responde 204 (sem 401/500 por "token já usado").
     */
    @Test
    @DisplayName("AC-008.5 logout repetido com o mesmo cookie → 204 nas duas vezes")
    void ac_008_5_logout_repetido_responde_204() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));
        mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken())).andExpect(status().isNoContent());

        mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken()))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract());
    }

    // ============================================================ logout exige sessão (bearer)

    /**
     * Contrato {@code logout} 401 / D-13: {@code x-required-account-state: any} dispensa exigência
     * de <strong>estado</strong>, não de sessão. Sem header {@code Authorization} (só com o cookie)
     * → 401 {@code Problem}. Validado contra o schema {@code Problem}, porque a requisição em si
     * viola o contrato (falta o bearer).
     */
    @Test
    @DisplayName("D-13 logout sem access token (só com o cookie) → 401 Problem")
    void d_13_logout_sem_access_token_responde_401() throws Exception {
        Sessao sessao = entrar(users.save(contaAtiva()));

        esperaProblem401(post(LOGOUT).cookie(new Cookie(COOKIE, sessao.refreshToken())));
    }

    /**
     * D-13 / P5: o logout recusado por falta de sessão não tem efeito colateral — o refresh token
     * apresentado no cookie não é revogado (quem não prova a sessão não derruba a de ninguém) e
     * continua renovando (200).
     */
    @Test
    @DisplayName("D-13 logout sem access token não revoga o refresh token do cookie (continua renovando)")
    void d_13_logout_sem_access_token_nao_revoga_nada() throws Exception {
        User user = users.save(contaAtiva());
        Sessao sessao = entrar(user);
        mockMvc.perform(post(LOGOUT).cookie(new Cookie(COOKIE, sessao.refreshToken())))
                .andExpect(status().isUnauthorized());

        assertThat(linhasAtivasDe(user.getId())).isEqualTo(1);
        mockMvc.perform(renovar(sessao.refreshToken())).andExpect(status().isOk());
    }

    // ============================================================ logout em qualquer estado

    /**
     * Contrato {@code x-required-account-state: any} em {@code logout}: um membro
     * {@code REMOVIDO} (AC-009.2, simulado no banco pela entidade, D-07), com um access token
     * ainda no prazo, consegue "Sair" — 204 conforme o contrato, com cookie de limpeza, e o refresh
     * token dele é revogado (o mesmo padrão de rota {@code ANY} de {@code RequestAuthorizationTest}).
     * O refresh token é emitido direto por {@link RefreshTokenService#issue}, porque uma conta
     * {@code REMOVIDO} não consegue entrar por {@code /auth/login} (AC-008.1).
     */
    @Test
    @DisplayName("x-required-account-state any: membro REMOVIDO consegue sair → 204 e token revogado")
    void any_logout_aceita_membro_removido() throws Exception {
        User user = membroRemovido();
        String refresh = refreshTokenService.issue(user.getId()).plainToken();
        String access = jwtService.issue(user.getId(), clock);

        MvcResult result = mockMvc.perform(sair(access, refresh))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        esperaCookieDeLimpeza(result);
        assertThat(linhasAtivasDe(user.getId())).isZero();
    }

    /**
     * Contrato {@code x-required-account-state: any}: conta {@code ATIVO} <strong>sem</strong>
     * grupo (que a exigência padrão recusaria com 403 {@code ACCOUNT_STATE}) também consegue sair —
     * 204. Todas as contas dos outros testes de logout já são deste tipo; este teste deixa a
     * exigência explícita.
     */
    @Test
    @DisplayName("x-required-account-state any: ATIVO sem grupo consegue sair → 204 (não 403)")
    void any_logout_aceita_ativo_sem_grupo() throws Exception {
        User user = users.save(contaAtiva());
        assertThat(memberships.findByUserId(user.getId())).as("conta sem grupo").isEmpty();
        Sessao sessao = entrar(user);

        mockMvc.perform(sair(sessao.accessToken(), sessao.refreshToken())).andExpect(status().isNoContent());
    }

    // ============================================================ apoio

    /** O que um login real entrega ao aparelho: o JWT do corpo e o valor opaco do cookie. */
    record Sessao(String accessToken, String refreshToken) {
    }

    /** Login real por {@code /auth/login} (T-023) — gera um access token e uma família nova. */
    private Sessao entrar(User user) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", user.getEmail());
        corpo.put("password", SENHA);
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isOk())
                .andReturn();
        return new Sessao(corpo(result).path("accessToken").asText(), valorDoCookie(setCookieRefreshToken(result)));
    }

    /** {@code POST /auth/refresh} só com o cookie {@code refresh_token} (sem bearer). */
    private static MockHttpServletRequestBuilder renovar(String refreshToken) {
        return post(REFRESH).cookie(new Cookie(COOKIE, refreshToken));
    }

    /** {@code POST /auth/logout} com o bearer da sessão e o cookie do aparelho. */
    private static MockHttpServletRequestBuilder sair(String accessToken, String refreshToken) {
        return post(LOGOUT)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .cookie(new Cookie(COOKIE, refreshToken));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Membro aprovado como Cuidador num grupo novo e depois removido (AC-009.2), via entidade (D-07). */
    private User membroRemovido() {
        User responsavel = users.save(contaAtiva());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        memberships.save(Membership.admin(groupId, responsavel.getId(), clock));
        User user = users.save(contaAtiva());
        MutableClock relogio = new MutableClock(clock.instant(), ZoneOffset.UTC);
        Membership m = Membership.request(groupId, user.getId(), relogio);
        m.approve(Role.CUIDADOR, responsavel.getId(), relogio);
        m.remove(relogio);
        memberships.save(m);
        assertThat(memberships.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REMOVIDO);
        return user;
    }

    private User contaAtiva() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "refresh-" + UUID.randomUUID() + "@teste.com", "21987654321", endereco,
                passwordEncoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        return user;
    }

    private JsonNode corpo(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** O cabeçalho {@code Set-Cookie} do {@code refresh_token}, ou {@code null} se não houver. */
    private static String setCookieRefreshToken(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(COOKIE + "="))
                .findFirst()
                .orElse(null);
    }

    /** Valor do par {@code refresh_token=<valor>} de um {@code Set-Cookie}. */
    private static String valorDoCookie(String setCookie) {
        String par = setCookie.split(";", 2)[0];
        return par.substring(par.indexOf('=') + 1);
    }

    /** Valor do {@code Set-Cookie refresh_token}, ou {@code null} se a resposta não o trouxer. */
    private static String valorDoCookieOuNulo(MvcResult result) {
        String setCookie = setCookieRefreshToken(result);
        return setCookie == null ? null : valorDoCookie(setCookie);
    }

    /** Confere o cookie de limpeza exigido pelo contrato no 204 de {@code logout}. */
    private static void esperaCookieDeLimpeza(MvcResult result) {
        String setCookie = setCookieRefreshToken(result);
        assertThat(setCookie).as("Set-Cookie refresh_token de limpeza presente").isNotNull();
        assertThat(valorDoCookie(setCookie)).as("valor vazio").isEmpty();
        assertThat(setCookie)
                .contains("Max-Age=0")
                .contains("Path=/api/v1/auth")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict");
    }

    /**
     * Executa a requisição e confere: 401, {@code application/problem+json}, campo {@code status}
     * 401 e corpo conforme {@code #/components/schemas/Problem}; sem cookie de sessão novo.
     */
    private void esperaProblem401(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = corpo(result);
        assertThat(body.path("status").asInt()).isEqualTo(401);
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem: %s", violacoes).isEmpty();
        assertThat(valorDoCookieOuNulo(result)).as("nenhum token novo emitido").isNullOrEmpty();
    }

    /**
     * Faz um login real e devolve, junto da sessão, o {@code family_id} que ele criou. O banco só
     * guarda o hash do token (D-04), então a família do aparelho é identificada como a única
     * família da conta que não existia antes deste login.
     */
    private Aparelho entrarComoAparelho(User user) throws Exception {
        List<UUID> antes = familiasDe(user.getId());
        Sessao sessao = entrar(user);
        List<UUID> novas = familiasDe(user.getId()).stream().filter(f -> !antes.contains(f)).toList();
        assertThat(novas).as("o login criou exatamente uma família nova").hasSize(1);
        return new Aparelho(sessao, novas.getFirst());
    }

    /** Uma sessão aberta num aparelho e a família de refresh tokens desse login (D-11 rev. 3). */
    record Aparelho(Sessao sessao, UUID familia) {
    }

    private List<UUID> familiasDe(UUID userId) {
        return jdbc.queryForList(
                "SELECT DISTINCT family_id FROM refresh_tokens WHERE user_id = ?", UUID.class, userId);
    }

    private int linhasAtivasDe(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
    }

    private int ativasNaFamilia(UUID familyId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL", Integer.class, familyId);
    }

    private int totalDeLinhasDe(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }
}
