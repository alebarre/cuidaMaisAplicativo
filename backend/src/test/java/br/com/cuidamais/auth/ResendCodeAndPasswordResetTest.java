package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.membership.Role;
import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-026 — três rotas públicas ({@code security: []}) de reenvio de código e redefinição de senha,
 * caixa-preta pela rota HTTP real (contexto Spring completo, MockMvc, PostgreSQL e Mailpit via
 * Testcontainers), conferindo o efeito no banco por SQL puro e os e-mails no Mailpit real:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/resend-code} ({@code operationId: resendCode}) — AC-001.6 (ação
 *       "reenviar código", resposta sempre igual exista ou não a conta), AC-005.3 (no máximo 1
 *       reenvio por minuto → {@code 429} com {@code Retry-After}), D-15 (resposta neutra).
 *   <li>{@code POST /api/v1/auth/password-reset/request} ({@code requestPasswordReset}) — AC-010.1
 *       (o usuário informa o e-mail e recebe um código com as regras de AC-005), AC-010.2 / D-15
 *       (resposta igual exista ou não o e-mail), AC-005.3 (pedir de novo antes de 1 min → 429).
 *   <li>{@code POST /api/v1/auth/password-reset/confirm} ({@code confirmPasswordReset}) — AC-010.3
 *       (nova senha com código válido, seguindo AC-001.3), AC-010.4 / D-37 (<strong>todas</strong>
 *       as sessões da conta são encerradas — todas as famílias de refresh token, não só um
 *       aparelho como no logout da T-025), AC-010.5 / D-37 (qualquer conta existente pode
 *       redefinir e o estado dela <strong>não muda</strong>), AC-005.1/AC-005.2 (prazo de 15 min e
 *       5 tentativas, mesmo padrão 400/410 de {@code verify-email}).
 * </ul>
 *
 * <p>"Pronto quando" da T-026: {@link #ac_010_5_aguardando_aprovacao_redefine_e_continua_no_mesmo_estado}
 * (conta {@code AGUARDANDO_APROVACAO} redefine a senha e continua no mesmo estado) e {@link
 * #ac_010_4_confirm_revoga_todas_as_familias_de_refresh_token} (refresh tokens antigos inválidos
 * após {@code confirm}).
 *
 * <p><strong>Desenho esperado</strong> (proposta do orquestrador; os testes só observam HTTP, banco
 * e Mailpit, então os nomes internos são sugestão, não exigência de compilação):
 *
 * <ul>
 *   <li>{@code ResendCodeRequest(email, purpose)} com {@code purpose} como {@code String} validada
 *       por {@code @Pattern("^(EMAIL_VERIFICATION|PASSWORD_RESET)$")} (valores do contrato, em
 *       inglês) — um valor fora do enum vira {@code 400 ValidationProblem} em {@code purpose}, nunca
 *       erro de desserialização; o serviço mapeia {@code EMAIL_VERIFICATION→CONFIRMACAO_EMAIL} e
 *       {@code PASSWORD_RESET→REDEFINICAO_SENHA} ({@link OtpPurpose}). Os nomes do enum Java
 *       ({@code CONFIRMACAO_EMAIL}...) <em>não</em> são aceitos na API.
 *   <li>{@code EmailOnlyRequest(email)} e {@code PasswordResetConfirmRequest(email, code,
 *       newPassword)} — {@code newPassword} com as mesmas regras de {@code SignupRequest.password}
 *       (AC-001.3), {@code code} com {@code ^\d{6}$}.
 *   <li>Reenvio dentro de 60 s: {@link OtpService#resend} lança {@code IllegalStateException}; o
 *       serviço converte para uma exceção nova (ex. {@code TooManyRequestsException(long
 *       retryAfterSeconds)}) mapeada em {@code GlobalExceptionHandler} para {@code 429 Problem} com
 *       o cabeçalho {@code Retry-After} = segundos que faltam para completar 60 s desde {@code
 *       otp_codes.last_sent_at} (arredondado para cima).
 *   <li>{@code RefreshTokenService#revokeAllForUser(UUID userId)} — revoga <strong>todas</strong>
 *       as linhas de {@code refresh_tokens} do usuário (todas as famílias), D-37.
 *   <li>E-mails: {@code ConfirmationCodeEmail(to, code, purpose)} (T-016), publicado dentro de uma
 *       transação (o listener é {@code AFTER_COMMIT}, D-23).
 * </ul>
 *
 * <p><strong>Leitura adotada para "não há código pendente" em {@code resend-code}</strong> (cenário
 * 4 do orquestrador, "ex. conta já confirmada"): {@link OtpCodeRepository} nunca apaga o histórico,
 * então uma conta já confirmada ainda tem a linha {@code CONFIRMACAO_EMAIL} — só que
 * <em>consumida</em>. {@link OtpCode#resend} aceitaria reenviá-la, o que mandaria um código de
 * confirmação a uma conta {@code ATIVO} (e reabriria {@code verify-email}, que regrava {@code
 * email_verified_at} e abre sessão sem senha). Por isso o teste exige: código mais recente da
 * finalidade já <strong>consumido</strong> (ou inexistente) → 202 neutro, nenhum e-mail, nada muda.
 * Códigos expirados ou esgotados (não consumidos) continuam reenviáveis — é para isso que o
 * reenvio existe (AC-005.2 "é preciso pedir um novo").
 *
 * <p>Respostas 400 de corpo inválido são validadas contra {@code ValidationProblem} via {@link
 * ContractSchemas}, não com {@link ContractValidation#matchesContract()}, porque a requisição em si
 * viola o contrato (mesmo padrão de {@code SignupTest}/{@code VerifyEmailTest}); o 400 de código
 * errado é validado contra {@code InvalidCodeProblem} isoladamente pelo mesmo motivo já documentado
 * em {@code VerifyEmailTest} ({@code oneOf} da resposta {@code InvalidCode}).
 *
 * <p>O banco e o Mailpit não são limpos entre testes: cada teste usa e-mails únicos e filtra o
 * Mailpit por destinatário e assunto. O {@link MutableClock} é compartilhado pelo contexto; os
 * testes só o avançam e medem sempre a partir de {@code clock.instant()}.
 */
@AutoConfigureMockMvc
class ResendCodeAndPasswordResetTest extends IntegrationTest {

    private static final String RESEND_CODE = "/api/v1/auth/resend-code";
    private static final String RESET_REQUEST = "/api/v1/auth/password-reset/request";
    private static final String RESET_CONFIRM = "/api/v1/auth/password-reset/confirm";
    private static final String VERIFY_EMAIL = "/api/v1/auth/verify-email";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String COOKIE = "refresh_token";

    private static final String SENHA_ANTIGA = "Senha@Antiga123";
    private static final String SENHA_NOVA = "NovaSenha456";

    /** Valores do contrato ({@code ResendCodeRequest.purpose}), em inglês. */
    private static final String EMAIL_VERIFICATION = "EMAIL_VERIFICATION";
    private static final String PASSWORD_RESET = "PASSWORD_RESET";

    /** Assuntos dos templates de {@code EmailTemplates} (T-016). */
    private static final String ASSUNTO_CONFIRMACAO = "Cuida+: seu código de confirmação";
    private static final String ASSUNTO_REDEFINICAO = "Cuida+: código para redefinir sua senha";

    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);
    /** Tempo em que a caixa precisa ficar estável para afirmar "não chegou e-mail". */
    private static final Duration ESTABILIDADE_CAIXA = Duration.ofSeconds(2);
    private static final Pattern SEIS_DIGITOS = Pattern.compile("\\b(\\d{6})\\b");

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
    OtpCodeRepository otpCodes;

    @Autowired
    OtpService otpService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RefreshTokenService refreshTokenService;

    // ==================================================================================
    // POST /auth/resend-code
    // ==================================================================================

    /**
     * Caminho feliz do ponto de vista do contrato: conta não confirmada com código pendente, mais de
     * 1 min depois do envio, pede o reenvio — 202 sem corpo ({@code AcceptedNeutral}), requisição e
     * resposta conforme {@code resendCode} no {@code openapi.yaml}.
     */
    @Test
    @DisplayName("AC-001.6 resend-code EMAIL_VERIFICATION com código pendente → 202, conforme o contrato")
    void ac_001_6_resend_code_responde_202_conforme_contrato() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-001.6 / AC-005.3: o reenvio entrega de fato um novo e-mail de confirmação (template
     * {@code CONFIRMACAO_EMAIL}) ao dono da conta, com um código de 6 dígitos que confirma a conta
     * em {@code /auth/verify-email} (200). Prova que o código do e-mail é o mesmo cujo hash ficou
     * gravado, não um valor qualquer.
     */
    @Test
    @DisplayName("AC-001.6 resend-code envia novo e-mail de confirmação cujo código confirma a conta")
    void ac_001_6_resend_code_envia_codigo_que_confirma_a_conta() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());

        String novo = codigoDoUnicoEmail(c.email(), ASSUNTO_CONFIRMACAO);
        mockMvc.perform(verificarEmail(c.email(), novo)).andExpect(status().isOk());
        assertThat(emailVerifiedAtDe(c.userId())).as("conta confirmada com o código reenviado").isNotNull();
    }

    /**
     * AC-005.3 / {@link OtpCode#resend}: o reenvio <strong>substitui</strong> o código — o código
     * antigo deixa de servir em {@code /auth/verify-email} (é tratado como código errado, 400) e o
     * reenvio é a mesma linha de {@code otp_codes} (não cria outra), com hash novo e tentativas
     * zeradas. Etapas: reenvia, confere o banco, tenta o antigo, confirma com o novo.
     */
    @Test
    @DisplayName("AC-005.3 resend-code substitui o código: o antigo não confirma mais, o novo sim")
    void ac_005_3_resend_code_substitui_o_codigo_antigo() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        String hashAntes = codeHashDe(c.otpId());
        clock.advance(Duration.ofSeconds(60));

        // Etapa 1: reenvio aceito.
        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());
        String novo = codigoDoUnicoEmail(c.email(), ASSUNTO_CONFIRMACAO);
        assumeThat(novo).as("colisão aleatória de 1 em 1 milhão").isNotEqualTo(c.codigo());

        // Etapa 2: mesma linha, hash trocado; nenhuma linha nova de CONFIRMACAO_EMAIL.
        assertThat(codeHashDe(c.otpId())).as("hash do código trocado").isNotEqualTo(hashAntes);
        assertThat(otpsDe(c.userId(), OtpPurpose.CONFIRMACAO_EMAIL)).as("reenvio não cria outra linha").isEqualTo(1);

        // Etapa 3: o código antigo virou código errado.
        mockMvc.perform(verificarEmail(c.email(), c.codigo())).andExpect(status().isBadRequest());
        assertThat(emailVerifiedAtDe(c.userId())).isNull();

        // Etapa 4: o novo confirma.
        mockMvc.perform(verificarEmail(c.email(), novo)).andExpect(status().isOk());
    }

    /**
     * AC-005.2 "depois de 5 tentativas erradas ... é preciso pedir um novo": com o código esgotado
     * (5 erradas em {@code verify-email}), {@code resend-code} é justamente como a pessoa volta a
     * poder confirmar — 202 e o código novo confirma (200). Etapas: esgota, espera 1 min, reenvia,
     * confirma.
     */
    @Test
    @DisplayName("AC-005.2 / AC-005.3 resend-code depois de esgotar as 5 tentativas entrega código novo que confirma")
    void ac_005_3_resend_code_apos_codigo_esgotado_permite_confirmar() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();

        // Etapa 1: esgota as 5 tentativas.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(verificarEmail(c.email(), codigoErrado(c.codigo()))).andExpect(status().isBadRequest());
        }

        // Etapa 2: depois de 1 min, reenvio aceito.
        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());
        assertThat(tentativasDe(c.otpId())).as("reenvio zera as tentativas").isZero();

        // Etapa 3: o código novo confirma.
        mockMvc.perform(verificarEmail(c.email(), codigoDoUnicoEmail(c.email(), ASSUNTO_CONFIRMACAO)))
                .andExpect(status().isOk());
    }

    /**
     * AC-005.1 + AC-005.3: um código expirado (16 min) também é reenviável — o reenvio dá um novo
     * prazo de 15 min a partir de agora e o código novo confirma a conta.
     */
    @Test
    @DisplayName("AC-005.1 / AC-005.3 resend-code com código expirado entrega código novo, com prazo novo, que confirma")
    void ac_005_3_resend_code_apos_codigo_expirado_permite_confirmar() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofMinutes(16));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("SELECT expires_at FROM otp_codes WHERE id = ?", Timestamp.class, c.otpId())
                        .toInstant())
                .as("novo prazo de 15 min a partir do reenvio (AC-005.1)")
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        mockMvc.perform(verificarEmail(c.email(), codigoDoUnicoEmail(c.email(), ASSUNTO_CONFIRMACAO)))
                .andExpect(status().isOk());
    }

    /**
     * AC-005.3 "no máximo 1 vez por minuto": 20 s depois do envio original, o reenvio é recusado
     * com 429 {@code Problem} conforme o contrato ({@code TooManyRequests}, inclusive o cabeçalho
     * {@code Retry-After} inteiro).
     */
    @Test
    @DisplayName("AC-005.3 resend-code antes de 1 minuto → 429 Problem com Retry-After, conforme o contrato")
    void ac_005_3_resend_code_antes_de_1_minuto_responde_429_conforme_contrato() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(20));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-005.3 / contrato {@code TooManyRequests}: {@code Retry-After} informa em segundos quanto
     * falta para completar 1 min desde o último envio — enviado em t0, pedido em t0+20 s → 40.
     */
    @Test
    @DisplayName("AC-005.3 resend-code 20 s depois do envio → Retry-After = 40 segundos")
    void ac_005_3_resend_code_retry_after_informa_segundos_restantes() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(20));

        MvcResult result = mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("40");
    }

    /**
     * AC-005.3: o 429 não tem efeito colateral — o código pendente continua o mesmo (mesmo hash,
     * mesmo {@code last_sent_at}, o original ainda confirma) e nenhum e-mail sai.
     */
    @Test
    @DisplayName("AC-005.3 resend-code recusado (429) não troca o código nem envia e-mail")
    void ac_005_3_resend_code_recusado_nao_muda_nada() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        String hashAntes = codeHashDe(c.otpId());
        Timestamp enviadoEm = lastSentAtDe(c.otpId());
        clock.advance(Duration.ofSeconds(59));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isTooManyRequests());

        assertThat(codeHashDe(c.otpId())).isEqualTo(hashAntes);
        assertThat(lastSentAtDe(c.otpId())).isEqualTo(enviadoEm);
        esperaNenhumEmail(c.email());
        mockMvc.perform(verificarEmail(c.email(), c.codigo())).andExpect(status().isOk());
    }

    /**
     * AC-005.3, limite exato: com 60 s completos desde o envio ({@link OtpCode#resend} só recusa
     * {@code agora < last_sent_at + 60 s}), o reenvio já é aceito (202). Garante que o 429 acima é o
     * intervalo de 1 min, e não outra regra.
     */
    @Test
    @DisplayName("AC-005.3 resend-code com exatamente 60 s desde o envio → 202")
    void ac_005_3_resend_code_aos_60_segundos_e_aceito() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());
    }

    /**
     * AC-005.3: o intervalo conta a partir do <strong>último</strong> envio, não do primeiro —
     * reenvio aceito em t0+60 s, um segundo reenvio em t0+90 s (30 s depois do anterior) → 429 com
     * {@code Retry-After} 30. Cada etapa é um pedido.
     */
    @Test
    @DisplayName("AC-005.3 segundo reenvio 30 s depois do primeiro → 429 com Retry-After = 30")
    void ac_005_3_intervalo_conta_do_ultimo_reenvio() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();

        // Etapa 1: t0+60 s, primeiro reenvio aceito.
        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());

        // Etapa 2: t0+90 s, só 30 s depois do último envio.
        clock.advance(Duration.ofSeconds(30));
        MvcResult result = mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    }

    /**
     * AC-001.6 / D-15 (P5): e-mail sem conta nenhuma → o mesmo 202 conforme o contrato; nenhum
     * e-mail sai para o endereço e nenhuma conta é criada.
     */
    @Test
    @DisplayName("AC-001.6 / D-15 resend-code com e-mail desconhecido → 202, nenhum e-mail, nada criado")
    void ac_001_6_resend_code_email_desconhecido_responde_202_sem_efeito() throws Exception {
        String email = emailUnico();

        mockMvc.perform(reenviar(email, EMAIL_VERIFICATION))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        esperaNenhumEmail(email);
    }

    /**
     * AC-001.6 / D-15 (P5) "responde sempre com a mesma mensagem": a resposta para e-mail
     * desconhecido é indistinguível da de uma conta real com reenvio aceito — mesmo status, mesmo
     * corpo, sem {@code Retry-After}.
     */
    @Test
    @DisplayName("AC-001.6 / D-15 resend-code: resposta de e-mail desconhecido é idêntica à de conta real")
    void ac_001_6_resend_code_resposta_identica_exista_ou_nao_a_conta() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(60));

        MvcResult real = mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION)).andReturn();
        MvcResult desconhecido = mockMvc.perform(reenviar(emailUnico(), EMAIL_VERIFICATION)).andReturn();

        esperaRespostasIdenticas(real, desconhecido);
    }

    /**
     * Cenário 4 do orquestrador (ver Javadoc da classe): conta já confirmada (o código {@code
     * CONFIRMACAO_EMAIL} dela existe, mas já foi consumido por {@code verify-email}) pede
     * "reenviar código" de confirmação — 202 neutro, nenhum e-mail, o código consumido não é
     * reaberto (mesmo hash, {@code consumed_at} preservado) e {@code email_verified_at} não muda.
     * Espera mais de 1 min antes, para o 202 não ser confundido com o intervalo de AC-005.3.
     */
    @Test
    @DisplayName("AC-001.6 resend-code EMAIL_VERIFICATION em conta já confirmada → 202, nenhum e-mail, nada muda")
    void ac_001_6_resend_code_conta_ja_confirmada_nao_reenvia() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        mockMvc.perform(verificarEmail(c.email(), c.codigo())).andExpect(status().isOk());
        Timestamp verificadoEm = emailVerifiedAtDe(c.userId());
        String hashAntes = codeHashDe(c.otpId());
        clock.advance(Duration.ofMinutes(2));

        mockMvc.perform(reenviar(c.email(), EMAIL_VERIFICATION))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());

        assertThat(codeHashDe(c.otpId())).as("código consumido não é reaberto").isEqualTo(hashAntes);
        assertThat(consumedAtDe(c.otpId())).as("continua consumido").isNotNull();
        assertThat(emailVerifiedAtDe(c.userId())).isEqualTo(verificadoEm);
        esperaNenhumEmail(c.email());
    }

    /**
     * Cenário 4 do orquestrador: conta existente sem <strong>nenhum</strong> código da finalidade
     * pedida — {@code resend-code} só reenvia o que já existe, não cria código novo: 202, nenhuma
     * linha em {@code otp_codes}, nenhum e-mail.
     */
    @Test
    @DisplayName("AC-001.6 resend-code em conta sem código da finalidade → 202, nenhum código criado, nenhum e-mail")
    void ac_001_6_resend_code_sem_codigo_nao_cria_nada() throws Exception {
        User user = users.save(contaNaoConfirmada());

        mockMvc.perform(reenviar(user.getEmail(), EMAIL_VERIFICATION)).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_codes WHERE user_id = ?", Integer.class, user.getId()))
                .isZero();
        esperaNenhumEmail(user.getEmail());
    }

    /**
     * Mapeamento {@code purpose} → {@link OtpPurpose}: {@code PASSWORD_RESET} reenvia o código
     * {@code REDEFINICAO_SENHA} pendente — 202, chega o e-mail de <strong>redefinição</strong> (não
     * o de confirmação), e o código dele redefine a senha em {@code password-reset/confirm} (204).
     */
    @Test
    @DisplayName("AC-001.6 / AC-010.1 resend-code PASSWORD_RESET reenvia o código de redefinição, que redefine a senha")
    void ac_010_1_resend_code_password_reset_reenvia_codigo_de_redefinicao() throws Exception {
        User user = users.save(contaAtiva());
        emitir(user.getId(), OtpPurpose.REDEFINICAO_SENHA);
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(user.getEmail(), PASSWORD_RESET))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());

        String novo = codigoDoUnicoEmail(user.getEmail(), ASSUNTO_REDEFINICAO);
        assertThat(emails(user.getEmail(), ASSUNTO_CONFIRMACAO)).as("não é o template de confirmação").isEmpty();
        mockMvc.perform(confirmar(user.getEmail(), novo, SENHA_NOVA)).andExpect(status().isNoContent());
    }

    /**
     * Mapeamento {@code purpose} → {@link OtpPurpose}, lado negativo: conta com só um código de
     * {@code CONFIRMACAO_EMAIL} pendente pede {@code PASSWORD_RESET} — a finalidade é respeitada:
     * 202, o código de confirmação não é tocado, nenhum código de redefinição é criado (resend não
     * cria) e nenhum e-mail sai.
     */
    @Test
    @DisplayName("AC-001.6 resend-code PASSWORD_RESET não mexe no código de CONFIRMACAO_EMAIL")
    void ac_001_6_resend_code_respeita_a_finalidade() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        String hashAntes = codeHashDe(c.otpId());
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(c.email(), PASSWORD_RESET)).andExpect(status().isAccepted());

        assertThat(codeHashDe(c.otpId())).isEqualTo(hashAntes);
        assertThat(otpsDe(c.userId(), OtpPurpose.REDEFINICAO_SENHA)).isZero();
        esperaNenhumEmail(c.email());
    }

    /**
     * Decisão da T-020 (e-mail gravado em minúsculas): o e-mail digitado em maiúsculas é a mesma
     * conta — o reenvio acontece e o e-mail chega ao endereço normalizado.
     */
    @Test
    @DisplayName("AC-001.6 (decisão da T-020) resend-code com e-mail em maiúsculas reenvia para a mesma conta")
    void ac_001_6_resend_code_normaliza_o_email() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        clock.advance(Duration.ofSeconds(60));

        mockMvc.perform(reenviar(c.email().toUpperCase(Locale.ROOT), EMAIL_VERIFICATION))
                .andExpect(status().isAccepted());

        codigoDoUnicoEmail(c.email(), ASSUNTO_CONFIRMACAO);
    }

    // ------------------------------------------------------------- resend-code: corpo inválido

    /**
     * {@code ResendCodeRequest.purpose} só aceita os valores do contrato, em inglês. Os nomes do
     * enum Java ({@code CONFIRMACAO_EMAIL}/{@code REDEFINICAO_SENHA}), caixa diferente e vazio são
     * 400 {@code ValidationProblem} em {@code purpose} — nunca erro de desserialização nem 500.
     */
    @ParameterizedTest(name = "AC-001.6 purpose \"{0}\" é recusado em purpose")
    @ValueSource(strings = {
        "CONFIRMACAO_EMAIL",  // nome do enum Java, não do contrato
        "REDEFINICAO_SENHA",  // idem
        "email_verification", // caixa errada
        "LOGIN",              // fora do enum
        ""                    // vazio
    })
    @DisplayName("AC-001.6 resend-code com purpose fora do enum do contrato → 400 ValidationProblem em purpose")
    void ac_001_6_resend_code_purpose_invalido_e_recusado(String purpose) throws Exception {
        esperaValidationProblem(post(RESEND_CODE), corpoReenvio(emailUnico(), purpose), "purpose");
    }

    /** {@code ResendCodeRequest.purpose} é obrigatório: ausente → 400 em {@code purpose}. */
    @Test
    @DisplayName("AC-001.6 resend-code sem purpose → 400 ValidationProblem em purpose")
    void ac_001_6_resend_code_sem_purpose_e_recusado() throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", emailUnico());

        esperaValidationProblem(post(RESEND_CODE), corpo, "purpose");
    }

    /** {@code ResendCodeRequest.email} malformado ou vazio → 400 em {@code email}. */
    @ParameterizedTest(name = "AC-001.6 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {
        "sem-arroba", // sem @
        "@teste.com", // sem parte local
        ""            // vazio
    })
    @DisplayName("AC-001.6 resend-code com e-mail malformado → 400 ValidationProblem em email")
    void ac_001_6_resend_code_email_malformado_e_recusado(String email) throws Exception {
        esperaValidationProblem(post(RESEND_CODE), corpoReenvio(email, EMAIL_VERIFICATION), "email");
    }

    /** {@code ResendCodeRequest.email} é obrigatório: ausente → 400 em {@code email}. */
    @Test
    @DisplayName("AC-001.6 resend-code sem email → 400 ValidationProblem em email")
    void ac_001_6_resend_code_sem_email_e_recusado() throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("purpose", EMAIL_VERIFICATION);

        esperaValidationProblem(post(RESEND_CODE), corpo, "email");
    }

    // ==================================================================================
    // POST /auth/password-reset/request
    // ==================================================================================

    /**
     * AC-010.1 caminho feliz conforme o contrato: conta existente sem código de redefinição pede
     * para redefinir a senha — 202 sem corpo ({@code AcceptedNeutral}).
     */
    @Test
    @DisplayName("AC-010.1 password-reset/request com e-mail existente → 202, conforme o contrato")
    void ac_010_1_reset_request_responde_202_conforme_contrato() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(pedirRedefinicao(user.getEmail()))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-010.1 "recebe um código com as regras de AC-005": o primeiro pedido emite um código {@code
     * REDEFINICAO_SENHA} (uma linha em {@code otp_codes}, só o hash — D-04, prazo de 15 min —
     * AC-005.1, sem tentativas) e não mexe em nenhum código de outra finalidade.
     */
    @Test
    @DisplayName("AC-010.1 password-reset/request emite um código REDEFINICAO_SENHA de 15 min")
    void ac_010_1_reset_request_emite_codigo_de_redefinicao() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());

        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT purpose, attempts, expires_at, consumed_at FROM otp_codes WHERE user_id = ?", user.getId());
        assertThat(linhas).hasSize(1);
        assertThat(linhas.getFirst().get("purpose")).isEqualTo(OtpPurpose.REDEFINICAO_SENHA.name());
        assertThat(linhas.getFirst().get("attempts")).isEqualTo(0);
        assertThat(linhas.getFirst().get("consumed_at")).isNull();
        assertThat(((Timestamp) linhas.getFirst().get("expires_at")).toInstant())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
    }

    /**
     * AC-010.1 de ponta a ponta: o e-mail de <strong>redefinição</strong> chega ao dono da conta e
     * o código dele redefine a senha em {@code password-reset/confirm} (204).
     */
    @Test
    @DisplayName("AC-010.1 password-reset/request envia o e-mail de redefinição cujo código redefine a senha")
    void ac_010_1_reset_request_envia_codigo_que_redefine_a_senha() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());

        String codigo = codigoDoUnicoEmail(user.getEmail(), ASSUNTO_REDEFINICAO);
        mockMvc.perform(confirmar(user.getEmail(), codigo, SENHA_NOVA)).andExpect(status().isNoContent());
    }

    /**
     * AC-010.1 / AC-010.5 "qualquer conta existente": uma conta que ainda não confirmou o e-mail
     * também pode pedir a redefinição — recebe o código {@code REDEFINICAO_SENHA}, e o código de
     * confirmação pendente dela continua intocado.
     */
    @Test
    @DisplayName("AC-010.5 password-reset/request em conta não confirmada emite o código sem mexer na confirmação pendente")
    void ac_010_5_reset_request_em_conta_nao_confirmada() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        String hashConfirmacao = codeHashDe(c.otpId());

        mockMvc.perform(pedirRedefinicao(c.email())).andExpect(status().isAccepted());

        codigoDoUnicoEmail(c.email(), ASSUNTO_REDEFINICAO);
        assertThat(otpsDe(c.userId(), OtpPurpose.REDEFINICAO_SENHA)).isEqualTo(1);
        assertThat(codeHashDe(c.otpId())).as("confirmação pendente intocada").isEqualTo(hashConfirmacao);
    }

    /**
     * AC-010.2 / D-15 (P5): e-mail sem conta → o mesmo 202 conforme o contrato; nenhum e-mail sai e
     * nenhuma conta é criada.
     */
    @Test
    @DisplayName("AC-010.2 / D-15 password-reset/request com e-mail desconhecido → 202, nenhum e-mail, nada criado")
    void ac_010_2_reset_request_email_desconhecido_responde_202_sem_efeito() throws Exception {
        String email = emailUnico();

        mockMvc.perform(pedirRedefinicao(email))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        esperaNenhumEmail(email);
    }

    /**
     * AC-010.2 "a resposta é a mesma quer o e-mail exista ou não": mesmo status e mesmo corpo para
     * uma conta real e um e-mail desconhecido.
     */
    @Test
    @DisplayName("AC-010.2 / D-15 password-reset/request: resposta de e-mail desconhecido é idêntica à de conta real")
    void ac_010_2_reset_request_resposta_identica_exista_ou_nao_o_email() throws Exception {
        User user = users.save(contaAtiva());

        MvcResult real = mockMvc.perform(pedirRedefinicao(user.getEmail())).andReturn();
        MvcResult desconhecido = mockMvc.perform(pedirRedefinicao(emailUnico())).andReturn();

        esperaRespostasIdenticas(real, desconhecido);
    }

    /**
     * AC-005.3 aplicado a {@code password-reset/request} (decisão do orquestrador: já havendo um
     * código de redefinição, o pedido é um reenvio): chamar duas vezes seguidas não escapa do limite
     * de 1/min — o segundo pedido, 10 s depois, é 429 {@code Problem} conforme o contrato, com
     * {@code Retry-After} 50.
     */
    @Test
    @DisplayName("AC-005.3 password-reset/request repetido antes de 1 minuto → 429 com Retry-After, conforme o contrato")
    void ac_005_3_reset_request_repetido_antes_de_1_minuto_responde_429() throws Exception {
        User user = users.save(contaAtiva());
        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());
        clock.advance(Duration.ofSeconds(10));

        MvcResult result = mockMvc.perform(pedirRedefinicao(user.getEmail()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("50");
    }

    /**
     * AC-005.3: o pedido repetido recusado (429) não cria uma segunda linha de código nem envia
     * um segundo e-mail — só o e-mail do primeiro pedido existe.
     */
    @Test
    @DisplayName("AC-005.3 password-reset/request recusado (429) não cria outro código nem envia outro e-mail")
    void ac_005_3_reset_request_recusado_nao_cria_nem_envia() throws Exception {
        User user = users.save(contaAtiva());
        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());
        codigoDoUnicoEmail(user.getEmail(), ASSUNTO_REDEFINICAO);
        clock.advance(Duration.ofSeconds(10));

        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isTooManyRequests());

        assertThat(otpsDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA)).isEqualTo(1);
        await().during(ESTABILIDADE_CAIXA)
                .atMost(ESTABILIDADE_CAIXA.plusSeconds(3))
                .untilAsserted(() -> assertThat(emails(user.getEmail(), ASSUNTO_REDEFINICAO)).hasSize(1));
    }

    /**
     * AC-005.3 / decisão do orquestrador ("já havendo um, trata como reenvio"): depois de 1 min,
     * um novo pedido é aceito e <strong>reenvia</strong> o mesmo código de redefinição — continua
     * uma linha só em {@code otp_codes}, com hash novo; o código antigo deixa de redefinir (400) e o
     * novo redefine (204). Etapas: primeiro pedido, segundo pedido após 1 min, tentativas.
     */
    @Test
    @DisplayName("AC-005.3 password-reset/request após 1 minuto reenvia o mesmo código (uma linha, hash novo)")
    void ac_005_3_reset_request_apos_1_minuto_reenvia() throws Exception {
        User user = users.save(contaAtiva());

        // Etapa 1: primeiro pedido.
        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());
        String primeiro = codigoDoUnicoEmail(user.getEmail(), ASSUNTO_REDEFINICAO);
        UUID otpId = otpIdDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA);
        String hashAntes = codeHashDe(otpId);

        // Etapa 2: segundo pedido, 1 min depois — aceito, mesma linha, hash novo.
        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());
        assertThat(otpsDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA)).isEqualTo(1);
        assertThat(codeHashDe(otpId)).isNotEqualTo(hashAntes);

        // Etapa 3: o código novo é o do e-mail mais recente.
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emails(user.getEmail(), ASSUNTO_REDEFINICAO)).hasSize(2));
        String segundo = codigoDe(emails(user.getEmail(), ASSUNTO_REDEFINICAO).getFirst());
        assumeThat(segundo).as("colisão aleatória de 1 em 1 milhão").isNotEqualTo(primeiro);

        // Etapa 4: o antigo virou código errado; o novo redefine.
        mockMvc.perform(confirmar(user.getEmail(), primeiro, SENHA_NOVA)).andExpect(status().isBadRequest());
        mockMvc.perform(confirmar(user.getEmail(), segundo, SENHA_NOVA)).andExpect(status().isNoContent());
    }

    /**
     * AC-010.1 / AC-005.3 / decisão documentada em {@link PasswordResetService#requestReset}: um
     * código {@code REDEFINICAO_SENHA} <strong>já consumido</strong> (por uma redefinição anterior
     * bem-sucedida) ainda conta como "já existe um" — o novo pedido é tratado como reenvio, que
     * <strong>reabre a mesma linha</strong> de {@code otp_codes} ({@code consumed_at} volta a
     * {@code NULL}, hash novo, prazo novo de 15 min a partir de agora), em vez de criar uma linha
     * nova. O comportamento visível continua o de AC-010.1: a pessoa recebe um código que funciona
     * e consegue redefinir a senha de novo. Espera 61 s simulados antes do segundo pedido para que
     * o 202 não se confunda com o limite de 1/min (AC-005.3). Etapas: primeiro pedido, primeira
     * redefinição, segundo pedido, conferência por SQL, segunda redefinição.
     */
    @Test
    @DisplayName("AC-010.1 / AC-005.3 password-reset/request após redefinição concluída reabre o mesmo código consumido em vez de criar outro")
    void ac_010_1_reset_request_reabre_codigo_consumido_em_vez_de_criar_outro() throws Exception {
        User user = users.save(contaAtiva());
        String terceiraSenha = "TerceiraSenha789";

        // Etapa 1: primeiro pedido emite o código 1 (uma linha nova).
        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());
        String primeiro = codigoDoUnicoEmail(user.getEmail(), ASSUNTO_REDEFINICAO);
        UUID otpId = otpIdDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA);
        String hashPrimeiro = codeHashDe(otpId);

        // Etapa 2: primeira redefinição com o código 1 — 204, senha trocada, código consumido.
        mockMvc.perform(confirmar(user.getEmail(), primeiro, SENHA_NOVA)).andExpect(status().isNoContent());
        assertThat(passwordEncoder.matches(SENHA_NOVA, passwordHashDe(user.getId()))).as("senha trocada").isTrue();
        assertThat(consumedAtDe(otpId)).as("código 1 consumido").isNotNull();

        // Etapa 3: 61 s depois (fora do limite de 1/min), novo pedido na mesma conta — 202.
        clock.advance(Duration.ofSeconds(61));
        mockMvc.perform(pedirRedefinicao(user.getEmail()))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());

        // Etapa 4: por SQL, é a MESMA linha reaberta — não uma linha nova.
        assertThat(otpsDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA)).as("nenhuma linha nova").isEqualTo(1);
        assertThat(otpIdDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA)).as("mesmo id").isEqualTo(otpId);
        assertThat(consumedAtDe(otpId)).as("consumed_at voltou a NULL").isNull();
        assertThat(codeHashDe(otpId)).as("hash novo").isNotEqualTo(hashPrimeiro);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM otp_codes WHERE id = ?", Timestamp.class, otpId)
                        .toInstant())
                .as("prazo novo de 15 min a partir do novo pedido (AC-005.1)")
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        // Etapa 5: o novo código (e-mail mais recente) redefine a senha de novo — 204.
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(emails(user.getEmail(), ASSUNTO_REDEFINICAO)).hasSize(2));
        String segundo = codigoDe(emails(user.getEmail(), ASSUNTO_REDEFINICAO).getFirst());
        assumeThat(segundo).as("colisão aleatória de 1 em 1 milhão").isNotEqualTo(primeiro);
        mockMvc.perform(confirmar(user.getEmail(), segundo, terceiraSenha)).andExpect(status().isNoContent());
        assertThat(passwordEncoder.matches(terceiraSenha, passwordHashDe(user.getId())))
                .as("senha trocada de novo")
                .isTrue();
        assertThat(consumedAtDe(otpId)).as("código reaberto consumido de novo").isNotNull();
    }

    /**
     * {@code password-reset/request} só pede o código: não altera a senha, não encerra sessões e
     * não muda o estado da conta (o efeito de AC-010.4/AC-010.5 só vem no {@code confirm}).
     */
    @Test
    @DisplayName("AC-010.1 password-reset/request não altera senha nem encerra sessões")
    void ac_010_1_reset_request_nao_altera_senha_nem_sessoes() throws Exception {
        User user = users.save(contaAtiva());
        String hashSenha = passwordHashDe(user.getId());
        refreshTokenService.issue(user.getId());

        mockMvc.perform(pedirRedefinicao(user.getEmail())).andExpect(status().isAccepted());

        assertThat(passwordHashDe(user.getId())).isEqualTo(hashSenha);
        assertThat(sessoesAtivasDe(user.getId())).isEqualTo(1);
    }

    /** Decisão da T-020: e-mail em maiúsculas é a mesma conta — o código chega ao endereço gravado. */
    @Test
    @DisplayName("AC-010.1 (decisão da T-020) password-reset/request com e-mail em maiúsculas emite para a mesma conta")
    void ac_010_1_reset_request_normaliza_o_email() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(pedirRedefinicao(user.getEmail().toUpperCase(Locale.ROOT))).andExpect(status().isAccepted());

        assertThat(otpsDe(user.getId(), OtpPurpose.REDEFINICAO_SENHA)).isEqualTo(1);
    }

    // ------------------------------------------------------------- password-reset/request: corpo inválido

    /** {@code EmailOnly.email} malformado ou vazio → 400 {@code ValidationProblem} em {@code email}. */
    @ParameterizedTest(name = "AC-010.1 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {
        "sem-arroba", // sem @
        "@teste.com", // sem parte local
        ""            // vazio
    })
    @DisplayName("AC-010.1 password-reset/request com e-mail malformado → 400 ValidationProblem em email")
    void ac_010_1_reset_request_email_malformado_e_recusado(String email) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);

        esperaValidationProblem(post(RESET_REQUEST), corpo, "email");
    }

    /** {@code EmailOnly.email} é obrigatório: corpo sem ele → 400 em {@code email}. */
    @Test
    @DisplayName("AC-010.1 password-reset/request sem email → 400 ValidationProblem em email")
    void ac_010_1_reset_request_sem_email_e_recusado() throws Exception {
        esperaValidationProblem(post(RESET_REQUEST), new LinkedHashMap<>(), "email");
    }

    // ==================================================================================
    // POST /auth/password-reset/confirm
    // ==================================================================================

    /**
     * AC-010.3 caminho feliz conforme o contrato: código certo e senha forte → 204 sem corpo
     * ({@code confirmPasswordReset}).
     */
    @Test
    @DisplayName("AC-010.3 password-reset/confirm com código certo e senha forte → 204, conforme o contrato")
    void ac_010_3_confirm_responde_204_conforme_contrato() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * AC-010.3 / D-14: a senha é de fato trocada no banco — {@code password_hash} muda, é um hash
     * BCrypt da senha nova (confere com {@link PasswordEncoder#matches}) e nunca o texto puro.
     */
    @Test
    @DisplayName("AC-010.3 password-reset/confirm grava em password_hash o hash da senha nova")
    void ac_010_3_confirm_troca_o_hash_da_senha() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String hashAntes = passwordHashDe(r.user().getId());

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        String hashDepois = passwordHashDe(r.user().getId());
        assertThat(hashDepois).isNotEqualTo(hashAntes).doesNotContain(SENHA_NOVA);
        assertThat(passwordEncoder.matches(SENHA_NOVA, hashDepois)).as("hash da senha nova").isTrue();
        assertThat(passwordEncoder.matches(SENHA_ANTIGA, hashDepois)).as("não é mais a antiga").isFalse();
    }

    /**
     * AC-010.4 "é preciso entrar de novo com a nova senha": depois do 204, o login com a senha
     * antiga é recusado (401) e com a senha nova abre sessão (200). Etapas: redefine, senha antiga,
     * senha nova.
     */
    @Test
    @DisplayName("AC-010.3 / AC-010.4 após confirm, login com a senha antiga → 401 e com a nova → 200")
    void ac_010_4_apos_confirm_so_a_senha_nova_entra() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        // Etapa 1: redefine.
        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        // Etapa 2: senha antiga não entra mais.
        mockMvc.perform(entrar(r.user().getEmail(), SENHA_ANTIGA)).andExpect(status().isUnauthorized());

        // Etapa 3: senha nova entra.
        mockMvc.perform(entrar(r.user().getEmail(), SENHA_NOVA)).andExpect(status().isOk());
    }

    /**
     * D-37: o {@code confirm} encerra sessões, não abre nenhuma — o 204 não traz {@code Set-Cookie
     * refresh_token} e não grava refresh token novo (a pessoa precisa entrar de novo, AC-010.4).
     */
    @Test
    @DisplayName("AC-010.4 / D-37 password-reset/confirm não abre sessão (sem cookie, sem refresh token novo)")
    void ac_010_4_confirm_nao_abre_sessao() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        MvcResult result = mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(setCookieRefreshToken(result)).isNull();
        assertThat(totalDeRefreshTokensDe(r.user().getId())).isZero();
    }

    /**
     * AC-005 (uso único): o código de redefinição é consumido no 204 ({@code consumed_at}
     * preenchido); reapresentá-lo responde 410 e não troca a senha de novo.
     */
    @Test
    @DisplayName("AC-005.1 password-reset/confirm consome o código: reusá-lo → 410 e a senha não muda de novo")
    void ac_005_1_confirm_consome_o_codigo() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());
        assertThat(consumedAtDe(r.otpId())).isNotNull();
        String hashDepoisDaPrimeira = passwordHashDe(r.user().getId());

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), "OutraSenha789"))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());

        assertThat(passwordHashDe(r.user().getId())).isEqualTo(hashDepoisDaPrimeira);
    }

    /** Decisão da T-020: e-mail em maiúsculas no {@code confirm} é a mesma conta (204). */
    @Test
    @DisplayName("AC-010.3 (decisão da T-020) password-reset/confirm com e-mail em maiúsculas redefine a mesma conta")
    void ac_010_3_confirm_normaliza_o_email() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        mockMvc.perform(confirmar(r.user().getEmail().toUpperCase(Locale.ROOT), r.codigo(), SENHA_NOVA))
                .andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches(SENHA_NOVA, passwordHashDe(r.user().getId()))).isTrue();
    }

    // ------------------------------------------------------------- AC-010.4 / D-37 todas as sessões

    /**
     * "Pronto quando" da T-026 (refresh tokens antigos inválidos) / AC-010.4 / D-37 "revoga
     * <strong>todos</strong> os refresh tokens da conta": a mesma conta entra em dois aparelhos (dois
     * logins = duas famílias, D-11 rev. 3) e um deles ainda rotaciona o token (família com duas
     * linhas). Depois do {@code confirm}, por SQL <strong>as duas</strong> famílias estão revogadas
     * (diferente do logout da T-025, que derruba só um aparelho), e {@code /auth/refresh} com o
     * token atual de cada aparelho → 401.
     */
    @Test
    @DisplayName("AC-010.4 / D-37 / Pronto quando: confirm revoga as duas famílias e nenhum aparelho renova mais (401)")
    void ac_010_4_confirm_revoga_todas_as_familias_de_refresh_token() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        UUID userId = r.user().getId();

        // Etapa 1: dois aparelhos, duas famílias; o aparelho A ainda rotaciona uma vez.
        String tokenA = refreshDoLogin(r.user().getEmail());
        String tokenB = refreshDoLogin(r.user().getEmail());
        String tokenAAtual = valorDoCookie(setCookieRefreshToken(
                mockMvc.perform(renovar(tokenA)).andExpect(status().isOk()).andReturn()));
        assertThat(familiasDe(userId)).as("duas famílias (dois aparelhos)").hasSize(2);
        assertThat(sessoesAtivasDe(userId)).as("um token ativo por família").isEqualTo(2);

        // Etapa 2: redefine a senha.
        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        // Etapa 3: por SQL, nenhuma linha ativa em nenhuma das famílias.
        assertThat(sessoesAtivasDe(userId)).as("todas as linhas da conta revogadas").isZero();
        for (UUID familia : familiasDe(userId)) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                            Integer.class, familia))
                    .as("família %s revogada", familia)
                    .isZero();
        }

        // Etapa 4: nenhum dos dois aparelhos renova mais.
        mockMvc.perform(renovar(tokenAAtual))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
        mockMvc.perform(renovar(tokenB))
                .andExpect(status().isUnauthorized())
                .andExpect(ContractValidation.matchesContract());
    }

    /**
     * D-37: a revogação é feita no instante do {@code confirm} — {@code revoked_at} das linhas que
     * estavam ativas é o "agora" do relógio injetado (D-10), não um valor qualquer.
     */
    @Test
    @DisplayName("D-37 confirm grava revoked_at = agora (relógio injetado) nas sessões que estavam ativas")
    void d_37_confirm_revoga_no_instante_do_confirm() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        refreshTokenService.issue(r.user().getId());
        refreshTokenService.issue(r.user().getId());
        clock.advance(Duration.ofMinutes(1));

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        List<Timestamp> revogadas = jdbc.queryForList(
                "SELECT revoked_at FROM refresh_tokens WHERE user_id = ?", Timestamp.class, r.user().getId());
        assertThat(revogadas).hasSize(2);
        assertThat(revogadas).allSatisfy(ts -> assertThat(ts.toInstant()).isEqualTo(clock.instant()));
    }

    /**
     * D-37 "todos os refresh tokens <em>da conta</em>": as sessões de <strong>outra</strong> conta
     * não são tocadas — continuam ativas e renovando (200).
     */
    @Test
    @DisplayName("D-37 confirm não encerra sessões de outra conta")
    void d_37_confirm_nao_afeta_outra_conta() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        User outra = users.save(contaAtiva());
        String tokenDaOutra = refreshDoLogin(outra.getEmail());

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        assertThat(sessoesAtivasDe(outra.getId())).isEqualTo(1);
        mockMvc.perform(renovar(tokenDaOutra)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------- AC-010.5 estado não muda

    /**
     * "Pronto quando" da T-026 (cenário central) / AC-010.5 / D-37: uma conta {@code
     * AGUARDANDO_APROVACAO} (convidada, membership pendente) redefine a senha — 204, e por SQL a
     * membership <strong>continua</strong> {@code AGUARDANDO_APROVACAO} (não foi aprovada, nem
     * expirada, nem recusada), sem decisor nem data de decisão, e o e-mail continua verificado.
     * Por fim, o login com a senha nova mostra o mesmo estado: 403 {@code AWAITING_APPROVAL}.
     */
    @Test
    @DisplayName("AC-010.5 / D-37 / Pronto quando: conta AGUARDANDO_APROVACAO redefine a senha e continua AGUARDANDO_APROVACAO")
    void ac_010_5_aguardando_aprovacao_redefine_e_continua_no_mesmo_estado() throws Exception {
        User user = contaNoEstado(Estado.AGUARDANDO_APROVACAO);
        Map<String, Object> membershipAntes = membershipDe(user.getId());
        Timestamp verificadoEm = emailVerifiedAtDe(user.getId());
        IssuedOtp codigo = emitir(user.getId(), OtpPurpose.REDEFINICAO_SENHA);

        // Etapa 1: redefine.
        mockMvc.perform(confirmar(user.getEmail(), codigo.plainCode(), SENHA_NOVA))
                .andExpect(status().isNoContent())
                .andExpect(ContractValidation.matchesContract());

        // Etapa 2: por SQL, a membership está exatamente como antes.
        Map<String, Object> membershipDepois = membershipDe(user.getId());
        assertThat(membershipDepois.get("status")).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(membershipDepois).as("nenhum campo da membership mudou").isEqualTo(membershipAntes);
        assertThat(emailVerifiedAtDe(user.getId())).isEqualTo(verificadoEm);

        // Etapa 3: a senha mudou (a nova passa da checagem de senha) e o estado continua o mesmo.
        MvcResult login = mockMvc.perform(entrar(user.getEmail(), SENHA_NOVA))
                .andExpect(status().isForbidden())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        assertThat(corpo(login).path("code").asText()).isEqualTo("AWAITING_APPROVAL");
    }

    /**
     * "Pronto quando" da T-026 (refresh tokens antigos inválidos) aplicado à conta {@code
     * AGUARDANDO_APROVACAO}: ela não consegue entrar por {@code /auth/login} (403), então a sessão
     * antiga é emitida direto por {@link RefreshTokenService#issue} (ex. sessão aberta antes de ela
     * pedir para entrar num grupo). Depois do {@code confirm}, essa sessão não renova mais (401).
     */
    @Test
    @DisplayName("AC-010.4 conta AGUARDANDO_APROVACAO: sessão antiga deixa de renovar após confirm (401)")
    void ac_010_4_aguardando_aprovacao_sessao_antiga_invalida_apos_confirm() throws Exception {
        User user = contaNoEstado(Estado.AGUARDANDO_APROVACAO);
        String sessaoAntiga = refreshTokenService.issue(user.getId()).plainToken();
        IssuedOtp codigo = emitir(user.getId(), OtpPurpose.REDEFINICAO_SENHA);

        mockMvc.perform(confirmar(user.getEmail(), codigo.plainCode(), SENHA_NOVA)).andExpect(status().isNoContent());

        assertThat(sessoesAtivasDe(user.getId())).isZero();
        mockMvc.perform(renovar(sessaoAntiga)).andExpect(status().isUnauthorized());
    }

    /**
     * AC-010.5 "qualquer conta existente pode redefinir a senha; a redefinição não altera o status":
     * para cada estado de conta de D-33 (inclusive os sem membership), o {@code confirm} responde
     * 204, o estado derivado por {@link AccountState#of} é o mesmo antes e depois, e por SQL nem
     * {@code users.email_verified_at} nem {@code memberships.status} mudam. Em especial, a linha
     * {@code AGUARDANDO_CONFIRMACAO_EMAIL} prova que o {@code confirm} não confirma o e-mail (não
     * chama {@code User#verifyEmail}, diferente de {@code VerifyEmailService}).
     */
    @ParameterizedTest(name = "AC-010.5 conta {0} redefine a senha e continua {0}")
    @EnumSource(Estado.class)
    @DisplayName("AC-010.5 qualquer estado de conta redefine a senha (204) e continua no mesmo estado")
    void ac_010_5_qualquer_estado_redefine_e_nao_muda(Estado estado) throws Exception {
        // Uma linha por valor de Estado: AGUARDANDO_CONFIRMACAO_EMAIL, ATIVO_SEM_GRUPO,
        // ATIVO_RESPONSAVEL, AGUARDANDO_APROVACAO, RECUSADO, EXPIRADO, REMOVIDO.
        User user = contaNoEstado(estado);
        AccountStateValue antes = estadoDerivadoDe(user.getId());
        Timestamp verificadoEm = emailVerifiedAtDe(user.getId());
        String statusMembership = statusMembershipDe(user.getId());
        IssuedOtp codigo = emitir(user.getId(), OtpPurpose.REDEFINICAO_SENHA);

        mockMvc.perform(confirmar(user.getEmail(), codigo.plainCode(), SENHA_NOVA)).andExpect(status().isNoContent());

        assertThat(estadoDerivadoDe(user.getId())).as("estado derivado (D-33)").isEqualTo(antes);
        assertThat(emailVerifiedAtDe(user.getId())).as("users.email_verified_at").isEqualTo(verificadoEm);
        assertThat(statusMembershipDe(user.getId())).as("memberships.status").isEqualTo(statusMembership);
        assertThat(passwordEncoder.matches(SENHA_NOVA, passwordHashDe(user.getId()))).as("senha trocada").isTrue();
    }

    /**
     * D-37 "não toca no estado da conta", em nível de linha: o {@code confirm} só troca {@code
     * password_hash} — nome, e-mail, telefone, endereço, {@code email_verified_at} e {@code
     * created_at} ficam exatamente como estavam.
     */
    @Test
    @DisplayName("D-37 confirm só altera password_hash na linha de users")
    void d_37_confirm_so_altera_password_hash() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String colunas = "name, email, phone, address_zip_code, address_street, address_number, address_complement, "
                + "address_district, address_city, address_state, email_verified_at, created_at";
        Map<String, Object> antes = jdbc.queryForMap("SELECT " + colunas + " FROM users WHERE id = ?", r.user().getId());

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForMap("SELECT " + colunas + " FROM users WHERE id = ?", r.user().getId()))
                .isEqualTo(antes);
    }

    // ------------------------------------------------------------- confirm: código errado (400)

    /**
     * AC-005.2 aplicado ao {@code confirm}: código de formato válido mas errado → 400 {@code
     * InvalidCodeProblem} com {@code remainingAttempts} 4, e a tentativa fica gravada.
     */
    @Test
    @DisplayName("AC-005.2 password-reset/confirm com código errado → 400 InvalidCodeProblem com remainingAttempts=4")
    void ac_005_2_confirm_codigo_errado_responde_400() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        MvcResult result = mockMvc.perform(confirmar(r.user().getEmail(), codigoErrado(r.codigo()), SENHA_NOVA))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode corpo = corpo(result);
        List<Error> violacoes = ContractSchemas.schemaFor("InvalidCodeProblem").validate(corpo);
        assertThat(violacoes).as("corpo casa com InvalidCodeProblem: %s", violacoes).isEmpty();
        assertThat(corpo.path("remainingAttempts").asInt()).isEqualTo(4);
        assertThat(tentativasDe(r.otpId())).as("tentativa errada persistida").isEqualTo(1);
    }

    /**
     * AC-005.2 / AC-010.4: código errado não troca a senha e não encerra nenhuma sessão — só quem
     * prova o código derruba as sessões da conta.
     */
    @Test
    @DisplayName("AC-005.2 password-reset/confirm com código errado não troca a senha nem encerra sessões")
    void ac_005_2_confirm_codigo_errado_nao_muda_nada() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String hashSenha = passwordHashDe(r.user().getId());
        refreshTokenService.issue(r.user().getId());

        mockMvc.perform(confirmar(r.user().getEmail(), codigoErrado(r.codigo()), SENHA_NOVA))
                .andExpect(status().isBadRequest());

        assertThat(passwordHashDe(r.user().getId())).isEqualTo(hashSenha);
        assertThat(sessoesAtivasDe(r.user().getId())).isEqualTo(1);
        assertThat(consumedAtDe(r.otpId())).isNull();
    }

    /**
     * AC-005.2: o contador decresce a cada tentativa errada no {@code confirm}, de requisição em
     * requisição, até 0 na 5ª.
     */
    @Test
    @DisplayName("AC-005.2 password-reset/confirm: tentativas erradas seguidas decrescem remainingAttempts 4, 3, 2, 1, 0")
    void ac_005_2_confirm_tentativas_erradas_decrescem() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String errado = codigoErrado(r.codigo());

        // Linha a linha: tentativa N errada -> remainingAttempts = 5 - N.
        int[] esperado = {
            4, // 1ª errada
            3, // 2ª errada
            2, // 3ª errada
            1, // 4ª errada
            0  // 5ª errada: ainda 400, sem tentativas restantes
        };
        for (int i = 0; i < esperado.length; i++) {
            JsonNode corpo = corpo(mockMvc.perform(confirmar(r.user().getEmail(), errado, SENHA_NOVA))
                    .andExpect(status().isBadRequest())
                    .andReturn());
            assertThat(corpo.path("remainingAttempts").asInt()).as("%dª tentativa errada", i + 1).isEqualTo(esperado[i]);
        }
    }

    // ------------------------------------------------------------- confirm: 410

    /**
     * AC-005.1 aplicado ao {@code confirm}: com 15 min exatos o código de redefinição já expirou — o
     * código certo responde 410 {@code Problem} conforme o contrato e a senha não muda.
     */
    @Test
    @DisplayName("AC-005.1 password-reset/confirm com código expirado (15 min) → 410, conforme o contrato, senha intacta")
    void ac_005_1_confirm_codigo_expirado_responde_410() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String hashSenha = passwordHashDe(r.user().getId());
        clock.advance(Duration.ofMinutes(15));

        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());

        assertThat(passwordHashDe(r.user().getId())).isEqualTo(hashSenha);
    }

    /**
     * AC-005.2 aplicado ao {@code confirm}: depois de 5 tentativas erradas, o código certo responde
     * 410 (não 204), a senha não muda e as sessões continuam. Etapas: esgota, tenta o certo.
     */
    @Test
    @DisplayName("AC-005.2 password-reset/confirm após 5 erradas: código certo → 410, senha e sessões intactas")
    void ac_005_2_confirm_codigo_esgotado_responde_410() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String hashSenha = passwordHashDe(r.user().getId());
        refreshTokenService.issue(r.user().getId());

        // Etapa 1: esgota as 5 tentativas.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(confirmar(r.user().getEmail(), codigoErrado(r.codigo()), SENHA_NOVA))
                    .andExpect(status().isBadRequest());
        }

        // Etapa 2: o código certo já não vale.
        mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());
        assertThat(passwordHashDe(r.user().getId())).isEqualTo(hashSenha);
        assertThat(sessoesAtivasDe(r.user().getId())).isEqualTo(1);
    }

    /**
     * Decisão do orquestrador (P5, mesmo padrão de {@code VerifyEmailService}): e-mail sem conta no
     * {@code confirm} → 410 {@code Problem} genérico conforme o contrato, sem {@code
     * remainingAttempts} (não há 404 nesta rota).
     */
    @Test
    @DisplayName("AC-010.2 (decisão do orquestrador, P5) password-reset/confirm com e-mail desconhecido → 410 genérico")
    void ac_010_2_confirm_email_desconhecido_responde_410() throws Exception {
        MvcResult result = mockMvc.perform(confirmar(emailUnico(), "123456", SENHA_NOVA))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(corpo(result).has("remainingAttempts")).isFalse();
    }

    /**
     * P5: o 410 de e-mail desconhecido é idêntico (status e corpo) ao de um código expirado de uma
     * conta real — o {@code confirm} não revela se o e-mail tem cadastro.
     */
    @Test
    @DisplayName("AC-010.2 (P5) 410 de e-mail desconhecido no confirm é idêntico ao de código expirado")
    void ac_010_2_confirm_410_identico_exista_ou_nao_o_email() throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        clock.advance(Duration.ofMinutes(15));

        MvcResult expirado = mockMvc.perform(confirmar(r.user().getEmail(), r.codigo(), SENHA_NOVA)).andReturn();
        MvcResult desconhecido = mockMvc.perform(confirmar(emailUnico(), r.codigo(), SENHA_NOVA)).andReturn();

        assertThat(desconhecido.getResponse().getStatus()).isEqualTo(410).isEqualTo(expirado.getResponse().getStatus());
        assertThat(corpo(desconhecido)).isEqualTo(corpo(expirado));
    }

    /**
     * Separação de finalidades (segurança): conta que só tem um código de {@code CONFIRMACAO_EMAIL}
     * pendente não pode usá-lo para trocar a senha — não há código de redefinição, então 410, e a
     * senha e o código de confirmação ficam intactos.
     */
    @Test
    @DisplayName("AC-010.3 código de CONFIRMACAO_EMAIL não redefine senha: 410, senha intacta")
    void ac_010_3_codigo_de_confirmacao_nao_redefine_senha() throws Exception {
        Cenario c = contaNaoConfirmadaComCodigo();
        String hashSenha = passwordHashDe(c.userId());

        mockMvc.perform(confirmar(c.email(), c.codigo(), SENHA_NOVA))
                .andExpect(status().isGone())
                .andExpect(ContractValidation.matchesContract());

        assertThat(passwordHashDe(c.userId())).isEqualTo(hashSenha);
        assertThat(consumedAtDe(c.otpId())).isNull();
        assertThat(emailVerifiedAtDe(c.userId())).isNull();
    }

    // ------------------------------------------------------------- confirm: corpo inválido

    /**
     * AC-010.3 "a nova senha segue AC-001.3" (mínimo 8, com letra e número): senha fraca → 400
     * {@code ValidationProblem} em {@code newPassword}; a senha não muda, o código não é consumido
     * nem conta tentativa e as sessões continuam.
     */
    @ParameterizedTest(name = "AC-010.3 / AC-001.3 nova senha \"{0}\" é recusada em newPassword")
    @ValueSource(strings = {
        "somenteletras", // sem número
        "12345678",      // sem letra
        "abc1234",       // 7 caracteres
        ""               // vazia
    })
    @DisplayName("AC-010.3 / AC-001.3 password-reset/confirm com senha fraca → 400 ValidationProblem em newPassword, nada muda")
    void ac_010_3_confirm_senha_fraca_e_recusada(String senha) throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        String hashSenha = passwordHashDe(r.user().getId());
        refreshTokenService.issue(r.user().getId());

        esperaValidationProblem(post(RESET_CONFIRM), corpoConfirmacao(r.user().getEmail(), r.codigo(), senha), "newPassword");

        assertThat(passwordHashDe(r.user().getId())).isEqualTo(hashSenha);
        assertThat(tentativasDe(r.otpId())).isZero();
        assertThat(consumedAtDe(r.otpId())).isNull();
        assertThat(sessoesAtivasDe(r.user().getId())).isEqualTo(1);
    }

    /**
     * {@code PasswordResetConfirmRequest.code} deve casar {@code ^\d{6}$}: fora do padrão é erro de
     * validação (400 em {@code code}), não tentativa errada.
     */
    @ParameterizedTest(name = "AC-005.1 código \"{0}\" é recusado em code")
    @ValueSource(strings = {
        "12345",   // 5 dígitos
        "1234567", // 7 dígitos
        "abcdef",  // letras
        ""         // vazio
    })
    @DisplayName("AC-005.1 password-reset/confirm com código fora de 6 dígitos → 400 ValidationProblem em code")
    void ac_005_1_confirm_codigo_fora_do_padrao_e_recusado(String codigo) throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();

        esperaValidationProblem(post(RESET_CONFIRM), corpoConfirmacao(r.user().getEmail(), codigo, SENHA_NOVA), "code");

        assertThat(tentativasDe(r.otpId())).isZero();
    }

    /** {@code PasswordResetConfirmRequest.email} malformado → 400 em {@code email}. */
    @ParameterizedTest(name = "AC-010.3 e-mail \"{0}\" é recusado em email")
    @ValueSource(strings = {
        "sem-arroba", // sem @
        "@teste.com", // sem parte local
        ""            // vazio
    })
    @DisplayName("AC-010.3 password-reset/confirm com e-mail malformado → 400 ValidationProblem em email")
    void ac_010_3_confirm_email_malformado_e_recusado(String email) throws Exception {
        esperaValidationProblem(post(RESET_CONFIRM), corpoConfirmacao(email, "123456", SENHA_NOVA), "email");
    }

    /**
     * Campos obrigatórios de {@code PasswordResetConfirmRequest}: cada um ausente → 400 com erro
     * naquele campo. D-37 "exige a nova senha na mesma chamada": sem {@code newPassword} não há
     * redefinição em duas etapas.
     */
    @ParameterizedTest(name = "D-37 corpo sem {0} → 400 em {0}")
    @ValueSource(strings = {
        "newPassword", // D-37: a senha nova vem na mesma chamada
        "code",        // sem código
        "email"        // sem e-mail
    })
    @DisplayName("D-37 password-reset/confirm sem campo obrigatório → 400 ValidationProblem no campo")
    void d_37_confirm_sem_campo_obrigatorio_e_recusado(String campo) throws Exception {
        Reset r = contaAtivaComCodigoDeRedefinicao();
        Map<String, Object> corpo = corpoConfirmacao(r.user().getEmail(), r.codigo(), SENHA_NOVA);
        corpo.remove(campo);

        esperaValidationProblem(post(RESET_CONFIRM), corpo, campo);

        assertThat(consumedAtDe(r.otpId())).as("nada consumido").isNull();
    }

    // ==================================================================================
    // apoio
    // ==================================================================================

    /** Conta não confirmada com um código {@code CONFIRMACAO_EMAIL} pendente e o código em texto. */
    private record Cenario(UUID userId, String email, UUID otpId, String codigo) {}

    /** Conta {@code ATIVO} (senha {@link #SENHA_ANTIGA}) com um código {@code REDEFINICAO_SENHA} pendente. */
    private record Reset(User user, UUID otpId, String codigo) {}

    /**
     * Estados de conta de D-33 usados em AC-010.5: os dois sem membership (não confirmada, ativa sem
     * grupo) e os de membership.
     */
    enum Estado {
        AGUARDANDO_CONFIRMACAO_EMAIL,
        ATIVO_SEM_GRUPO,
        ATIVO_RESPONSAVEL,
        AGUARDANDO_APROVACAO,
        RECUSADO,
        EXPIRADO,
        REMOVIDO
    }

    /** Mesmo estado que o cadastro (T-020) deixa, montado pelos serviços, sem ler o Mailpit. */
    private Cenario contaNaoConfirmadaComCodigo() {
        User user = users.save(contaNaoConfirmada());
        IssuedOtp emitido = emitir(user.getId(), OtpPurpose.CONFIRMACAO_EMAIL);
        return new Cenario(user.getId(), user.getEmail(), emitido.otp().getId(), emitido.plainCode());
    }

    private Reset contaAtivaComCodigoDeRedefinicao() {
        User user = users.save(contaAtiva());
        IssuedOtp emitido = emitir(user.getId(), OtpPurpose.REDEFINICAO_SENHA);
        return new Reset(user, emitido.otp().getId(), emitido.plainCode());
    }

    private IssuedOtp emitir(UUID userId, OtpPurpose purpose) {
        IssuedOtp emitido = otpService.issue(userId, purpose);
        otpCodes.save(emitido.otp());
        return emitido;
    }

    /**
     * Conta no estado pedido, com senha {@link #SENHA_ANTIGA}. As transições de membership usam a
     * própria entidade {@link Membership} (D-07) e um relógio local, para não mexer no relógio
     * compartilhado (mesmo padrão de {@code LoginTest}).
     */
    private User contaNoEstado(Estado estado) {
        if (estado == Estado.AGUARDANDO_CONFIRMACAO_EMAIL) {
            return users.save(contaNaoConfirmada());
        }
        if (estado == Estado.ATIVO_SEM_GRUPO) {
            return users.save(contaAtiva());
        }
        User responsavel = users.save(contaAtiva());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        User user = users.save(contaAtiva());
        MutableClock relogio = new MutableClock(clock.instant(), ZoneOffset.UTC);
        Membership m = switch (estado) {
            case ATIVO_RESPONSAVEL -> Membership.admin(groupId, user.getId(), relogio);
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
            default -> throw new IllegalArgumentException(estado.name());
        };
        memberships.save(m);
        return user;
    }

    private User contaAtiva() {
        User user = contaNaoConfirmada();
        user.verifyEmail(clock);
        return user;
    }

    private User contaNaoConfirmada() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        return User.signup(
                "Maria da Silva", emailUnico(), "21987654321", endereco, passwordEncoder.encode(SENHA_ANTIGA), clock);
    }

    private static String emailUnico() {
        return "reset-" + UUID.randomUUID() + "@teste.com";
    }

    /** Outro código de 6 dígitos, garantidamente diferente do certo. */
    private static String codigoErrado(String certo) {
        return String.format("%06d", (Integer.parseInt(certo) + 1) % 1_000_000);
    }

    // ---------- requisições

    private static Map<String, Object> corpoReenvio(String email, String purpose) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("purpose", purpose);
        return corpo;
    }

    private static Map<String, Object> corpoConfirmacao(String email, String codigo, String novaSenha) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("code", codigo);
        corpo.put("newPassword", novaSenha);
        return corpo;
    }

    private MockHttpServletRequestBuilder comCorpo(MockHttpServletRequestBuilder base, Map<String, Object> corpo)
            throws Exception {
        return base.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    private MockHttpServletRequestBuilder reenviar(String email, String purpose) throws Exception {
        return comCorpo(post(RESEND_CODE), corpoReenvio(email, purpose));
    }

    private MockHttpServletRequestBuilder pedirRedefinicao(String email) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        return comCorpo(post(RESET_REQUEST), corpo);
    }

    private MockHttpServletRequestBuilder confirmar(String email, String codigo, String novaSenha) throws Exception {
        return comCorpo(post(RESET_CONFIRM), corpoConfirmacao(email, codigo, novaSenha));
    }

    private MockHttpServletRequestBuilder verificarEmail(String email, String codigo) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("code", codigo);
        return comCorpo(post(VERIFY_EMAIL), corpo);
    }

    private MockHttpServletRequestBuilder entrar(String email, String senha) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("password", senha);
        return comCorpo(post(LOGIN), corpo);
    }

    private static MockHttpServletRequestBuilder renovar(String refreshToken) {
        return post(REFRESH).cookie(new Cookie(COOKIE, refreshToken));
    }

    /** Login real com {@link #SENHA_ANTIGA}; devolve o refresh token opaco do cookie (uma família nova). */
    private String refreshDoLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(entrar(email, SENHA_ANTIGA)).andExpect(status().isOk()).andReturn();
        return valorDoCookie(setCookieRefreshToken(result));
    }

    // ---------- respostas

    private JsonNode corpo(MvcResult result) throws Exception {
        String texto = result.getResponse().getContentAsString();
        return texto.isEmpty() ? json.nullNode() : json.readTree(texto);
    }

    private static String setCookieRefreshToken(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith(COOKIE + "="))
                .findFirst()
                .orElse(null);
    }

    private static String valorDoCookie(String setCookie) {
        String par = setCookie.split(";", 2)[0];
        return par.substring(par.indexOf('=') + 1);
    }

    /** D-15: mesmo status (202), mesmo corpo e nenhum {@code Retry-After} nas duas respostas. */
    private static void esperaRespostasIdenticas(MvcResult real, MvcResult desconhecido) throws Exception {
        assertThat(real.getResponse().getStatus()).isEqualTo(202);
        assertThat(desconhecido.getResponse().getStatus()).isEqualTo(real.getResponse().getStatus());
        assertThat(desconhecido.getResponse().getContentAsString()).isEqualTo(real.getResponse().getContentAsString());
        assertThat(real.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(desconhecido.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    /**
     * Envia o corpo inválido e confere: 400, {@code application/problem+json}, corpo conforme
     * {@code ValidationProblem} e um item de {@code errors} com {@code field} igual a {@code
     * campoEsperado}.
     */
    private void esperaValidationProblem(
            MockHttpServletRequestBuilder base, Map<String, Object> corpo, String campoEsperado) throws Exception {
        MvcResult result = mockMvc.perform(comCorpo(base, corpo))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = corpo(result);
        List<Error> violacoes = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violacoes).as("corpo casa com ValidationProblem do contrato: %s", violacoes).isEmpty();

        List<String> campos = new ArrayList<>();
        body.get("errors").forEach(e -> campos.add(e.get("field").asText()));
        assertThat(campos).as("errors[].field").contains(campoEsperado);
    }

    // ---------- Mailpit

    private List<MailpitClient.Summary> emails(String email, String assunto) {
        return mailpit.messages().stream()
                .filter(m -> m.to().stream().anyMatch(to -> to.equalsIgnoreCase(email)))
                .filter(m -> assunto.equals(m.subject()))
                .toList();
    }

    /** Espera chegar exatamente um e-mail com o assunto e devolve o código de 6 dígitos do corpo. */
    private String codigoDoUnicoEmail(String email, String assunto) {
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(emails(email, assunto)).hasSize(1));
        return codigoDe(emails(email, assunto).getFirst());
    }

    private String codigoDe(MailpitClient.Summary resumo) {
        String texto = mailpit.message(resumo.id()).text();
        Matcher m = SEIS_DIGITOS.matcher(texto);
        assertThat(m.find()).as("o e-mail traz um código de 6 dígitos").isTrue();
        return m.group(1);
    }

    /** A caixa do endereço fica vazia durante a janela de estabilidade. */
    private void esperaNenhumEmail(String email) {
        await().during(ESTABILIDADE_CAIXA)
                .atMost(ESTABILIDADE_CAIXA.plusSeconds(3))
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).isEmpty());
    }

    // ---------- SQL

    private String passwordHashDe(UUID userId) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
    }

    private Timestamp emailVerifiedAtDe(UUID userId) {
        return jdbc.queryForObject("SELECT email_verified_at FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private String codeHashDe(UUID otpId) {
        return jdbc.queryForObject("SELECT code_hash FROM otp_codes WHERE id = ?", String.class, otpId);
    }

    private int tentativasDe(UUID otpId) {
        return jdbc.queryForObject("SELECT attempts FROM otp_codes WHERE id = ?", Integer.class, otpId);
    }

    private Timestamp consumedAtDe(UUID otpId) {
        return jdbc.queryForObject("SELECT consumed_at FROM otp_codes WHERE id = ?", Timestamp.class, otpId);
    }

    private Timestamp lastSentAtDe(UUID otpId) {
        return jdbc.queryForObject("SELECT last_sent_at FROM otp_codes WHERE id = ?", Timestamp.class, otpId);
    }

    private int otpsDe(UUID userId, OtpPurpose purpose) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM otp_codes WHERE user_id = ? AND purpose = ?", Integer.class, userId, purpose.name());
    }

    private UUID otpIdDe(UUID userId, OtpPurpose purpose) {
        return jdbc.queryForObject(
                "SELECT id FROM otp_codes WHERE user_id = ? AND purpose = ?", UUID.class, userId, purpose.name());
    }

    private int sessoesAtivasDe(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
    }

    private int totalDeRefreshTokensDe(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
    }

    private List<UUID> familiasDe(UUID userId) {
        return jdbc.queryForList("SELECT DISTINCT family_id FROM refresh_tokens WHERE user_id = ?", UUID.class, userId);
    }

    private Map<String, Object> membershipDe(UUID userId) {
        return jdbc.queryForMap(
                "SELECT status, role, is_admin, requested_at, expires_at, decided_by, decided_at, version "
                        + "FROM memberships WHERE user_id = ?",
                userId);
    }

    /** {@code memberships.status} da conta, ou {@code null} se ela não tem vínculo. */
    private String statusMembershipDe(UUID userId) {
        List<String> status = jdbc.queryForList("SELECT status FROM memberships WHERE user_id = ?", String.class, userId);
        return status.isEmpty() ? null : status.getFirst();
    }

    /** Estado derivado da conta (D-33), relido do banco. */
    private AccountStateValue estadoDerivadoDe(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        return AccountState.of(user, memberships.findByUserId(userId).orElse(null)).state();
    }
}
