package br.com.cuidamais.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.AccountState;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * T-023 — {@code POST /api/v1/auth/login} (contrato {@code operationId: login}), caixa-preta pela
 * rota HTTP real (contexto Spring completo, MockMvc), conferindo o efeito no banco por SQL puro e os
 * e-mails no Mailpit real.
 *
 * <p>Cobre: AC-008.1 (só {@code ATIVO} entra — inclusive {@code ATIVO} sem grupo, AC-001.7),
 * AC-008.2 (o {@code 403} por estado só sai com e-mail <strong>e</strong> senha corretos), AC-008.4
 * (bloqueio por conta após 5 erros seguidos, por 15 min; durante o bloqueio qualquer tentativa,
 * inclusive com a senha certa, recebe o mesmo {@code 401}; um único e-mail na transição; zera no
 * acerto e ao fim da janela). Decisões: D-11/D-12 (sessão igual à de {@code verify-email}), D-14
 * rev. 2 ({@code failed_logins}, {@code locked_until}, {@code lock_notified_at}), D-15 (resposta
 * neutra: e-mail inexistente, senha errada e conta bloqueada são indistinguíveis), D-33 (estado
 * derivado por {@link AccountState#of}), D-10 (relógio controlado).
 *
 * <p><strong>Desenho esperado</strong>:
 *
 * <ul>
 *   <li>Rota pública ({@code security: []}), corpo {@code LoginRequest {email, password}}, ambos
 *       obrigatórios; e-mail comparado sem diferença de caixa (decisão da T-020).
 *   <li>{@code 200} {@code SessionStarted}: {@code AccessToken} no corpo + {@code Set-Cookie
 *       refresh_token} (mesmo padrão da T-022).
 *   <li>{@code 401} {@code Problem} único (sem {@code code} obrigatório) para e-mail inexistente,
 *       senha errada e conta bloqueada — corpo idêntico nos três casos.
 *   <li>{@code 403} {@code AccountStateProblem} com {@code code}: {@code
 *       AGUARDANDO_CONFIRMACAO_EMAIL→AWAITING_EMAIL_CONFIRMATION}, {@code
 *       AGUARDANDO_APROVACAO→AWAITING_APPROVAL}, {@code RECUSADO→REJECTED}, {@code
 *       EXPIRADO→EXPIRED}, {@code REMOVIDO→REMOVED}. {@code ATIVO} nunca gera 403.
 *   <li>Ordem de avaliação: (1) limpa bloqueio vencido; (2) conta bloqueada → 401 sem conferir a
 *       senha e sem alterar nada; (3) senha errada → registra o erro (e publica {@code
 *       PasswordLockEmail} se for a transição) → 401; (4) senha certa → zera o contador; estado
 *       ≠ {@code ATIVO} → 403; {@code ATIVO} → 200 com sessão.
 *   <li>Novo repositório {@code br.com.cuidamais.membership.repository.MembershipRepository extends
 *       JpaRepository<Membership, UUID>} com {@code Optional<Membership> findByUserId(UUID)} (D-02:
 *       {@code memberships.user_id} é {@code UNIQUE}).
 * </ul>
 *
 * <p><strong>Decisão desta tarefa (cenário 8):</strong> durante o bloqueio, nenhuma tentativa altera
 * {@code failed_logins}, {@code locked_until} ou {@code lock_notified_at} — o contador fica em 5, o
 * bloqueio não se prolonga e não sai segundo e-mail.
 *
 * <p>Os testes de corpo inválido validam a resposta contra o schema {@code ValidationProblem} via
 * {@link ContractSchemas} em vez de {@link ContractValidation#matchesContract()}, porque a
 * requisição em si é propositalmente inválida (mesmo padrão já usado em {@code SignupTest}).
 *
 * <p>O banco e o Mailpit não são limpos entre testes: cada teste usa um e-mail único e filtra o
 * Mailpit por destinatário e assunto.
 */
@AutoConfigureMockMvc
class LoginTest extends IntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String SENHA = "Senha@Forte123";
    private static final String SENHA_ERRADA = "Senha@Errada999";
    private static final int LIMITE_ERROS = 5;
    private static final Duration JANELA_BLOQUEIO = Duration.ofMinutes(15);
    private static final String ASSUNTO_BLOQUEIO = "Cuida+: sua conta foi bloqueada temporariamente";
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);
    /** Tempo em que a caixa precisa ficar estável para afirmar "não chegou outro e-mail". */
    private static final Duration ESTABILIDADE_CAIXA = Duration.ofSeconds(2);
    private static final int EXPIRES_IN_SEGUNDOS = 900;
    private static final long MAX_AGE_REFRESH_SEGUNDOS = Duration.ofDays(7).toSeconds();

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

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

    // ============================================================ AC-008.1 / AC-008.2: 403 por estado

    /**
     * AC-008.2 / D-33: conta que ainda não confirmou o e-mail (sem membership), com senha certa,
     * recebe 403 {@code AWAITING_EMAIL_CONFIRMATION} — o frontend a leva à confirmação (AC-001.6).
     */
    @Test
    @DisplayName("AC-008.2 senha certa em AGUARDANDO_CONFIRMACAO_EMAIL → 403 AWAITING_EMAIL_CONFIRMATION, conforme o contrato")
    void ac_008_2_aguardando_confirmacao_email_responde_403() throws Exception {
        User user = users.save(contaNaoConfirmada());

        esperaAccountStateProblem(user.getEmail(), "AWAITING_EMAIL_CONFIRMATION");
    }

    /**
     * AC-008.2 / D-33: convidado com membership {@code AGUARDANDO_APROVACAO} e senha certa recebe
     * 403 {@code AWAITING_APPROVAL} ("Seu cadastro está aguardando aprovação do responsável.").
     */
    @Test
    @DisplayName("AC-008.2 senha certa em AGUARDANDO_APROVACAO → 403 AWAITING_APPROVAL, conforme o contrato")
    void ac_008_2_aguardando_aprovacao_responde_403() throws Exception {
        User user = contaComMembership(EstadoMembership.AGUARDANDO_APROVACAO);

        esperaAccountStateProblem(user.getEmail(), "AWAITING_APPROVAL");
    }

    /** AC-008.2 / D-33: membership {@code RECUSADO} + senha certa → 403 {@code REJECTED}. */
    @Test
    @DisplayName("AC-008.2 senha certa em RECUSADO → 403 REJECTED, conforme o contrato")
    void ac_008_2_recusado_responde_403() throws Exception {
        User user = contaComMembership(EstadoMembership.RECUSADO);

        esperaAccountStateProblem(user.getEmail(), "REJECTED");
    }

    /** AC-008.2 / D-33: membership {@code EXPIRADO} + senha certa → 403 {@code EXPIRED}. */
    @Test
    @DisplayName("AC-008.2 senha certa em EXPIRADO → 403 EXPIRED, conforme o contrato")
    void ac_008_2_expirado_responde_403() throws Exception {
        User user = contaComMembership(EstadoMembership.EXPIRADO);

        esperaAccountStateProblem(user.getEmail(), "EXPIRED");
    }

    /** AC-008.2 / D-33: membership {@code REMOVIDO} + senha certa → 403 {@code REMOVED}. */
    @Test
    @DisplayName("AC-008.2 senha certa em REMOVIDO → 403 REMOVED, conforme o contrato")
    void ac_008_2_removido_responde_403() throws Exception {
        User user = contaComMembership(EstadoMembership.REMOVIDO);

        esperaAccountStateProblem(user.getEmail(), "REMOVED");
    }

    /**
     * AC-008.1 "só ATIVO entra": o 403 não abre sessão — sem cookie {@code refresh_token}, sem
     * refresh token gravado. Usa {@code AGUARDANDO_APROVACAO}, o caso mais comum de 403.
     */
    @Test
    @DisplayName("AC-008.1 403 por estado não abre sessão (sem cookie, sem refresh token)")
    void ac_008_1_403_nao_abre_sessao() throws Exception {
        User user = contaComMembership(EstadoMembership.AGUARDANDO_APROVACAO);

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNull();
        assertThat(refreshTokensDe(user.getId())).isZero();
    }

    /**
     * AC-008.2 "mensagens por status só depois de e-mail e senha corretos": com senha errada,
     * todo estado não-{@code ATIVO} recebe o mesmo 401 genérico, nunca o 403 que revelaria o
     * estado. Cada linha da tabela é um estado.
     */
    @ParameterizedTest(name = "AC-008.2 senha errada em {0} → 401, não 403")
    @EnumSource(EstadoNaoAtivo.class)
    @DisplayName("AC-008.2 senha errada em estado não-ATIVO → 401 genérico, nunca 403")
    void ac_008_2_senha_errada_em_estado_nao_ativo_responde_401(EstadoNaoAtivo estado) throws Exception {
        // Linha da tabela: AGUARDANDO_CONFIRMACAO_EMAIL (sem membership) ou um dos 4 status de membership.
        User user = estado.criar(this);

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        // O 401 não revela o estado: é idêntico ao de senha errada de uma conta ATIVO qualquer.
        assertThat(corpo(result))
                .as("401 em %s idêntico ao de uma conta ATIVO", estado)
                .isEqualTo(corpo(senhaErradaDeContaAtivaDeReferencia()));
    }

    // ============================================================ AC-008.1 ATIVO entra

    /**
     * "Pronto quando" da T-023 / AC-008.1 + AC-001.7: conta {@code ATIVO} sem nenhuma membership
     * (criador que confirmou o e-mail e ainda não cadastrou o idoso) entra normalmente: 200
     * {@code SessionStarted} conforme o contrato.
     */
    @Test
    @DisplayName("AC-008.1 / AC-001.7 ATIVO sem grupo entra: 200 SessionStarted, conforme o contrato")
    void ac_008_1_ativo_sem_grupo_entra() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(ContractValidation.matchesContract());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM memberships WHERE user_id = ?", Integer.class, user.getId()))
                .as("continua sem grupo; login não cria membership")
                .isZero();
    }

    /**
     * AC-008.1 / D-11: o 200 traz um access token Bearer de 900 s cujo subject é a conta que entrou
     * (conferido pelo {@link JwtService} da T-021).
     */
    @Test
    @DisplayName("AC-008.1 / D-11 login devolve AccessToken Bearer de 900 s cujo subject é a conta")
    void ac_008_1_login_devolve_access_token_da_conta() throws Exception {
        User user = users.save(contaAtiva());

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode corpo = corpo(result);
        assertThat(corpo.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(corpo.path("expiresIn").asInt()).isEqualTo(EXPIRES_IN_SEGUNDOS);
        assertThat(jwtService.parseSubject(corpo.path("accessToken").asText(), clock)).isEqualTo(user.getId());
    }

    /**
     * AC-008.1 / D-11 / D-12: o 200 envia o cookie {@code refresh_token} com os atributos de
     * segurança e grava exatamente um refresh token novo (família própria) para a conta.
     */
    @Test
    @DisplayName("AC-008.1 / D-12 login envia cookie refresh_token seguro e grava um refresh token novo")
    void ac_008_1_login_abre_sessao_com_refresh_token() throws Exception {
        User user = users.save(contaAtiva());

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isOk())
                .andReturn();

        String cookie = cookieRefreshToken(result);
        assertThat(cookie).isNotNull();
        assertThat(cookie)
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .contains("Max-Age=" + MAX_AGE_REFRESH_SEGUNDOS);
        List<Map<String, Object>> tokens = jdbc.queryForList(
                "SELECT id, family_id, revoked_at FROM refresh_tokens WHERE user_id = ?", user.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().get("family_id")).isEqualTo(tokens.getFirst().get("id"));
        assertThat(tokens.getFirst().get("revoked_at")).isNull();
    }

    /** AC-008.1: membro {@code ATIVO} de um grupo (Responsável) entra normalmente — 200. */
    @Test
    @DisplayName("AC-008.1 ATIVO com grupo entra: 200 SessionStarted, conforme o contrato")
    void ac_008_1_ativo_com_grupo_entra() throws Exception {
        User user = contaComMembership(EstadoMembership.ATIVO);

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNotNull();
    }

    /**
     * Decisão da T-020 (e-mail gravado em minúsculas, comparado sem diferença de caixa): entrar
     * com o e-mail em maiúsculas é a mesma conta — 200.
     */
    @Test
    @DisplayName("AC-008.1 (decisão da T-020) e-mail em maiúsculas entra na mesma conta")
    void ac_008_1_email_em_maiusculas_entra() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(entrar(user.getEmail().toUpperCase(Locale.ROOT), SENHA)).andExpect(status().isOk());
    }

    // ============================================================ D-15 / AC-008.4: 401 único

    /**
     * D-15 / P5: e-mail sem conta → 401 {@code Problem} conforme o contrato, sem sessão.
     */
    @Test
    @DisplayName("AC-008.4 / D-15 e-mail inexistente → 401 Problem, conforme o contrato")
    void ac_008_4_email_inexistente_responde_401() throws Exception {
        MvcResult result = mockMvc.perform(entrar(emailUnico(), SENHA))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNull();
    }

    /**
     * D-15 / P5: a resposta para e-mail inexistente é idêntica (status e corpo) à de senha errada
     * de uma conta real — não revela se o e-mail tem cadastro.
     */
    @Test
    @DisplayName("AC-008.4 / D-15 401 de e-mail inexistente é idêntico ao de senha errada")
    void ac_008_4_email_inexistente_identico_a_senha_errada() throws Exception {
        User user = users.save(contaAtiva());

        MvcResult senhaErrada = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andReturn();
        MvcResult semConta = mockMvc.perform(entrar(emailUnico(), SENHA)).andReturn();

        assertThat(semConta.getResponse().getStatus()).isEqualTo(401).isEqualTo(senhaErrada.getResponse().getStatus());
        assertThat(corpo(semConta)).isEqualTo(corpo(senhaErrada));
    }

    /**
     * AC-008.4: senha errada → 401 {@code Problem} conforme o contrato, sem cookie e sem sessão.
     */
    @Test
    @DisplayName("AC-008.4 senha errada → 401 Problem conforme o contrato, sem sessão")
    void ac_008_4_senha_errada_responde_401() throws Exception {
        User user = users.save(contaAtiva());

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNull();
        assertThat(refreshTokensDe(user.getId())).isZero();
    }

    // ============================================================ AC-008.4 contagem e bloqueio

    /**
     * AC-008.4 / D-14: da 1ª à 4ª senha errada, cada tentativa responde 401 e grava o erro
     * ({@code failed_logins} sobe de requisição em requisição — persistido mesmo com a resposta
     * de erro) e {@code locked_until} continua nulo.
     */
    @Test
    @DisplayName("AC-008.4 / D-14 da 1ª à 4ª senha errada: 401, failed_logins sobe 1..4, locked_until nulo")
    void ac_008_4_quatro_primeiros_erros_contam_sem_bloquear() throws Exception {
        User user = users.save(contaAtiva());

        for (int tentativa = 1; tentativa <= LIMITE_ERROS - 1; tentativa++) {
            // Tentativa N (1..4): 401, contador = N, sem bloqueio.
            mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andExpect(status().isUnauthorized());
            assertThat(failedLoginsDe(user.getId())).as("failed_logins após a %dª", tentativa).isEqualTo(tentativa);
            assertThat(lockedUntilDe(user.getId())).as("locked_until após a %dª", tentativa).isNull();
        }
    }

    /**
     * AC-008.4 / D-14: a 5ª senha errada seguida responde o mesmo 401 das anteriores (o bloqueio
     * nunca é revelado) e grava {@code locked_until = agora + 15 min} e {@code lock_notified_at =
     * agora}.
     */
    @Test
    @DisplayName("AC-008.4 / D-14 5ª senha errada: mesmo 401, locked_until = agora + 15 min, lock_notified_at gravado")
    void ac_008_4_quinta_errada_bloqueia() throws Exception {
        User user = users.save(contaAtiva());
        MvcResult primeira = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andReturn();
        errar(user.getEmail(), LIMITE_ERROS - 2);
        Instant agora = clock.instant();

        MvcResult quinta = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(corpo(quinta)).as("mesmo corpo da 1ª errada").isEqualTo(corpo(primeira));
        assertThat(lockedUntilDe(user.getId())).isEqualTo(agora.plus(JANELA_BLOQUEIO));
        assertThat(lockNotifiedAtDe(user.getId())).isEqualTo(agora);
        assertThat(failedLoginsDe(user.getId())).isEqualTo(LIMITE_ERROS);
    }

    /**
     * "Pronto quando" da T-023 / AC-008.4 "um e-mail no momento em que o bloqueio começa": depois da
     * 5ª senha errada, exatamente um {@code PasswordLockEmail} chega ao dono da conta no Mailpit — e
     * continua sendo um só depois de um tempo de estabilidade.
     */
    @Test
    @DisplayName("AC-008.4 / D-14 a 5ª senha errada envia exatamente um e-mail de bloqueio ao dono da conta")
    void ac_008_4_quinta_errada_envia_um_email() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: 4 erros não enviam nada.
        errar(user.getEmail(), LIMITE_ERROS - 1);
        // Etapa 2: o 5º erro inicia o bloqueio.
        errar(user.getEmail(), 1);

        // Etapa 3: o e-mail chega, e só um.
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(1));
        await().during(ESTABILIDADE_CAIXA)
                .atMost(ESTABILIDADE_CAIXA.plusSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(1));
    }

    /**
     * AC-008.4: 4 senhas erradas não enviam e-mail de bloqueio (o e-mail é só da transição).
     */
    @Test
    @DisplayName("AC-008.4 quatro senhas erradas não enviam e-mail de bloqueio")
    void ac_008_4_quatro_erros_nao_enviam_email() throws Exception {
        User user = users.save(contaAtiva());

        errar(user.getEmail(), LIMITE_ERROS - 1);

        await().during(ESTABILIDADE_CAIXA)
                .atMost(ESTABILIDADE_CAIXA.plusSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).isEmpty());
    }

    // ============================================================ AC-008.4 durante o bloqueio

    /**
     * "Pronto quando" da T-023 / prova central de AC-008.4: durante o bloqueio, a senha
     * <strong>certa</strong> recebe o mesmo 401 (status e corpo) da senha errada — não 200, não
     * 403 —, não abre sessão e não mexe no estado do bloqueio (não o "reseta" antes da hora).
     */
    @Test
    @DisplayName("AC-008.4 senha certa durante o bloqueio → 401 idêntico ao de senha errada, sem sessão e sem mexer no bloqueio")
    void ac_008_4_senha_certa_durante_bloqueio_responde_401_igual() throws Exception {
        User user = users.save(contaAtiva());
        MvcResult senhaErrada = mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andReturn();
        errar(user.getEmail(), LIMITE_ERROS - 1);
        Timestamp bloqueadoAte = lockedUntilTs(user.getId());
        Timestamp avisadoEm = lockNotifiedAtTs(user.getId());
        clock.advance(Duration.ofMinutes(1));

        MvcResult senhaCerta = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        // Mesma resposta que a senha errada.
        assertThat(corpo(senhaCerta)).isEqualTo(corpo(senhaErrada));
        // Nenhuma sessão aberta.
        assertThat(cookieRefreshToken(senhaCerta)).isNull();
        assertThat(refreshTokensDe(user.getId())).isZero();
        // O bloqueio não foi tocado.
        assertThat(failedLoginsDe(user.getId())).isEqualTo(LIMITE_ERROS);
        assertThat(lockedUntilTs(user.getId())).isEqualTo(bloqueadoAte);
        assertThat(lockNotifiedAtTs(user.getId())).isEqualTo(avisadoEm);
    }

    /**
     * AC-008.4 + AC-008.2: o bloqueio vem antes do estado. Conta {@code REMOVIDO} bloqueada, com
     * senha certa, recebe 401 (não o 403 {@code REMOVED}) — o bloqueio não deixa a senha certa
     * revelar nada.
     */
    @Test
    @DisplayName("AC-008.4 / AC-008.2 senha certa durante o bloqueio em conta REMOVIDO → 401, não 403")
    void ac_008_4_bloqueio_vem_antes_do_403() throws Exception {
        User user = contaComMembership(EstadoMembership.REMOVIDO);
        errar(user.getEmail(), LIMITE_ERROS);

        mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-008.4 "um e-mail ... (não a cada tentativa)" + decisão desta tarefa: mais senhas erradas
     * durante o bloqueio continuam 401, não enviam um segundo e-mail ({@code lock_notified_at}) e
     * não alteram {@code failed_logins} (fica em 5) nem {@code locked_until} (não prolonga).
     */
    @Test
    @DisplayName("AC-008.4 / D-14 senhas erradas durante o bloqueio: 401, nenhum segundo e-mail, bloqueio inalterado")
    void ac_008_4_erros_durante_bloqueio_nao_reenviam_email() throws Exception {
        User user = users.save(contaAtiva());
        errar(user.getEmail(), LIMITE_ERROS);
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(1));
        Timestamp bloqueadoAte = lockedUntilTs(user.getId());
        clock.advance(Duration.ofMinutes(2));

        // Etapa 1: mais 3 erradas durante o bloqueio, todas 401.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andExpect(status().isUnauthorized());
        }

        // Etapa 2: o bloqueio não mudou.
        assertThat(failedLoginsDe(user.getId())).isEqualTo(LIMITE_ERROS);
        assertThat(lockedUntilTs(user.getId())).isEqualTo(bloqueadoAte);
        // Etapa 3: continua um e-mail só.
        await().during(ESTABILIDADE_CAIXA)
                .atMost(ESTABILIDADE_CAIXA.plusSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(1));
    }

    /**
     * AC-008.4, lado de dentro da janela: 1 segundo antes dos 15 minutos a senha certa ainda
     * recebe 401. Garante que o 200 do teste de fim da janela é o prazo, e não outra coisa.
     */
    @Test
    @DisplayName("AC-008.4 senha certa 1 segundo antes dos 15 minutos ainda recebe 401")
    void ac_008_4_senha_certa_um_segundo_antes_do_fim_responde_401() throws Exception {
        User user = users.save(contaAtiva());
        errar(user.getEmail(), LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO.minusSeconds(1));

        mockMvc.perform(entrar(user.getEmail(), SENHA)).andExpect(status().isUnauthorized());
    }

    // ============================================================ AC-008.4 zera no acerto

    /**
     * AC-008.4 "um login correto zera o contador": 3 erros (sem bloquear) e depois o acerto → 200,
     * e no banco {@code failed_logins = 0}, {@code locked_until} e {@code lock_notified_at} nulos.
     */
    @Test
    @DisplayName("AC-008.4 login correto após 3 erros: 200 e failed_logins = 0, locked_until/lock_notified_at nulos")
    void ac_008_4_acerto_zera_contador() throws Exception {
        User user = users.save(contaAtiva());
        errar(user.getEmail(), 3);
        assertThat(failedLoginsDe(user.getId())).isEqualTo(3);

        mockMvc.perform(entrar(user.getEmail(), SENHA)).andExpect(status().isOk());

        assertThat(failedLoginsDe(user.getId())).isZero();
        assertThat(lockedUntilDe(user.getId())).isNull();
        assertThat(lockNotifiedAtDe(user.getId())).isNull();
    }

    /**
     * AC-008.4 "5 senhas erradas <strong>seguidas</strong>": 4 erros, acerto, mais 4 erros não
     * bloqueiam — a senha certa seguinte ainda entra (200).
     */
    @Test
    @DisplayName("AC-008.4 4 erros + acerto + 4 erros não bloqueiam: a senha certa seguinte entra")
    void ac_008_4_acerto_no_meio_recomeca_contagem() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: 4 erros.
        errar(user.getEmail(), LIMITE_ERROS - 1);
        // Etapa 2: acerto zera.
        mockMvc.perform(entrar(user.getEmail(), SENHA)).andExpect(status().isOk());
        // Etapa 3: mais 4 erros.
        errar(user.getEmail(), LIMITE_ERROS - 1);

        // Etapa 4: não bloqueou — entra.
        assertThat(lockedUntilDe(user.getId())).isNull();
        mockMvc.perform(entrar(user.getEmail(), SENHA)).andExpect(status().isOk());
    }

    // ============================================================ AC-008.4 zera ao fim da janela

    /**
     * AC-008.4 "ao fim dos 15 minutos o contador também volta a zero": com a conta bloqueada,
     * avançar o relógio exatamente 15 minutos e entrar com a senha certa → 200 (não 401), e no
     * banco contador, {@code locked_until} e {@code lock_notified_at} voltaram a 0/nulo.
     */
    @Test
    @DisplayName("AC-008.4 após 15 minutos de bloqueio, a senha certa entra (200) e o bloqueio é zerado")
    void ac_008_4_fim_da_janela_libera_login() throws Exception {
        User user = users.save(contaAtiva());
        errar(user.getEmail(), LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO);

        MvcResult result = mockMvc.perform(entrar(user.getEmail(), SENHA))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(cookieRefreshToken(result)).isNotNull();
        assertThat(failedLoginsDe(user.getId())).isZero();
        assertThat(lockedUntilDe(user.getId())).isNull();
        assertThat(lockNotifiedAtDe(user.getId())).isNull();
    }

    /**
     * AC-008.4 "ao fim dos 15 minutos o contador também volta a zero", visto pelo erro: depois da
     * janela, uma senha errada conta como a 1ª ({@code failed_logins = 1}, não 6) e não bloqueia.
     */
    @Test
    @DisplayName("AC-008.4 após 15 minutos de bloqueio, uma senha errada conta como a 1ª e não bloqueia")
    void ac_008_4_fim_da_janela_zera_contador_para_novo_erro() throws Exception {
        User user = users.save(contaAtiva());
        errar(user.getEmail(), LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO.plusMinutes(5));

        mockMvc.perform(entrar(user.getEmail(), SENHA_ERRADA)).andExpect(status().isUnauthorized());

        assertThat(failedLoginsDe(user.getId())).isEqualTo(1);
        assertThat(lockedUntilDe(user.getId())).isNull();
        assertThat(lockNotifiedAtDe(user.getId())).isNull();
    }

    /**
     * AC-008.4 "um e-mail no momento em que o bloqueio começa": um novo bloqueio, depois que o
     * anterior terminou, é outra transição e envia outro e-mail (2 no total para a conta).
     */
    @Test
    @DisplayName("AC-008.4 / D-14 novo bloqueio após o fim do anterior envia um novo e-mail")
    void ac_008_4_novo_bloqueio_envia_novo_email() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: primeiro bloqueio, 1 e-mail.
        errar(user.getEmail(), LIMITE_ERROS);
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(1));
        // Etapa 2: a janela termina e a conta erra 5 vezes de novo.
        clock.advance(JANELA_BLOQUEIO);
        errar(user.getEmail(), LIMITE_ERROS);

        // Etapa 3: segundo e-mail, da nova transição.
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emailsDeBloqueio(user.getEmail())).hasSize(2));
    }

    // ============================================================ corpo inválido

    /**
     * {@code LoginRequest.email} é {@code format: email}: e-mail malformado é erro de validação
     * (400 {@code ValidationProblem} em {@code email}), não uma tentativa de login. Cada linha é
     * uma forma de e-mail inválido.
     */
    @ParameterizedTest(name = "AC-008.4 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {
        "sem-arroba", // sem @
        "@teste.com", // sem parte local
        ""            // vazio
    })
    @DisplayName("AC-008.4 e-mail malformado → 400 ValidationProblem em email")
    void ac_008_4_email_malformado_e_recusado(String email) throws Exception {
        esperaValidationProblem(corpo(email, SENHA), "email");
    }

    /**
     * {@code LoginRequest.password} é obrigatório: sem senha → 400 em {@code password}, e isso
     * <strong>não</strong> conta como senha errada para a conta ({@code failed_logins} fica em 0).
     */
    @Test
    @DisplayName("AC-008.4 corpo sem password → 400 ValidationProblem em password, sem contar erro")
    void ac_008_4_corpo_sem_senha_e_recusado_sem_contar_erro() throws Exception {
        User user = users.save(contaAtiva());
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", user.getEmail());

        esperaValidationProblem(corpo, "password");

        assertThat(failedLoginsDe(user.getId())).isZero();
    }

    /** {@code LoginRequest.email} é obrigatório: sem e-mail → 400 em {@code email}. */
    @Test
    @DisplayName("AC-008.4 corpo sem email → 400 ValidationProblem em email")
    void ac_008_4_corpo_sem_email_e_recusado() throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("password", SENHA);

        esperaValidationProblem(corpo, "email");
    }

    // ============================================================ apoio

    /** Estados de membership que os cenários precisam montar no banco. */
    enum EstadoMembership { AGUARDANDO_APROVACAO, ATIVO, RECUSADO, EXPIRADO, REMOVIDO }

    /** Os 5 estados de conta que, com senha certa, geram 403 (D-33); usado no teste parametrizado. */
    enum EstadoNaoAtivo {
        AGUARDANDO_CONFIRMACAO_EMAIL,
        AGUARDANDO_APROVACAO,
        RECUSADO,
        EXPIRADO,
        REMOVIDO;

        User criar(LoginTest t) {
            return this == AGUARDANDO_CONFIRMACAO_EMAIL
                    ? t.users.save(t.contaNaoConfirmada())
                    : t.contaComMembership(EstadoMembership.valueOf(name()));
        }
    }

    /**
     * Conta com e-mail verificado e membership no estado pedido, num grupo próprio (com um
     * Responsável à parte, que é quem decide/remove). As transições usam a própria entidade
     * {@link Membership} (D-07) e um relógio local, para não mexer no relógio compartilhado.
     */
    User contaComMembership(EstadoMembership estado) {
        User responsavel = users.save(contaAtiva());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        User user = users.save(contaAtiva());
        MutableClock relogio = new MutableClock(clock.instant(), ZoneOffset.UTC);

        Membership m = switch (estado) {
            case ATIVO -> Membership.admin(groupId, user.getId(), relogio);
            case AGUARDANDO_APROVACAO -> Membership.request(groupId, user.getId(), relogio);
            case RECUSADO -> {
                Membership p = Membership.request(groupId, user.getId(), relogio);
                p.reject(responsavel.getId(), relogio);
                yield p;
            }
            case EXPIRADO -> {
                Membership p = Membership.request(groupId, user.getId(), relogio);
                relogio.advance(Duration.ofHours(24));
                p.expire(relogio);
                yield p;
            }
            case REMOVIDO -> {
                Membership p = Membership.request(groupId, user.getId(), relogio);
                p.approve(Role.CUIDADOR, responsavel.getId(), relogio);
                p.remove(relogio);
                yield p;
            }
        };
        memberships.save(m);
        return user;
    }

    User contaAtiva() {
        User user = contaNaoConfirmada();
        user.verifyEmail(clock);
        return user;
    }

    User contaNaoConfirmada() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        return User.signup(
                "Maria da Silva", emailUnico(), "21987654321", endereco, passwordEncoder.encode(SENHA), clock);
    }

    /** 401 de senha errada de uma conta ATIVO qualquer, para comparar corpos. */
    private MvcResult senhaErradaDeContaAtivaDeReferencia() throws Exception {
        User referencia = users.save(contaAtiva());
        return mockMvc.perform(entrar(referencia.getEmail(), SENHA_ERRADA)).andReturn();
    }

    private static String emailUnico() {
        return "login-" + UUID.randomUUID() + "@teste.com";
    }

    private void errar(String email, int vezes) throws Exception {
        for (int i = 0; i < vezes; i++) {
            mockMvc.perform(entrar(email, SENHA_ERRADA)).andExpect(status().isUnauthorized());
        }
    }

    private static Map<String, Object> corpo(String email, String senha) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("password", senha);
        return corpo;
    }

    private RequestBuilder entrar(String email, String senha) throws Exception {
        return requisicao(corpo(email, senha));
    }

    private RequestBuilder requisicao(Map<String, Object> corpo) throws Exception {
        return post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    private JsonNode corpo(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String cookieRefreshToken(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("refresh_token="))
                .findFirst()
                .orElse(null);
    }

    private int refreshTokensDe(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private int failedLoginsDe(UUID userId) {
        return jdbc.queryForObject("SELECT failed_logins FROM users WHERE id = ?", Integer.class, userId);
    }

    private Timestamp lockedUntilTs(UUID userId) {
        return jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private Timestamp lockNotifiedAtTs(UUID userId) {
        return jdbc.queryForObject("SELECT lock_notified_at FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private Instant lockedUntilDe(UUID userId) {
        Timestamp ts = lockedUntilTs(userId);
        return ts == null ? null : ts.toInstant();
    }

    private Instant lockNotifiedAtDe(UUID userId) {
        Timestamp ts = lockNotifiedAtTs(userId);
        return ts == null ? null : ts.toInstant();
    }

    private List<MailpitClient.Summary> emailsDeBloqueio(String email) {
        return mailpit.messages().stream()
                .filter(m -> m.to().stream().anyMatch(to -> to.equalsIgnoreCase(email)))
                .filter(m -> ASSUNTO_BLOQUEIO.equals(m.subject()))
                .toList();
    }

    /**
     * Entra com a senha certa e confere: 403, {@code application/problem+json}, conforme o
     * contrato (rota {@code login}), corpo conforme {@code AccountStateProblem} e {@code code}
     * igual ao esperado.
     */
    private void esperaAccountStateProblem(String email, String codeEsperado) throws Exception {
        MvcResult result = mockMvc.perform(entrar(email, SENHA))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = corpo(result);
        List<Error> violacoes = ContractSchemas.schemaFor("AccountStateProblem").validate(body);
        assertThat(violacoes).as("corpo casa com AccountStateProblem: %s", violacoes).isEmpty();
        assertThat(body.path("code").asText()).isEqualTo(codeEsperado);
        assertThat(body.path("status").asInt()).isEqualTo(403);
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} (via {@link ContractSchemas}, porque o contrato de {@code login}
     * não declara 400) e um item de {@code errors} com {@code field} igual a {@code campoEsperado};
     * sem sessão.
     */
    private void esperaValidationProblem(Map<String, Object> corpo, String campoEsperado) throws Exception {
        MvcResult result = mockMvc.perform(requisicao(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = corpo(result);
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem: %s", violacoes).isEmpty();
        List<String> campos = new ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
        assertThat(cookieRefreshToken(result)).isNull();
    }
}
