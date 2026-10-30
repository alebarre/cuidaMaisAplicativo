package br.com.cuidamais.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-024 — autorização por requisição: o filtro de segurança lê {@code status}, {@code role} e
 * {@code is_admin} <strong>do banco</strong> a cada requisição (D-13 rev. 2) e aplica as extensões
 * {@code x-required-account-state} e {@code x-required-permission} do contrato.
 *
 * <p>Cobre: AC-009.2 (membro removido perde o acesso na requisição seguinte, com o mesmo token
 * ainda dentro do prazo), AC-009.1 (troca de papel vale imediatamente, sem novo login), AC-015.2 /
 * D-32 (Cuidador ou Familiar sem administração numa rota de admin → 403 {@code NOT_ALLOWED}, sem
 * revelar dados — P5), a regra transversal do contrato "toda operação autenticada exige conta
 * {@code ATIVO} com vínculo ativo, salvo indicação" (403 {@code ACCOUNT_STATE} para {@code ATIVO}
 * sem grupo numa rota padrão) e as respostas {@code Unauthorized}/{@code Forbidden} do contrato
 * (corpo {@code Problem}). AC-015.3 (404 idêntico para outro grupo/inexistente) <strong>não</strong>
 * é provado aqui: o filtro é genérico e não conhece recursos; isso é dos serviços das tarefas
 * T-030+ via {@code ResourceNotFoundException} (já coberto em {@code GlobalExceptionHandlerTest}).
 *
 * <p>Como nenhuma rota autenticada real existe ainda, a prova usa {@link RotasDeTesteController},
 * um controller só de teste (nunca em {@code src/main}) registrado com {@code @Import} — classes
 * aninhadas numa classe de teste ficam fora do component scan ({@code TestTypeExcludeFilter}),
 * então ele só existe neste contexto. Contexto Spring completo (Testcontainers + MockMvc com a
 * cadeia de filtros do Spring Security), caixa-preta pela rota HTTP; o estado da membership é
 * alterado direto no banco pelas transições da entidade {@link Membership} (D-07), simulando o
 * que as tarefas futuras de gestão de membros (T-047) farão.
 *
 * <p><strong>Desenho esperado</strong> (a ser criado pelo implementador):
 *
 * <ul>
 *   <li>{@code br.com.cuidamais.auth.domain.AccountPrincipal} — {@code record AccountPrincipal(UUID
 *       userId, AccountState state)}; é o {@code Authentication#getPrincipal()} populado pelo
 *       filtro, resolvível por {@code @AuthenticationPrincipal}.
 *   <li>Filtro ({@code OncePerRequestFilter}) que lê {@code Authorization: Bearer <token>}, valida
 *       com {@link JwtService#parseSubject} usando o {@code Clock} injetado (D-10), e, se válido,
 *       busca {@code User} e {@code Membership} <strong>agora</strong> no banco e deriva
 *       {@code AccountState.of(user, membership)}. Token inválido/expirado, ou {@code sub} sem
 *       conta no banco → segue sem autenticação (mesmo tratamento de "sem token").
 *   <li>{@code br.com.cuidamais.shared.security.PublicEndpoint} — anotação marcadora (método ou
 *       classe): rota sem nenhuma exigência, nem de sessão. Deve ser posta em {@code signup},
 *       {@code verifyEmail} e {@code login} do {@code AuthController} (os testes existentes dessas
 *       rotas, que não mandam token, continuam provando isso).
 *   <li>{@code br.com.cuidamais.shared.security.RequiredAccountState} — {@code
 *       AccountStateRequirement value()} (método ou classe); {@code enum AccountStateRequirement
 *       {ANY, ACTIVE_WITHOUT_GROUP}}. Ausência = padrão: {@code ATIVO} <strong>e</strong> {@code
 *       hasGroup}. {@code ANY} → só exige sessão válida; {@code ACTIVE_WITHOUT_GROUP} → exige
 *       {@code ATIVO}, com ou sem grupo.
 *   <li>{@code br.com.cuidamais.shared.security.RequiresAdminPermission} — anotação marcadora
 *       (método ou classe) para {@code x-required-permission: admin}: exige {@code isAdmin()}.
 *   <li>Ordem de avaliação: (1) rota pública passa; (2) sem principal → 401 {@code Problem}; (3)
 *       exigência de estado não satisfeita → 401 se o estado é {@code REMOVIDO} ("vínculo que
 *       deixou de existir"), senão 403 {@code code: ACCOUNT_STATE}; (4) rota de admin sem
 *       {@code isAdmin()} → 403 {@code code: NOT_ALLOWED} ({@code NotAllowedException}, T-007).
 *   <li>Todas as recusas em {@code application/problem+json}, casando com o schema {@code Problem}
 *       do {@code openapi.yaml}, sem nenhum dado da conta (P5).
 * </ul>
 *
 * <p>O banco não é limpo entre testes: cada teste cria suas próprias contas (e-mail único) e seu
 * próprio grupo.
 */
@AutoConfigureMockMvc
@Import({
    RequestAuthorizationTest.RotasDeTesteController.class,
    RequestAuthorizationTest.RotasAdminPorClasseController.class
})
class RequestAuthorizationTest extends IntegrationTest {

    private static final String ROTA_PADRAO = "/api/v1/teste-autorizacao/padrao";
    private static final String ROTA_ANY = "/api/v1/teste-autorizacao/qualquer-estado";
    private static final String ROTA_SEM_GRUPO = "/api/v1/teste-autorizacao/ativo-sem-grupo";
    private static final String ROTA_ADMIN = "/api/v1/teste-autorizacao/admin";
    private static final String ROTA_ADMIN_POR_CLASSE = "/api/v1/teste-autorizacao-admin/acao";
    private static final String ROTA_PUBLICA = "/api/v1/teste-autorizacao/publica";

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

    // ============================================================ sem sessão válida → 401

    /**
     * Regra transversal / {@code Unauthorized}: rota autenticada (padrão) chamada sem nenhum
     * header {@code Authorization} recebe 401 com {@code Problem} genérico — não o 403 vazio que o
     * Spring Security dá por padrão sem ponto de entrada configurado.
     */
    @Test
    @DisplayName("D-13 sem token na rota padrão → 401 Problem")
    void d_13_sem_token_na_rota_padrao_responde_401() throws Exception {
        esperaProblem(get(ROTA_PADRAO), 401);
    }

    /**
     * {@code x-required-account-state: any} dispensa exigência de <strong>estado</strong>, não de
     * sessão: sem token, mesmo a rota {@code ANY} recebe 401.
     */
    @Test
    @DisplayName("D-13 sem token na rota ANY → 401 Problem (ANY não dispensa sessão)")
    void d_13_sem_token_na_rota_any_responde_401() throws Exception {
        esperaProblem(get(ROTA_ANY), 401);
    }

    /**
     * {@code Unauthorized} "sem sessão válida": um token presente mas inválido é tratado
     * exatamente como token ausente (401 {@code Problem}), seja qual for o motivo da recusa. Cada
     * linha do enum {@link TokenInvalido} é um motivo diferente (texto que não é JWT, assinatura
     * de outro segredo, assinatura adulterada, prazo vencido, header sem o esquema Bearer).
     */
    @ParameterizedTest(name = "D-13 token inválido ({0}) na rota padrão → 401")
    @EnumSource(TokenInvalido.class)
    @DisplayName("D-13 token inválido/adulterado/expirado na rota padrão → 401 Problem")
    void d_13_token_invalido_na_rota_padrao_responde_401(TokenInvalido tipo) throws Exception {
        User user = membroAtivo(Role.CUIDADOR).user();

        esperaProblem(get(ROTA_PADRAO).header(HttpHeaders.AUTHORIZATION, tipo.header(this, user)), 401);
    }

    /**
     * D-13 "o JWT só carrega {@code sub}": um token genuíno (assinatura e prazo válidos) cujo
     * usuário não existe mais no banco não autentica ninguém — nem na rota {@code ANY}, que não
     * exige estado. Prova que o filtro não "confia" em nada do token além do {@code sub} e
     * sempre consulta o banco.
     */
    @Test
    @DisplayName("D-13 token genuíno de conta apagada do banco → 401 até na rota ANY")
    void d_13_token_de_conta_inexistente_responde_401() throws Exception {
        User user = users.save(contaAtiva());
        String token = tokenDe(user);
        // Etapa 1: com a conta existindo, a rota ANY aceita o token.
        mockMvc.perform(get(ROTA_ANY).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Etapa 2: a conta some do banco (sem membership nem refresh token, nada a apagar antes).
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

        // Etapa 3: o mesmo token, ainda no prazo, já não autentica.
        esperaProblem(get(ROTA_ANY).header(HttpHeaders.AUTHORIZATION, bearer(token)), 401);
    }

    /**
     * Mesmo motivo do teste anterior, sem passo intermediário: um JWT bem assinado para um
     * {@code sub} que nunca existiu → 401 na rota padrão.
     */
    @Test
    @DisplayName("D-13 token genuíno para um sub que nunca existiu → 401")
    void d_13_token_de_sub_desconhecido_responde_401() throws Exception {
        String token = jwtService.issue(UUID.randomUUID(), clock);

        esperaProblem(get(ROTA_PADRAO).header(HttpHeaders.AUTHORIZATION, bearer(token)), 401);
    }

    // ============================================================ rota padrão (ATIVO + grupo)

    /**
     * Controle positivo da rota padrão: membro {@code ATIVO} com grupo, token válido → 200. Sem
     * este caso, todos os 401/403 deste arquivo poderiam passar com uma rota que recusa tudo.
     */
    @Test
    @DisplayName("Regra transversal: membro ATIVO com grupo na rota padrão → 200")
    void regra_transversal_membro_ativo_com_grupo_na_rota_padrao_responde_200() throws Exception {
        User user = membroAtivo(Role.CUIDADOR).user();

        mockMvc.perform(comToken(get(ROTA_PADRAO), user)).andExpect(status().isOk());
    }

    /**
     * Regra transversal "exige conta {@code ATIVO} com vínculo ativo" + {@code Forbidden}
     * ({@code code: ACCOUNT_STATE}): o criador que confirmou o e-mail mas ainda não cadastrou o
     * idoso ({@code ATIVO}, sem membership — AC-001.7) tem sessão válida, mas não está no estado
     * exigido pela rota padrão → 403 {@code ACCOUNT_STATE}, não 401 (só {@code REMOVIDO} é 401).
     */
    @Test
    @DisplayName("Regra transversal: ATIVO sem grupo na rota padrão → 403 ACCOUNT_STATE")
    void regra_transversal_ativo_sem_grupo_na_rota_padrao_responde_403_account_state() throws Exception {
        User user = users.save(contaAtiva());

        JsonNode body = esperaProblem(comToken(get(ROTA_PADRAO), user), 403);

        assertThat(body.get("code").asText()).isEqualTo("ACCOUNT_STATE");
    }

    // ============================================================ AC-009.2 remoção imediata

    /**
     * AC-009.2 / D-13 — cenário central do "Pronto quando": token emitido para um membro
     * {@code ATIVO}, aceito (200); a membership passa a {@code REMOVIDO} no banco; a requisição
     * seguinte com o <strong>mesmo</strong> token (ainda longe dos 15 min de validade) recebe 401,
     * porque o estado é lido do banco a cada requisição e não do JWT.
     */
    @Test
    @DisplayName("AC-009.2 token válido de membro removido recebe 401 na requisição seguinte")
    void ac_009_2_membro_removido_recebe_401_com_o_mesmo_token() throws Exception {
        Membro membro = membroAtivo(Role.CUIDADOR);
        String token = tokenDe(membro.user());
        // Etapa 1: antes da remoção, o token é aceito.
        mockMvc.perform(get(ROTA_PADRAO).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Etapa 2: o Responsável remove o membro (simulado direto no banco).
        remover(membro.user());

        // Etapa 3: mesmo token, sem novo login, um minuto depois → 401.
        clock.advance(Duration.ofMinutes(1));
        JsonNode body = esperaProblem(get(ROTA_PADRAO).header(HttpHeaders.AUTHORIZATION, bearer(token)), 401);
        assertThat(body.has("code"))
                .as("401 é o Problem genérico de Unauthorized, sem code de estado (P5)")
                .isFalse();
    }

    /**
     * AC-009.2: a remoção também derruba o acesso às rotas {@code ACTIVE_WITHOUT_GROUP} (exigem
     * {@code ATIVO}); como o estado é {@code REMOVIDO}, a resposta é 401, não 403.
     */
    @Test
    @DisplayName("AC-009.2 membro removido na rota ACTIVE_WITHOUT_GROUP → 401")
    void ac_009_2_membro_removido_na_rota_ativo_sem_grupo_responde_401() throws Exception {
        Membro membro = membroAtivo(Role.FAMILIAR);
        String token = tokenDe(membro.user());
        remover(membro.user());

        esperaProblem(get(ROTA_SEM_GRUPO).header(HttpHeaders.AUTHORIZATION, bearer(token)), 401);
    }

    // ============================================================ AC-009.1 troca de papel imediata

    /**
     * AC-009.1 / D-13 — cenário central do "Pronto quando": a rota de teste devolve o papel lido
     * do {@link AccountPrincipal}. Com o mesmo token, o papel visto pelo servidor passa de
     * {@code CUIDADOR} para {@code FAMILIAR} assim que a membership muda no banco, sem novo login.
     */
    @Test
    @DisplayName("AC-009.1 Cuidador que virou Familiar já vê o novo papel sem novo login")
    void ac_009_1_cuidador_que_virou_familiar_ve_novo_papel_sem_novo_login() throws Exception {
        Membro membro = membroAtivo(Role.CUIDADOR);
        String token = tokenDe(membro.user());
        // Etapa 1: papel atual visto pelo servidor = CUIDADOR.
        assertThat(papelVisto(token)).isEqualTo("CUIDADOR");

        // Etapa 2: o Responsável troca o papel (simulado direto no banco).
        trocarPapel(membro.user(), Role.FAMILIAR);

        // Etapa 3: mesmo token → FAMILIAR.
        assertThat(papelVisto(token)).isEqualTo("FAMILIAR");
    }

    /**
     * AC-009.1 "entre Cuidador e Familiar": a direção inversa (Familiar → Cuidador) também vale
     * na requisição seguinte, com o mesmo token.
     */
    @Test
    @DisplayName("AC-009.1 Familiar que virou Cuidador já vê o novo papel sem novo login")
    void ac_009_1_familiar_que_virou_cuidador_ve_novo_papel_sem_novo_login() throws Exception {
        Membro membro = membroAtivo(Role.FAMILIAR);
        String token = tokenDe(membro.user());
        // Etapa 1: papel atual = FAMILIAR.
        assertThat(papelVisto(token)).isEqualTo("FAMILIAR");

        // Etapa 2: troca no banco.
        trocarPapel(membro.user(), Role.CUIDADOR);

        // Etapa 3: mesmo token → CUIDADOR.
        assertThat(papelVisto(token)).isEqualTo("CUIDADOR");
    }

    /**
     * D-13: o principal carrega a identidade do {@code sub} e o estado derivado do banco
     * ({@code ATIVO}, com grupo, flag de admin) — conferido pelo eco da rota de teste.
     */
    @Test
    @DisplayName("D-13 principal traz userId do token e estado/grupo/admin lidos do banco")
    void d_13_principal_traz_estado_lido_do_banco() throws Exception {
        Membro responsavel = responsavel();

        JsonNode eco = eco(tokenDe(responsavel.user()));

        assertThat(eco.get("userId").asText()).isEqualTo(responsavel.user().getId().toString());
        assertThat(eco.get("state").asText()).isEqualTo("ATIVO");
        assertThat(eco.get("hasGroup").asBoolean()).isTrue();
        assertThat(eco.get("role").asText()).isEqualTo("FAMILIAR");
        assertThat(eco.get("admin").asBoolean()).isTrue();
    }

    // ============================================================ x-required-permission: admin

    /**
     * AC-015.2 / D-32 — cenário central do "Pronto quando": Cuidador {@code ATIVO} do grupo (sem
     * administração) chamando rota de admin → 403 {@code NOT_ALLOWED}, sem dado nenhum da conta
     * no corpo (P5). Cada linha é um papel sem administração (Cuidador, e Familiar comum).
     */
    @ParameterizedTest(name = "AC-015.2 {0} sem administração na rota de admin → 403 NOT_ALLOWED")
    @EnumSource(Role.class)
    @DisplayName("AC-015.2 membro sem administração na rota de admin → 403 NOT_ALLOWED")
    void ac_015_2_membro_sem_administracao_na_rota_admin_responde_403_not_allowed(Role papel)
            throws Exception {
        // CUIDADOR: o caso do "Pronto quando"; FAMILIAR: papel do Responsável, mas sem a flag.
        User user = membroAtivo(papel).user();

        JsonNode body = esperaProblem(comToken(get(ROTA_ADMIN), user), 403);

        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(json.writeValueAsString(body))
                .as("P5: o 403 não revela papel, estado nem identidade")
                .doesNotContain(user.getId().toString())
                .doesNotContain(papel.name());
    }

    /**
     * Controle positivo de {@code x-required-permission: admin}: o Responsável ({@code FAMILIAR}
     * com {@code is_admin = true}) passa (200). Prova que o 403 acima é por falta de
     * administração, não por a rota recusar todo mundo.
     */
    @Test
    @DisplayName("AC-015.2 Responsável (is_admin) na rota de admin → 200")
    void ac_015_2_responsavel_na_rota_admin_responde_200() throws Exception {
        User user = responsavel().user();

        mockMvc.perform(comToken(get(ROTA_ADMIN), user)).andExpect(status().isOk());
    }

    /**
     * {@code @RequiresAdminPermission} também vale quando posto na <strong>classe</strong> do
     * controller (como {@code @RequiredAccountState}): Cuidador → 403 {@code NOT_ALLOWED}.
     */
    @Test
    @DisplayName("AC-015.2 @RequiresAdminPermission na classe: Cuidador → 403 NOT_ALLOWED")
    void ac_015_2_anotacao_admin_na_classe_recusa_cuidador() throws Exception {
        User user = membroAtivo(Role.CUIDADOR).user();

        JsonNode body = esperaProblem(comToken(get(ROTA_ADMIN_POR_CLASSE), user), 403);

        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    /**
     * Controle positivo da anotação na classe: o Responsável passa (200).
     */
    @Test
    @DisplayName("AC-015.2 @RequiresAdminPermission na classe: Responsável → 200")
    void ac_015_2_anotacao_admin_na_classe_aceita_responsavel() throws Exception {
        User user = responsavel().user();

        mockMvc.perform(comToken(get(ROTA_ADMIN_POR_CLASSE), user)).andExpect(status().isOk());
    }

    /**
     * Ordem de avaliação: a rota de admin sem {@code @RequiredAccountState} também tem a exigência
     * padrão de estado, conferida <strong>antes</strong> da permissão. {@code ATIVO} sem grupo
     * (logo, sem admin) → 403 {@code ACCOUNT_STATE}, não {@code NOT_ALLOWED}.
     */
    @Test
    @DisplayName("Regra transversal: ATIVO sem grupo na rota de admin → 403 ACCOUNT_STATE (estado antes da permissão)")
    void regra_transversal_ativo_sem_grupo_na_rota_admin_responde_403_account_state() throws Exception {
        User user = users.save(contaAtiva());

        JsonNode body = esperaProblem(comToken(get(ROTA_ADMIN), user), 403);

        assertThat(body.get("code").asText()).isEqualTo("ACCOUNT_STATE");
    }

    // ============================================================ x-required-account-state: any

    /**
     * {@code x-required-account-state: any} (ex. {@code /auth/logout}, {@code /me/account}): não
     * há exigência de estado — nem {@code ATIVO}. Membro removido, com o mesmo token, continua
     * recebendo 200 (é assim que ele consegue sair ou ver o próprio estado).
     */
    @Test
    @DisplayName("x-required-account-state any: membro REMOVIDO com o mesmo token → 200")
    void any_aceita_membro_removido() throws Exception {
        Membro membro = membroAtivo(Role.CUIDADOR);
        String token = tokenDe(membro.user());
        remover(membro.user());

        MvcResult result = mockMvc.perform(get(ROTA_ANY).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        // O principal reflete o estado atual do banco, não o do momento da emissão do token.
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("state").asText())
                .isEqualTo("REMOVIDO");
    }

    /**
     * {@code any}: conta {@code ATIVO} sem grupo (que a rota padrão recusa com 403) → 200.
     */
    @Test
    @DisplayName("x-required-account-state any: ATIVO sem grupo → 200")
    void any_aceita_ativo_sem_grupo() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(comToken(get(ROTA_ANY), user)).andExpect(status().isOk());
    }

    // ============================================================ active-without-group

    /**
     * {@code x-required-account-state: active-without-group} (ex. {@code POST /elders}): exige
     * {@code ATIVO} mas não olha {@code hasGroup}. Caso 1: criador {@code ATIVO} sem grupo → 200.
     */
    @Test
    @DisplayName("active-without-group: ATIVO sem grupo → 200")
    void ativo_sem_grupo_aceita_conta_sem_grupo() throws Exception {
        User user = users.save(contaAtiva());

        mockMvc.perform(comToken(get(ROTA_SEM_GRUPO), user)).andExpect(status().isOk());
    }

    /**
     * {@code active-without-group}, caso 2: membro {@code ATIVO} <strong>com</strong> grupo
     * também passa (200) — "já tem grupo" é conflito de negócio do serviço, não do filtro.
     */
    @Test
    @DisplayName("active-without-group: ATIVO com grupo → 200 (conflito é do serviço, não do filtro)")
    void ativo_sem_grupo_aceita_conta_com_grupo() throws Exception {
        User user = membroAtivo(Role.CUIDADOR).user();

        mockMvc.perform(comToken(get(ROTA_SEM_GRUPO), user)).andExpect(status().isOk());
    }

    // ============================================================ @PublicEndpoint

    /**
     * {@code @PublicEndpoint} ({@code security: []}, ex. {@code /auth/login}): sem token → 200.
     */
    @Test
    @DisplayName("@PublicEndpoint: sem token → 200")
    void rota_publica_aceita_sem_token() throws Exception {
        mockMvc.perform(get(ROTA_PUBLICA)).andExpect(status().isOk());
    }

    /**
     * {@code @PublicEndpoint}: um header {@code Authorization} inválido não transforma a rota
     * pública em 401 — o filtro só deixa a requisição sem autenticação, e a rota não exige sessão.
     */
    @Test
    @DisplayName("@PublicEndpoint: token inválido não bloqueia a rota pública → 200")
    void rota_publica_ignora_token_invalido() throws Exception {
        mockMvc.perform(get(ROTA_PUBLICA).header(HttpHeaders.AUTHORIZATION, "Bearer nao-e-um-jwt"))
                .andExpect(status().isOk());
    }

    // ============================================================ apoio

    /** Motivos de token inválido do teste parametrizado; cada um monta o header {@code Authorization}. */
    enum TokenInvalido {
        /** Texto qualquer, que nem é JWT. */
        LIXO {
            @Override
            String header(RequestAuthorizationTest t, User user) {
                return "Bearer isto-nao-e-um-jwt";
            }
        },
        /** JWT bem formado, mas assinado com outro segredo. */
        OUTRO_SEGREDO {
            @Override
            String header(RequestAuthorizationTest t, User user) {
                JwtService outro = new JwtService("outro-segredo-de-teste-1111111111111111111111111111111111");
                return bearer(outro.issue(user.getId(), t.clock));
            }
        },
        /** JWT legítimo com um caractere da assinatura trocado. */
        ADULTERADO {
            @Override
            String header(RequestAuthorizationTest t, User user) {
                String token = t.tokenDe(user);
                char ultimo = token.charAt(token.length() - 1);
                return bearer(token.substring(0, token.length() - 1) + (ultimo == 'A' ? 'B' : 'A'));
            }
        },
        /** JWT legítimo emitido há 16 minutos (validade de 15, D-11). */
        EXPIRADO {
            @Override
            String header(RequestAuthorizationTest t, User user) {
                Clock emissao = Clock.fixed(t.clock.instant().minus(Duration.ofMinutes(16)), ZoneOffset.UTC);
                return bearer(t.jwtService.issue(user.getId(), emissao));
            }
        },
        /** JWT legítimo, mas sem o esquema {@code Bearer}. */
        SEM_BEARER {
            @Override
            String header(RequestAuthorizationTest t, User user) {
                return t.tokenDe(user);
            }
        };

        abstract String header(RequestAuthorizationTest t, User user);
    }

    /** Conta + membership criadas por um cenário. */
    record Membro(User user, Membership membership) {
    }

    /**
     * Membro {@code ATIVO} de um grupo novo, aprovado pelo Responsável desse grupo com o papel
     * pedido (AC-006.3), sem administração. Para {@code is_admin = true}, use {@link
     * #responsavel()}.
     */
    Membro membroAtivo(Role papel) {
        Membro responsavel = responsavel();
        User user = users.save(contaAtiva());
        MutableClock relogio = new MutableClock(clock.instant(), ZoneOffset.UTC);
        Membership m = Membership.request(responsavel.membership().getGroupId(), user.getId(), relogio);
        m.approve(papel, responsavel.user().getId(), relogio);
        return new Membro(user, memberships.save(m));
    }

    /** Responsável: cria o grupo e nasce {@code ATIVO}, {@code FAMILIAR}, {@code is_admin} (D-05, D-29). */
    Membro responsavel() {
        User user = users.save(contaAtiva());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, user.getId());
        return new Membro(user, memberships.save(Membership.admin(groupId, user.getId(), clock)));
    }

    /** AC-009.2 simulado: a membership passa a {@code REMOVIDO} no banco (o que a T-047 fará). */
    private void remover(User user) {
        Membership m = memberships.findByUserId(user.getId()).orElseThrow();
        m.remove(clock);
        memberships.save(m);
        assertThat(memberships.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REMOVIDO);
    }

    /** AC-009.1 simulado: troca de papel gravada no banco (o que a T-047 fará). */
    private void trocarPapel(User user, Role novo) {
        Membership m = memberships.findByUserId(user.getId()).orElseThrow();
        m.changeRole(novo);
        memberships.save(m);
    }

    User contaAtiva() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "autorizacao-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private MockHttpServletRequestBuilder comToken(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(tokenDe(user)));
    }

    private JsonNode eco(String token) throws Exception {
        MvcResult result = mockMvc.perform(get(ROTA_PADRAO).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String papelVisto(String token) throws Exception {
        return eco(token).get("role").asText();
    }

    /**
     * Executa a requisição e confere: status esperado, {@code application/problem+json}, campo
     * {@code status} igual, e corpo conforme {@code #/components/schemas/Problem} do contrato.
     */
    private JsonNode esperaProblem(MockHttpServletRequestBuilder request, int esperado) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(esperado))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(esperado);
        List<Error> violations = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violations).as("corpo deve casar com #/components/schemas/Problem: %s", violations).isEmpty();
        return body;
    }

    /**
     * Controller só de teste (nunca em {@code src/main}): uma rota por combinação de exigência.
     * Todas devolvem o eco do {@link AccountPrincipal} (quando houver), para os testes lerem o
     * papel/estado que o servidor enxerga — sempre do principal, nunca do JWT.
     */
    @RestController
    @RequestMapping("/teste-autorizacao")
    static class RotasDeTesteController {

        /** Sem anotação de estado: padrão do contrato, exige {@code ATIVO} e grupo. */
        @GetMapping("/padrao")
        Map<String, Object> padrao(@AuthenticationPrincipal AccountPrincipal principal) {
            return ecoDe(principal);
        }

        /** {@code x-required-account-state: any}. */
        @RequiredAccountState(AccountStateRequirement.ANY)
        @GetMapping("/qualquer-estado")
        Map<String, Object> qualquerEstado(@AuthenticationPrincipal AccountPrincipal principal) {
            return ecoDe(principal);
        }

        /** {@code x-required-account-state: active-without-group}. */
        @RequiredAccountState(AccountStateRequirement.ACTIVE_WITHOUT_GROUP)
        @GetMapping("/ativo-sem-grupo")
        Map<String, Object> ativoSemGrupo(@AuthenticationPrincipal AccountPrincipal principal) {
            return ecoDe(principal);
        }

        /** {@code x-required-permission: admin}, com a exigência padrão de estado. */
        @RequiresAdminPermission
        @GetMapping("/admin")
        Map<String, Object> admin(@AuthenticationPrincipal AccountPrincipal principal) {
            return ecoDe(principal);
        }

        /** {@code security: []}: sem nenhuma exigência. */
        @PublicEndpoint
        @GetMapping("/publica")
        Map<String, Object> publica() {
            return Map.of("ok", true);
        }

        static Map<String, Object> ecoDe(AccountPrincipal principal) {
            Map<String, Object> eco = new LinkedHashMap<>();
            eco.put("userId", principal.userId().toString());
            eco.put("state", principal.state().state().name());
            eco.put("hasGroup", principal.state().hasGroup());
            eco.put("role", principal.state().role() == null ? null : principal.state().role().name());
            eco.put("admin", principal.state().isAdmin());
            return eco;
        }
    }

    /** {@code @RequiresAdminPermission} na classe: vale para todos os métodos dela. */
    @RestController
    @RequestMapping("/teste-autorizacao-admin")
    @RequiresAdminPermission
    static class RotasAdminPorClasseController {

        @GetMapping("/acao")
        Map<String, Object> acao() {
            return Map.of("ok", true);
        }
    }
}
