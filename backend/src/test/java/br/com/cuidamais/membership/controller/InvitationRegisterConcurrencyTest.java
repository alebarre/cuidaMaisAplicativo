package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-042 / R-08 / D-16 - concorrencia em {@code POST /api/v1/invitations/register}: o token de
 * cadastro e de uso unico e a ordem global de locks ({@code invite_code_attempts -> invitations ->
 * memberships}) evita deadlock com {@code resend}/{@code cancel} do Responsavel.
 *
 * <p>Falhas que a classe reproduz: (a) dois registers do mesmo token criando dois usuarios ou
 * duas memberships, ou estourando a unique de {@code users.email} como 500; (b) deadlock entre o
 * register e um cancel/resend do mesmo convite, que o banco resolve abortando uma transacao
 * (500).
 *
 * <p>Natureza nao deterministica: o resultado depende do entrelacamento das threads. Para tornar a
 * falha confiavel sem a correcao, as threads largam juntas numa barreira ({@link CountDownLatch}),
 * ha varias threads por rodada e cada cenario e repetido algumas rodadas com e-mails e convites
 * novos; qualquer violacao reprova o teste. Com a correcao o resultado e deterministico nos
 * invariantes verificados (um unico vencedor; nenhum 500). Sem a correcao a taxa de falha e alta,
 * mas nao e 100%. O limite de redeem por IP (R-03) e neutralizado por propriedade.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.invitations.redeem-rate-limit.max-requests=100000",
    "app.invitations.redeem-rate-limit.window=PT1M"
})
class InvitationRegisterConcurrencyTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String REGISTER = "/api/v1/invitations/register";
    private static final String ELDERS = "/api/v1/elders";
    private static final String SENHA = "Senha@Forte123";
    private static final String CODIGO = "123456";
    private static final String ASSUNTO_PEDIDO = "Cuida+: pedido de entrada no grupo aguarda sua decisão";
    private static final int REGISTERS_SIMULTANEOS = 8;
    private static final int RODADAS = 4;

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Autowired
    PasswordEncoder encoder;

    /**
     * R-08 / D-16: 8 registers simultaneos com o MESMO token, repetido em 4 rodadas com convites
     * novos. Em cada rodada: exatamente um 201, os outros sete 401, nenhum 500, um unico usuario e
     * uma unica membership para o e-mail, convite USADO e um unico e-mail ao Responsavel.
     */
    @Test
    @DisplayName("R-08/D-16: 8 registers simultâneos com o mesmo token => exatamente um 201, sete 401, 1 usuário, 1 membership, 1 e-mail, sem 500")
    void r_08_registers_simultaneos_com_o_mesmo_token_criam_uma_unica_conta() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            // Etapa: convite novo, resgatado de verdade para obter o token.
            mailpit.deleteAll();
            Grupo grupo = grupoComIdoso();
            String email = "conc-" + UUID.randomUUID() + "@teste.com";
            UUID convite = semearConvite(grupo, email);
            String token = resgatar(email, rodada);

            List<Integer> codigos = dispararRegisters(REGISTERS_SIMULTANEOS, token, null);

            String ctx = "rodada " + rodada + ", respostas=" + codigos;
            assertThat(codigos).as("sem 500 - %s", ctx).allMatch(s -> s == 201 || s == 401);
            assertThat(codigos.stream().filter(s -> s == 201).count()).as("um único 201 - %s", ctx).isEqualTo(1);
            assertThat(codigos.stream().filter(s -> s == 401).count()).as("sete 401 - %s", ctx).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                    .as("um usuário - %s", ctx).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM memberships m JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                            Integer.class, email))
                    .as("uma membership - %s", ctx).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, convite))
                    .as("convite USADO - %s", ctx).isEqualTo("USADO");
            // Etapa: o vencedor dispara um unico e-mail ao Responsavel (AC-006.7).
            String responsavel = grupo.responsavel().getEmail();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(mailpit.messagesTo(responsavel))
                    .filteredOn(s -> ASSUNTO_PEDIDO.equals(s.subject()))
                    .hasSize(1));
        }
    }

    /**
     * R-08: register concorrente com {@code cancel} do mesmo convite (4 registers + 1 cancel por
     * rodada, 4 rodadas). Ambos os desfechos sao validos, mas nunca 500 (deadlock) e o estado final
     * e coerente: se algum register deu 201, o convite e USADO e ha um usuario; senao o convite e
     * CANCELADO e nao ha usuario.
     */
    @Test
    @DisplayName("R-08: register concorrente com cancel do mesmo convite => sem 500 por deadlock e estado final coerente (USADO+conta ou CANCELADO sem conta)")
    void r_08_register_concorrente_com_cancel_nao_gera_deadlock() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            verificaRegisterContraAcaoDoResponsavel("cancel", rodada);
        }
    }

    /**
     * R-08: register concorrente com {@code resend} (cancela o convite antigo e cria outro).
     * Mesmos invariantes: sem 500; convite do token USADO se algum register venceu, senao
     * CANCELADO (o resend o cancelou).
     */
    @Test
    @DisplayName("R-08: register concorrente com resend do mesmo convite => sem 500 por deadlock e estado final coerente")
    void r_08_register_concorrente_com_resend_nao_gera_deadlock() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            verificaRegisterContraAcaoDoResponsavel("resend", rodada);
        }
    }

    // ============================================================ apoio

    private void verificaRegisterContraAcaoDoResponsavel(String acao, int rodada) throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = "conc-" + UUID.randomUUID() + "@teste.com";
        UUID convite = semearConvite(grupo, email);
        String token = resgatar(email, rodada);
        String bearer = "Bearer " + jwtService.issue(grupo.responsavel().getId(), clock);
        Callable<Integer> acaoDoResponsavel = () -> {
            try {
                return mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/" + acao, grupo.elderId(), convite)
                                .header(HttpHeaders.AUTHORIZATION, bearer))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception e) {
                return 500;
            }
        };

        List<Integer> codigos = dispararRegisters(4, token, acaoDoResponsavel);

        // O ultimo item e o da acao do Responsavel; os demais sao os registers.
        int statusAcao = codigos.getLast();
        List<Integer> registers = codigos.subList(0, codigos.size() - 1);
        String ctx = acao + ", rodada " + rodada + ", registers=" + registers + ", acao=" + statusAcao;
        assertThat(statusAcao).as("ação do Responsável sem 5xx - %s", ctx).isLessThan(500);
        assertThat(registers).as("registers sem 500 - %s", ctx).allMatch(s -> s == 201 || s == 401);
        long criados = registers.stream().filter(s -> s == 201).count();
        assertThat(criados).as("no máximo um 201 - %s", ctx).isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .as("usuários == 201s - %s", ctx).isEqualTo((int) criados);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, convite))
                .as("estado final do convite - %s", ctx)
                .isEqualTo(criados == 1 ? "USADO" : "CANCELADO");
    }

    /** Chama o redeem real (IP distinto por chamada) e devolve o registrationToken. */
    private String resgatar(String email, int n) throws Exception {
        String ip = "10.77." + (n % 250) + "." + (int) (Math.random() * 250 + 1);
        MvcResult r = mockMvc.perform(post(REDEEM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(json.writeValueAsString(Map.of("email", email, "code", CODIGO))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("registrationToken").asText();
    }

    private Map<String, Object> corpo(String token) {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("registrationToken", token);
        c.put("name", "Joana Souza");
        c.put("phone", "21912345678");
        c.put("address", end);
        c.put("password", "Convidada#2026");
        return c;
    }

    /**
     * Dispara {@code n} registers simultaneos (largada unica por barreira) com o mesmo token e,
     * opcionalmente, mais uma acao concorrente (ultimo item da lista devolvida). Excecao nao
     * tratada pelo MockMvc conta como 500.
     */
    private List<Integer> dispararRegisters(int n, String token, Callable<Integer> extra) throws Exception {
        int total = n + (extra == null ? 0 : 1);
        ExecutorService pool = Executors.newFixedThreadPool(total);
        try {
            CountDownLatch pronto = new CountDownLatch(total);
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            String corpoJson = json.writeValueAsString(corpo(token));
            for (int i = 0; i < n; i++) {
                futuros.add(pool.submit(() -> {
                    pronto.countDown();
                    largada.await();
                    try {
                        return mockMvc.perform(post(REGISTER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(corpoJson))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    } catch (Exception e) {
                        return 500;
                    }
                }));
            }
            if (extra != null) {
                futuros.add(pool.submit(() -> {
                    pronto.countDown();
                    largada.await();
                    return extra.call();
                }));
            }
            assertThat(pronto.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<Integer> codigos = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                codigos.add(f.get(60, TimeUnit.SECONDS));
            }
            return codigos;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    /** Semeia convite ENVIADO vigente (7 dias) com o hash real do codigo (D-04). */
    private UUID semearConvite(Grupo grupo, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, encoder.encode(CODIGO),
                Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    /** Responsavel ATIVO que cria o idoso (e o grupo) pela API real. */
    private Grupo grupoComIdoso() throws Exception {
        User user = User.signup("Maria da Silva", "resp-" + UUID.randomUUID() + "@teste.com", "21987654321",
                new Endereco("22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ"),
                encoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        User responsavel = users.save(user);

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
