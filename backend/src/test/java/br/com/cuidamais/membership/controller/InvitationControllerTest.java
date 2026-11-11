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
import br.com.cuidamais.group.domain.CareGroup;
import br.com.cuidamais.group.repository.CareGroupRepository;
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
import com.networknt.schema.Error;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-040 — convites do Responsável: {@code POST/GET /elders/{elderId}/invitations}, {@code POST
 * .../{invitationId}/resend} e {@code POST .../{invitationId}/cancel} (contrato {@code
 * createInvitation}, {@code listInvitations}, {@code resendInvitation}, {@code cancelInvitation}).
 *
 * <p>ACs cobertos: AC-002.4 (409 {@code NO_ELDER}), AC-003.1 (e-mail com código de 6 dígitos,
 * nome do Responsável, só o primeiro nome do idoso; código guardado só como hash, D-04), AC-003.4
 * (lista só do Responsável, cancelar só {@code ENVIADO}, reenviar {@code ENVIADO}/{@code
 * VENCIDO}/{@code CANCELADO} e nunca {@code USADO}), AC-003.5 ({@code ALREADY_MEMBER}), AC-003.6
 * (reconvite de {@code RECUSADO}/{@code EXPIRADO}/{@code REMOVIDO}), AC-003.7 ({@code
 * EMAIL_UNAVAILABLE} para outro grupo ou conta sem grupo, mesma resposta), AC-003.8 ({@code
 * failedAttempts}/{@code blockedUntil}/{@code autoResends} na lista, D-39), AC-003.9 (convite novo
 * encerra o bloqueio na hora) e AC-003.12 (e-mail com convite {@code ENVIADO} = reenvio, D-31).
 * Também cobre D-32/AC-015 (403 para não admin, 404 para outro grupo) e o limite de 3 reenvios
 * automáticos não zerado por reenvio manual (AC-003.3).
 *
 * <p>Caixa-preta pela rota HTTP real, com contrato validado ({@link ContractValidation} e {@link
 * ContractSchemas}). O grupo com idoso nasce por {@code POST /elders} (T-030); membros em estados
 * variados nascem pela máquina de estados de {@link Membership}; convites e o contador {@code
 * invite_code_attempts} são semeados e lidos por {@link JdbcTemplate} para o teste não depender
 * de nenhuma classe de produção da T-040 além dos endpoints. {@link
 * br.com.cuidamais.shared.testsupport.MutableClock} (D-10) fixa o "agora".
 *
 * <p>Decisão assumida (a confirmar com o implementador): o convite {@code ENVIADO} cujo {@code
 * expires_at} já passou é apresentado como {@code VENCIDO} na listagem (derivado pelo {@link
 * java.time.Clock}, sem depender do job de D-09), inclusive para o filtro {@code status}.
 */
