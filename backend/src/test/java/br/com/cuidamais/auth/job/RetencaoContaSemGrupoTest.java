package br.com.cuidamais.auth.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.CareGroup;
import br.com.cuidamais.group.repository.CareGroupRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * T-034 (plan D-09 rev. 2, item 3, segunda metade) — rotina agendada de retenção de uma conta
 * {@code ATIVO} (e-mail confirmado) que nunca cadastrou o idoso, ou seja, que nunca ganhou
 * nenhuma {@code Membership} (critério "ATIVO sem grupo" de D-33, já usado por {@code
 * AccountState#hasGroup()} e testado em {@code MyAccountStateTest}: existe {@code
 * memberships.user_id} para a conta, de qualquer status, ela já "tem grupo" e está fora do
 * escopo desta rotina). Único AC coberto: <b>AC-014.3</b> — lembrete aos 23 dias (e-mail de
 * {@code ElderReminderEmail}, gravado em {@code elder_reminder_sent_at} para não duplicar) e
 * exclusão completa aos 30 dias desde a confirmação do e-mail, com o mesmo e-mail curto de
 * exclusão de AC-014.1 ({@code AccountDeletedEmail}).
 *
 * <p><b>Fora do escopo desta classe</b> (apesar de estarem no mesmo parágrafo de D-09 item 3 do
 * plan): a retenção de membership {@code RECUSADO}/{@code EXPIRADO} via {@code
 * retention_deadline} é AC-014.1, tarefa <b>T-049</b> (futura); esta classe não cria nem observa
 * nenhuma membership nesses dois estados, nem toca em {@code retention_deadline}.
 *
 * <p>Infra de e-mail (plan D-23) e de job (T-019/D-42, reaproveitada por T-028/T-034) já existem
 * por completo — ver {@code ElderReminderEmail}, {@code AccountDeletedEmail}, {@code
 * EmailTemplates#elderReminder()}/{@code #accountDeleted()}, {@code JobRunner}, {@code
 * SkipLockedClaimer}. A rotina de produção desta tarefa (algo como {@code
 * RetencaoContaSemGrupoRoutine}) <b>ainda não existe</b>; estes testes não dependem de nenhuma
 * classe nova, só de {@link JobRunner#runAll()} (que já descobre e roda toda {@code
 * ScheduledRoutine} do contexto, hoje só a de T-028) junto de {@link UserRepository}, {@link
 * MembershipRepository}/{@link CareGroupRepository} (para fabricar uma conta "com grupo" no
 * cenário de controle) e {@link MailpitClient}. O "agora" das contas e da rotina vem sempre do
 * {@code MutableClock} herdado de {@link IntegrationTest} (D-10), avançado com {@code
 * clock.advance(...)} a partir do valor corrente — nunca do relógio de sistema.
 *
 * <p><b>Borda dos "23"/"30 dias" (fechada, mesma convenção de {@code
 * DescarteContasNaoConfirmadasTest} para as 24h de AC-001.5):</b> uma conta já é elegível para o
 * lembrete/exclusão no instante exato em que completa 23/30 dias desde {@code
 * email_verified_at} ({@code emailVerifiedAt + 23d <= agora} / {@code + 30d <= agora}), não só
 * estritamente depois — para que uma rodada do job não precise esperar mais um ciclo além do
 * prazo anunciado à pessoa.
 *
 * <p>O envio de e-mail é pós-commit e assíncrono (D-23: {@code EmailEventListener}), por isso os
 * testes usam {@code Awaitility} para aguardar a entrega no Mailpit antes de ler o assunto; o
 * teste de "não duplica o lembrete" também confere, com {@code during(...)}, que a contagem se
 * mantém estável por um pequeno intervalo, não só no instante imediato após a segunda rodada —
 * para não deixar passar um segundo envio assíncrono tardio.
 */
@AutoConfigureMockMvc
class RetencaoContaSemGrupoTest extends IntegrationTest {

