package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-040 - casos de borda dos convites do Responsavel que complementam {@link InvitationControllerTest}:
 * (1) cancelar um convite gravado como ENVIADO mas ja vencido pelo Clock (AC-003.4: so ENVIADO vigente
 * e cancelavel), (2) reenviar esse mesmo convite vencido (AC-003.4; o dominio, em {@code
 * Invitation.resend}, nao altera um ENVIADO cujo prazo ja passou) e (3) a normalizacao do e-mail
 * (strip + minusculas, D-34 esclarecimento 1) na criacao do convite, no contador {@code
 * invite_code_attempts}, no reenvio por e-mail repetido (AC-003.12) e na deteccao de ALREADY_MEMBER
 * (AC-003.5).
 *
 * <p>Caixa-preta pela rota HTTP real, com contrato validado; convites e contador sao semeados e lidos
 * por {@link JdbcTemplate}. O {@link br.com.cuidamais.shared.testsupport.MutableClock} (D-10) fixa o agora.
 */
@AutoConfigureMockMvc
class InvitationEdgeCasesTest extends IntegrationTest {

    private static final String ELDERS = "/api/v1/elders";

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

    /**
     * ENVIADO com expires_at no passado (job de D-09 ainda nao rodou) nao e mais cancelavel: o Clock o
     * torna VENCIDO, entao cancelar responde 409 INVITATION_NOT_CANCELLABLE conforme o contrato
     * (Problem) e o status gravado permanece ENVIADO (nada foi escrito).
     */
    @Test
    @DisplayName("AC-003.4: cancelar ENVIADO ja vencido pelo Clock => 409 INVITATION_NOT_CANCELLABLE")
    void ac_003_4_cancelar_enviado_vencido_pelo_clock_409() throws Exception {
        Grupo grupo = grupoComIdoso();
        UUID convite = semearConvite(grupo, emailNovo(), "ENVIADO", clock.instant().minusSeconds(1));

        MvcResult result = mockMvc.perform(post(ELDERS + "/{e}/invitations/{id}/cancel", grupo.elderId(), convite)
                        .header(HttpHeaders.AUTHORIZATION, bearer(grupo.responsavel())))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("INVITATION_NOT_CANCELLABLE");
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(statusDe(convite)).as("nada foi escrito").isEqualTo("ENVIADO");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM invitations WHERE id = ?", Timestamp.class, convite))
                .isNull();
    }

    /**
     * Reenviar um ENVIADO ja vencido pelo Clock e permitido (201): nasce um convite novo ENVIADO com
     * codigo (hash) e prazo de 7 dias novos e tentativas zeradas; o anterior NAO e alterado pelo
     * dominio ({@code Invitation.resend}), ou seja, segue gravado como ENVIADO e sem cancelled_at.
     */
    @Test
    @DisplayName("AC-003.4: reenviar ENVIADO ja vencido pelo Clock => 201 novo ENVIADO zerado; anterior inalterado")
    void ac_003_4_reenviar_enviado_vencido_pelo_clock_201() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = emailNovo();
        UUID anterior = semearConvite(grupo, email, "ENVIADO", clock.instant().minusSeconds(1));
        String hashAnterior = hashDe(anterior);
        jdbc.update("INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                + " VALUES (?, 3, NULL, 1, ?)", email, Timestamp.from(clock.instant()));

        MvcResult result = mockMvc.perform(post(ELDERS + "/{e}/invitations/{id}/resend", grupo.elderId(), anterior)
                        .header(HttpHeaders.AUTHORIZATION, bearer(grupo.responsavel())))
                .andExpect(status().isCreated())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();

        // Etapa: o convite novo e ENVIADO, com prazo de 7 dias, contador zerado e codigo novo.
        JsonNode novo = json.readTree(result.getResponse().getContentAsString());
        UUID novoId = UUID.fromString(novo.path("id").asText());
        assertThat(novoId).isNotEqualTo(anterior);
        assertThat(novo.path("status").asText()).isEqualTo("ENVIADO");
        assertThat(OffsetDateTime.parse(novo.path("expiresAt").asText()).toInstant())
                .isCloseTo(clock.instant().plus(Duration.ofDays(7)), within(1, ChronoUnit.SECONDS));
        assertThat(novo.path("failedAttempts").asInt()).isZero();
        assertThat(novo.path("blockedUntil").isNull()).isTrue();
        assertThat(hashDe(novoId)).isNotEqualTo(hashAnterior);

        // Etapa: o anterior permanece como estava (o dominio so cancela ENVIADO dentro do prazo).
        assertThat(statusDe(anterior)).isEqualTo("ENVIADO");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM invitations WHERE id = ?", Timestamp.class, anterior))
                .isNull();
        assertThat(hashDe(anterior)).isEqualTo(hashAnterior);
    }

