package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-041 - equalizacao de custo (P5 / R-07 / R-03) de {@code POST /invitations/redeem}: o numero de
 * chamadas a {@code PasswordEncoder.matches} (o BCrypt caro) nao pode revelar se o e-mail tem convite.
 * Determinista: um {@code @MockitoSpyBean} sobre o {@link PasswordEncoder} real conta as chamadas, sem
 * medir tempo. Cobre: (a) e-mail sem convite faz >= 1 chamada (hash fantasma), (b) conta em estado
 * incompatível com codigo errado faz >= 1 chamada e (c) o caso (a) tem exatamente o mesmo numero de
 * chamadas que um e-mail com 1 convite vigente e codigo errado.
 *
 * <p>Classe propria porque o spy muda a configuracao do contexto de teste.
 */
@AutoConfigureMockMvc
class InvitationRedeemTimingEqualizationTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String ELDERS = "/api/v1/elders";
    private static final String CODIGO = "123456";
    private static final String CODIGO_ERRADO = "654321";

    private final ObjectMapper json = new ObjectMapper();
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

    @MockitoSpyBean
    PasswordEncoder encoder;

    @BeforeEach
    void prepara() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ip = "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    /** Sanidade: o bean injetado e de fato um spy (senao as contagens abaixo seriam vacuas). */
    @Test
    @DisplayName("R-07: o PasswordEncoder do contexto e um spy do Mockito")
    void r_07_encoder_e_spy() {
        assertThat(mockingDetails(encoder).isSpy()).isTrue();
    }

    /**
     * E-mail sem nenhum convite + codigo errado: ainda assim o servico deve gastar um BCrypt.matches
     * (contra o hash fantasma), resposta 400 como qualquer codigo errado (AC-004.7).
     */
    @Test
    @DisplayName("R-07 / P5: e-mail sem convite com codigo errado chama matches pelo menos 1 vez")
    void r_07_email_sem_convite_gasta_bcrypt() throws Exception {
        String email = emailNovo();
        clearInvocations(encoder);

        mockMvc.perform(resgatar(email, CODIGO_ERRADO)).andExpect(status().isBadRequest());

        assertThat(chamadasAMatches()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Conta ATIVA do grupo (estado incompatível com REINSTATED) com convite vigente e codigo ERRADO:
     * o BCrypt.matches e executado contra o hash do convite (>= 1 chamada) e a resposta e o 400 generico.
     */
    @Test
    @DisplayName("R-07 / P5: conta em estado incompativel com codigo errado chama matches pelo menos 1 vez")
    void r_07_conta_incompativel_gasta_bcrypt() throws Exception {
        Grupo grupo = grupoComIdoso();
        User ativo = contaAtivaSemGrupo();
        Membership m = Membership.request(grupo.groupId(), ativo.getId(), clock);
        m.approve(Role.CUIDADOR, grupo.responsavel().getId(), clock);
        memberships.save(m);
        semearConviteVigente(grupo, ativo.getEmail());
        clearInvocations(encoder);

        mockMvc.perform(resgatar(ativo.getEmail(), CODIGO_ERRADO)).andExpect(status().isBadRequest());

        assertThat(chamadasAMatches()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Equalizacao: o numero de chamadas a matches para e-mail SEM convite e IGUAL ao de um e-mail com
     * exatamente 1 convite vigente, ambos com codigo errado - nenhum canal de contagem revela existencia.
     */
    @Test
    @DisplayName("R-07 / P5: matches e chamado o mesmo numero de vezes com 0 convites e com 1 convite vigente (codigo errado)")
    void r_07_mesmo_numero_de_matches_com_e_sem_convite() throws Exception {
        Grupo grupo = grupoComIdoso();
        String semConvite = emailNovo();
        String comConvite = emailNovo();
        semearConviteVigente(grupo, comConvite);

        // Etapa 1: e-mail sem convite.
        clearInvocations(encoder);
        mockMvc.perform(resgatar(semConvite, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        int semConviteChamadas = chamadasAMatches();

        // Etapa 2: e-mail com 1 convite vigente.
        clearInvocations(encoder);
        mockMvc.perform(resgatar(comConvite, CODIGO_ERRADO)).andExpect(status().isBadRequest());
        int comConviteChamadas = chamadasAMatches();

        assertThat(semConviteChamadas).isGreaterThanOrEqualTo(1);
        assertThat(semConviteChamadas).isEqualTo(comConviteChamadas);
    }

    // ============================================================ helpers

    private int chamadasAMatches() {
        return (int) mockingDetails(encoder).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("matches"))
                .count();
    }

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resgatar(
            String email, String codigo) throws Exception {
        return post(REDEEM)
                .contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content(json.writeValueAsString(Map.of("email", email, "code", codigo)));
    }

    private void semearConviteVigente(Grupo grupo, String email) {
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                UUID.randomUUID(), grupo.groupId(), email, encoder.encode(CODIGO),
                Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
    }

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

    private User contaAtivaSemGrupo() {
        User user = User.signup(
                "Maria da Silva", emailNovo(), "21987654321",
                new Endereco("22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ"),
                encoder.encode("Senha@Forte123"), clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return "timing-" + UUID.randomUUID() + "@teste.com";
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
