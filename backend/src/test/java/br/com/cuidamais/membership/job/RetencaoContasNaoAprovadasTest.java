package br.com.cuidamais.membership.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationOrigin;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.InvitationRepository;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.membership.service.InviteCodeAttemptsLocker;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.jobs.ScheduledRoutine;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-049 (plan D-09 rev. 2, item 3; esclarecimentos da T-049) - rotina agendada de retenção das
 * contas {@code RECUSADO}/{@code EXPIRADO} ({@code RetencaoContasNaoAprovadasRoutine}, bean {@code
 * ScheduledRoutine} de {@code membership.job}, nome {@code "retencao-contas-nao-aprovadas"}).
 *
 * <p>Regra provada (AC-014.1): uma membership {@code RECUSADO}/{@code EXPIRADO} é elegível à
 * exclusão quando, com borda FECHADA ({@code <=}), (a) o {@code retention_deadline} dela já chegou
 * (nulo = legado: vale {@code decided_at + 30 d}; os dois nulos = nunca elegível) E (b) NENHUM
 * convite do e-mail da conta, em qualquer grupo e status, ainda segura o prazo: {@code createdAt +
 * 30 d > agora}, ou {@code CANCELADO} com {@code cancelledAt + 30 d > agora}, ou {@code ENVIADO}/
 * {@code VENCIDO} com {@code expiresAt + 30 d > agora} (isso faz o {@code ENVIADO} vigente bloquear e
 * o vencido pelo relógio recomeçar a contagem em {@code expiresAt}). Convites de OUTRO e-mail nunca
 * influenciam. A exclusão apaga usuário (otp/refresh em cascata), vínculo, TODOS os convites do
 * e-mail e a linha de {@code invite_code_attempts} dele, e publica {@code AccountDeletedEmail} (um
 * só, D-23, D-42).
 *
 * <p>ACs e decisões cobertos: AC-014.1 (todos os testes), AC-014.2 ({@code REMOVIDO} nunca é
 * excluída, nem {@code ATIVO}/{@code AGUARDANDO_APROVACAO}), AC-003.3 (reenvio manual e automático
 * criam convite e reiniciam o prazo), D-09 (item 3 e esclarecimentos), D-23 (e-mail só após o
 * commit), D-42 (idempotência, SKIP LOCKED, MAX_ROWS 200, ordem por e-mail), R-08 (a rotina trava o
 * contador do e-mail primeiro e reconfere a elegibilidade sob o lock), D-10 (tempo só pelo
 * {@code MutableClock}).
 *
 * <p><b>Como rodam os testes:</b> quase todos executam a rotina DIRETAMENTE (buscada pelo nome na
 * lista de {@code ScheduledRoutine} e executada numa transação {@code REQUIRES_NEW}, como o {@link
 * JobRunner} faz), assim as outras rotinas não interferem nem mandam e-mails; a classe da rotina não
 * é importada (a falha antes da implementação é de comportamento: "rotina não registrada"). Só os
 * testes marcados "runAll" passam pelo {@link JobRunner#runAll()}.
 *
 * <p><b>Ambiente compartilhado:</b> o banco, o Mailpit e o relógio são compartilhados entre classes
 * e o relógio nunca volta; todo instante é calculado relativo ao relógio atual. Antes e depois de cada
 * teste os restos de {@code RECUSADO}/{@code EXPIRADO} (e os convites/contadores/usuários deles) são
 * apagados por SQL, para a rotina só ver os dados do teste em curso.
 *
 * <p><b>Notação:</b> {@code T} = instante em que a conta passou a {@code RECUSADO}/{@code EXPIRADO}.
 *
 * <p><b>Decisões fixadas (registradas no plan.md, D-09, T-049):</b> (1) o {@code ENVIADO} de 7 dias, que sempre vence antes
 * de {@code createdAt + 30 d}, só libera a conta em {@code createdAt + 7 d + 30 d} (37 dias após a
 * criação), e não em {@code createdAt + 30 d}; (2) o convite {@code USADO} conta só pelo {@code
 * createdAt}.
 */
@AutoConfigureMockMvc
class RetencaoContasNaoAprovadasTest extends IntegrationTest {

    private static final String NOME_ROTINA = "retencao-contas-nao-aprovadas";
    private static final String ELDERS = "/api/v1/elders";
    private static final String ASSUNTO_EXCLUSAO = "Cuida+: sua conta foi excluída";
    private static final Duration RETENCAO = Duration.ofDays(30);
    private static final Duration PRAZO_CONVITE = Duration.ofDays(7);
    private static final int RODADAS = 3;
    private static final String PREFIXO_EMAIL = "ret49-";

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JobRunner runner;

    @Autowired
    List<ScheduledRoutine> rotinas;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    InviteCodeAttemptsLocker locker;

    @Autowired
    JwtService jwtService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private record Grupo(User responsavel, UUID groupId, UUID elderId) {}

    /** Conta RECUSADO/EXPIRADO criada pelo teste; {@code transicao} é o T. */
    private record Conta(User user, UUID memberId, Instant transicao, Grupo grupo) {
        String email() {
            return user.getEmail();
        }
    }

    // ============================================================ ambiente

    /**
     * Apaga por SQL os restos de RECUSADO/EXPIRADO deixados por outras classes (e por testes
     * anteriores desta) - com os convites, contadores e usuários deles - para a rotina só enxergar os
     * dados do teste em curso, e zera a caixa do Mailpit depois que ela estabiliza.
     */
    @BeforeEach
    void limpaLegadoEZeraCaixa() {
        limparNaoAprovados();
        aguardarCaixaEstavel();
        mailpit.deleteAll();
    }

    /** Não deixa RECUSADO/EXPIRADO desta classe para as próximas classes (relógio avançado os tornaria elegíveis). */
    @AfterEach
    void limpaDepois() {
        limparNaoAprovados();
    }

    // ============================================================ registro da rotina

    /**
     * D-09 item 3 / AC-014.1: existe exatamente uma rotina agendada de nome
     * {@code retencao-contas-nao-aprovadas} registrada no contexto (é ela que o JobRunner executa).
     */
    @Test
    @DisplayName("AC-014.1/D-09: a rotina 'retencao-contas-nao-aprovadas' está registrada como ScheduledRoutine")
    void ac_014_1_rotina_registrada() {
        // Cenário: nenhum dado; só a lista de rotinas do contexto, a mesma que o JobRunner executa.
        long quantas = rotinas.stream().filter(r -> NOME_ROTINA.equals(r.name())).count();

        assertThat(quantas).as("rotina '%s' registrada", NOME_ROTINA).isEqualTo(1);
    }

    // ============================================================ exclusão no dia 30 (borda fechada)

    /**
     * AC-014.1 (borda fechada): a conta que recusada/expirada em T segue intacta 1 segundo antes de
     * T + 30 d (sem e-mail) e é excluída no instante exato de T + 30 d, com um e-mail curto. Cada
     * linha: o estado de partida (EXPIRADO pela rotina de expiração de pedidos, RECUSADO pelo domínio).
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"EXPIRADO", "RECUSADO"})
    @DisplayName("AC-014.1: a conta RECUSADO/EXPIRADO é excluída no instante exato de T + 30 dias, nunca 1 segundo antes")
    void ac_014_1_exclui_no_dia_30(MembershipStatus estado) {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, estado);
        Instant limite = c.transicao().plus(RETENCAO);

        // Etapa 1 (T + 30 d - 1 s): a conta e o vínculo continuam, e nenhum e-mail sai.
        rodarEm(limite.minusSeconds(1));
        assertMantida(c);
        semEmailDeExclusao(c.email());

        // Etapa 2 (T + 30 d exatos, borda fechada): excluída, com exatamente 1 e-mail curto.
        rodarEm(limite);
        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    /**
     * AC-014.1: a rotina atrasada (primeira rodada só em T + 45 d) também exclui; o "dia 30" é um
     * piso, não uma janela. Um único e-mail.
     */
    @Test
    @DisplayName("AC-014.1: rotina que só roda em T + 45 dias exclui a conta do mesmo jeito, com 1 e-mail")
    void ac_014_1_rotina_atrasada_exclui() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);

        rodarEm(c.transicao().plus(Duration.ofDays(45)));

        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    /**
     * AC-014.1 / AC-006.4: recusa feita pela API real (POST reject) e contagem desde esse momento: a
     * conta ainda existe 1 s antes de decided_at + 30 d e some no instante exato.
     */
    @Test
    @DisplayName("AC-014.1: pedido recusado pelo POST reject é excluído em decided_at + 30 dias exatos")
    void ac_014_1_recusado_via_http_exclui_no_dia_30() throws Exception {
        Grupo g = grupo();
        User pessoa = conta("Recusada Http", PREFIXO_EMAIL);
        Membership m = memberships.save(Membership.request(g.groupId(), pessoa.getId(), clock));
        clock.advance(Duration.ofHours(2));
        mockMvc.perform(post(ELDERS + "/{e}/members/{m}/reject", g.elderId(), m.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(g)))
                .andExpect(status().isOk());
        Conta c = new Conta(pessoa, m.getId(), clock.instant(), g);
        assertThat(statusNoBanco(c)).as("pré-condição: recusado pela API").isEqualTo("RECUSADO");

        // Etapa 1: 1 s antes dos 30 dias contados da recusa.
        rodarEm(c.transicao().plus(RETENCAO).minusSeconds(1));
        assertMantida(c);

        // Etapa 2: no instante exato.
        rodarEm(c.transicao().plus(RETENCAO));
        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    // ============================================================ o que some e o que fica

    /**
     * AC-014.1 / D-09 item 3: na exclusão some TUDO da pessoa - usuário (com otp e refresh em
     * cascata), vínculo, TODOS os convites do e-mail (dois grupos; ENVIADO vencido, VENCIDO,
     * CANCELADO e USADO) e a linha de invite_code_attempts - e NADA de outro e-mail (conta no mesmo
     * grupo ainda retida por um convite vigente, seus convites e contador, o Familiar ativo, os
     * Responsáveis). O e-mail da pessoa fica livre.
     */
    @Test
    @DisplayName("AC-014.1: exclui usuário, vínculo, todos os convites do e-mail (2 grupos, 4 status) e o contador; nada de outro e-mail")
    void ac_014_1_exclusao_completa_e_so_do_email() {
        Grupo a = grupo();
        Grupo b = grupo();
        Instant t0 = clock.instant();
        Conta c = contaNaoAprovada(a, MembershipStatus.EXPIRADO); // T = t0 + 24 h
        String email = c.email();
        // Convites do e-mail, todos criados em t0: o prazo efetivo é t0 + 7 d + 30 d = t0 + 37 d.
        semear(a, email, "ENVIADO", "MANUAL", t0, t0.plus(PRAZO_CONVITE), null, null);
        semear(a, email, "VENCIDO", "MANUAL", t0, t0.plus(PRAZO_CONVITE), null, null);
        semear(a, email, "CANCELADO", "AUTO", t0, t0.plus(PRAZO_CONVITE), null, t0.plus(Duration.ofDays(1)));
        semear(b, email, "USADO", "MANUAL", t0, t0.plus(PRAZO_CONVITE), t0.plus(Duration.ofDays(2)), null);
        semear(b, email, "CANCELADO", "MANUAL", t0, t0.plus(PRAZO_CONVITE), null, t0.plus(Duration.ofDays(3)));
        semearContador(email, 2, null, 1);
        semearOtpERefresh(c.user().getId());

        // Controles (nada disto pode sumir): conta EXPIRADO de outro e-mail retida por convite vigente
        // (criado em t0 + 20 d, expira t0 + 27 d => elegível só em t0 + 57 d), convites e contador dela,
        // um Familiar ativo no mesmo grupo e os Responsáveis.
        Conta controle = contaNaoAprovada(a, MembershipStatus.EXPIRADO);
        semear(a, controle.email(), "ENVIADO", "MANUAL", t0.plus(Duration.ofDays(20)), t0.plus(Duration.ofDays(27)), null, null);
        semear(b, controle.email(), "VENCIDO", "MANUAL", t0, t0.plus(PRAZO_CONVITE), null, null);
        semearContador(controle.email(), 3, null, 0);
        User familiar = familiarAtiva(a, "Familiar Ativo");
        int conviteDoControle = convitesDe(controle.email());

        // Etapa 1 (t0 + 37 d - 1 s): os convites VENCIDO/ENVIADO vencido ainda seguram a conta.
        rodarEm(t0.plus(Duration.ofDays(37)).minusSeconds(1));
        assertMantida(c);
        assertThat(convitesDe(email)).as("nada foi apagado antes da hora").isEqualTo(5);

        // Etapa 2 (t0 + 37 d exatos): some tudo da pessoa.
        rodarEm(t0.plus(Duration.ofDays(37)));
        assertExcluida(c);
        assertThat(contagem("SELECT count(*) FROM otp_codes WHERE user_id = ?", c.user().getId()))
                .as("otp em cascata").isZero();
        assertThat(contagem("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", c.user().getId()))
                .as("refresh em cascata").isZero();
        assertThat(users.findByEmail(email)).as("o e-mail ficou livre").isEmpty();

        // Etapa 3: o que não era da pessoa continua intacto.
        assertMantida(controle);
        assertThat(convitesDe(controle.email())).as("convites do outro e-mail").isEqualTo(conviteDoControle);
        assertThat(contadorExiste(controle.email())).as("contador do outro e-mail").isTrue();
        assertThat(users.findById(familiar.getId())).as("Familiar ativo").isPresent();
        assertThat(users.findById(a.responsavel().getId())).as("Responsável do grupo A").isPresent();
        assertThat(users.findById(b.responsavel().getId())).as("Responsável do grupo B").isPresent();

        // Etapa 4: e-mail só para a pessoa excluída.
        umEmailDeExclusao(email);
        semEmailDeExclusao(controle.email());
        semEmailDeExclusao(familiar.getEmail());
    }

    // ============================================================ reinício do prazo por convite

    /**
     * AC-014.1 / AC-003.3: convite criado depois da transição (manual ou AUTO - cada linha é a
     * origem) reinicia a contagem: a conta NÃO é excluída em T + 30 d, nem em createdAt + 30 d (o
     * convite de 7 dias ainda está vigente/recém-vencido), nem 1 s antes do fim; é excluída em
     * createdAt + 7 d + 30 d, quando o convite terminou sem uso (vencido pelo relógio).
     */
    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "AUTO"})
    @DisplayName("AC-014.1: convite criado após a transição reinicia o prazo (conta sai 30 dias depois do vencimento dele)")
    void ac_014_1_convite_criado_reinicia_o_prazo(String origem) {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        avancarAte(T.plus(Duration.ofDays(10)));
        Instant criado = clock.instant();
        semear(g, c.email(), "ENVIADO", origem, criado, criado.plus(PRAZO_CONVITE), null, null);

        // Etapa 1 (T + 30 d): o prazo antigo venceu, mas o convite novo ainda segura a conta.
        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        // Etapa 2 (criado + 30 d): ainda segura, pois o convite venceu em criado + 7 d.
        rodarEm(criado.plus(RETENCAO));
        assertMantida(c);
        // Etapa 3 (criado + 37 d - 1 s): ainda não.
        rodarEm(criado.plus(PRAZO_CONVITE).plus(RETENCAO).minusSeconds(1));
        assertMantida(c);
        semEmailDeExclusao(c.email());
        // Etapa 4 (criado + 37 d exatos): excluída.
        rodarEm(criado.plus(PRAZO_CONVITE).plus(RETENCAO));
        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    /**
     * AC-014.1 / AC-003.1: o mesmo reinício, com o convite criado pelo fluxo real (POST /invitations
     * do Responsável sobre a conta EXPIRADO, que é reconvidável, AC-003.6): a conta sobrevive a T + 30 d
     * e só sai 30 dias depois do vencimento do convite.
     */
    @Test
    @DisplayName("AC-014.1: convite criado pelo POST /invitations reinicia o prazo da conta EXPIRADO")
    void ac_014_1_convite_via_http_reinicia_o_prazo() throws Exception {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        avancarAte(c.transicao().plus(Duration.ofDays(10)));
        Instant criado = clock.instant();
        mockMvc.perform(criarConvite(g, c.email())).andExpect(status().isCreated());

        // Etapa 1: no dia 30 contado da transição a conta fica (o convite novo está vigente).
        rodarEm(c.transicao().plus(RETENCAO));
        assertMantida(c);
        // Etapa 2: 1 s antes de (criado + 7 d + 30 d) fica; no instante exato some.
        rodarEm(criado.plus(PRAZO_CONVITE).plus(RETENCAO).minusSeconds(1));
        assertMantida(c);
        rodarEm(criado.plus(PRAZO_CONVITE).plus(RETENCAO));
        assertExcluida(c);
    }

    /**
     * AC-014.1 / AC-003.3 (reenvio manual): o reenvio (POST resend) cria um convite novo e cancela o
     * anterior; o novo reinicia o prazo de novo. Convite criado em T + 10 d, reenviado em T + 12 d: o
     * novo expira em T + 19 d, então a conta sai em T + 49 d (e não em T + 47 d, de criado + 7 d + 30 d).
     */
    @Test
    @DisplayName("AC-014.1/AC-003.3: reenvio manual cria convite novo e reinicia o prazo mais uma vez")
    void ac_014_1_reenvio_manual_reinicia_o_prazo() throws Exception {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        avancarAte(T.plus(Duration.ofDays(10)));
        mockMvc.perform(criarConvite(g, c.email())).andExpect(status().isCreated());
        UUID primeiro = jdbc.queryForObject("SELECT id FROM invitations WHERE email = ?", UUID.class, c.email());
        avancarAte(T.plus(Duration.ofDays(12)));
        mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/resend", g.elderId(), primeiro)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g)))
                .andExpect(status().isCreated());
        assertThat(convitesDe(c.email())).as("pré-condição: o antigo (cancelado) e o novo").isEqualTo(2);

        // Etapa 1 (T + 47 d, fim do 1o convite + 30 d): o novo convite ainda segura a conta.
        rodarEm(T.plus(Duration.ofDays(47)));
        assertMantida(c);
        // Etapa 2 (T + 49 d - 1 s): ainda não; (T + 49 d): excluída.
        rodarEm(T.plus(Duration.ofDays(49)).minusSeconds(1));
        assertMantida(c);
        rodarEm(T.plus(Duration.ofDays(49)));
        assertExcluida(c);
    }

    /**
     * AC-014.1: convite ENVIADO ainda vigente bloqueia a exclusão no dia 30 da transição; enquanto
     * vigente segue bloqueando; quando vence pelo relógio (expiresAt), a contagem recomeça dele e a
     * conta sai em expiresAt + 30 d.
     */
    @Test
    @DisplayName("AC-014.1: convite ENVIADO vigente bloqueia a exclusão; ao vencer, conta 30 dias do expiresAt")
    void ac_014_1_convite_enviado_vigente_bloqueia() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        avancarAte(T.plus(Duration.ofDays(25)));
        Instant criado = clock.instant();
        Instant expira = criado.plus(PRAZO_CONVITE); // T + 32 d
        semear(g, c.email(), "ENVIADO", "MANUAL", criado, expira, null, null);

        // Etapa 1 (T + 30 d): convite vigente (vence em T + 32 d) bloqueia.
        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        // Etapa 2 (expira - 1 s): ainda vigente, bloqueia.
        rodarEm(expira.minusSeconds(1));
        assertMantida(c);
        // Etapa 3 (expira + 30 d - 1 s): vencido pelo relógio, mas faltando 1 s.
        rodarEm(expira.plus(RETENCAO).minusSeconds(1));
        assertMantida(c);
        // Etapa 4 (expira + 30 d): excluída.
        rodarEm(expira.plus(RETENCAO));
        assertExcluida(c);
    }

    /**
     * AC-014.1 (convite que termina sem uso): VENCIDO gravado e ENVIADO já vencido pelo relógio (cada
     * linha) contam 30 dias a partir do expiresAt. Convite criado em T + 2 d, vencido em T + 9 d:
     * conta mantida em T + 30 d e em T + 39 d - 1 s, excluída em T + 39 d.
     */
    @ParameterizedTest
    @ValueSource(strings = {"VENCIDO", "ENVIADO"})
    @DisplayName("AC-014.1: convite VENCIDO (ou ENVIADO vencido pelo relógio) reinicia a contagem a partir do expiresAt")
    void ac_014_1_convite_vencido_reinicia_a_partir_do_expires_at(String statusDoConvite) {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        semear(g, c.email(), statusDoConvite, "MANUAL", T.plus(Duration.ofDays(2)), T.plus(Duration.ofDays(9)), null, null);

        // Etapa 1 (T + 30 d): o prazo antigo venceu, mas expiresAt + 30 d = T + 39 d ainda não.
        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        // Etapa 2 (T + 39 d - 1 s): ainda mantida.
        rodarEm(T.plus(Duration.ofDays(39)).minusSeconds(1));
        assertMantida(c);
        // Etapa 3 (T + 39 d): excluída.
        rodarEm(T.plus(Duration.ofDays(39)));
        assertExcluida(c);
    }

    /**
     * AC-014.1: convite CANCELADO conta 30 dias a partir do cancelledAt, e NÃO do expiresAt. Convite
     * criado em T + 2 d, cancelado em T + 3 d (expiraria em T + 9 d): excluída em T + 33 d - e não
     * em T + 39 d. Mantida 1 s antes.
     */
    @Test
    @DisplayName("AC-014.1: convite CANCELADO reinicia a contagem a partir do cancelledAt, não do expiresAt")
    void ac_014_1_convite_cancelado_conta_do_cancelled_at() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        semear(g, c.email(), "CANCELADO", "MANUAL", T.plus(Duration.ofDays(2)), T.plus(Duration.ofDays(9)),
                null, T.plus(Duration.ofDays(3)));

        // Etapa 1 (T + 30 d): cancelledAt + 30 d = T + 33 d ainda não chegou.
        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        // Etapa 2 (T + 33 d - 1 s): ainda mantida.
        rodarEm(T.plus(Duration.ofDays(33)).minusSeconds(1));
        assertMantida(c);
        // Etapa 3 (T + 33 d): excluída, bem antes de expiresAt + 30 d (T + 39 d).
        rodarEm(T.plus(Duration.ofDays(33)));
        assertExcluida(c);
    }

    /**
     * AC-014.1 / AC-003.4: o mesmo, pelo fluxo real: convite criado em T + 10 d (expiraria em T + 17 d)
     * e cancelado pelo Responsável (POST cancel) em T + 12 d. A conta sai em T + 42 d (cancelledAt +
     * 30 d), e não em T + 47 d (expiresAt + 30 d).
     */
    @Test
    @DisplayName("AC-014.1/AC-003.4: convite cancelado pelo POST cancel libera a conta 30 dias depois do cancelamento")
    void ac_014_1_convite_cancelado_via_http() throws Exception {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        avancarAte(T.plus(Duration.ofDays(10)));
        MvcResult criado = mockMvc.perform(criarConvite(g, c.email())).andExpect(status().isCreated()).andReturn();
        UUID conviteId = UUID.fromString(json.readTree(criado.getResponse().getContentAsString()).path("id").asText());
        avancarAte(T.plus(Duration.ofDays(12)));
        mockMvc.perform(post(ELDERS + "/{e}/invitations/{i}/cancel", g.elderId(), conviteId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(g)))
                .andExpect(status().is2xxSuccessful());

        // Etapa 1 (T + 42 d - 1 s): ainda mantida; Etapa 2 (T + 42 d): excluída.
        rodarEm(T.plus(Duration.ofDays(42)).minusSeconds(1));
        assertMantida(c);
        rodarEm(T.plus(Duration.ofDays(42)));
        assertExcluida(c);
    }

    /**
     * AC-014.1: o convite do e-mail em OUTRO grupo também reinicia a contagem (D-09: "qualquer
     * grupo"). Convite VENCIDO no grupo B (criado em T + 1 d, vencido em T + 8 d), nenhum no grupo
     * da membership: a conta sai em T + 38 d.
     */
    @Test
    @DisplayName("AC-014.1: convite do mesmo e-mail em OUTRO grupo também reinicia o prazo")
    void ac_014_1_convite_de_outro_grupo_reinicia() {
        Grupo a = grupo();
        Grupo b = grupo();
        Conta c = contaNaoAprovada(a, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        semear(b, c.email(), "VENCIDO", "MANUAL", T.plus(Duration.ofDays(1)), T.plus(Duration.ofDays(8)), null, null);

        // Etapa 1 (T + 30 d): o convite do grupo B ainda segura; Etapa 2: 1 s antes de T + 38 d; Etapa 3: T + 38 d.
        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        rodarEm(T.plus(Duration.ofDays(38)).minusSeconds(1));
        assertMantida(c);
        rodarEm(T.plus(Duration.ofDays(38)));
        assertExcluida(c);
        assertThat(convitesDe(c.email())).as("o convite do grupo B sumiu junto").isZero();
    }

    /**
     * AC-014.1: a clausula "convite criado" vale para qualquer status: um convite USADO criado em
     * T + 5 d segura a conta até createdAt + 30 d = T + 35 d (USADO só conta pelo createdAt).
     */
    @Test
    @DisplayName("AC-014.1: o createdAt de qualquer convite (até USADO) conta 30 dias")
    void ac_014_1_created_at_de_convite_usado_conta_30_dias() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        Instant criado = T.plus(Duration.ofDays(5));
        semear(g, c.email(), "USADO", "MANUAL", criado, criado.plus(PRAZO_CONVITE), criado, null);

        rodarEm(T.plus(RETENCAO));
        assertMantida(c);
        rodarEm(criado.plus(RETENCAO).minusSeconds(1));
        assertMantida(c);
        rodarEm(criado.plus(RETENCAO));
        assertExcluida(c);
    }

    /**
     * AC-014.1: convites de OUTRO e-mail nunca influenciam. Convite ENVIADO vigente de outro e-mail
     * no mesmo grupo (criado em T + 25 d) não segura a conta, que sai no dia 30; o convite do outro
     * e-mail continua lá.
     */
    @Test
    @DisplayName("AC-014.1: convite de outro e-mail não interfere no prazo nem é apagado")
    void ac_014_1_convite_de_outro_email_nao_interfere() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        String outro = emailNovo();
        semear(g, outro, "ENVIADO", "MANUAL", T.plus(Duration.ofDays(25)), T.plus(Duration.ofDays(32)), null, null);
        semearContador(outro, 1, null, 0);

        rodarEm(T.plus(RETENCAO));

        assertExcluida(c);
        assertThat(convitesDe(outro)).as("convite do outro e-mail intacto").isEqualTo(1);
        assertThat(contadorExiste(outro)).as("contador do outro e-mail intacto").isTrue();
        umEmailDeExclusao(c.email());
    }

    // ============================================================ AC-014.2

    /**
     * AC-014.2: conta REMOVIDO nunca é excluída pela rotina (nem ATIVO nem AGUARDANDO_APROVACAO;
     * cada linha é um estado), mesmo 400 dias depois, e os convites do e-mail dela ficam. O controle
     * positivo é uma conta EXPIRADO antiga, que a mesma rodada tem de excluir.
     */
    @ParameterizedTest
    @EnumSource(value = MembershipStatus.class, names = {"REMOVIDO", "ATIVO", "AGUARDANDO_APROVACAO"})
    @DisplayName("AC-014.2: REMOVIDO (e ATIVO, AGUARDANDO_APROVACAO) nunca é excluída pela rotina, nem 400 dias depois")
    void ac_014_2_outros_estados_nunca_sao_excluidos(MembershipStatus estado) {
        Grupo g = grupo();
        Conta sentinela = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Conta c = contaEmEstado(g, estado);
        semear(g, c.email(), "VENCIDO", "MANUAL", clock.instant().minus(Duration.ofDays(20)),
                clock.instant().minus(Duration.ofDays(13)), null, null);

        rodarEm(clock.instant().plus(Duration.ofDays(400)));

        assertExcluida(sentinela); // controle positivo: a rotina rodou e excluiu o elegível
        assertMantida(c);
        assertThat(statusNoBanco(c)).as("status intocado").isEqualTo(estado.name());
        assertThat(convitesDe(c.email())).as("convite da conta intocado").isEqualTo(1);
        semEmailDeExclusao(c.email());
    }

    /**
     * AC-014.2: mesmo com um retention_deadline residual já vencido gravado numa membership REMOVIDO
     * (o filtro é por status, não só pela coluna), a conta não é excluída.
     */
    @Test
    @DisplayName("AC-014.2: REMOVIDO com retention_deadline residual já vencido continua sem ser excluída")
    void ac_014_2_removido_com_retention_residual_nao_e_excluido() {
        Grupo g = grupo();
        Conta sentinela = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Conta c = contaEmEstado(g, MembershipStatus.REMOVIDO);
        jdbc.update("UPDATE memberships SET retention_deadline = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(5))), c.memberId());

        rodarEm(sentinela.transicao().plus(RETENCAO).plus(Duration.ofDays(60)));

        assertExcluida(sentinela);
        assertMantida(c);
        assertThat(statusNoBanco(c)).isEqualTo("REMOVIDO");
        semEmailDeExclusao(c.email());
    }

    // ============================================================ legado (retention_deadline nulo)

    /**
     * D-09 (legado): RECUSADO sem retention_deadline (gravado antes da T-049) vale decided_at + 30 d,
     * borda fechada. Cada linha: a idade do decided_at e se deve sair - 30 d exatos saem, 1 s a menos não.
     */
    @ParameterizedTest
    @CsvSource({"PT720H, true", "PT719H59M59S, false"})
    @DisplayName("AC-014.1/D-09: RECUSADO legado (retention_deadline nulo) usa decided_at + 30 dias, borda fechada")
    void ac_014_1_legado_usa_decided_at(String idade, boolean deveExcluir) {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.RECUSADO);
        // Cenário: zera o prazo gravado e recua o decided_at para a idade da linha (decided_at = agora - idade).
        jdbc.update("UPDATE memberships SET retention_deadline = NULL, decided_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.parse(idade))), c.memberId());

        rodar();

        if (deveExcluir) {
            // Linha "PT720H": decided_at há exatamente 30 d => elegível.
            assertExcluida(c);
            umEmailDeExclusao(c.email());
        } else {
            // Linha "PT719H59M59S": falta 1 s => mantida.
            assertMantida(c);
            semEmailDeExclusao(c.email());
        }
    }

    /**
     * D-09 (legado): EXPIRADO com retention_deadline e decided_at AMBOS nulos nunca é elegível (não há
     * de onde contar), mesmo muito depois; a sentinela mostra que a rotina rodou.
     */
    @Test
    @DisplayName("AC-014.1/D-09: sem retention_deadline e sem decided_at a conta não é elegível")
    void ac_014_1_legado_sem_nenhuma_base_nao_e_elegivel() {
        Grupo g = grupo();
        Conta sentinela = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        jdbc.update("UPDATE memberships SET retention_deadline = NULL, decided_at = NULL WHERE id = ?", c.memberId());

        rodarEm(c.transicao().plus(Duration.ofDays(60)));

        assertExcluida(sentinela);
        assertMantida(c);
        semEmailDeExclusao(c.email());
    }

    // ============================================================ idempotência (D-42)

    /**
     * D-42: depois da exclusão, rodadas repetidas (no mesmo instante e com o relógio avançando dias)
     * não fazem nada e não mandam um 2o e-mail.
     */
    @Test
    @DisplayName("D-42: rodar de novo depois da exclusão não faz nada nem manda o e-mail em dobro")
    void d42_segunda_rodada_nao_repete() {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        rodarEm(c.transicao().plus(RETENCAO));
        umEmailDeExclusao(c.email());

        // Etapa 1: segunda rodada imediata. Etapa 2: rodadas com o relógio avançando (1 dia, 30 dias).
        rodar();
        clock.advance(Duration.ofDays(1));
        rodar();
        clock.advance(Duration.ofDays(30));
        rodar();

        assertExcluida(c);
        umEmailDeExclusao(c.email()); // continua 1 só
    }

    /**
     * D-42: MAX_ROWS 200 e ordem por e-mail. Com 201 contas elegíveis, a 1a rodada exclui as 200
     * primeiras em ordem de e-mail e deixa a de índice 200; a 2a rodada exclui a que sobrou, com 1
     * e-mail; uma 3a rodada não muda nada.
     */
    @Test
    @DisplayName("D-42: no máximo 200 contas por rodada, em ordem de e-mail; o excedente sai na rodada seguinte")
    void d42_max_rows_200_por_rodada_em_ordem_de_email() {
        Grupo g = grupo();
        String lote = PREFIXO_EMAIL + "lote-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        List<Conta> contas = contasExpiradasEmLote(g, 201, lote);
        Instant T = contas.getFirst().transicao();

        // Etapa 1: 1a rodada em T + 30 d - exclui exatamente as 200 primeiras por e-mail.
        rodarEm(T.plus(RETENCAO));
        for (int i = 0; i < 200; i++) {
            assertThat(contagem("SELECT count(*) FROM users WHERE id = ?", contas.get(i).user().getId()))
                    .as("conta %03d da rodada 1", i).isZero();
        }
        assertMantida(contas.get(200));

        // Etapa 2: 2a rodada exclui a que sobrou, com 1 e-mail.
        rodar();
        assertExcluida(contas.get(200));
        umEmailDeExclusao(contas.get(200).email());

        // Etapa 3: 3a rodada não muda nada (1 e-mail ainda).
        rodar();
        umEmailDeExclusao(contas.get(200).email());
    }

    // ============================================================ concorrência (R-08, D-42)

    /**
     * D-42: três execuções simultâneas da rotina sobre várias contas elegíveis (SKIP LOCKED + lock do
     * contador em ordem de e-mail): sem exceção nem deadlock, todas excluídas, exatamente 1 e-mail
     * cada e o controle (conta retida por convite vigente) intacto e sem e-mail. Repetido em rodadas.
     */
    @Test
    @DisplayName("D-42: três execuções simultâneas da rotina => cada conta é excluída uma vez, com 1 e-mail, sem exceção")
    void d42_execucoes_simultaneas_nao_duplicam_nem_travam() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            List<Conta> contas = contasExpiradasEmLote(g, 6, PREFIXO_EMAIL + "conc-");
            Instant T = contas.getFirst().transicao();
            // Controle: retida por convite ENVIADO vigente (criado em T + 29 d, expira em T + 36 d).
            Conta controle = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
            semear(g, controle.email(), "ENVIADO", "MANUAL", T.plus(Duration.ofDays(29)), T.plus(Duration.ofDays(36)), null, null);
            avancarAte(controle.transicao().plus(RETENCAO));
            mailpit.deleteAll();

            // Etapa 1: três threads largam juntas rodando a rotina.
            List<Callable<Integer>> acoes = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                acoes.add(() -> {
                    rodar();
                    return 0;
                });
            }
            largadaSimultanea(acoes); // qualquer exceção (inclusive deadlock) falha aqui
            // Etapa 2: rodada sequencial depois - nada pode mudar.
            rodar();

            String ctx = "rodada " + rodada;
            for (Conta c : contas) {
                assertExcluida(c);
            }
            aguardarUmEmailDeExclusaoDe(contas.stream().map(Conta::email).toList());
            assertMantida(controle);
            assertThat(emailsDeExclusao(controle.email())).as(ctx + ": controle sem e-mail").isEmpty();
        }
    }

    /**
     * R-08 (convite criado ao mesmo tempo): uma transação "de convite" trava o contador do e-mail e
     * ainda não gravou; a rotina roda nesse meio tempo (a conta é elegível). A rotina espera o lock (ou
     * pula) e, depois que o convite novo é confirmado, NÃO exclui a conta: usuário, vínculo e o
     * convite novo continuam, e nenhum e-mail sai. O escritor só grava 1,5 s depois de a rotina
     * começar, então uma rotina que NÃO travasse o contador teria excluído antes.
     */
    @Test
    @DisplayName("R-08: convite criado sob o lock do contador enquanto a rotina roda => a conta NÃO é excluída")
    void r08_convite_criado_durante_a_rotina_impede_a_exclusao() throws Exception {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        avancarAte(c.transicao().plus(RETENCAO)); // elegível
        TransactionTemplate tx = transacaoNova();
        CountDownLatch travado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Etapa 1: o "serviço de convite" trava o contador e segura a transação.
            Future<?> escritor = pool.submit(() -> tx.executeWithoutResult(s -> {
                locker.lock(c.email());
                travado.countDown();
                aguardar(liberar);
                invitations.save(Invitation.send(
                        g.groupId(), c.email(), "hash-irrelevante", InvitationOrigin.MANUAL, g.responsavel().getId(), clock));
            }));
            assertThat(travado.await(30, TimeUnit.SECONDS)).isTrue();

            // Etapa 2: a rotina começa com o contador travado por outra transação.
            Future<?> rotina = pool.submit(this::rodar);
            Thread.sleep(1500);

            // Etapa 3: o convite é confirmado; a rotina então reconfere e não pode excluir.
            liberar.countDown();
            escritor.get(60, TimeUnit.SECONDS);
            rotina.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertMantida(c);
        assertThat(convitesDe(c.email())).as("o convite novo existe").isEqualTo(1);
        semEmailDeExclusao(c.email());
    }

    /**
     * R-08 (convite cancelado ao mesmo tempo): a conta é elegível porque o único convite do e-mail é um
     * ENVIADO vencido há mais de 30 dias do prazo; uma transação o cancela (cancelledAt = agora) sob o
     * lock do contador enquanto a rotina espera. Depois do commit a rotina reconfere: o cancelamento
     * recomeçou os 30 dias, então a conta NÃO é excluída.
     */
    @Test
    @DisplayName("R-08: convite cancelado sob o lock do contador enquanto a rotina roda => a conta NÃO é excluída")
    void r08_convite_cancelado_durante_a_rotina_impede_a_exclusao() throws Exception {
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        Instant T = c.transicao();
        UUID conviteId = semear(g, c.email(), "ENVIADO", "MANUAL", T.minus(Duration.ofDays(8)), T.minus(Duration.ofDays(1)), null, null);
        avancarAte(T.plus(RETENCAO)); // elegível: expiresAt + 30 d = T + 29 d já passou
        TransactionTemplate tx = transacaoNova();
        CountDownLatch travado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> escritor = pool.submit(() -> tx.executeWithoutResult(s -> {
                locker.lock(c.email());
                travado.countDown();
                aguardar(liberar);
                Invitation convite = invitations.findById(conviteId).orElseThrow();
                convite.cancel(clock);
                invitations.save(convite);
            }));
            assertThat(travado.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> rotina = pool.submit(this::rodar);
            Thread.sleep(1500);
            liberar.countDown();
            escritor.get(60, TimeUnit.SECONDS);
            rotina.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertMantida(c);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id = ?", String.class, conviteId))
                .as("o convite foi cancelado").isEqualTo("CANCELADO");
        semEmailDeExclusao(c.email());
    }

    /**
     * R-08 / D-42 (corrida real pela API): POST /invitations sobre uma conta elegível exatamente
     * quando a rotina roda. Os dois desfechos são coerentes: (a) o convite entrou primeiro => a conta
     * fica, com o convite antigo e o novo, sem e-mail de exclusão; (b) a rotina excluiu primeiro =>
     * conta e convite antigo somem, só o convite novo existe, com 1 e-mail de exclusão. Nunca 5xx nem
     * convite antigo sobrando sem a conta. Repetido em rodadas.
     */
    @Test
    @DisplayName("R-08: POST /invitations x rotina na virada do prazo => ou a conta fica com o convite novo, ou sai antes dele; nunca incoerente")
    void r08_corrida_convite_http_x_rotina() throws Exception {
        for (int rodada = 1; rodada <= RODADAS; rodada++) {
            Grupo g = grupo();
            Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
            Instant T = c.transicao();
            // Convite antigo, vencido há tempo: já não segura a conta em T + 30 d (vence + 30 d = T + 21 d).
            semear(g, c.email(), "VENCIDO", "MANUAL", T.minus(Duration.ofDays(16)), T.minus(Duration.ofDays(9)), null, null);
            avancarAte(T.plus(RETENCAO));
            MockHttpServletRequestBuilder criar = criarConvite(g, c.email());
            mailpit.deleteAll();

            List<Integer> res = largadaSimultanea(List.of(
                    () -> statusHttp(criar),
                    () -> {
                        rodar();
                        return 0;
                    }));

            String ctx = "rodada " + rodada;
            assertThat(res.get(0)).as("criar o convite não pode dar 5xx - " + ctx).isEqualTo(201);
            boolean viva = contagem("SELECT count(*) FROM users WHERE id = ?", c.user().getId()) == 1;
            if (viva) {
                // (a) o convite entrou antes da exclusão: conta e vínculo ficam, antigo + novo, sem e-mail de exclusão.
                assertThat(contagem("SELECT count(*) FROM memberships WHERE id = ?", c.memberId())).as(ctx).isEqualTo(1);
                assertThat(convitesDe(c.email())).as(ctx + ": antigo + novo").isEqualTo(2);
                semEmailDeExclusao(c.email());
            } else {
                // (b) a exclusão veio antes: só o convite novo (criado depois) resta, e saiu 1 e-mail de exclusão.
                assertThat(contagem("SELECT count(*) FROM memberships WHERE id = ?", c.memberId())).as(ctx).isZero();
                assertThat(convitesDe(c.email())).as(ctx + ": só o novo").isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE email = ?", String.class, c.email()))
                        .as(ctx).isEqualTo("ENVIADO");
                umEmailDeExclusao(c.email());
            }
        }
    }

    // ============================================================ runAll, logs e outras rotinas

    /**
     * D-09 / D-42 (runAll): pelo JobRunner a rotina exclui a conta elegível e NÃO registra
     * "Rotina agendada ... falhou" (nenhuma rotina falha, nem a nova nem as outras), com 1 e-mail.
     */
    @Test
    @DisplayName("D-09/D-42 (runAll): pelo JobRunner a rotina exclui a conta e nenhuma rotina loga falha")
    void d09_run_all_exclui_sem_logar_falha() {
        drenarOutrasRotinas();
        Grupo g = grupo();
        Conta c = contaNaoAprovada(g, MembershipStatus.EXPIRADO);
        avancarAte(c.transicao().plus(RETENCAO));

        try (CapturaDeLogs falhas = new CapturaDeLogs(JobRunner.class.getName())) {
            runner.runAll();
            assertThat(falhas.mensagens()).as("nenhuma rotina falhou").isEmpty();
        }

        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    /**
     * AC-014.1 x AC-014.3: a rotina de contas sem grupo (T-034, "verificado sem NENHUMA membership")
     * não apaga a conta com membership RECUSADO: a pessoa verificou o e-mail há mais de 30 dias mas o
     * prazo da recusa ainda não venceu => sobrevive a um runAll; no prazo, é excluída pela rotina nova,
     * com exatamente 1 e-mail (nunca dois, um de cada rotina).
     */
    @Test
    @DisplayName("AC-014.1/AC-014.3 (runAll): a retenção de conta sem grupo não apaga RECUSADO, e a exclusão sai com um único e-mail")
    void ac_014_1_nao_interfere_com_retencao_de_conta_sem_grupo() {
        drenarOutrasRotinas();
        Grupo g = grupo();
        Instant t0 = clock.instant(); // e-mail da pessoa verificado em t0 (AC-014.3 contaria 30 d a partir daqui)
        Conta c = contaNaoAprovada(g, MembershipStatus.RECUSADO); // T = t0 + 1 h

        // Etapa 1 (t0 + 30 d, antes de T + 30 d): a rotina de AC-014.3 já poderia agir, mas há membership.
        avancarAte(t0.plus(RETENCAO));
        runner.runAll();
        assertMantida(c);
        semEmailDeExclusao(c.email());

        // Etapa 2 (T + 30 d): a rotina nova exclui, com um único e-mail.
        avancarAte(c.transicao().plus(RETENCAO));
        runner.runAll();
        assertExcluida(c);
        umEmailDeExclusao(c.email());
    }

    // ============================================================ apoio: execução da rotina

    /** Executa a rotina nova direto, numa transação REQUIRES_NEW (como o JobRunner), com o relógio do contexto. */
    private void rodar() {
        ScheduledRoutine rotina = rotinas.stream()
                .filter(r -> NOME_ROTINA.equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "funcionalidade ausente: nenhuma ScheduledRoutine com name() = '" + NOME_ROTINA + "'"));
        transacaoNova().executeWithoutResult(s -> rotina.run(clock));
    }

    private TransactionTemplate transacaoNova() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** Avança o relógio até {@code quando} (nunca volta) e executa a rotina. */
    private void rodarEm(Instant quando) {
        avancarAte(quando);
        rodar();
    }

    private void avancarAte(Instant alvo) {
        Duration d = Duration.between(clock.instant(), alvo);
        assertThat(d.isNegative()).as("o relógio nunca volta: alvo %s, agora %s", alvo, clock.instant()).isFalse();
        clock.advance(d);
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch não liberado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Drena o que as OUTRAS rotinas têm a fazer (pedidos vencidos, contas sem grupo...) e zera o Mailpit. */
    private void drenarOutrasRotinas() {
        runner.runAll();
        aguardarCaixaEstavel();
        mailpit.deleteAll();
    }

    private void aguardarCaixaEstavel() {
        int[] ultimo = {-1};
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).until(() -> {
            int atual = mailpit.count();
            boolean estavel = atual == ultimo[0];
            ultimo[0] = atual;
            return estavel;
        });
    }

    // ============================================================ apoio: concorrência e logs

    /** Largada simultânea numa barreira; devolve os resultados na ordem das ações (exceção de qualquer uma falha o teste). */
    private List<Integer> largadaSimultanea(List<Callable<Integer>> acoes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acoes.size());
        try {
            CountDownLatch prontos = new CountDownLatch(acoes.size());
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> acao : acoes) {
                futuros.add(pool.submit(() -> {
                    prontos.countDown();
                    largada.await();
                    return acao.call();
                }));
            }
            assertThat(prontos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<Integer> res = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                res.add(f.get(90, TimeUnit.SECONDS));
            }
            return res;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Status HTTP da requisição; exceção não tratada pelo MockMvc conta como 500. */
    private int statusHttp(MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    /** Captura as mensagens de log de um logger durante um try-with-resources. */
    private static final class CapturaDeLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level nivelAnterior;

        CapturaDeLogs(String nomeDoLogger) {
            this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(nomeDoLogger);
            this.nivelAnterior = logger.getLevel();
            appender.start();
            logger.addAppender(appender);
        }

        List<String> mensagens() {
            List<String> res = new ArrayList<>();
            synchronized (appender) {
                for (ILoggingEvent e : appender.list) {
                    res.add(e.getFormattedMessage());
                }
            }
            return res;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(nivelAnterior);
            appender.stop();
        }
    }

    // ============================================================ apoio: HTTP

    private String bearer(Grupo g) {
        return "Bearer " + jwtService.issue(g.responsavel().getId(), clock);
    }

    private MockHttpServletRequestBuilder criarConvite(Grupo g, String email) throws Exception {
        return post(ELDERS + "/{e}/invitations", g.elderId())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(g))
                .content(json.writeValueAsString(Map.of("email", email)));
    }

    // ============================================================ apoio: e-mail

    private List<MailpitClient.Summary> emailsDeExclusao(String email) {
        return mailpit.messagesTo(email).stream().filter(m -> ASSUNTO_EXCLUSAO.equals(m.subject())).toList();
    }

    /** Espera exatamente 1 e-mail de exclusão ao endereço e confirma que não chega um 2o. */
    private void umEmailDeExclusao(String email) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(emailsDeExclusao(email)).hasSize(1));
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeExclusao(email)).hasSize(1));
    }

    private void aguardarUmEmailDeExclusaoDe(List<String> emails) {
        for (String e : emails) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(emailsDeExclusao(e)).hasSize(1));
        }
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            for (String e : emails) {
                assertThat(emailsDeExclusao(e)).hasSize(1);
            }
        });
    }

    /** Nenhum e-mail de exclusão chega ao endereço durante uma janela de observação. */
    private void semEmailDeExclusao(String email) {
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(emailsDeExclusao(email)).isEmpty());
    }

    // ============================================================ apoio: banco

    private int contagem(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private int convitesDe(String email) {
        return contagem("SELECT count(*) FROM invitations WHERE email = ?", email);
    }

    private boolean contadorExiste(String email) {
        return contagem("SELECT count(*) FROM invite_code_attempts WHERE email = ?", email) > 0;
    }

    private String statusNoBanco(Conta c) {
        return jdbc.queryForObject("SELECT status FROM memberships WHERE id = ?", String.class, c.memberId());
    }

    private void assertMantida(Conta c) {
        assertThat(contagem("SELECT count(*) FROM users WHERE id = ?", c.user().getId()))
                .as("usuário %s deve existir (agora=%s)", c.email(), clock.instant()).isEqualTo(1);
        assertThat(contagem("SELECT count(*) FROM memberships WHERE id = ?", c.memberId()))
                .as("vínculo deve existir").isEqualTo(1);
    }

    private void assertExcluida(Conta c) {
        assertThat(contagem("SELECT count(*) FROM users WHERE id = ?", c.user().getId()))
                .as("usuário %s deve ter sido excluído (agora=%s)", c.email(), clock.instant()).isZero();
        assertThat(contagem("SELECT count(*) FROM memberships WHERE id = ?", c.memberId()))
                .as("vínculo deve ter sido excluído").isZero();
        assertThat(convitesDe(c.email())).as("convites do e-mail devem ter sido excluídos").isZero();
        assertThat(contadorExiste(c.email())).as("contador do e-mail deve ter sido excluído").isFalse();
        assertThat(users.findByEmail(c.email())).as("e-mail livre").isEmpty();
    }

    /** Semeia um convite com as datas exatas pedidas (hash fictício; criador = Responsável do grupo). */
    private UUID semear(
            Grupo g, String email, String status, String origem, Instant criado, Instant expira, Instant usado, Instant cancelado) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invitations (id, group_id, email, code_hash, status, origin, expires_at, created_by,"
                        + " created_at, used_at, cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, g.groupId(), email, "hash-irrelevante", status, origem, Timestamp.from(expira),
                g.responsavel().getId(), Timestamp.from(criado),
                usado == null ? null : Timestamp.from(usado), cancelado == null ? null : Timestamp.from(cancelado));
        return id;
    }

    private void semearContador(String email, int falhas, Instant bloqueadoAte, int reenvios) {
        jdbc.update("DELETE FROM invite_code_attempts WHERE email = ?", email);
        jdbc.update(
                "INSERT INTO invite_code_attempts (email, failed_attempts, blocked_until, auto_resends, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                email, falhas, bloqueadoAte == null ? null : Timestamp.from(bloqueadoAte), reenvios,
                Timestamp.from(clock.instant()));
    }

    private void semearOtpERefresh(UUID userId) {
        jdbc.update(
                "INSERT INTO otp_codes (id, user_id, purpose, code_hash, expires_at) VALUES (?, ?, 'CONFIRMACAO_EMAIL', 'x', ?)",
                UUID.randomUUID(), userId, Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))));
        UUID refresh = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, absolute_expires_at, family_id)"
                        + " VALUES (?, ?, 'x', ?, ?, ?)",
                refresh, userId, Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                Timestamp.from(clock.instant().plus(Duration.ofDays(30))), refresh);
    }

    /**
     * Apaga por SQL todo RECUSADO/EXPIRADO e o que é deles: convites e contador do e-mail, vínculo e
     * usuário (cascata em otp/refresh). Usuários que ainda são referenciados (decisor, criador de
     * convite/grupo) são preservados para não violar FK.
     */
    private void limparNaoAprovados() {
        String emails = "SELECT u.email FROM users u JOIN memberships m ON m.user_id = u.id"
                + " WHERE m.status IN ('RECUSADO', 'EXPIRADO')";
        jdbc.update("DELETE FROM invitations WHERE email IN (" + emails + ")");
        jdbc.update("DELETE FROM invite_code_attempts WHERE email IN (" + emails + ")");
        List<UUID> ids = jdbc.queryForList(
                "SELECT user_id FROM memberships WHERE status IN ('RECUSADO', 'EXPIRADO')", UUID.class);
        jdbc.update("DELETE FROM memberships WHERE status IN ('RECUSADO', 'EXPIRADO')");
        for (UUID id : ids) {
            jdbc.update(
                    "DELETE FROM users WHERE id = ?"
                            + " AND NOT EXISTS (SELECT 1 FROM memberships WHERE decided_by = users.id)"
                            + " AND NOT EXISTS (SELECT 1 FROM invitations WHERE created_by = users.id)"
                            + " AND NOT EXISTS (SELECT 1 FROM care_groups WHERE created_by = users.id)",
                    id);
        }
    }

    // ============================================================ apoio: fábricas

    /** Responsável ATIVO que cria o idoso (e o grupo) pela API real. */
    private Grupo grupo() {
        try {
            User responsavel = conta("Zélia Responsável", PREFIXO_EMAIL + "resp-");
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("name", "Francisca Oliveira");
            req.put("birthDate", java.time.LocalDate.now(clock).minusYears(80).toString());
            req.put("address", enderecoJson());
            req.put("emergencyContacts", List.of());
            MvcResult criacao = mockMvc.perform(post(ELDERS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(responsavel.getId(), clock))
                            .content(json.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode corpo = json.readTree(criacao.getResponse().getContentAsString());
            UUID elderId = UUID.fromString(corpo.path("id").asText());
            UUID groupId = memberships.findByUserId(responsavel.getId()).orElseThrow().getGroupId();
            return new Grupo(responsavel, groupId, elderId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Cria a conta (e-mail verificado) e a leva a {@code RECUSADO} (1 h depois do pedido, pelo
     * domínio) ou {@code EXPIRADO} (24 h depois, pelo domínio, como a rotina de expiração faz). T =
     * relógio no instante da transição.
     */
    private Conta contaNaoAprovada(Grupo g, MembershipStatus estado) {
        User pessoa = conta("Pessoa " + estado, PREFIXO_EMAIL);
        Membership m = Membership.request(g.groupId(), pessoa.getId(), clock);
        switch (estado) {
            case RECUSADO -> {
                clock.advance(Duration.ofHours(1));
                m.reject(g.responsavel().getId(), clock);
            }
            case EXPIRADO -> {
                clock.advance(Duration.ofHours(24));
                m.expire(clock);
            }
            default -> throw new IllegalArgumentException(estado.name());
        }
        memberships.save(m);
        return new Conta(pessoa, m.getId(), clock.instant(), g);
    }

    /** {@code n} contas EXPIRADO com a MESMA transição T (um único avanço de 24 h) e e-mails ordenados pelo índice. */
    private List<Conta> contasExpiradasEmLote(Grupo g, int n, String prefixo) {
        List<User> pessoas = new ArrayList<>();
        List<Membership> vinculos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            User pessoa = conta("Lote " + i, prefixo + String.format("%03d", i) + "-");
            pessoas.add(pessoa);
            vinculos.add(Membership.request(g.groupId(), pessoa.getId(), clock));
        }
        clock.advance(Duration.ofHours(24));
        List<Conta> contas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Membership m = vinculos.get(i);
            m.expire(clock);
            memberships.save(m);
            contas.add(new Conta(pessoas.get(i), m.getId(), clock.instant(), g));
        }
        return contas;
    }

    /** Conta num estado que a rotina nunca exclui (ATIVO, AGUARDANDO_APROVACAO ou REMOVIDO), por transições de domínio. */
    private Conta contaEmEstado(Grupo g, MembershipStatus estado) {
        User pessoa = conta("Pessoa " + estado, PREFIXO_EMAIL);
        Membership m = Membership.request(g.groupId(), pessoa.getId(), clock);
        switch (estado) {
            case AGUARDANDO_APROVACAO -> { }
            case ATIVO -> m.approve(Role.CUIDADOR, g.responsavel().getId(), clock);
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, g.responsavel().getId(), clock);
                m.remove(clock);
            }
            default -> throw new IllegalArgumentException(estado.name());
        }
        memberships.save(m);
        return new Conta(pessoa, m.getId(), clock.instant(), g);
    }

    /** Conta verificada com membership ATIVA (Familiar) no grupo. */
    private User familiarAtiva(Grupo g, String nome) {
        User user = conta(nome, PREFIXO_EMAIL + "fam-");
        Membership m = Membership.request(g.groupId(), user.getId(), clock);
        m.approve(Role.FAMILIAR, g.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private User conta(String nome, String prefixoEmail) {
        String sufixo = UUID.randomUUID().toString().replace("-", "");
        String telefone = "219" + String.format("%08d", Math.abs(sufixo.hashCode()) % 100_000_000);
        Endereco endereco = new Endereco(
                "22041001", "Rua " + sufixo.substring(0, 8), "370", null, "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(nome, prefixoEmail + sufixo + "@teste.com", telefone, endereco, "hash-irrelevante", clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    private static String emailNovo() {
        return PREFIXO_EMAIL + "outro-" + UUID.randomUUID() + "@teste.com";
    }

    private static Map<String, Object> enderecoJson() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("zipCode", "22041001");
        end.put("street", "Rua Barata Ribeiro");
        end.put("number", "370");
        end.put("complement", "Apto 802");
        end.put("district", "Copacabana");
        end.put("city", "Rio de Janeiro");
        end.put("state", "RJ");
        return end;
    }
}