    /**
     * D-34 esclarecimento 1: "  Ana@X.com " (espacos nas pontas + caixa mista) deve ser aceito e gravado
     * normalizado (sem espacos, minusculo) tanto em invitations.email quanto na linha de
     * invite_code_attempts. Cada etapa cobre uma das duas tabelas.
     */
    @Test
    @DisplayName("AC-003.12 / D-34: '  Ana@X.com ' => 201 e convite + invite_code_attempts gravados com o e-mail normalizado")
    void ac_003_12_email_sujo_e_gravado_normalizado() throws Exception {
        Grupo grupo = grupoComIdoso();
        String normalizado = "ana-" + UUID.randomUUID() + "@x.com";
        String sujo = "  " + normalizado.replace("ana-", "Ana-").replace("@x", "@X") + " ";

        // Etapa 1: o convite e criado e o e-mail devolvido ja esta normalizado.
        MvcResult res = mockMvc.perform(convidar(grupo, sujo)).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(res.getResponse().getContentAsString()).path("email").asText())
                .isEqualTo(normalizado);

        // Etapa 2: a tabela de convites tem 1 linha com o e-mail normalizado.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitations WHERE email = ?", Integer.class, normalizado))
                .isEqualTo(1);

        // Etapa 3: o contador tem exatamente 1 linha, com o e-mail normalizado.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM invite_code_attempts WHERE email = ?", Integer.class, normalizado))
                .isEqualTo(1);
    }

    /**
     * AC-003.12: convidar "ANA-x@X.COM" e depois a forma minuscula e o mesmo e-mail (D-34): o segundo e
     * tratado como reenvio (201), o primeiro vira CANCELADO, o novo e ENVIADO e so existe 1 linha de contador.
     */
    @Test
    @DisplayName("AC-003.12 / D-34: convite com caixa diferente do ENVIADO anterior = reenvio (anterior CANCELADO)")
    void ac_003_12_caixa_diferente_e_tratada_como_reenvio() throws Exception {
        Grupo grupo = grupoComIdoso();
        String normalizado = "ana-" + UUID.randomUUID() + "@x.com";

        MvcResult primeiro = mockMvc.perform(convidar(grupo, normalizado.toUpperCase()))
                .andExpect(status().isCreated()).andReturn();
        JsonNode corpo1 = json.readTree(primeiro.getResponse().getContentAsString());
        MvcResult segundo = mockMvc.perform(convidar(grupo, normalizado)).andExpect(status().isCreated()).andReturn();
        JsonNode corpo2 = json.readTree(segundo.getResponse().getContentAsString());

        assertThat(statusDe(UUID.fromString(corpo1.path("id").asText()))).isEqualTo("CANCELADO");
        assertThat(corpo2.path("status").asText()).isEqualTo("ENVIADO");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM invite_code_attempts WHERE email = ?", Integer.class, normalizado))
                .isEqualTo(1);
    }

    /**
     * AC-003.5 / D-34: membro ATIVO com e-mail minusculo e convidado com o e-mail em CAIXA ALTA =>
     * 409 ALREADY_MEMBER (a comparacao e feita sobre o e-mail normalizado) e nenhum convite e criado.
     */
    @Test
    @DisplayName("AC-003.5 / D-34: ALREADY_MEMBER e detectado mesmo com caixa diferente do e-mail do membro")
    void ac_003_5_already_member_com_caixa_diferente() throws Exception {
        Grupo grupo = grupoComIdoso();
        User membro = contaAtivaSemGrupo();
        memberships.save(approved(grupo, membro));

        MvcResult conflito = mockMvc.perform(convidar(grupo, membro.getEmail().toUpperCase()))
                .andExpect(status().isConflict()).andReturn();

        JsonNode problema = json.readTree(conflito.getResponse().getContentAsString());
        assertThat(problema.path("code").asText()).isEqualTo("ALREADY_MEMBER");
        assertThat(ContractSchemas.schemaFor("InvitationConflictProblem").validate(problema)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM invitations WHERE email = ?", Integer.class, membro.getEmail()))
                .isZero();
    }

    // ============================================================ helpers

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private Membership approved(Grupo grupo, User user) {
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        m.approve(Role.CUIDADOR, grupo.responsavel().getId(), clock);
        return m;
    }

    private MockHttpServletRequestBuilder convidar(Grupo grupo, String email) throws Exception {
        return post(ELDERS + "/{e}/invitations", grupo.elderId())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(grupo.responsavel()))
                .content(json.writeValueAsString(Map.of("email", email)));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issue(user.getId(), clock);
    }

    private String statusDe(UUID id) {
        return jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, id);
    }

    private String hashDe(UUID id) {
        return jdbc.queryForObject("SELECT code_hash FROM invitations WHERE id = ?", String.class, id);
    }

    private UUID semearConvite(Grupo grupo, String email, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, "hash-semeado-" + id, status, Timestamp.from(expiresAt),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    private Grupo grupoComIdoso() {
        try {
            User responsavel = contaAtivaSemGrupo();
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, bearer(responsavel))
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

    private User contaAtivaSemGrupo() {
        User user = User.signup(
                "Maria da Silva", emailNovo(), "21987654321",
                new Endereco("22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ"),
                "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return "borda-" + UUID.randomUUID() + "@teste.com";
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