    private static final Duration PRAZO_LEMBRETE = Duration.ofDays(23);
    private static final Duration PRAZO_EXCLUSAO = Duration.ofDays(30);
    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);

    private static final String ASSUNTO_LEMBRETE = "Cuida+: conclua o cadastro do idoso";
    private static final String ASSUNTO_EXCLUSAO = "Cuida+: sua conta foi excluída";

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String SENHA = "senhaForte1";
    private static final String NOME = "Maria da Silva";
    private static final String TELEFONE = "21987654321";

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    JobRunner runner;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    CareGroupRepository careGroups;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void limpaCaixaDoMailpit() {
        // Cada teste confere o Mailpit a partir de uma caixa vazia (mesmo padrão de
        // DescarteContasNaoConfirmadasTest/DeleteGroupTest), para não confundir mensagens de uma
        // rodada com as de outra.
        mailpit.deleteAll();
    }

    // ============================================================ AC-014.3 lembrete aos 23 dias

    /**
     * Núcleo do "Pronto quando" da T-034 (primeira metade): uma conta {@code ATIVO} sem nenhuma
     * membership, confirmada agora, ao completar exatamente 23 dias desde a confirmação recebe o
     * lembrete (assunto do template {@code EmailTemplates.elderReminder()}) e continua existindo
     * — o lembrete avisa, não exclui.
     */
    @Test
    @DisplayName("AC-014.3 lembrete chega ao completar exatamente 23 dias desde a confirmação; a conta continua existindo")
    void ac_014_3_lembrete_aos_23_dias_chega_e_conta_continua_existindo() {
        User conta = contaAtivaSemGrupo();
        UUID id = conta.getId();
        String email = conta.getEmail();

        clock.advance(PRAZO_LEMBRETE);
        runner.runAll();

        assertThat(users.findById(id)).as("o lembrete não exclui a conta, só avisa").isPresent();
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
        String assunto = mailpit.message(mailpit.messagesTo(email).getFirst().id()).subject();
        assertThat(assunto)
                .as("assunto do template de lembrete (EmailTemplates.elderReminder())")
                .isEqualTo(ASSUNTO_LEMBRETE);
    }

    // ============================================================ AC-014.3 não reenvia o lembrete

    /**
     * AC-014.3 / {@code elder_reminder_sent_at}: depois do lembrete já enviado aos 23 dias, uma
     * segunda rodada no MESMO instante (sem avançar o relógio outra vez) não produz um segundo
     * e-mail — prova de que a rotina grava a marca de envio e a consulta na rodada seguinte.
     */
    @Test
    @DisplayName("AC-014.3 uma segunda rodada no mesmo instante (sem avançar o relógio) não reenvia o lembrete")
    void ac_014_3_segunda_rodada_sem_avancar_relogio_nao_reenvia_lembrete() {
        User conta = contaAtivaSemGrupo();
        String email = conta.getEmail();

        // Etapa 1: primeira rodada aos 23 dias — já provada isoladamente no teste anterior;
        // aqui só a pré-condição para a etapa central deste teste.
        clock.advance(PRAZO_LEMBRETE);
        runner.runAll();
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));

        // Etapa 2 (central): chama a rotina de novo, sem avançar o relógio. A contagem de
        // mensagens para este e-mail deve permanecer em 1 de forma estável (não só no instante
        // imediato depois da chamada, que poderia coincidir com um envio assíncrono tardio ainda
        // não entregue) — por isso o during(...) além do atMost(...).
        runner.runAll();
        await().atMost(Duration.ofSeconds(5))
                .during(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
    }

    // ============================================================ AC-014.3 conta recente (< 23 dias)

    /**
     * AC-014.3 "23 dias" é um prazo, não "qualquer idade": uma conta confirmada há só 22 dias (1
     * dia antes de completar o prazo do lembrete) não recebe nenhum e-mail de retenção e continua
     * existindo.
     */
    @Test
    @DisplayName("AC-014.3 conta confirmada há 22 dias (ainda dentro do prazo) não recebe lembrete nem é excluída")
    void ac_014_3_conta_com_22_dias_nao_recebe_lembrete_e_continua_existindo() {
        User conta = contaAtivaSemGrupo();
        UUID id = conta.getId();
        String email = conta.getEmail();

        clock.advance(PRAZO_LEMBRETE.minusDays(1));
        runner.runAll();

        assertThat(users.findById(id)).as("ainda dentro do prazo de 23 dias: a conta continua existindo").isPresent();
        assertThat(mailpit.messagesTo(email)).as("nenhum e-mail de retenção antes do dia 23").isEmpty();
    }

    // ============================================================ AC-014.3 exclusão aos 30 dias

    /**
     * Núcleo do "Pronto quando" da T-034 (segunda metade): reaproveita a linha do tempo do
     * lembrete (dia 23) e avança mais 7 dias (completando os 30 desde a confirmação). A conta é
     * excluída por completo e, NESSE ciclo (segunda rodada), chega exatamente 1 e-mail NOVO — o
     * de exclusão ({@code AccountDeletedEmail}) — nunca um segundo lembrete, mesmo que a conta já
     * tivesse recebido um antes. Ao todo, a caixa do e-mail acumula os 2 e-mails do caso feliz:
     * 1 lembrete (dia 23) + 1 exclusão (dia 30), nunca duplicado nenhum dos dois.
     */
    @Test
    @DisplayName("AC-014.3 aos 30 dias (7 dias após o lembrete) a conta é excluída; só o e-mail de exclusão chega nesse ciclo")
    void ac_014_3_exclusao_aos_30_dias_apos_lembrete_envia_so_email_de_exclusao_nesse_ciclo() {
        User conta = contaAtivaSemGrupo();
        UUID id = conta.getId();
        String email = conta.getEmail();

        // Etapa 1 (dia 23): lembrete, igual ao primeiro teste — aqui é só a montagem da linha do
        // tempo até os 30 dias.
        clock.advance(PRAZO_LEMBRETE);
        runner.runAll();
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));

        // Etapa 2 (mais 7 dias, completando os 30 desde a confirmação): exclusão completa, com
        // exatamente 1 e-mail novo no ciclo (o de exclusão, não um segundo lembrete).
        clock.advance(Duration.ofDays(7));
        runner.runAll();

        assertThat(users.findById(id)).as("a conta foi excluída por completo aos 30 dias").isEmpty();
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(2));
        List<String> assuntos =
                mailpit.messagesTo(email).stream().map(MailpitClient.Summary::subject).toList();
        assertThat(assuntos)
                .as("ao todo: 1 lembrete (dia 23) + 1 exclusão (dia 30), nunca dois lembretes nem duas exclusões")
                .containsExactlyInAnyOrder(ASSUNTO_LEMBRETE, ASSUNTO_EXCLUSAO);
    }

    // ============================================================ AC-014.3 31 dias sem lembrete prévio

    /**
     * Variação explícita do "Pronto quando": simula um agendador que ficou parado e só roda de
     * novo quando a conta já passou dos 30 dias (aqui, 31), sem nenhuma rodada no dia 23 — logo
     * {@code elder_reminder_sent_at} nunca foi gravado para esta conta. Mesmo assim ela é
     * excluída direto, com o e-mail de exclusão, e <b>sem</b> nenhum lembrete nesse ou em algum
     * ciclo anterior — a exclusão não depende de o lembrete ter sido enviado antes.
     */
    @Test
    @DisplayName("AC-014.3 conta confirmada há 31 dias que nunca recebeu lembrete é excluída direto, só com o e-mail de exclusão")
    void ac_014_3_conta_com_31_dias_sem_lembrete_previo_e_excluida_direto_sem_lembrete() {
        User conta = contaAtivaSemGrupo();
        UUID id = conta.getId();
        String email = conta.getEmail();

        clock.advance(Duration.ofDays(31));
        runner.runAll();

        assertThat(users.findById(id)).as("a conta foi excluída, mesmo sem ter passado pelo lembrete antes").isEmpty();
        await().atMost(ESPERA_ENTREGA).untilAsserted(() -> assertThat(mailpit.messagesTo(email)).hasSize(1));
        String assunto = mailpit.message(mailpit.messagesTo(email).getFirst().id()).subject();
        assertThat(assunto)
                .as("só o e-mail de exclusão chega: nenhum lembrete, nem antes nem neste ciclo")
                .isEqualTo(ASSUNTO_EXCLUSAO);
    }

    // ============================================================ AC-014.3 e-mail fica livre depois da exclusão

    /**
     * AC-014.3, mesmo espírito de AC-013.2/AC-001.5 (e-mail liberado por apagamento físico): depois
     * da exclusão por retenção, o MESMO e-mail consegue um novo {@code POST /auth/signup} sem
     * nenhum conflito de unicidade — prova de que a rotina apaga a linha de fato, não marca um
     * estado de "inativo".
     */
    @Test
    @DisplayName("AC-014.3 depois da exclusão por retenção, o mesmo e-mail faz um novo signup sem conflito de unicidade")
    void ac_014_3_apos_exclusao_email_fica_livre_para_novo_signup() throws Exception {
        User antiga = contaAtivaSemGrupo();
        String email = antiga.getEmail();
        UUID idAntigo = antiga.getId();

        clock.advance(PRAZO_EXCLUSAO);
        runner.runAll();
        assertThat(users.findById(idAntigo)).as("pré-condição: a conta antiga já foi excluída pela rotina").isEmpty();

        mockMvc.perform(signup(requisicaoDeSignup(email))).andExpect(status().isAccepted());

        assertThat(users.findByEmail(email)).as("o novo signup gravou uma conta nova com o mesmo e-mail").isPresent();
        assertThat(users.findByEmail(email).orElseThrow().getId())
                .as("é uma conta nova, com id diferente da antiga excluída")
                .isNotEqualTo(idAntigo);
    }

    // ============================================================ AC-014.3 conta COM grupo nunca é tocada

    /**
     * D-33 ("ATIVO sem grupo" exige nenhuma membership): uma conta que já tem uma {@link
     * Membership} (aqui, fabricada direto como administradora de um grupo recém-criado, sem
     * depender do fluxo HTTP de {@code POST /elders}) nunca é tocada por esta rotina, mesmo tendo
     * se confirmado há 40 dias — muito além dos 23/30 dias do prazo. Nem o lembrete, nem a
     * exclusão, se aplicam a uma conta que já "tem grupo".
     */
    @Test
    @DisplayName("AC-014.3 conta ATIVO que já tem grupo (Membership) nunca é tocada pela rotina, mesmo com 40 dias desde a confirmação")
    void ac_014_3_conta_com_grupo_nunca_e_tocada_mesmo_com_40_dias() {
        User conta = contaAtivaSemGrupo();
        UUID id = conta.getId();
        String email = conta.getEmail();
        CareGroup grupo = careGroups.save(CareGroup.create(conta.getId(), clock));
        memberships.save(Membership.admin(grupo.getId(), conta.getId(), clock));

        clock.advance(Duration.ofDays(40));
        runner.runAll();

        assertThat(users.findById(id))
                .as("conta com grupo sobrevive à rotina de retenção desta tarefa (D-33 hasGroup())")
                .isPresent();
        assertThat(mailpit.messagesTo(email))
                .as("nenhum e-mail de retenção (lembrete ou exclusão) chega a uma conta que já tem grupo")
                .isEmpty();
    }

    // ============================================================ AC-014.3 conta não confirmada: fora do escopo

    /**
     * Guarda de escopo entre rotinas: uma conta ainda {@code AGUARDANDO_CONFIRMACAO_EMAIL} (sem
     * {@code email_verified_at}), mesmo com {@code created_at} de 40 dias, nunca recebe nenhum
     * e-mail de retenção <b>desta</b> tarefa (nem lembrete, nem exclusão com o template de
     * AC-014.3) — o filtro desta rotina é {@code emailVerifiedAt IS NOT NULL} (D-33), então ela
     * nunca nem enxerga esta conta.
     *
     * <p>Este teste não afirma que a conta "continua existindo": uma conta não confirmada com
     * mais de 24h é, de fato, apagada pela rotina de T-028 ({@code
     * DescarteContasNaoConfirmadasRoutine}, já registrada no mesmo {@link JobRunner} e fora do
     * escopo de revisão desta tarefa) — comportamento correto e esperado, não um efeito desta
     * rotina nova. A checagem aqui é mais estreita e já basta para o "não mexe acidentalmente":
     * nenhum e-mail chega a esta conta (nem o silêncio de T-028, que não envia nada, nem um
     * lembrete/exclusão vindos por engano desta rotina).
     */
    @Test
    @DisplayName("AC-014.3 conta ainda não confirmada nunca recebe e-mail de retenção desta rotina, mesmo com 40 dias de created_at")
    void ac_014_3_conta_nao_confirmada_nunca_recebe_email_de_retencao_desta_rotina() {
        User conta = users.save(
                User.signup(NOME, emailUnico(), TELEFONE, enderecoValido(), passwordEncoder.encode(SENHA), clock));
        String email = conta.getEmail();

        clock.advance(Duration.ofDays(40));
        runner.runAll();

        assertThat(mailpit.messagesTo(email))
                .as("conta nunca confirmada: nenhum e-mail chega, nem de T-028 (silenciosa) nem de retenção desta tarefa")
                .isEmpty();
    }

    // ============================================================ apoio

    private static String emailUnico() {
        return "retencao-sem-grupo-" + UUID.randomUUID() + "@teste.com";
    }

    private static Endereco enderecoValido() {
        return new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
    }

    /** Conta {@code ATIVO} sem grupo (confirmada agora, sem nenhuma membership), já persistida. */
    private User contaAtivaSemGrupo() {
        User user =
                User.signup(NOME, emailUnico(), TELEFONE, enderecoValido(), passwordEncoder.encode(SENHA), clock);
        user.verifyEmail(clock);
        return users.save(user);
    }

    /** Corpo válido de {@code SignupRequest} (mesmo formato de {@code SignupTest}). */
    private static Map<String, Object> requisicaoDeSignup(String email) {
        Map<String, Object> endereco = new LinkedHashMap<>();
        endereco.put("zipCode", "22041001");
        endereco.put("street", "Rua Barata Ribeiro");
        endereco.put("number", "370");
        endereco.put("complement", "Apto 802");
        endereco.put("district", "Copacabana");
        endereco.put("city", "Rio de Janeiro");
        endereco.put("state", "RJ");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", NOME);
        req.put("email", email);
        req.put("phone", TELEFONE);
        req.put("address", endereco);
        req.put("password", SENHA);
        return req;
    }

    private RequestBuilder signup(Map<String, Object> corpo) throws Exception {
        return post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }
}