@AutoConfigureMockMvc
class InvitationControllerTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";
    private static final Pattern CODIGO_6_DIGITOS = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);

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
    CareGroupRepository careGroups;

    @Autowired
    JwtService jwtService;

    @BeforeEach
    void limpaCaixaDeEntrada() {
        mailpit.deleteAll();
    }

    // ============================================================ AC-003.1 criar convite

    /**
     * Caminho feliz: o Responsável convida um e-mail livre. Prova o 201 conforme o contrato, o
     * convite {@code ENVIADO} de origem {@code MANUAL}, o prazo de 7 dias contado pelo Clock fixo
     * (AC-003.2), o e-mail com código de 6 dígitos e o nome do Responsável, e que o código no
     * banco é só hash (D-04): o texto puro nunca é gravado.
     */
    @Test
    @DisplayName("AC-003.1: Responsável convida e-mail livre; 201 ENVIADO/MANUAL, vence em 7 dias e e-mail traz código e nome do Responsável")
    void ac_003_1_convida_email_livre_envia_codigo_e_guarda_so_hash() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();

        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("email").asText()).isEqualTo(email);
        assertThat(body.path("status").asText()).isEqualTo("ENVIADO");
        assertThat(body.path("origin").asText()).isEqualTo("MANUAL");
        assertThat(instante(body, "expiresAt"))
                .as("expiresAt = agora + 7 dias (AC-003.2)")
                .isCloseTo(clock.instant().plus(Duration.ofDays(7)), within(1, ChronoUnit.SECONDS));

        // Etapa: o e-mail chega ao convidado com código de 6 dígitos e o nome do Responsável.
        MailpitClient.Detail mail = esperarEmail(email, 1);
        String codigo = extrairCodigo(mail);
        assertThat(mail.text()).contains("Maria da Silva");

        // Etapa: D-04 — no banco só há hash; o código em texto não aparece nem é igual ao hash.
        String hash = jdbc.queryForObject(
                "SELECT code_hash FROM invitations WHERE id = ?", String.class,
                UUID.fromString(body.path("id").asText()));
        assertThat(hash).isNotEqualTo(codigo).doesNotContain(codigo);
    }

    /**
     * AC-003.1 / P5: o e-mail cita apenas o primeiro nome do idoso ("Francisca") e jamais o
     * sobrenome ("Oliveira") nem o nome completo, nem em texto nem em HTML.
     */
    @Test
    @DisplayName("AC-003.1: e-mail do convite traz o primeiro nome do idoso e nunca o sobrenome (P5)")
    void ac_003_1_email_traz_so_o_primeiro_nome_do_idoso() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();

        mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel())).andExpect(status().isCreated());

        MailpitClient.Detail mail = esperarEmail(email, 1);
        assertThat(mail.text()).contains("Francisca");
        assertThat(mail.text()).doesNotContain("Oliveira").doesNotContain("Francisca Oliveira");
        if (mail.html() != null) {
            assertThat(mail.html()).doesNotContain("Oliveira");
        }
    }

    // ============================================================ permissões e isolamento (D-32)

    /** AC-015.2 / D-32: Cuidador do próprio grupo tentando convidar recebe 403 NOT_ALLOWED e nada é criado. */
    @Test
    @DisplayName("AC-003.4: Cuidador (não admin) convidando recebe 403 NOT_ALLOWED e nenhum convite é criado")
    void ac_003_4_cuidador_nao_convida_403() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.ATIVO);
        String email = emailNovo();

        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), email, cuidador))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("NOT_ALLOWED");
        assertThat(contarConvites(email)).isZero();
    }

    /** D-32 / AC-015.3: Responsável de OUTRO grupo convidando no idoso do grupo A recebe 404 genérico. */
    @Test
    @DisplayName("AC-003.4: Responsável de outro grupo convidando no idoso do grupo A recebe 404 (D-32)")
    void ac_003_4_outro_grupo_convidar_404() throws Exception {
        Grupo grupoA = grupoComIdoso();
        Grupo grupoB = grupoComIdoso();
        String email = emailNovo();

        MvcResult result = mockMvc.perform(convidar(grupoA.elderId(), email, grupoB.responsavel()))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(contarConvites(email)).isZero();
    }

    // ============================================================ AC-003.5 ALREADY_MEMBER

    /** AC-003.5: e-mail de membro ATIVO do grupo => 409 ALREADY_MEMBER, sem convite criado. */
    @Test
    @DisplayName("AC-003.5: convidar membro ATIVO do grupo => 409 ALREADY_MEMBER")
    void ac_003_5_membro_ativo_409_already_member() throws Exception {
        Grupo grupo = grupoComIdoso();
        User ativo = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.ATIVO);

        esperarConflito(grupo, ativo.getEmail(), "ALREADY_MEMBER");
        assertThat(contarConvites(ativo.getEmail())).isZero();
    }

    /** AC-003.5: e-mail com pedido AGUARDANDO_APROVACAO no grupo => 409 ALREADY_MEMBER. */
    @Test
    @DisplayName("AC-003.5: convidar e-mail com pedido AGUARDANDO_APROVACAO => 409 ALREADY_MEMBER")
    void ac_003_5_pedido_em_analise_409_already_member() throws Exception {
        Grupo grupo = grupoComIdoso();
        User pendente = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.AGUARDANDO_APROVACAO);

        esperarConflito(grupo, pendente.getEmail(), "ALREADY_MEMBER");
        assertThat(contarConvites(pendente.getEmail())).isZero();
    }

    // ============================================================ AC-003.7 EMAIL_UNAVAILABLE

    /**
     * AC-003.7: e-mail de conta de OUTRO grupo, em cada um dos 5 status de membership => 409
     * EMAIL_UNAVAILABLE (a mensagem não revela grupo nem pessoa).
     */
    @ParameterizedTest
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-003.7: e-mail de outro grupo, em qualquer status => 409 EMAIL_UNAVAILABLE")
    void ac_003_7_outro_grupo_qualquer_status_409_email_unavailable(MembershipStatus statusNoOutroGrupo)
            throws Exception {
        Grupo grupo = grupoComIdoso();
        Grupo outro = grupoComIdoso();
        // Cada linha da tabela = um status possível da membership da pessoa no OUTRO grupo.
        User alvo = membro(outro.groupId(), outro.responsavel(), statusNoOutroGrupo);

        esperarConflito(grupo, alvo.getEmail(), "EMAIL_UNAVAILABLE");
        assertThat(contarConvites(alvo.getEmail())).isZero();
    }

    /** AC-003.7: o próprio Responsável de outro grupo (admin ATIVO) também é indisponível. */
    @Test
    @DisplayName("AC-003.7: e-mail do Responsável de outro grupo => 409 EMAIL_UNAVAILABLE")
    void ac_003_7_responsavel_de_outro_grupo_409_email_unavailable() throws Exception {
        Grupo grupo = grupoComIdoso();
        Grupo outro = grupoComIdoso();

        esperarConflito(grupo, outro.responsavel().getEmail(), "EMAIL_UNAVAILABLE");
    }

    /** AC-003.7: conta sem grupo AGUARDANDO_CONFIRMACAO_EMAIL => 409 EMAIL_UNAVAILABLE. */
    @Test
    @DisplayName("AC-003.7: conta sem grupo AGUARDANDO_CONFIRMACAO_EMAIL => 409 EMAIL_UNAVAILABLE")
    void ac_003_7_conta_aguardando_confirmacao_409_email_unavailable() throws Exception {
        Grupo grupo = grupoComIdoso();
        User naoConfirmada = users.save(User.signup(
                "Pessoa Nova", emailNovo(), "21987654321", endereco(), "hash-irrelevante", clock));

        esperarConflito(grupo, naoConfirmada.getEmail(), "EMAIL_UNAVAILABLE");
        assertThat(contarConvites(naoConfirmada.getEmail())).isZero();
    }

    /** AC-003.7: conta ATIVO que ainda não cadastrou o idoso => mesma resposta 409 EMAIL_UNAVAILABLE. */
    @Test
    @DisplayName("AC-003.7: conta ATIVO sem grupo => 409 EMAIL_UNAVAILABLE (mesma resposta do outro grupo)")
    void ac_003_7_conta_ativa_sem_grupo_409_email_unavailable() throws Exception {
        Grupo grupo = grupoComIdoso();
        User semGrupo = contaAtivaSemGrupo();

        esperarConflito(grupo, semGrupo.getEmail(), "EMAIL_UNAVAILABLE");
        assertThat(contarConvites(semGrupo.getEmail())).isZero();
    }

    /**
     * AC-003.7: "mesma mensagem nos dois casos" — outro grupo e conta sem grupo devolvem corpos
     * idênticos (type, title, detail, code), para não revelar qual dos dois é (P5).
     */
    @Test
    @DisplayName("AC-003.7: 409 de outro grupo e de conta sem grupo têm corpo idêntico (exceto instance)")
    void ac_003_7_mesma_resposta_nos_dois_casos() throws Exception {
        Grupo grupo = grupoComIdoso();
        Grupo outro = grupoComIdoso();
        User semGrupo = contaAtivaSemGrupo();

        JsonNode deOutroGrupo = esperarConflito(grupo, outro.responsavel().getEmail(), "EMAIL_UNAVAILABLE");
        JsonNode deSemGrupo = esperarConflito(grupo, semGrupo.getEmail(), "EMAIL_UNAVAILABLE");

        assertThat(semInstance(deOutroGrupo)).isEqualTo(semInstance(deSemGrupo));
    }

    // ============================================================ AC-002.4 NO_ELDER

    /**
     * AC-002.4: grupo sem idoso (montado direto pelos repositórios, pois POST /elders cria tudo
     * junto): o admin não consegue convidar => 409 NO_ELDER e nada é criado. Como não existe
     * idoso, o {@code elderId} da rota é um UUID qualquer; a regra "o grupo do admin não tem idoso"
     * tem de ser avaliada pelo grupo do chamador antes do 404 (ver relatório).
     */
    @Test
    @DisplayName("AC-002.4: grupo sem idoso cadastrado => 409 NO_ELDER")
    void ac_002_4_grupo_sem_idoso_409_no_elder() throws Exception {
        User admin = contaAtivaSemGrupo();
        CareGroup grupoSemIdoso = careGroups.save(CareGroup.create(admin.getId(), clock));
        memberships.save(Membership.admin(grupoSemIdoso.getId(), admin.getId(), clock));
        String email = emailNovo();

        MvcResult result = mockMvc.perform(convidar(UUID.randomUUID(), email, admin))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("NO_ELDER");
        assertThat(ContractSchemas.schemaFor("InvitationConflictProblem").validate(body)).isEmpty();
        assertThat(contarConvites(email)).isZero();
    }

    // ============================================================ AC-003.6 reconvite

    /** AC-003.6: conta RECUSADO no grupo pode ser convidada de novo => 201. */
    @Test
    @DisplayName("AC-003.6: reconvite permitido para membership RECUSADO")
    void ac_003_6_reconvite_recusado_201() throws Exception {
        reconvidar(MembershipStatus.RECUSADO);
    }

    /** AC-003.6: conta EXPIRADO (pedido venceu em 24 h) pode ser convidada de novo => 201. */
    @Test
    @DisplayName("AC-003.6: reconvite permitido para membership EXPIRADO")
    void ac_003_6_reconvite_expirado_201() throws Exception {
        reconvidar(MembershipStatus.EXPIRADO);
    }

    /** AC-003.6: conta REMOVIDO pode ser convidada de novo => 201. */
    @Test
    @DisplayName("AC-003.6: reconvite permitido para membership REMOVIDO")
    void ac_003_6_reconvite_removido_201() throws Exception {
        reconvidar(MembershipStatus.REMOVIDO);
    }

    private void reconvidar(MembershipStatus statusNoGrupo) throws Exception {
        Grupo grupo = grupoComIdoso();
        User alvo = membro(grupo.groupId(), grupo.responsavel(), statusNoGrupo);

        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), alvo.getEmail(), grupo.responsavel()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).path("status").asText())
                .isEqualTo("ENVIADO");
        esperarEmail(alvo.getEmail(), 1);
    }

    // ============================================================ AC-003.12 e-mail ENVIADO = reenvio

    /**
     * AC-003.12 / D-31: convidar de novo um e-mail com convite {@code ENVIADO} não dá erro: 201,
     * o anterior vira CANCELADO, o novo é ENVIADO com prazo novo (relógio avançou 1 dia) e um
     * segundo e-mail é entregue.
     */
    @Test
    @DisplayName("AC-003.12: convidar e-mail com convite ENVIADO = reenvio (201, anterior CANCELADO, novo código e prazo)")
    void ac_003_12_email_com_convite_enviado_vira_reenvio() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        JsonNode primeiro = criarConvite(grupo, email);
        esperarEmail(email, 1);

        // Etapa: um dia depois, o mesmo e-mail é convidado de novo.
        clock.advance(Duration.ofDays(1));
        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        JsonNode segundo = json.readTree(result.getResponse().getContentAsString());

        assertThat(segundo.path("id").asText()).isNotEqualTo(primeiro.path("id").asText());
        assertThat(segundo.path("status").asText()).isEqualTo("ENVIADO");
        assertThat(instante(segundo, "expiresAt"))
                .as("prazo novo de 7 dias a partir do reenvio")
                .isCloseTo(instante(primeiro, "expiresAt").plus(Duration.ofDays(1)), within(1, ChronoUnit.SECONDS));
        assertThat(statusDe(primeiro.path("id").asText())).isEqualTo("CANCELADO");
        assertThat(statusDe(segundo.path("id").asText())).isEqualTo("ENVIADO");
        assertThat(hashDe(primeiro.path("id").asText())).isNotEqualTo(hashDe(segundo.path("id").asText()));

        // Etapa: a pessoa recebe um segundo e-mail (com o código novo).
        esperarEmail(email, 2);
    }

    // ============================================================ AC-003.9 / AC-003.11 zera contador

    /** AC-003.11: criar convite zera o contador por e-mail (5 erros + bloqueio) já existente. */
    @Test
    @DisplayName("AC-003.9: criar convite zera invite_code_attempts do e-mail e encerra o bloqueio")
    void ac_003_9_criar_convite_zera_contador_e_bloqueio() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        semearTentativas(email, 5, clock.instant().plus(Duration.ofMinutes(20)), 1);

        mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel())).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                        "SELECT failed_attempts FROM invite_code_attempts WHERE email = ?", Integer.class, email))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT blocked_until FROM invite_code_attempts WHERE email = ?", Timestamp.class, email))
                .isNull();
    }

    /**
     * Pronto-quando da T-040: reenviar durante o bloqueio o encerra na hora. Monta 5 erros com
     * bloqueio de mais 20 min, confirma na lista que o convite ENVIADO aparece bloqueado, reenvia
     * e confere na lista que o convite NOVO tem failedAttempts=0 e blockedUntil nulo, mantendo
     * autoResends=2 (AC-003.3: reenvio manual não zera o limite).
     */
    @Test
    @DisplayName("AC-003.9: reenviar durante o bloqueio encerra o bloqueio na hora (failedAttempts=0, blockedUntil nulo)")
    void ac_003_9_reenviar_durante_bloqueio_encerra_na_hora() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID convite = semearConvite(grupo, email, "ENVIADO", clock.instant().plus(Duration.ofDays(3)));
        semearTentativas(email, 5, clock.instant().plus(Duration.ofMinutes(20)), 2);

        // Etapa 1: antes do reenvio a lista mostra o e-mail bloqueado.
        JsonNode antes = itemDaLista(grupo, convite);
        assertThat(antes.path("failedAttempts").asInt()).isEqualTo(5);
        assertThat(antes.path("blockedUntil").isNull()).isFalse();

        // Etapa 2: reenvio manual (201).
        mockMvc.perform(reenviar(grupo.elderId(), convite, grupo.responsavel()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract());

        // Etapa 3: a lista (lida do contador) mostra o convite novo desbloqueado e o limite de 3 intacto.
        JsonNode novo = itensDaLista(grupo, "ENVIADO").get(0);
        assertThat(novo.path("id").asText()).isNotEqualTo(convite.toString());
        assertThat(novo.path("failedAttempts").asInt()).isZero();
        assertThat(novo.path("blockedUntil").isNull()).isTrue();
        assertThat(novo.path("autoResends").asInt()).isEqualTo(2);
    }

    // ============================================================ AC-003.4 / AC-003.8 listar

    /**
     * AC-003.4 / AC-003.8 / D-39: a lista traz, por convite, failedAttempts, blockedUntil e
     * autoResends lidos de invite_code_attempts (3 erros, sem bloqueio, 1 reenvio automático).
     */
    @Test
    @DisplayName("AC-003.8: lista traz failedAttempts, blockedUntil e autoResends do contador do e-mail")
    void ac_003_8_lista_traz_tentativas_bloqueio_e_reenvios_automaticos() throws Exception {
        Grupo grupo = grupoComIdoso();
        String livre = emailNovo();
        String bloqueado = emailNovo();
        UUID conviteLivre = semearConvite(grupo, livre, "ENVIADO", clock.instant().plus(Duration.ofDays(3)));
        UUID conviteBloqueado = semearConvite(grupo, bloqueado, "ENVIADO", clock.instant().plus(Duration.ofDays(3)));
        Instant bloqueioAte = clock.instant().plus(Duration.ofMinutes(25));
        semearTentativas(livre, 3, null, 1);
        semearTentativas(bloqueado, 5, bloqueioAte, 3);

        // Etapa 1: e-mail com 3 erros e sem bloqueio.
        JsonNode a = itemDaLista(grupo, conviteLivre);
        assertThat(a.path("failedAttempts").asInt()).isEqualTo(3);
        assertThat(a.path("blockedUntil").isNull()).isTrue();
        assertThat(a.path("autoResends").asInt()).isEqualTo(1);

        // Etapa 2: e-mail bloqueado, com horário do fim do bloqueio.
        JsonNode b = itemDaLista(grupo, conviteBloqueado);
        assertThat(b.path("failedAttempts").asInt()).isEqualTo(5);
        assertThat(OffsetDateTime.parse(b.path("blockedUntil").asText()).toInstant())
                .isCloseTo(bloqueioAte, within(1, ChronoUnit.SECONDS));
        assertThat(b.path("autoResends").asInt()).isEqualTo(3);
    }

    /** AC-003.4: a lista é paginada (page/size) e valida contra InvitationPage do contrato. */
    @Test
    @DisplayName("AC-003.4: lista paginada (size=1 com 2 convites => 1 item, totalPages 2) conforme o contrato")
    void ac_003_4_lista_paginada() throws Exception {
        Grupo grupo = grupoComIdoso();
        semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));
        semearConvite(grupo, emailNovo(), "CANCELADO", clock.instant().plus(Duration.ofDays(3)));

        MvcResult result = mockMvc.perform(listar(grupo.elderId(), "size=1&page=0", grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("size").asInt()).isEqualTo(1);
        assertThat(body.path("totalItems").asInt()).isEqualTo(2);
        assertThat(body.path("totalPages").asInt()).isEqualTo(2);
    }

    /** AC-003.4: o filtro status devolve só convites do status pedido. */
    @Test
    @DisplayName("AC-003.4: filtro status=CANCELADO devolve só convites CANCELADO")
    void ac_003_4_lista_filtra_por_status() throws Exception {
        Grupo grupo = grupoComIdoso();
        semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));
        UUID cancelado = semearConvite(grupo, emailNovo(), "CANCELADO", clock.instant().plus(Duration.ofDays(3)));

        List<JsonNode> itens = itensDaLista(grupo, "CANCELADO");

        assertThat(itens).hasSize(1);
        assertThat(itens.get(0).path("id").asText()).isEqualTo(cancelado.toString());
    }

    /**
     * AC-003.2 / AC-003.4: um convite gravado como ENVIADO mas com prazo já vencido (job de D-09
     * ainda não rodou) aparece como VENCIDO na lista — e entra no filtro VENCIDO, não no ENVIADO.
     */
    @Test
    @DisplayName("AC-003.4: ENVIADO com expiresAt no passado aparece como VENCIDO na lista (derivado pelo Clock)")
    void ac_003_4_enviado_com_prazo_vencido_aparece_como_vencido() throws Exception {
        Grupo grupo = grupoComIdoso();
        UUID vencido = semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().minus(Duration.ofMinutes(1)));

        assertThat(itemDaLista(grupo, vencido).path("status").asText()).isEqualTo("VENCIDO");
        assertThat(itensDaLista(grupo, "VENCIDO")).extracting(n -> n.path("id").asText())
                .contains(vencido.toString());
        assertThat(itensDaLista(grupo, "ENVIADO")).extracting(n -> n.path("id").asText())
                .doesNotContain(vencido.toString());
    }

    /** AC-003.4: "somente o Responsável vê a lista" — Cuidador recebe 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-003.4: Cuidador listando convites recebe 403 NOT_ALLOWED")
    void ac_003_4_cuidador_nao_lista_403() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.ATIVO);

        MvcResult result = mockMvc.perform(listar(grupo.elderId(), "", cuidador))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("NOT_ALLOWED");
    }

    /** D-32: Responsável de outro grupo listando convites do grupo A recebe 404. */
    @Test
    @DisplayName("AC-003.4: Responsável de outro grupo listando convites do grupo A recebe 404")
    void ac_003_4_outro_grupo_listar_404() throws Exception {
        Grupo grupoA = grupoComIdoso();
        Grupo grupoB = grupoComIdoso();

        mockMvc.perform(listar(grupoA.elderId(), "", grupoB.responsavel())).andExpect(status().isNotFound());
    }

    // ============================================================ AC-003.4 reenviar

    /** AC-003.4: reenviar ENVIADO => 201, novo ENVIADO zerado, anterior CANCELADO, limite de 3 intacto. */
    @Test
    @DisplayName("AC-003.4: reenviar convite ENVIADO => 201; anterior vira CANCELADO; autoResends não zera")
    void ac_003_4_reenviar_enviado() throws Exception {
        reenviarEVerificar("ENVIADO", "CANCELADO");
    }

    /** AC-003.4: reenviar VENCIDO => 201; o anterior continua VENCIDO (não vira CANCELADO). */
    @Test
    @DisplayName("AC-003.4: reenviar convite VENCIDO => 201; anterior continua VENCIDO; autoResends não zera")
    void ac_003_4_reenviar_vencido() throws Exception {
        reenviarEVerificar("VENCIDO", "VENCIDO");
    }

    /** AC-003.4: reenviar CANCELADO => 201; o anterior continua CANCELADO. */
    @Test
    @DisplayName("AC-003.4: reenviar convite CANCELADO => 201; anterior continua CANCELADO; autoResends não zera")
    void ac_003_4_reenviar_cancelado() throws Exception {
        reenviarEVerificar("CANCELADO", "CANCELADO");
    }

    private void reenviarEVerificar(String statusInicial, String statusDoAnteriorDepois) throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID anterior = semearConvite(grupo, email, statusInicial, clock.instant().plus(Duration.ofDays(3)));
        semearTentativas(email, 2, null, 2);

        MvcResult result = mockMvc.perform(reenviar(grupo.elderId(), anterior, grupo.responsavel()))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode novo = json.readTree(result.getResponse().getContentAsString());
        assertThat(novo.path("id").asText()).isNotEqualTo(anterior.toString());
        assertThat(novo.path("email").asText()).isEqualTo(email);
        assertThat(novo.path("status").asText()).isEqualTo("ENVIADO");
        assertThat(novo.path("failedAttempts").asInt()).as("zero tentativas").isZero();
        assertThat(novo.path("autoResends").asInt()).as("reenvio manual não zera o limite de 3").isEqualTo(2);
        assertThat(instante(novo, "expiresAt"))
                .isCloseTo(clock.instant().plus(Duration.ofDays(7)), within(1, ChronoUnit.SECONDS));
        assertThat(statusDe(anterior.toString())).isEqualTo(statusDoAnteriorDepois);
        assertThat(jdbc.queryForObject(
                        "SELECT auto_resends FROM invite_code_attempts WHERE email = ?", Integer.class, email))
                .isEqualTo(2);
        esperarEmail(email, 1);
    }

    /** AC-003.4: convite USADO não pode ser reenviado => 409 INVITATION_USED (corpo Problem). */
    @Test
    @DisplayName("AC-003.4: reenviar convite USADO => 409 INVITATION_USED")
    void ac_003_4_reenviar_usado_409() throws Exception {
        Grupo grupo = grupoComIdoso();
        UUID usado = semearConvite(grupo, emailNovo(), "USADO", clock.instant().plus(Duration.ofDays(3)));

        MvcResult result = mockMvc.perform(reenviar(grupo.elderId(), usado, grupo.responsavel()))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("INVITATION_USED");
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(statusDe(usado.toString())).isEqualTo("USADO");
    }

    /** D-32: reenviar convite de OUTRO grupo => 404, e nada é criado. */
    @Test
    @DisplayName("AC-003.4: reenviar convite de outro grupo => 404")
    void ac_003_4_reenviar_de_outro_grupo_404() throws Exception {
        Grupo grupoA = grupoComIdoso();
        Grupo grupoB = grupoComIdoso();
        String email = emailNovo();
        UUID conviteDeA = semearConvite(grupoA, email, "ENVIADO", clock.instant().plus(Duration.ofDays(3)));

        mockMvc.perform(reenviar(grupoB.elderId(), conviteDeA, grupoB.responsavel()))
                .andExpect(status().isNotFound());

        assertThat(statusDe(conviteDeA.toString())).isEqualTo("ENVIADO");
        assertThat(contarConvites(email)).isEqualTo(1);
    }

    /** D-32: reenviar convite inexistente => 404. */
    @Test
    @DisplayName("AC-003.4: reenviar convite inexistente => 404")
    void ac_003_4_reenviar_inexistente_404() throws Exception {
        Grupo grupo = grupoComIdoso();

        mockMvc.perform(reenviar(grupo.elderId(), UUID.randomUUID(), grupo.responsavel()))
                .andExpect(status().isNotFound());
    }

    /** AC-015.2: Cuidador reenviando recebe 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-003.4: Cuidador reenviando convite recebe 403 NOT_ALLOWED")
    void ac_003_4_cuidador_nao_reenvia_403() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.ATIVO);
        UUID convite = semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));

        mockMvc.perform(reenviar(grupo.elderId(), convite, cuidador)).andExpect(status().isForbidden());

        assertThat(statusDe(convite.toString())).isEqualTo("ENVIADO");
    }

    // ============================================================ AC-003.4 cancelar

    /** AC-003.4: cancelar ENVIADO => 200 com status CANCELADO e cancelledAt preenchido. */
    @Test
    @DisplayName("AC-003.4: cancelar convite ENVIADO => 200 CANCELADO com cancelledAt")
    void ac_003_4_cancelar_enviado_200() throws Exception {
        Grupo grupo = grupoComIdoso();
        UUID convite = semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));

        MvcResult result = mockMvc.perform(cancelar(grupo.elderId(), convite, grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("CANCELADO");
        assertThat(instante(body, "cancelledAt")).isCloseTo(clock.instant(), within(1, ChronoUnit.SECONDS));
        assertThat(statusDe(convite.toString())).isEqualTo("CANCELADO");
    }

    /** AC-003.4: cancelar convite USADO/VENCIDO/CANCELADO => 409 INVITATION_NOT_CANCELLABLE, sem mudar nada. */
    @ParameterizedTest
    @EnumSource(names = {"USADO", "VENCIDO", "CANCELADO"})
    @DisplayName("AC-003.4: cancelar convite que não está ENVIADO => 409 INVITATION_NOT_CANCELLABLE")
    void ac_003_4_cancelar_nao_enviado_409(br.com.cuidamais.membership.domain.InvitationStatus statusInicial)
            throws Exception {
        Grupo grupo = grupoComIdoso();
        // Cada linha = um status diferente de ENVIADO.
        UUID convite = semearConvite(grupo, emailNovo(), statusInicial.name(), clock.instant().plus(Duration.ofDays(3)));

        MvcResult result = mockMvc.perform(cancelar(grupo.elderId(), convite, grupo.responsavel()))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("INVITATION_NOT_CANCELLABLE");
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(statusDe(convite.toString())).isEqualTo(statusInicial.name());
    }

    /** D-32: cancelar convite de outro grupo => 404 e o convite fica ENVIADO. */
    @Test
    @DisplayName("AC-003.4: cancelar convite de outro grupo => 404")
    void ac_003_4_cancelar_de_outro_grupo_404() throws Exception {
        Grupo grupoA = grupoComIdoso();
        Grupo grupoB = grupoComIdoso();
        UUID conviteDeA = semearConvite(grupoA, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));

        mockMvc.perform(cancelar(grupoB.elderId(), conviteDeA, grupoB.responsavel()))
                .andExpect(status().isNotFound());

        assertThat(statusDe(conviteDeA.toString())).isEqualTo("ENVIADO");
    }

    /** AC-015.2: Cuidador cancelando recebe 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-003.4: Cuidador cancelando convite recebe 403 NOT_ALLOWED")
    void ac_003_4_cuidador_nao_cancela_403() throws Exception {
        Grupo grupo = grupoComIdoso();
        User cuidador = membro(grupo.groupId(), grupo.responsavel(), MembershipStatus.ATIVO);
        UUID convite = semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().plus(Duration.ofDays(3)));

        mockMvc.perform(cancelar(grupo.elderId(), convite, cuidador)).andExpect(status().isForbidden());

        assertThat(statusDe(convite.toString())).isEqualTo("ENVIADO");
    }

    // ============================================================ helpers de requisição

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private MockHttpServletRequestBuilder convidar(UUID elderId, String email, User autor) throws Exception {
        return post(ELDERS + "/{elderId}/invitations", elderId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor))
                .content(json.writeValueAsString(Map.of("email", email)));
    }

    private MockHttpServletRequestBuilder listar(UUID elderId, String query, User autor) {
        String sufixo = query == null || query.isEmpty() ? "" : "?" + query;
        return get(ELDERS + "/{elderId}/invitations" + sufixo, elderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    private MockHttpServletRequestBuilder reenviar(UUID elderId, UUID invitationId, User autor) {
        return post(ELDERS + "/{elderId}/invitations/{id}/resend", elderId, invitationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    private MockHttpServletRequestBuilder cancelar(UUID elderId, UUID invitationId, User autor) {
        return post(ELDERS + "/{elderId}/invitations/{id}/cancel", elderId, invitationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    // ============================================================ helpers de verificação

    /** Convida como Responsável, espera 409 com o {@code code} dado e valida contra InvitationConflictProblem. */
    private JsonNode esperarConflito(Grupo grupo, String email, String code) throws Exception {
        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(code);
        List<Error> violacoes = ContractSchemas.schemaFor("InvitationConflictProblem").validate(body);
        assertThat(violacoes).as("corpo casa com InvitationConflictProblem: %s", violacoes).isEmpty();
        return body;
    }

    private JsonNode semInstance(JsonNode body) {
        com.fasterxml.jackson.databind.node.ObjectNode copia = body.deepCopy();
        copia.remove("instance");
        return copia;
    }

    private JsonNode criarConvite(Grupo grupo, String email) throws Exception {
        MvcResult result = mockMvc.perform(convidar(grupo.elderId(), email, grupo.responsavel()))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<JsonNode> itensDaLista(Grupo grupo, String status) throws Exception {
        String query = status == null ? "size=100" : "size=100&status=" + status;
        MvcResult result = mockMvc.perform(listar(grupo.elderId(), query, grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).path("items");
        java.util.ArrayList<JsonNode> lista = new java.util.ArrayList<>();
        items.forEach(lista::add);
        return lista;
    }

    private JsonNode itemDaLista(Grupo grupo, UUID id) throws Exception {
        return itensDaLista(grupo, null).stream()
                .filter(n -> n.path("id").asText().equals(id.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("convite " + id + " não está na lista"));
    }

    private MailpitClient.Detail esperarEmail(String email, int quantidade) {
        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(quantidade));
        return mailpit.message(mailpit.messagesTo(email).getFirst().id());
    }

    private String extrairCodigo(MailpitClient.Detail mail) {
        Matcher m = CODIGO_6_DIGITOS.matcher(mail.text());
        assertThat(m.find()).as("e-mail traz um código de 6 dígitos").isTrue();
        return m.group();
    }

    private static Instant instante(JsonNode body, String campo) {
        return OffsetDateTime.parse(body.path(campo).asText()).toInstant();
    }

    private String statusDe(String invitationId) {
        return jdbc.queryForObject(
                "SELECT status FROM invitations WHERE id = ?", String.class, UUID.fromString(invitationId));
    }

    private String hashDe(String invitationId) {
        return jdbc.queryForObject(
                "SELECT code_hash FROM invitations WHERE id = ?", String.class, UUID.fromString(invitationId));
    }

    private int contarConvites(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM invitations WHERE email = ?", Integer.class, email);
    }

    // ============================================================ fábricas de cenário

    /** Semeia um convite direto no banco no status pedido (sem passar pela API da T-040). */
    private UUID semearConvite(Grupo grupo, String email, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, "hash-semeado-" + id, status, Timestamp.from(expiresAt),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    /** Semeia/atualiza o contador por e-mail (invite_code_attempts). */
    private void semearTentativas(String email, int falhas, Instant bloqueadoAte, int autoResends) {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                email, falhas, bloqueadoAte == null ? null : Timestamp.from(bloqueadoAte), autoResends,
                Timestamp.from(clock.instant()));
    }

    private Grupo grupoComIdoso() {
        try {
            User responsavel = contaAtivaSemGrupo();
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(responsavel))
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

    /** Fabrica uma conta com membership no grupo, no status pedido (mesmo padrão de DeleteGroupTest). */
    private User membro(UUID groupId, User responsavel, MembershipStatus statusFinal) {
        User user = contaAtivaSemGrupo();
        Membership m = Membership.request(groupId, user.getId(), clock);
        switch (statusFinal) {
            case ATIVO -> m.approve(Role.CUIDADOR, responsavel.getId(), clock);
            case AGUARDANDO_APROVACAO -> { /* fica pendente */ }
            case RECUSADO -> m.reject(responsavel.getId(), clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, responsavel.getId(), clock);
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
                "Maria da Silva", emailNovo(), "21987654321", endereco(), "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return "convite-" + UUID.randomUUID() + "@teste.com";
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
