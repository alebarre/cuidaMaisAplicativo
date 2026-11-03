package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-044 — {@code GET /elders/{elderId}/members/pending-count} (contrato {@code getPendingCount},
 * {@code x-required-permission: admin}).
 *
 * <p>ACs cobertos: AC-006.1 (o Responsável vê quantos pedidos aguardam aprovação — o badge) e
 * AC-006.2 (só conta pedido com prazo vigente: {@code agora < expiresAt}, mesma regra de {@link
 * Membership#approve} e da listagem; vencido não conta nem antes do job). Decisões: D-17, D-21
 * (contrato), D-09 (prazo derivado do Clock), D-32/AC-015 (403 {@code NOT_ALLOWED} para não admin,
 * 404 para outro grupo/elder inexistente), P5 (a resposta só tem {@code count}, nunca dados de
 * membros).
 *
 * <p>Decisões do orquestrador (a confirmar): só conta {@code AGUARDANDO_APROVACAO} vigente; ATIVO,
 * RECUSADO, EXPIRADO e REMOVIDO não contam; Cuidador e Familiar comum recebem 403 NOT_ALLOWED; o
 * count é consistente com o número de itens de {@code GET /members?status=AGUARDANDO_APROVACAO}.
 * Estados de conta: pendente recebe 403 ACCOUNT_STATE e removido 401 (AccountSecurityInterceptor).
 *
 * <p>Caixa-preta pela rota HTTP real; cada 200 é validado contra o contrato ({@link
 * ContractValidation}) e cada erro contra o schema {@code Problem}.
 */
@AutoConfigureMockMvc
class PendingCountTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    JwtService jwtService;

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    // ============================================================ contagem

    /** Sem nenhum pedido, o Responsável recebe {@code {"count": 0}} (nunca 404 nem ausência de campo). */
    @Test
    @DisplayName("AC-006.1: sem pendentes o count é 0")
    void ac_006_1_sem_pendentes_count_zero() throws Exception {
        Grupo g = grupo();

        assertThat(contar(g.elderId(), g.responsavel())).isZero();
    }

    /** Três pedidos vigentes => count 3. */
    @Test
    @DisplayName("AC-006.1: count igual ao número de pedidos AGUARDANDO_APROVACAO vigentes")
    void ac_006_1_conta_pendentes_vigentes() throws Exception {
        Grupo g = grupo();
        membro(g, "P1", MembershipStatus.AGUARDANDO_APROVACAO);
        membro(g, "P2", MembershipStatus.AGUARDANDO_APROVACAO);
        membro(g, "P3", MembershipStatus.AGUARDANDO_APROVACAO);

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(3);
    }

    /**
     * Um membro em cada outro estado (ATIVO, RECUSADO, EXPIRADO, REMOVIDO) mais um pendente
     * vigente: só o pendente conta. Os pendentes são criados depois do avanço do relógio do
     * expirado para ficarem vigentes.
     */
    @Test
    @DisplayName("AC-006.1: ATIVO, RECUSADO, EXPIRADO e REMOVIDO não entram no count")
    void ac_006_1_so_conta_aguardando_aprovacao() throws Exception {
        Grupo g = grupo();
        membro(g, "Ativo", MembershipStatus.ATIVO);
        membro(g, "Recusado", MembershipStatus.RECUSADO);
        membro(g, "Removido", MembershipStatus.REMOVIDO);
        membro(g, "Expirado", MembershipStatus.EXPIRADO);
        membro(g, "Pendente", MembershipStatus.AGUARDANDO_APROVACAO);

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(1);
    }

    /** Pedido vencido (25h) ainda persistido como AGUARDANDO_APROVACAO (job não rodou) não conta (D-09). */
    @Test
    @DisplayName("AC-006.2: pedido vencido antes do job não conta")
    void ac_006_2_vencido_nao_conta() throws Exception {
        Grupo g = grupo();
        membro(g, "Vencida", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(25));

        assertThat(contar(g.elderId(), g.responsavel())).isZero();
    }

    /** Fronteira: aos 24h exatos o prazo já venceu (agora >= expiresAt) e o pedido não conta. */
    @Test
    @DisplayName("AC-006.2: aos 24h exatos o pedido não conta")
    void ac_006_2_aos_24h_exatos_nao_conta() throws Exception {
        Grupo g = grupo();
        membro(g, "Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(24));

        assertThat(contar(g.elderId(), g.responsavel())).isZero();
    }

    /** Fronteira oposta: aos 23h59 o pedido ainda é vigente e conta. */
    @Test
    @DisplayName("AC-006.2: aos 23h59 o pedido ainda conta")
    void ac_006_2_aos_23h59_conta() throws Exception {
        Grupo g = grupo();
        membro(g, "Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(1);
    }

    /** Um pedido antigo (vencido) e um recente (vigente) no mesmo grupo: só o recente conta. */
    @Test
    @DisplayName("AC-006.2: mistura de vencido e vigente conta só o vigente")
    void ac_006_2_mistura_vencido_e_vigente() throws Exception {
        Grupo g = grupo();
        membro(g, "Antiga", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(25));
        membro(g, "Recente", MembershipStatus.AGUARDANDO_APROVACAO);

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(1);
    }

    /** Pedidos de outro grupo não entram na conta deste (isolamento). */
    @Test
    @DisplayName("AC-006.1: pendentes de outro grupo não contam")
    void ac_006_1_isolamento_entre_grupos() throws Exception {
        Grupo a = grupo();
        Grupo b = grupo();
        membro(a, "Pendente de A", MembershipStatus.AGUARDANDO_APROVACAO);
        membro(b, "Pendente de B1", MembershipStatus.AGUARDANDO_APROVACAO);
        membro(b, "Pendente de B2", MembershipStatus.AGUARDANDO_APROVACAO);

        assertThat(contar(a.elderId(), a.responsavel())).isEqualTo(1);
        assertThat(contar(b.elderId(), b.responsavel())).isEqualTo(2);
    }

    /** O corpo tem exatamente a chave {@code count}: nenhum dado de membro vaza (P5). */
    @Test
    @DisplayName("AC-006.1: resposta contém só count, sem dados de membros")
    void ac_006_1_resposta_so_tem_count() throws Exception {
        Grupo g = grupo();
        User pendente = membro(g, "Pendente Sigilosa", MembershipStatus.AGUARDANDO_APROVACAO);

        JsonNode body = corpo(g.elderId(), g.responsavel());

        assertThat(body.fieldNames()).toIterable().containsExactly("count");
        assertThat(body.toString()).doesNotContain(pendente.getName()).doesNotContain(pendente.getEmail());
    }

    // ============================================================ consistência com a listagem

    /** Estado misto: o count bate com o total de itens da listagem filtrada do Responsável. */
    @Test
    @DisplayName("AC-006.1: count bate com GET /members?status=AGUARDANDO_APROVACAO (estado misto)")
    void ac_006_1_consistente_com_listagem() throws Exception {
        Grupo g = grupo();
        membro(g, "Ativo", MembershipStatus.ATIVO);
        membro(g, "Antiga", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(25));
        membro(g, "R1", MembershipStatus.AGUARDANDO_APROVACAO);
        membro(g, "R2", MembershipStatus.AGUARDANDO_APROVACAO);

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(totalListado(g)).isEqualTo(2);
    }

    /** No limite exato de 24h count e listagem concordam (ambos 0). */
    @Test
    @DisplayName("AC-006.2: count e listagem concordam aos 24h exatos")
    void ac_006_2_consistente_aos_24h_exatos() throws Exception {
        Grupo g = grupo();
        membro(g, "Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(24));

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(totalListado(g)).isZero();
    }

    /** Aos 23h59 count e listagem concordam (ambos 1). */
    @Test
    @DisplayName("AC-006.2: count e listagem concordam aos 23h59")
    void ac_006_2_consistente_aos_23h59() throws Exception {
        Grupo g = grupo();
        membro(g, "Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        assertThat(contar(g.elderId(), g.responsavel())).isEqualTo(totalListado(g)).isEqualTo(1);
    }

    // ============================================================ autorização

    /** Cuidador (não admin) => 403 NOT_ALLOWED, sem revelar a contagem. */
    @Test
    @DisplayName("AC-006.1: Cuidador recebe 403 NOT_ALLOWED")
    void ac_006_1_cuidador_403() throws Exception {
        Grupo g = grupo();
        User cuidador = membroAtivo(g, "Cuidador Um", Role.CUIDADOR);
        membro(g, "Pendente", MembershipStatus.AGUARDANDO_APROVACAO);

        JsonNode problema = esperar403(g.elderId(), cuidador);

        assertThat(problema.path("code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(problema.has("count")).isFalse();
    }

    /** Familiar comum (não admin) => 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-006.1: Familiar comum recebe 403 NOT_ALLOWED")
    void ac_006_1_familiar_comum_403() throws Exception {
        Grupo g = grupo();
        User familiar = membroAtivo(g, "Familiar Um", Role.FAMILIAR);

        assertThat(esperar403(g.elderId(), familiar).path("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    /** Sem token => 401 com Problem válido (contrato Unauthorized). */
    @Test
    @DisplayName("AC-006.1: sem token => 401 Problem")
    void ac_006_1_sem_token_401() throws Exception {
        Grupo g = grupo();

        MvcResult r = mockMvc.perform(get(ELDERS + "/{elderId}/members/pending-count", g.elderId()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    /** Responsável de OUTRO grupo no elderId do grupo A => 404 (D-32). */
    @Test
    @DisplayName("AC-006.1: Responsável de outro grupo recebe 404 (D-32)")
    void ac_006_1_outro_grupo_404() throws Exception {
        Grupo a = grupo();
        Grupo b = grupo();

        MvcResult r = mockMvc.perform(requisicao(a.elderId(), b.responsavel()))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    /** elderId inexistente para o Responsável => 404. */
    @Test
    @DisplayName("AC-006.1: elderId inexistente => 404")
    void ac_006_1_elder_inexistente_404() throws Exception {
        Grupo g = grupo();

        mockMvc.perform(requisicao(UUID.randomUUID(), g.responsavel())).andExpect(status().isNotFound());
    }

    /**
     * AccountSecurityInterceptor: rota padrão exige ATIVO com grupo; quem está AGUARDANDO_APROVACAO
     * recebe 403 ACCOUNT_STATE (e não NOT_ALLOWED), sem dados do grupo.
     */
    @Test
    @DisplayName("AC-006.1: membro AGUARDANDO_APROVACAO recebe 403 ACCOUNT_STATE")
    void ac_006_1_pendente_403_account_state() throws Exception {
        Grupo g = grupo();
        User pendente = membro(g, "Pendente Curiosa", MembershipStatus.AGUARDANDO_APROVACAO);

        JsonNode problema = esperar403(g.elderId(), pendente);

        assertThat(problema.path("code").asText()).isEqualTo("ACCOUNT_STATE");
        assertThat(problema.has("count")).isFalse();
    }

    /** Membro REMOVIDO perde a sessão: 401 Problem sem {@code code} (AC-009.2, interceptor). */
    @Test
    @DisplayName("AC-006.1: membro REMOVIDO recebe 401")
    void ac_006_1_removido_401() throws Exception {
        Grupo g = grupo();
        User removido = membro(g, "Removido Curioso", MembershipStatus.REMOVIDO);

        MvcResult r = mockMvc.perform(requisicao(g.elderId(), removido))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.has("code")).isFalse();
        assertThat(body.has("count")).isFalse();
    }

    // ============================================================ helpers HTTP

    private MockHttpServletRequestBuilder requisicao(UUID elderId, User autor) {
        return get(ELDERS + "/{elderId}/members/pending-count", elderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(autor.getId(), clock));
    }

    /** GET 200 validado contra o contrato; devolve o corpo. */
    private JsonNode corpo(UUID elderId, User autor) throws Exception {
        MvcResult r = mockMvc.perform(requisicao(elderId, autor))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int contar(UUID elderId, User autor) throws Exception {
        return corpo(elderId, autor).path("count").asInt(-1);
    }

    /** Total de itens da listagem filtrada do Responsável (para a prova de consistência). */
    private int totalListado(Grupo g) throws Exception {
        MvcResult r = mockMvc.perform(get(ELDERS + "/{elderId}/members?status=AGUARDANDO_APROVACAO&size=100", g.elderId())
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtService.issue(g.responsavel().getId(), clock)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("items").size();
    }

    private JsonNode esperar403(UUID elderId, User autor) throws Exception {
        MvcResult r = mockMvc.perform(requisicao(elderId, autor))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        return body;
    }

    // ============================================================ fábricas

    private Grupo grupo() {
        try {
            User responsavel = conta("Zélia Responsável");
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + jwtService.issue(responsavel.getId(), clock))
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

    private User membroAtivo(Grupo g, String nome, Role papel) {
        User user = conta(nome);
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        m.approve(papel, g.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    /** Conta com membership no status pedido (EXPIRADO avança o relógio 25h, como em MemberListTest). */
    private User membro(Grupo g, String nome, MembershipStatus statusFinal) {
        User user = conta(nome);
        UUID decisor = g.responsavel().getId();
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        switch (statusFinal) {
            case ATIVO -> m.approve(Role.CUIDADOR, decisor, clock);
            case AGUARDANDO_APROVACAO -> { /* fica pendente */ }
            case RECUSADO -> m.reject(decisor, clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, decisor, clock);
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

    private User conta(String nome) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "pendentes-" + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
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
