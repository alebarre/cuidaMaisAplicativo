package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.jobs.JobRunner;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * T-028 (plan D-09 rev. 2, item 2) — rotina agendada que descarta contas em
 * {@code AGUARDANDO_CONFIRMACAO_EMAIL} (sem {@code email_verified_at}) cujo cadastro completou
 * 24 horas sem a pessoa confirmar o e-mail. Único AC coberto: <b>AC-001.5</b> ("é descartada, sem
 * e-mail de aviso: o e-mail fica livre para um novo 'Criar grupo de cuidado'"), e a matriz de
 * notificação da spec confirma o silêncio: nenhum e-mail ao dono, porque a conta deixa de existir.
 *
 * <p>Infra de jobs já existe (T-019): a rotina real desta tarefa ainda não existe — é a primeira
 * rotina de produção de D-09. Por isso estes testes não dependem de nenhuma classe nova: usam
 * {@link JobRunner#runAll()} (já com bean no contexto de {@link IntegrationTest}) junto do
 * {@link UserRepository} e, no cenário de ponta a ponta, as rotas HTTP reais de
 * {@code /auth/signup} e {@code /auth/login}. O "agora" das contas e da rotina vem sempre do
 * {@link br.com.cuidamais.shared.testsupport.MutableClock} herdado de {@link IntegrationTest}
 * (D-10) — nunca do relógio de sistema —, avançado com {@code clock.advance(...)} a partir do
 * valor atual, para que os testes não dependam da ordem de execução nem de um valor absoluto que
 * outra classe de teste possa ter avançado antes.
 *
 * <p><b>Decisão da borda dos "24 horas" (escolhida nesta tarefa, pois a frase do AC-001.5
 * ("não é confirmada em 24 horas") e a de D-09 rev. 2 ("created_at > 24 h") são compatíveis com
 * mais de uma leitura):</b> o corte é <b>fechado</b> — uma conta já é elegível para o descarte no
 * instante exato em que completa 24 h sem confirmação ({@code agora - createdAt >= 24h}, ou seja
 * {@code createdAt + 24h <= agora}), não só estritamente depois. Isso evita uma rodada do job
 * ter de esperar mais um ciclo além do prazo anunciado à pessoa ("24 horas"). O teste da borda
 * inferior (23h59min, ainda dentro do prazo) prova que não é "qualquer idade"; o teste da borda
 * exata (24h0min0s) prova que o corte não exige "mais que 24h e um pouco".
 */
@AutoConfigureMockMvc
class DescarteContasNaoConfirmadasTest extends IntegrationTest {

    private static final Duration PRAZO_CONFIRMACAO = Duration.ofHours(24);
    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String SENHA = "senhaForte1";

    private final ObjectMapper json = new ObjectMapper();
    private final MailpitClient mailpit = mailpit();

    @Autowired
    JobRunner runner;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void limpaCaixaDoMailpit() {
        // Cada teste confere o Mailpit a partir de uma caixa vazia, para não confundir mensagens
        // de uma rodada com as de outra (mesmo padrão de SignupTest/LoginTest).
        mailpit.deleteAll();
    }

    // ============================================================ AC-001.5 caso positivo: descarte

    /**
     * AC-001.5, núcleo da tarefa: uma conta criada agora, nunca confirmada, some do banco depois
     * que o relógio avança exatamente 24 h e a rotina roda. Prova o "Pronto quando" da T-028: "com
     * Clock avançado 24h a conta some".
     */
    @Test
    @DisplayName("AC-001.5 conta não confirmada ao completar exatamente 24h é descartada pela rotina")
    void ac_001_5_conta_nao_confirmada_ao_completar_24h_e_descartada() {
        User conta = users.save(contaNaoConfirmada());
        UUID id = conta.getId();

        clock.advance(PRAZO_CONFIRMACAO);
        runner.runAll();

        assertThat(users.findById(id)).as("a conta não existe mais depois do descarte").isEmpty();
        assertThat(users.findByEmail(conta.getEmail())).as("também não é mais encontrável pelo e-mail").isEmpty();
    }

    // ============================================================ AC-001.5 e-mail fica livre

    /**
     * AC-001.5 "o e-mail fica livre para um novo 'Criar grupo de cuidado'": depois do descarte, um
     * novo cadastro com o MESMO e-mail não encontra nenhum conflito de unicidade — a rotina de
     * fato liberou o e-mail, não só marcou a conta como inativa.
     */
    @Test
    @DisplayName("AC-001.5 depois do descarte, o e-mail fica livre para um novo signup sem conflito")
    void ac_001_5_apos_descarte_email_fica_livre_para_novo_signup() {
        User antiga = users.save(contaNaoConfirmada());
        String email = antiga.getEmail();
        UUID idAntigo = antiga.getId();

        clock.advance(PRAZO_CONFIRMACAO);
        runner.runAll();
        assertThat(users.findById(idAntigo)).isEmpty();

        // O novo cadastro usa exatamente o mesmo e-mail (já em minúsculas) da conta descartada.
        User nova = User.signup(
                "Outra Pessoa", email, "21999998888", enderecoValido(), passwordEncoder.encode(SENHA), clock);
        assertThat(users.findByEmail(email)).as("antes de salvar, o e-mail já está livre (sem conta nenhuma)").isEmpty();
        User salva = users.save(nova);

        assertThat(salva.getId()).as("é uma conta nova, com id diferente da antiga").isNotEqualTo(idAntigo);
        assertThat(users.findByEmail(email)).isPresent();
    }

    // ============================================================ AC-001.5 silêncio total

    /**
     * AC-001.5 "sem e-mail de aviso" / matriz de notificação ("à pessoa: não (silencioso)"): depois
     * do descarte, nenhuma mensagem chega ao Mailpit para o e-mail descartado — nem um aviso de
     * descarte, nem qualquer outra mensagem disparada pela rotina.
     */
    @Test
    @DisplayName("AC-001.5 o descarte é silencioso: nenhum e-mail chega ao Mailpit para a conta descartada")
    void ac_001_5_descarte_e_silencioso_nenhum_email_no_mailpit() {
        User conta = users.save(contaNaoConfirmada());
        String email = conta.getEmail();

        clock.advance(PRAZO_CONFIRMACAO);
        runner.runAll();

        assertThat(mailpit.messagesTo(email))
                .as("P5/D-09: descarte silencioso, sem aviso nenhum ao dono do e-mail")
                .isEmpty();
    }

    // ============================================================ AC-001.5 conta confirmada sobrevive

    /**
     * AC-001.5 só se aplica a contas {@code AGUARDANDO_CONFIRMACAO_EMAIL}: uma conta já confirmada
     * (email_verified_at preenchido), mesmo com o relógio avançando bem além de 24 h desde a
     * criação, sobrevive à rotina — prova que o filtro é por {@code emailVerifiedAt IS NULL}, não
     * só pela idade da conta.
     */
    @Test
    @DisplayName("AC-001.5 conta já confirmada não é descartada, mesmo com created_at com mais de 24h")
    void ac_001_5_conta_confirmada_nao_e_descartada_mesmo_com_created_at_antigo() {
        User conta = contaNaoConfirmada();
        conta.verifyEmail(clock); // confirma no mesmo instante da criação, antes de avançar o relógio.
        UUID id = users.save(conta).getId();

        // Bem mais que 24h depois da criação/confirmação: só a idade, isoladamente, não bastaria
        // para descartar — e de fato não descarta, porque a conta está confirmada.
        clock.advance(PRAZO_CONFIRMACAO.multipliedBy(2));
        runner.runAll();

        assertThat(users.findById(id)).as("conta confirmada sobrevive à rotina de descarte").isPresent();
    }

    // ============================================================ AC-001.5 conta recente sobrevive

    /**
     * AC-001.5 "24 horas" é um prazo, não "qualquer idade": uma conta ainda não confirmada, mas
     * criada há só 23h59min (1 minuto antes de completar o prazo), continua existindo depois da
     * rotina rodar.
     */
    @Test
    @DisplayName("AC-001.5 conta não confirmada com menos de 24h (23h59) não é descartada")
    void ac_001_5_conta_recente_ainda_nao_confirmada_nao_e_descartada() {
        User conta = users.save(contaNaoConfirmada());
        UUID id = conta.getId();

        clock.advance(PRAZO_CONFIRMACAO.minusMinutes(1));
        runner.runAll();

        assertThat(users.findById(id)).as("ainda dentro do prazo de 24h: a conta continua existindo").isPresent();
    }

    // ============================================================ AC-001.5 fluxo completo (ponta a ponta)

    /**
     * AC-001.5, "Pronto quando" da T-028 visto de fora, pelas rotas HTTP reais: um cadastro feito
     * por {@code POST /auth/signup}, nunca confirmado, 24h depois é descartado pela rotina; uma
     * tentativa de login com o e-mail e a senha daquela conta recebe o mesmo 401 genérico de
     * "e-mail ou senha inválidos" que uma conta inexistente qualquer — porque, depois do descarte,
     * a conta de fato não existe mais (não é um 403 de estado, nem um 200).
     */
    @Test
    @DisplayName("AC-001.5 fluxo completo: signup real, 24h sem confirmar, descarte, e o login da conta some como 401 genérico")
    void ac_001_5_fluxo_completo_signup_descarte_e_login_recebe_conta_inexistente() throws Exception {
        String email = emailUnico();
        mockMvc.perform(signup(requisicaoDeSignup(email)))
                .andExpect(status().isAccepted())
                .andExpect(ContractValidation.matchesContract());
        assertThat(users.findByEmail(email)).as("o cadastro de fato criou a conta").isPresent();

        clock.advance(PRAZO_CONFIRMACAO);
        runner.runAll();
        assertThat(users.findByEmail(email)).as("a conta foi descartada pela rotina").isEmpty();

        mockMvc.perform(entrar(email, SENHA))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(ContractValidation.matchesContract());
    }

    // ============================================================ apoio

    private static String emailUnico() {
        return "descarte-" + UUID.randomUUID() + "@teste.com";
    }

    private static Endereco enderecoValido() {
        return new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
    }

    /** Conta recém-criada (AGUARDANDO_CONFIRMACAO_EMAIL), ainda não persistida. */
    private User contaNaoConfirmada() {
        return User.signup(
                "Maria da Silva",
                emailUnico(),
                "21987654321",
                enderecoValido(),
                passwordEncoder.encode(SENHA),
                clock);
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
        req.put("name", "Maria da Silva");
        req.put("email", email);
        req.put("phone", "21987654321");
        req.put("address", endereco);
        req.put("password", SENHA);
        return req;
    }

    private RequestBuilder signup(Map<String, Object> corpo) throws Exception {
        return post(SIGNUP).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }

    private RequestBuilder entrar(String email, String senha) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("email", email);
        corpo.put("password", senha);
        return post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo));
    }
}
