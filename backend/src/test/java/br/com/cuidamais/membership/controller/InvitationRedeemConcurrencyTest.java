package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
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
 * T-041 / R-03 — concorrência sobre {@code invite_code_attempts} em {@code POST
 * /api/v1/invitations/redeem}: a proteção contra força bruta (AC-003.3: 5 erros por e-mail =&gt;
 * 423; AC-004.7: mesma resposta com ou sem convite; D-34: contador por e-mail) não pode ser
 * burlada por pedidos simultâneos.
 *
 * <p>Falhas que a classe reproduz: (a) atualização perdida do contador (duas transações leem o
 * mesmo {@code failed_attempts} e gravam o mesmo valor +1), deixando passar mais de 5 palpites
 * antes do bloqueio; (b) criação concorrente da 1ª linha do e-mail, que viola a PK e vira 500.
 *
 * <p>Natureza não determinística: o resultado depende do entrelaçamento das threads. Para tornar
 * a falha confiável sem a correção, todas as threads largam juntas numa barreira ({@link
 * CountDownLatch}), há muitas threads (20/10), e o cenário 1 é repetido {@value #REPETICOES_E_MAIL_NOVO}
 * vezes com e-mails novos (a corrida da 1ª linha só ocorre na janela de criação). Cada repetição
 * é verificada em separado; qualquer violação reprova o teste. Com a correção (lock/versão com
 * retry, ou upsert) o resultado é determinístico: exatamente 4 respostas 400, 5ª bloqueia (423) e
 * as demais 423. Margem: sem a correção a taxa de falha é alta mas não 100%.
 *
 * <p>O limite por IP (R-03) é neutralizado com {@code max-requests} muito alto e, ainda por
 * cima, IP remoto diferente por pedido, para nunca mascarar o resultado com 429. O IP não
 * interfere no contador, que é por e-mail.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.invitations.redeem-rate-limit.max-requests=100000",
    "app.invitations.redeem-rate-limit.window=PT1M"
})
class InvitationRedeemConcurrencyTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";
    private static final String ELDERS = "/api/v1/elders";
    private static final String SENHA = "Senha@Forte123";
    private static final String CODIGO = "123456";
    private static final String CODIGO_ERRADO = "654321";
    static final int REPETICOES_E_MAIL_NOVO = 5;

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

    @Autowired
    PasswordEncoder encoder;

    /**
     * Cenário 1 (AC-004.7 / AC-003.3 / R-03): e-mail novo, sem linha de attempts e sem convite;
     * 20 palpites errados simultâneos, repetidos com e-mails novos. Em cada repetição: nenhum 500
     * (a criação concorrente da 1ª linha não pode violar a PK), exatamente 4 respostas 400 e 16
     * respostas 423 (a 5ª bloqueia), {@code failed_attempts} final 5 e {@code blocked_until}
     * preenchido, nunca mais que 5 tentativas "contadas".
     */
    @Test
    @DisplayName("AC-004.7/AC-003.3/R-03: 20 palpites errados simultâneos no mesmo e-mail novo => sem 500, exatamente 4x400, 16x423 e failed_attempts=5 com bloqueio")
    void ac_004_7_palpites_simultaneos_em_email_novo_bloqueiam_na_quinta_sem_500() throws Exception {
        for (int rodada = 1; rodada <= REPETICOES_E_MAIL_NOVO; rodada++) {
            // Etapa: e-mail novo, sem nenhuma linha de attempts nem convite.
            String email = "conc-" + UUID.randomUUID() + "@teste.com";
            List<Integer> codigos = disparar(20, email, CODIGO_ERRADO);

            String ctx = "rodada " + rodada + ", respostas=" + codigos;
            // Etapa: nenhum 500 (nem exceção não tratada), só 400 ou 423.
            assertThat(codigos).as("só 400/423 (sem 500) - %s", ctx).allMatch(s -> s == 400 || s == 423);
            // Etapa: exatamente 4 respostas 400 e 16 respostas 423 (a 5ª bloqueia).
            assertThat(codigos.stream().filter(s -> s == 400).count()).as("qtd de 400 - %s", ctx).isEqualTo(4);
            assertThat(codigos.stream().filter(s -> s == 423).count()).as("qtd de 423 - %s", ctx).isEqualTo(16);
            // Etapa: estado final no banco.
            Map<String, Object> linha = jdbc.queryForMap(
                    "SELECT failed_attempts, blocked_until FROM invite_code_attempts WHERE email = ?", email);
            assertThat(linha.get("failed_attempts")).as("failed_attempts final - %s", ctx).isEqualTo(5);
            assertThat(linha.get("blocked_until")).as("blocked_until preenchido - %s", ctx).isNotNull();
        }
    }

    /**
     * Cenário 2 (AC-003.3 / AC-004.7 / R-03): e-mail com convite ENVIADO e linha de attempts já
     * com 3 falhas; 10 palpites errados simultâneos (cada um paga um BCrypt, o que alarga a
     * janela da atualização perdida). Esperado: sem 500, no máximo 2 respostas 400 (restavam 2
     * tentativas), {@code failed_attempts} = 5 (e não 4, que seria o contador perdendo
     * incrementos) com {@code blocked_until} preenchido.
     */
    @Test
    @DisplayName("AC-003.3/AC-004.7/R-03: 10 palpites errados simultâneos com 3 falhas prévias => failed_attempts=5 (não 4), bloqueio, sem 500 e no máximo 2x400")
    void ac_003_3_palpites_simultaneos_com_tres_falhas_previas_nao_perdem_incremento() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = "conc-" + UUID.randomUUID() + "@teste.com";
        semearConvite(grupo, email, CODIGO);
        // Etapa: linha de attempts já existente com 3 falhas, sem bloqueio.
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, 3, NULL, 0, ?)",
                email, Timestamp.from(clock.instant()));

        List<Integer> codigos = disparar(10, email, CODIGO_ERRADO);

        String ctx = "respostas=" + codigos;
        assertThat(codigos).as("só 400/423 (sem 500) - %s", ctx).allMatch(s -> s == 400 || s == 423);
        assertThat(codigos.stream().filter(s -> s == 400).count()).as("no máximo 2x400 - %s", ctx).isLessThanOrEqualTo(2);
        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT failed_attempts, blocked_until FROM invite_code_attempts WHERE email = ?", email);
        assertThat(linha.get("failed_attempts")).as("failed_attempts final - %s", ctx).isEqualTo(5);
        assertThat(linha.get("blocked_until")).as("blocked_until preenchido - %s", ctx).isNotNull();
    }

    /**
     * Cenário 3 (AC-004.1, caminho feliz sob concorrência): 5 resgates simultâneos com o código
     * certo de convite ENVIADO vigente, e-mail sem conta. Garante que o controle de concorrência
     * não quebra o caminho feliz: todos 200 (REGISTER), nenhum 500, convite continua ENVIADO e
     * contador do e-mail em 0 (sem linha ou zerada, AC-003.11c).
     */
    @Test
    @DisplayName("AC-004.1/R-03: 5 resgates simultâneos com código certo => todos 200 REGISTER, sem 500, convite segue ENVIADO")
    void ac_004_1_resgates_corretos_simultaneos_seguem_devolvendo_register() throws Exception {
        Grupo grupo = grupoComIdoso();
        String email = "conc-" + UUID.randomUUID() + "@teste.com";
        UUID convite = semearConvite(grupo, email, CODIGO);

        List<Integer> codigos = disparar(5, email, CODIGO);

        assertThat(codigos).as("todos 200 - respostas=%s", codigos).allMatch(s -> s == 200);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, convite))
                .isEqualTo("ENVIADO");
        List<Integer> falhas = jdbc.queryForList(
                "SELECT failed_attempts FROM invite_code_attempts WHERE email = ?", Integer.class, email);
        assertThat(falhas.isEmpty() || falhas.getFirst() == 0).as("contador zerado: %s", falhas).isTrue();
    }

    // ============================================================ helpers

    /**
     * Dispara {@code n} pedidos simultâneos (largada única por barreira) para o mesmo e-mail e
     * código, cada um de um IP diferente, e devolve os status HTTP. Exceção não tratada pelo
     * MockMvc (ex.: violação de PK propagada) conta como 500.
     */
    private List<Integer> disparar(int n, String email, String codigo) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch pronto = new CountDownLatch(n);
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String ip = "10.99." + (i / 250) + "." + (i % 250 + 1);
                Callable<Integer> tarefa = () -> {
                    pronto.countDown();
                    largada.await();
                    try {
                        MvcResult r = mockMvc.perform(post(REDEEM)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .with(req -> {
                                            req.setRemoteAddr(ip);
                                            return req;
                                        })
                                        .content(json.writeValueAsString(Map.of("email", email, "code", codigo))))
                                .andReturn();
                        return r.getResponse().getStatus();
                    } catch (Exception e) {
                        return 500;
                    }
                };
                futuros.add(pool.submit(tarefa));
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

    private record Grupo(User responsavel, UUID groupId) {}

    /** Semeia convite ENVIADO vigente (7 dias) com o hash real do código (D-04). */
    private UUID semearConvite(Grupo grupo, String email, String codigo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at) VALUES (?, ?, ?, ?, 'ENVIADO', 'MANUAL', ?, ?, ?)",
                id, grupo.groupId(), email, encoder.encode(codigo),
                Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                grupo.responsavel().getId(), Timestamp.from(clock.instant()));
        return id;
    }

    /** Responsável ATIVO que cria o idoso (e o grupo) pela API real, como no teste de resgate. */
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
        mockMvc.perform(post(ELDERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(responsavel.getId(), clock))
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated());
        return new Grupo(responsavel, memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId());
    }
}
