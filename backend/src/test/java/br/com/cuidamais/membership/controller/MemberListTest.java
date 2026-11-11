package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-043 — {@code GET /elders/{elderId}/members} (contrato {@code listMembers}, schemas {@code
 * Member} e {@code MemberPage}).
 *
 * <p>ACs cobertos: AC-006.2 (pedido {@code AGUARDANDO_APROVACAO} vencido não aparece, mesmo antes
 * do job; vigente aparece com {@code approvalDeadline}), AC-006.9 (sem {@code status} só
 * {@code ATIVO}; {@code RECUSADO}/{@code EXPIRADO} consultáveis só pelo Responsável via filtro; os
 * demais recebem 403), AC-009.5 (e-mail, telefone e endereço do {@code REMOVIDO} só para o
 * Responsável, via filtro), AC-011.5 e AC-011.8 (matriz de visibilidade de endereço, telefone e
 * e-mail entre membros {@code ATIVO}: Cuidador vê só o próprio, Familiar e Responsável veem todos;
 * campo não permitido fica AUSENTE do JSON, nunca {@code null}). Decisões: D-09 (prazo vencido
 * derivado do Clock, sem depender do job), D-28 rev. 2 (política única de contato), D-32/AC-015
 * (403 {@code NOT_ALLOWED}, 404 para outro grupo), D-39 (campos do contrato).
 *
 * <p>Decisões do orquestrador para lacunas da spec (documentadas aqui, a confirmar):
 *
 * <ul>
 *   <li>Sem {@code status}: só {@code ATIVO}, para qualquer membro {@code ATIVO} (inclui o próprio
 *       chamador na lista).
 *   <li>Com {@code status} (qualquer valor, inclusive {@code ATIVO}): só o Responsável; Cuidador e
 *       Familiar comum recebem 403 {@code NOT_ALLOWED}.
 *   <li>Pendente vencido nunca aparece, nem com {@code ?status=AGUARDANDO_APROVACAO}; o prazo vence
 *       quando {@code agora >= expiresAt} (mesma regra de {@link Membership#approve}).
 *   <li>Para membros não {@code ATIVO} (que só o Responsável vê), o Responsável vê os três
 *       campos de contato, inclusive do {@code REMOVIDO} (AC-009.5).
 *   <li>{@code approvalDeadline} só enquanto {@code AGUARDANDO_APROVACAO}; ausente/null nos demais.
 *   <li>Paginação sanitizada como em {@code GET /invitations} (sem 400): page &lt; 0 vira 0; size
 *       &lt; 1 vira 1; size &gt; 100 vira 100. Ordem estável: nome sem diferenciar caixa, depois id.
 *   <li>Autorização: sem token 401; ATIVO de outro grupo ou elderId inexistente 404 (D-32).
 * </ul>
 *
 * <p>Caixa-preta pela rota HTTP real, cada 200 validado contra o contrato ({@link
 * ContractValidation}). Grupo nasce por {@code POST /elders}; membros em cada estado nascem pela
 * máquina de estados de {@link Membership}; {@link br.com.cuidamais.shared.testsupport.MutableClock}
 * (D-10) fixa e avança o "agora".
 */
@AutoConfigureMockMvc
class MemberListTest extends IntegrationTest {

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

    /** Quem consulta na matriz. */
    enum Viewer { CUIDADOR, FAMILIAR, RESPONSAVEL }

    /** De quem é o contato consultado, relativo ao viewer. */
    enum Alvo { PROPRIO, OUTRO_CUIDADOR, OUTRO_FAMILIAR, RESPONSAVEL }

    // ============================================================ AC-011.5 / AC-011.8 matriz

    /** Gera a tabela inteira: 3 viewers x 4 alvos x 3 campos (cada linha = uma célula da matriz). */
    static Stream<Arguments> celulasDaMatriz() {
        List<Arguments> linhas = new ArrayList<>();
        for (Viewer viewer : Viewer.values()) {
            for (Alvo alvo : Alvo.values()) {
                for (String campo : List.of("email", "phone", "address")) {
                    // Cuidador: só o próprio; Familiar e Responsável (Familiar com is_admin): todos.
                    boolean visivel = viewer != Viewer.CUIDADOR || alvo == Alvo.PROPRIO;
                    linhas.add(Arguments.of(viewer, alvo, campo, visivel));
                }
            }
        }
        return linhas.stream();
    }

    /**
     * Matriz de AC-011.5 (endereço) e AC-011.8 (telefone e e-mail), cada célula um assert: o
     * campo permitido vem com o valor real da conta alvo; o não permitido NÃO EXISTE no JSON (não
     * é {@code null}). Nome, papel e isAdmin do alvo continuam sempre presentes.
     */
    @ParameterizedTest(name = "AC-011.5/AC-011.8: viewer={0} alvo={1} campo={2} visivel={3}")
    @MethodSource("celulasDaMatriz")
    @DisplayName("AC-011.5/AC-011.8: matriz de visibilidade de e-mail, telefone e endereço")
    void ac_011_5_e_011_8_matriz_de_visibilidade(Viewer viewer, Alvo alvo, String campo, boolean visivel)
            throws Exception {
        Cenario c = cenarioDaMatriz();
        User quemConsulta = switch (viewer) {
            case CUIDADOR -> c.cuidador1();
            case FAMILIAR -> c.familiar1();
            case RESPONSAVEL -> c.responsavel();
        };
        User dono = switch (alvo) {
            case PROPRIO -> quemConsulta;
            case OUTRO_CUIDADOR -> quemConsulta.equals(c.cuidador1()) ? c.cuidador2() : c.cuidador1();
            case OUTRO_FAMILIAR -> quemConsulta.equals(c.familiar1()) ? c.familiar2() : c.familiar1();
            case RESPONSAVEL -> c.responsavel();
        };

        JsonNode item = itemDe(listar(c.elderId(), "size=100", quemConsulta), dono);

        // Etapa: campos sempre presentes, independentemente da matriz (AC-011.5: nome e papel visíveis).
        assertThat(item.path("name").asText()).isEqualTo(dono.getName());
        assertThat(item.has("role")).isTrue();
        assertThat(item.has("isAdmin")).isTrue();
        // Etapa: a célula da matriz.
        if (visivel) {
            assertThat(item.has(campo)).as("campo %s presente", campo).isTrue();
            switch (campo) {
                case "email" -> assertThat(item.path("email").asText()).isEqualTo(dono.getEmail());
                case "phone" -> assertThat(item.path("phone").asText()).isEqualTo(dono.getPhone());
                default -> assertThat(item.path("address").path("street").asText())
                        .isEqualTo(dono.getAddress().street());
            }
        } else {
            assertThat(item.has(campo)).as("campo %s deve estar AUSENTE (não null)", campo).isFalse();
        }
    }

    // ============================================================ AC-006.9 sem status

    /**
     * Sem {@code status}, o Cuidador vê apenas membros {@code ATIVO} (inclui ele mesmo); pendente
     * vigente, recusado, expirado e removido ficam de fora (AC-006.9).
     */
    @Test
    @DisplayName("AC-006.9: Cuidador sem status vê só membros ATIVO, inclusive ele mesmo")
    void ac_006_9_cuidador_sem_status_ve_so_ativos() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "size=100", c.cuidador1());

        assertThat(idsDeUsuarios(page)).containsExactlyInAnyOrder(
                c.responsavel().getId().toString(), c.familiar1().getId().toString(),
                c.cuidador1().getId().toString());
        assertThat(statusDosItens(page)).containsOnly("ATIVO");
    }

    /** Mesma regra para o Responsável: sem filtro ele também só enxerga os ATIVO (AC-006.9). */
    @Test
    @DisplayName("AC-006.9: Responsável sem status também vê só membros ATIVO")
    void ac_006_9_responsavel_sem_status_ve_so_ativos() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "size=100", c.responsavel());

        assertThat(statusDosItens(page)).containsOnly("ATIVO");
        assertThat(page.path("totalItems").asInt()).isEqualTo(3);
    }

    // ============================================================ AC-006.9 filtro só do Responsável

    /**
     * Cuidador com {@code ?status=X} recebe 403 NOT_ALLOWED para QUALQUER valor, inclusive
     * {@code ATIVO} (decisão do orquestrador), e a resposta é Problem sem dados dos membros (P5).
     */
    @ParameterizedTest
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-006.9: Cuidador com ?status=X recebe 403 NOT_ALLOWED, para todo status")
    void ac_006_9_cuidador_com_status_403(MembershipStatus filtro) throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        // Cada linha = um valor de MembershipStatus no filtro.
        JsonNode problema = esperar403(c.elderId(), "status=" + filtro, c.cuidador1());

        assertThat(problema.path("code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(problema.toString())
                .doesNotContain(c.removido().getName())
                .doesNotContain(c.removido().getEmail())
                .doesNotContain(c.removido().getPhone());
    }

    /** Caso nomeado do "Pronto quando" da T-043: Cuidador com ?status=REMOVIDO => 403 (AC-009.5). */
    @Test
    @DisplayName("AC-009.5: Cuidador com ?status=REMOVIDO recebe 403")
    void ac_009_5_cuidador_status_removido_403() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode problema = esperar403(c.elderId(), "status=REMOVIDO", c.cuidador1());

        assertThat(problema.path("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    /** Familiar comum (não admin) também não usa o filtro: 403 para qualquer valor (AC-006.9). */
    @ParameterizedTest
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-006.9: Familiar comum com ?status=X recebe 403 NOT_ALLOWED, para todo status")
    void ac_006_9_familiar_comum_com_status_403(MembershipStatus filtro) throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        // Cada linha = um valor de MembershipStatus no filtro, agora com viewer Familiar não admin.
        JsonNode problema = esperar403(c.elderId(), "status=" + filtro, c.familiar1());

        assertThat(problema.path("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    /** Responsável filtrando RECUSADO vê só o recusado, com decidedAt e sem approvalDeadline (AC-006.9). */
    @Test
    @DisplayName("AC-006.9: Responsável com ?status=RECUSADO vê o pedido recusado")
    void ac_006_9_responsavel_ve_recusados() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "status=RECUSADO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).containsExactly(c.recusado().getId().toString());
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("status").asText()).isEqualTo("RECUSADO");
        assertThat(item.hasNonNull("decidedAt")).isTrue();
        assertThat(item.hasNonNull("approvalDeadline")).isFalse();
    }

    /** Responsável filtrando EXPIRADO vê só o expirado, sem approvalDeadline (AC-006.9). */
    @Test
    @DisplayName("AC-006.9: Responsável com ?status=EXPIRADO vê o pedido expirado")
    void ac_006_9_responsavel_ve_expirados() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "status=EXPIRADO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).containsExactly(c.expirado().getId().toString());
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("EXPIRADO");
        assertThat(page.path("items").get(0).hasNonNull("approvalDeadline")).isFalse();
    }

    /** Responsável filtrando ATIVO (explicitamente) também funciona: é admin (AC-006.9). */
    @Test
    @DisplayName("AC-006.9: Responsável com ?status=ATIVO vê os três ATIVO")
    void ac_006_9_responsavel_filtra_ativo() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "status=ATIVO&size=100", c.responsavel());

        assertThat(statusDosItens(page)).containsOnly("ATIVO");
        assertThat(page.path("totalItems").asInt()).isEqualTo(3);
    }

    // ============================================================ AC-006.2 pendentes

    /**
     * Pedido vigente aparece para o Responsável com {@code approvalDeadline} = pedido + 24 h e
     * {@code requestedAt} preenchido (AC-006.2); nome, e-mail e telefone presentes.
     */
    @Test
    @DisplayName("AC-006.2: Responsável vê pedido AGUARDANDO_APROVACAO vigente com approvalDeadline = pedido + 24h")
    void ac_006_2_pendente_vigente_com_prazo() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "status=AGUARDANDO_APROVACAO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).containsExactly(c.pendente().getId().toString());
        JsonNode item = page.path("items").get(0);
        Instant pedido = instante(item, "requestedAt");
        assertThat(instante(item, "approvalDeadline"))
                .isCloseTo(pedido.plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));
        assertThat(item.path("email").asText()).isEqualTo(c.pendente().getEmail());
        assertThat(item.path("phone").asText()).isEqualTo(c.pendente().getPhone());
    }

    /**
     * Pedido cujo prazo venceu pelo Clock, SEM o job ter rodado (status ainda
     * AGUARDANDO_APROVACAO no banco), não aparece no filtro (AC-006.2, D-09).
     */
    @Test
    @DisplayName("AC-006.2: pedido vencido antes do job não aparece com ?status=AGUARDANDO_APROVACAO")
    void ac_006_2_pendente_vencido_nao_aparece_no_filtro() throws Exception {
        Cenario c = cenarioMinimo();
        User pendente = membro(c.groupId(), c.responsavel(), "Pendente Vencida", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(25));

        JsonNode page = listar(c.elderId(), "status=AGUARDANDO_APROVACAO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).doesNotContain(pendente.getId().toString());
        assertThat(page.path("totalItems").asInt()).isZero();
    }

    /** Vencido também não aparece na lista padrão (sem status) — já seria excluído por não ser ATIVO (AC-006.2). */
    @Test
    @DisplayName("AC-006.2: pedido vencido antes do job não aparece na lista sem status")
    void ac_006_2_pendente_vencido_nao_aparece_sem_status() throws Exception {
        Cenario c = cenarioMinimo();
        User pendente = membro(c.groupId(), c.responsavel(), "Pendente Vencida", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(25));

        JsonNode page = listar(c.elderId(), "size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).doesNotContain(pendente.getId().toString());
    }

    /** Fronteira: com exatamente 24 h o prazo já venceu (mesma regra de approve, AC-006.8) e o pedido some. */
    @Test
    @DisplayName("AC-006.2: aos 24h exatos o pedido já não aparece")
    void ac_006_2_pendente_aos_24h_exatos_nao_aparece() throws Exception {
        Cenario c = cenarioMinimo();
        User pendente = membro(c.groupId(), c.responsavel(), "Pendente Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(24));

        JsonNode page = listar(c.elderId(), "status=AGUARDANDO_APROVACAO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).doesNotContain(pendente.getId().toString());
    }

    /** Fronteira oposta: aos 23h59 ainda é vigente e aparece (AC-006.2). */
    @Test
    @DisplayName("AC-006.2: aos 23h59 o pedido ainda aparece")
    void ac_006_2_pendente_aos_23h59_aparece() throws Exception {
        Cenario c = cenarioMinimo();
        User pendente = membro(c.groupId(), c.responsavel(), "Pendente Limite", MembershipStatus.AGUARDANDO_APROVACAO);
        clock.advance(Duration.ofHours(23).plusMinutes(59));

        JsonNode page = listar(c.elderId(), "status=AGUARDANDO_APROVACAO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).containsExactly(pendente.getId().toString());
    }

    /** approvalDeadline é preenchido só para pendente; ATIVO, RECUSADO, EXPIRADO e REMOVIDO vêm sem (AC-006.2). */
    @Test
    @DisplayName("AC-006.2: approvalDeadline ausente/null fora de AGUARDANDO_APROVACAO")
    void ac_006_2_approval_deadline_so_para_pendente() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        for (MembershipStatus s : List.of(MembershipStatus.ATIVO, MembershipStatus.RECUSADO,
                MembershipStatus.EXPIRADO, MembershipStatus.REMOVIDO)) {
            // Cada iteração = um status não pendente.
            JsonNode page = listar(c.elderId(), "status=" + s + "&size=100", c.responsavel());
            assertThat(page.path("items")).as("itens de %s", s).isNotEmpty();
            for (JsonNode item : page.path("items")) {
                assertThat(item.hasNonNull("approvalDeadline")).as("%s sem prazo", s).isFalse();
            }
        }
    }

    // ============================================================ AC-009.5 removido

    /**
     * O Responsável, filtrando REMOVIDO, vê e-mail, telefone e endereço do removido (AC-009.5) —
     * ao contrário do Cuidador, que nem pode filtrar (teste 403 acima).
     */
    @Test
    @DisplayName("AC-009.5: Responsável com ?status=REMOVIDO vê e-mail, telefone e endereço do removido")
    void ac_009_5_responsavel_ve_contato_do_removido() throws Exception {
        Cenario c = cenarioComTodosOsStatus();

        JsonNode page = listar(c.elderId(), "status=REMOVIDO&size=100", c.responsavel());

        assertThat(idsDeUsuarios(page)).containsExactly(c.removido().getId().toString());
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("status").asText()).isEqualTo("REMOVIDO");
        assertThat(item.path("name").asText()).isEqualTo(c.removido().getName());
        assertThat(item.path("email").asText()).isEqualTo(c.removido().getEmail());
        assertThat(item.path("phone").asText()).isEqualTo(c.removido().getPhone());
        assertThat(item.path("address").path("street").asText()).isEqualTo(c.removido().getAddress().street());
    }

    /** Sem filtro, o removido nem entra na lista de nenhum viewer: nem o nome vaza (AC-009.5, AC-006.9). */
    @ParameterizedTest
    @EnumSource(value = Viewer.class)
    @DisplayName("AC-009.5: sem status o removido não aparece para nenhum viewer")
    void ac_009_5_removido_nao_aparece_sem_status(Viewer viewer) throws Exception {
        Cenario c = cenarioComTodosOsStatus();
        User quemConsulta = switch (viewer) {
            case CUIDADOR -> c.cuidador1();
            case FAMILIAR -> c.familiar1();
            case RESPONSAVEL -> c.responsavel();
        };

        // Cada linha = um papel de quem consulta.
        JsonNode page = listar(c.elderId(), "size=100", quemConsulta);

        assertThat(page.toString()).doesNotContain(c.removido().getEmail()).doesNotContain(c.removido().getPhone());
        assertThat(idsDeUsuarios(page)).doesNotContain(c.removido().getId().toString());
    }

    // ============================================================ paginação

    /**
     * Cinco ATIVO, size=2: três páginas (2,2,1), PageMeta coerente e a ordem por nome (sem
     * diferenciar caixa) atravessa as páginas sem repetir nem perder ninguém.
     */
    @Test
    @DisplayName("AC-006.9: paginação por nome (sem caixa) e depois id, com PageMeta do contrato")
    void ac_006_9_paginacao_ordenada_por_nome() throws Exception {
        Cenario c = cenarioMinimo(); // Responsável "Zélia Responsável"
        membro(c.groupId(), c.responsavel(), "bruno", MembershipStatus.ATIVO);
        membro(c.groupId(), c.responsavel(), "Ana", MembershipStatus.ATIVO);
        membro(c.groupId(), c.responsavel(), "Carla", MembershipStatus.ATIVO);
        membro(c.groupId(), c.responsavel(), "ana", MembershipStatus.ATIVO);
        // Esperado: Ana/ana (empate de nome ignorando caixa, desempate por id), bruno, Carla, Zélia.

        JsonNode p0 = listar(c.elderId(), "page=0&size=2", c.responsavel());
        JsonNode p1 = listar(c.elderId(), "page=1&size=2", c.responsavel());
        JsonNode p2 = listar(c.elderId(), "page=2&size=2", c.responsavel());

        // Etapa: PageMeta de cada página.
        assertThat(p0.path("page").asInt()).isZero();
        assertThat(p0.path("size").asInt()).isEqualTo(2);
        assertThat(p0.path("totalItems").asInt()).isEqualTo(5);
        assertThat(p0.path("totalPages").asInt()).isEqualTo(3);
        assertThat(p1.path("page").asInt()).isEqualTo(1);
        assertThat(p2.path("items")).hasSize(1);
        // Etapa: ordem nome (case-insensitive) -> id.
        List<String> nomes = new ArrayList<>();
        for (JsonNode p : List.of(p0, p1, p2)) {
            p.path("items").forEach(i -> nomes.add(i.path("name").asText()));
        }
        assertThat(nomes.stream().map(String::toLowerCase).toList())
                .containsExactly("ana", "ana", "bruno", "carla", "zélia responsável");
        // Etapa: desempate por id entre "Ana" e "ana".
        List<String> idsDosAna = new ArrayList<>();
        p0.path("items").forEach(i -> idsDosAna.add(i.path("id").asText()));
        assertThat(idsDosAna).isSorted();
    }

    /** page e size fora do intervalo são saneados (sem 400), como em GET /invitations. A requisição foge do contrato de propósito (defesa em profundidade), então não passa por matchesContract; só a resposta é validada (MemberPage). */
    @Test
    @DisplayName("AC-006.9: page negativo, size 0 e size 1000 são saneados, sem 400")
    void ac_006_9_paginacao_saneada() throws Exception {
        Cenario c = cenarioMinimo();

        // Etapa: page=-1 vira 0.
        assertThat(listarForaDoContrato(c.elderId(), "page=-1", c.responsavel()).path("page").asInt()).isZero();
        // Etapa: size=0 vira 1.
        assertThat(listarForaDoContrato(c.elderId(), "size=0", c.responsavel()).path("size").asInt()).isEqualTo(1);
        // Etapa: size=1000 vira 100.
        assertThat(listarForaDoContrato(c.elderId(), "size=1000", c.responsavel()).path("size").asInt()).isEqualTo(100);
    }

    // ============================================================ 401 / 403 / 404 e isolamento

    /** Sem token => 401 com Problem (contrato Unauthorized). */
    @Test
    @DisplayName("AC-006.9: sem token => 401 Problem")
    void ac_006_9_sem_token_401() throws Exception {
        Cenario c = cenarioMinimo();

        MvcResult r = mockMvc.perform(get(ELDERS + "/{elderId}/members", c.elderId()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    /** Responsável de OUTRO grupo no elderId do grupo A => 404 genérico (D-32), sem listar membros. */
    @Test
    @DisplayName("AC-006.9: Responsável de outro grupo recebe 404 (D-32)")
    void ac_006_9_outro_grupo_404() throws Exception {
        Cenario a = cenarioMinimo();
        Cenario b = cenarioMinimo();

        MvcResult r = mockMvc.perform(requisicao(a.elderId(), "", b.responsavel()))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(ContractSchemas.schemaFor("Problem")
                        .validate(json.readTree(r.getResponse().getContentAsString())))
                .isEmpty();
    }

    /** Cuidador de outro grupo, mesmo com ?status, recebe 404 (e não 403): não revela que o grupo existe (D-32). */
    @Test
    @DisplayName("AC-006.9: Cuidador de outro grupo, com ?status=REMOVIDO, recebe 404 e não 403")
    void ac_006_9_cuidador_de_outro_grupo_com_status_404() throws Exception {
        Cenario a = cenarioMinimo();
        Cenario b = cenarioMinimo();
        User cuidadorDeB = membro(b.groupId(), b.responsavel(), "Cuidador de B", MembershipStatus.ATIVO);

        mockMvc.perform(requisicao(a.elderId(), "status=REMOVIDO", cuidadorDeB))
                .andExpect(status().isNotFound());
    }

    /** elderId inexistente para um membro ATIVO => 404 (D-32). */
    @Test
    @DisplayName("AC-006.9: elderId inexistente => 404")
    void ac_006_9_elder_inexistente_404() throws Exception {
        Cenario c = cenarioMinimo();

        mockMvc.perform(requisicao(UUID.randomUUID(), "", c.responsavel())).andExpect(status().isNotFound());
    }

    /** Cada grupo lista só os próprios membros: nenhum id/e-mail do grupo B aparece na lista do A (isolamento). */
    @Test
    @DisplayName("AC-006.9: a lista de um grupo não contém membros de outro grupo")
    void ac_006_9_isolamento_entre_grupos() throws Exception {
        Cenario a = cenarioMinimo();
        Cenario b = cenarioMinimo();
        User deB = membro(b.groupId(), b.responsavel(), "Membro de B", MembershipStatus.ATIVO);
        User deA = membro(a.groupId(), a.responsavel(), "Membro de A", MembershipStatus.ATIVO);

        JsonNode paginaA = listar(a.elderId(), "size=100", a.responsavel());

        assertThat(idsDeUsuarios(paginaA)).contains(deA.getId().toString())
                .doesNotContain(deB.getId().toString(), b.responsavel().getId().toString());
        assertThat(paginaA.path("totalItems").asInt()).isEqualTo(2);
    }

    // ============================================================ reforços T-043 (desempate e estados de conta)

    /**
     * Reforço do desempate da ordenação ({@code order by lower(u.name), m.id} em {@code
     * MembershipRepository#findMembersPage}). Quatro membros com o MESMO nome ignorando caixa
     * ("Ana", "ana", "ANA", "aNa") têm ids controlados e são gravados FORA da ordem dos ids
     * (4, 2, 3, 1), de modo que a ordem física/de inserção difere da ordem por id. Se o {@code
     * m.id} sair do ORDER BY, o banco devolve a ordem de inserção e este teste quebra. O
     * Responsável "Zélia" fica por último pelo nome.
     */
    @Test
    @DisplayName("AC-006.9: nomes iguais ignorando caixa são desempatados por id crescente, não pela ordem de inserção")
    void ac_006_9_desempate_por_id_com_nomes_iguais_ignorando_caixa() throws Exception {
        Cenario c = cenarioMinimo();
        // Ids únicos por execução (a base é aleatória), com o final 1..4 definindo a ordem esperada.
        long base = UUID.randomUUID().getMostSignificantBits();
        UUID id1 = new UUID(base, 1L);
        UUID id2 = new UUID(base, 2L);
        UUID id3 = new UUID(base, 3L);
        UUID id4 = new UUID(base, 4L);
        // Etapa: inserção em ordem embaralhada, nomes que diferem só na caixa.
        User u4 = membroComId(c.groupId(), c.responsavel(), "aNa", id4);
        User u2 = membroComId(c.groupId(), c.responsavel(), "ana", id2);
        User u3 = membroComId(c.groupId(), c.responsavel(), "ANA", id3);
        User u1 = membroComId(c.groupId(), c.responsavel(), "Ana", id1);

        // Etapa: a ordem devolvida é (lower(name), membership id), com Zélia por último.
        JsonNode page = listar(c.elderId(), "size=100", c.responsavel());

        List<String> membershipIds = new ArrayList<>();
        page.path("items").forEach(i -> membershipIds.add(i.path("id").asText()));
        assertThat(membershipIds.subList(0, 4))
                .containsExactly(id1.toString(), id2.toString(), id3.toString(), id4.toString());
        assertThat(idsDeUsuarios(page).subList(0, 4)).containsExactly(
                u1.getId().toString(), u2.getId().toString(), u3.getId().toString(), u4.getId().toString());
        assertThat(page.path("items").get(4).path("name").asText()).isEqualTo("Zélia Responsável");
    }

    /**
     * O desempate também vale ENTRE páginas: com size=2 os quatro "ana" se dividem em duas páginas
     * e a concatenação mantém a ordem por id, sem repetir nem perder ninguém.
     */
    @Test
    @DisplayName("AC-006.9: desempate por id é estável entre páginas")
    void ac_006_9_desempate_por_id_estavel_entre_paginas() throws Exception {
        Cenario c = cenarioMinimo();
        long base = UUID.randomUUID().getMostSignificantBits();
        List<UUID> ids = List.of(
                new UUID(base, 1L),
                new UUID(base, 2L),
                new UUID(base, 3L),
                new UUID(base, 4L));
        // Inserção em ordem inversa dos ids.
        for (int i = ids.size() - 1; i >= 0; i--) {
            membroComId(c.groupId(), c.responsavel(), i % 2 == 0 ? "Ana" : "ana", ids.get(i));
        }

        List<String> vistos = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            // Cada iteração = uma página de 2 itens (as duas primeiras só têm "ana").
            listar(c.elderId(), "page=" + p + "&size=2", c.responsavel())
                    .path("items").forEach(i -> vistos.add(i.path("id").asText()));
        }

        assertThat(vistos).containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
    }

    /**
     * Estados de conta que NÃO chegam ao handler da listagem. Comportamento real do {@code
     * AccountSecurityInterceptor}: rota padrão exige conta {@code ATIVO} com grupo; quem está
     * {@code AGUARDANDO_APROVACAO} não satisfaz e recebe 403 {@code ACCOUNT_STATE}; {@code
     * REMOVIDO} é tratado como sessão inválida (401, sem {@code code}, AC-009.2).
     */
    @Test
    @DisplayName("AC-006.9: membro AGUARDANDO_APROVACAO recebe 403 ACCOUNT_STATE na listagem")
    void ac_006_9_pendente_nao_lista_403_account_state() throws Exception {
        Cenario c = cenarioMinimo();
        User pendente = membro(c.groupId(), c.responsavel(), "Pendente Curiosa", MembershipStatus.AGUARDANDO_APROVACAO);

        JsonNode problema = esperar403(c.elderId(), "", pendente);

        assertThat(problema.path("code").asText()).isEqualTo("ACCOUNT_STATE");
        assertThat(problema.toString()).doesNotContain(c.responsavel().getEmail());
    }

    /** Membro REMOVIDO perde a sessão: 401 Problem sem {@code code} e sem dados do grupo (AC-009.2). */
    @Test
    @DisplayName("AC-006.9: membro REMOVIDO recebe 401 na listagem")
    void ac_006_9_removido_nao_lista_401() throws Exception {
        Cenario c = cenarioMinimo();
        User removido = membro(c.groupId(), c.responsavel(), "Removido Curioso", MembershipStatus.REMOVIDO);

        MvcResult r = mockMvc.perform(requisicao(c.elderId(), "", removido))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(body.has("code")).isFalse();
    }

    /** Conta com membership ATIVO (CUIDADOR) de id escolhido, para provar o desempate por id. */
    private User membroComId(UUID groupId, User responsavel, String nome, UUID membershipId) {
        User user = conta(nome);
        Membership m = Membership.request(groupId, user.getId(), clock);
        m.approve(Role.CUIDADOR, responsavel.getId(), clock);
        org.springframework.test.util.ReflectionTestUtils.setField(m, "id", membershipId);
        memberships.save(m);
        return user;
    }

    // ============================================================ fábricas de cenário

    private record Cenario(
            User responsavel, UUID groupId, UUID elderId,
            User familiar1, User familiar2, User cuidador1, User cuidador2,
            User pendente, User recusado, User expirado, User removido) {}

    /** Grupo só com o Responsável "Zélia Responsável". */
    private Cenario cenarioMinimo() {
        Grupo g = grupoComIdoso();
        return new Cenario(g.responsavel(), g.groupId(), g.elderId(),
                null, null, null, null, null, null, null, null);
    }

    /** Responsável, 2 Familiares e 2 Cuidadores ATIVO, cada um com contato próprio e distinto. */
    private Cenario cenarioDaMatriz() {
        Grupo g = grupoComIdoso();
        User f1 = membroComPapel(g, "Familiar Um", Role.FAMILIAR);
        User f2 = membroComPapel(g, "Familiar Dois", Role.FAMILIAR);
        User c1 = membroComPapel(g, "Cuidador Um", Role.CUIDADOR);
        User c2 = membroComPapel(g, "Cuidador Dois", Role.CUIDADOR);
        return new Cenario(g.responsavel(), g.groupId(), g.elderId(), f1, f2, c1, c2,
                null, null, null, null);
    }

    /**
     * Responsável + Familiar + Cuidador ATIVO e um membro em cada outro estado. Os pendentes são
     * criados DEPOIS do avanço do relógio do expirado, para o pendente ficar vigente.
     */
    private Cenario cenarioComTodosOsStatus() {
        Grupo g = grupoComIdoso();
        User f1 = membroComPapel(g, "Familiar Um", Role.FAMILIAR);
        User c1 = membroComPapel(g, "Cuidador Um", Role.CUIDADOR);
        User recusado = membro(g.groupId(), g.responsavel(), "Recusada", MembershipStatus.RECUSADO);
        User removido = membro(g.groupId(), g.responsavel(), "Removido", MembershipStatus.REMOVIDO);
        User expirado = membro(g.groupId(), g.responsavel(), "Expirada", MembershipStatus.EXPIRADO);
        User pendente = membro(g.groupId(), g.responsavel(), "Pendente", MembershipStatus.AGUARDANDO_APROVACAO);
        return new Cenario(g.responsavel(), g.groupId(), g.elderId(), f1, null, c1, null,
                pendente, recusado, expirado, removido);
    }

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private Grupo grupoComIdoso() {
        try {
            User responsavel = conta("Zélia Responsável");
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

    private User membroComPapel(Grupo g, String nome, Role papel) {
        User user = conta(nome);
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        m.approve(papel, g.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    /** Conta com membership no status pedido; papel CUIDADOR quando aprovada. */
    private User membro(UUID groupId, User responsavel, String nome, MembershipStatus statusFinal) {
        User user = conta(nome);
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
                // Persistido pendente, relógio avança 25h e só então expira (o relógio fica adiante
                // para os demais; por isso os pendentes vigentes são criados depois dele).
                memberships.save(m);
                clock.advance(Duration.ofHours(25));
                m.expire(clock);
            }
        }
        memberships.save(m);
        return user;
    }

    /** Conta ATIVO com e-mail, telefone e rua únicos, para a matriz distinguir os valores. */
    private User conta(String nome) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, "membros-" + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
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

    // ============================================================ helpers HTTP

    private MockHttpServletRequestBuilder requisicao(UUID elderId, String query, User autor) {
        String sufixo = query == null || query.isEmpty() ? "" : "?" + query;
        return get(ELDERS + "/{elderId}/members" + sufixo, elderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
    }

    /** GET 200 validado contra o contrato (MemberPage); devolve o corpo. */
    private JsonNode listar(UUID elderId, String query, User autor) throws Exception {
        MvcResult r = mockMvc.perform(requisicao(elderId, query, autor))
                .andExpect(status().isOk())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /**
     * GET 200 SEM validar a requisição contra o contrato (query fora do intervalo do openapi, que o
     * validador rejeitaria); valida só a resposta contra o schema MemberPage.
     */
    private JsonNode listarForaDoContrato(UUID elderId, String query, User autor) throws Exception {
        MvcResult r = mockMvc.perform(requisicao(elderId, query, autor))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("MemberPage").validate(body)).isEmpty();
        return body;
    }

    /** GET que deve dar 403 com Problem válido; devolve o corpo. */
    private JsonNode esperar403(UUID elderId, String query, User autor) throws Exception {
        MvcResult r = mockMvc.perform(requisicao(elderId, query, autor))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        return body;
    }

    private JsonNode itemDe(JsonNode page, User user) {
        for (JsonNode item : page.path("items")) {
            if (item.path("userId").asText().equals(user.getId().toString())) {
                return item;
            }
        }
        throw new AssertionError("usuário " + user.getId() + " não está na lista");
    }

    private List<String> idsDeUsuarios(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(i -> ids.add(i.path("userId").asText()));
        return ids;
    }

    private List<String> statusDosItens(JsonNode page) {
        List<String> s = new ArrayList<>();
        page.path("items").forEach(i -> s.add(i.path("status").asText()));
        return s;
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    private static Instant instante(JsonNode body, String campo) {
        return OffsetDateTime.parse(body.path(campo).asText()).toInstant();
    }
}
