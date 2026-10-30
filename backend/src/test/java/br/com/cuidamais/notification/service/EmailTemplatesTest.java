package br.com.cuidamais.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.notification.event.AccountDeletedEmail;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import br.com.cuidamais.notification.event.DuplicateSignupEmail;
import br.com.cuidamais.notification.event.ElderReminderEmail;
import br.com.cuidamais.notification.event.EmailEvent;
import br.com.cuidamais.notification.event.GroupDeletedEmail;
import br.com.cuidamais.notification.event.InvitationEmail;
import br.com.cuidamais.notification.event.PasswordLockEmail;
import br.com.cuidamais.notification.event.PendingRequestEmail;
import br.com.cuidamais.notification.event.RequestResult;
import br.com.cuidamais.notification.event.RequestResultEmail;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-016 — templates pt-BR em texto simples, um por {@link EmailEvent}, sem Spring. Cada template
 * traz os campos do evento e os textos que a spec exige; nenhum traz dado de saúde (Constitution
 * P5). Datas/horas em {@code America/Sao_Paulo} (Constitution §5).
 *
 * <p>API esperada: {@code new EmailTemplates().render(EmailEvent)} devolve {@link EmailMessage}
 * {@code (subject, body)}.
 */
class EmailTemplatesTest {

    private static final String DESTINO = "pessoa@example.com";
    private static final String CODIGO = "483920";

    /** 2026-03-11T12:00Z = 11/03/2026 09:00 em America/Sao_Paulo (UTC-3, sem horário de verão). */
    private static final Instant PRAZO_UTC = Instant.parse("2026-03-11T12:00:00Z");
    private static final String PRAZO_DATA_SP = "11/03/2026";
    private static final String PRAZO_HORA_SP = "09:00";
    private static final String PRAZO_HORA_UTC = "12:00";

    /** 2026-03-10T23:30Z = 10/03/2026 20:30 em America/Sao_Paulo (dia diferente do UTC). */
    private static final Instant BLOQUEIO_ATE_UTC = Instant.parse("2026-03-10T23:30:00Z");
    private static final String BLOQUEIO_DATA_SP = "10/03/2026";
    private static final String BLOQUEIO_HORA_SP = "20:30";

    /**
     * Termos de <em>dados</em> de saúde que nunca podem aparecer em nome de campo de evento nem em
     * texto de e-mail (P5): alergias, condições, diagnósticos, medicamentos, sintomas, prontuário.
     */
    private static final Pattern TERMOS_DE_SAUDE = Pattern.compile(
            "alerg|condi[cç][aã]o|condition|diagn|medic|rem[eé]dio|doen[cç]|disease|"
                    + "press[aã]o|glic|sintoma|symptom|prontu[aá]rio",
            Pattern.CASE_INSENSITIVE);

    private final EmailTemplates templates = new EmailTemplates();

    // ---------------------------------------------------------------------------------------
    // Todos os 9 eventos
    // ---------------------------------------------------------------------------------------

    static Stream<Arguments> todosOsEventos() {
        return Stream.of(
                Arguments.of("código de confirmação de e-mail (AC-001.1, AC-005)",
                        new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.CONFIRMACAO_EMAIL)),
                Arguments.of("código de redefinição de senha (AC-010.1)",
                        new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.REDEFINICAO_SENHA)),
                Arguments.of("tentativa de cadastro duplicado (AC-001.4)", new DuplicateSignupEmail(DESTINO)),
                Arguments.of("convite (AC-003.1)",
                        new InvitationEmail(DESTINO, CODIGO, "Alexandre Barreto", "Maria")),
                Arguments.of("pedido pendente ao Responsável (AC-006.7)",
                        new PendingRequestEmail(DESTINO, "Joana Silva", PRAZO_UTC)),
                Arguments.of("pedido aprovado (AC-007.1)", new RequestResultEmail(DESTINO, RequestResult.APROVADO)),
                Arguments.of("pedido recusado (AC-007.1)", new RequestResultEmail(DESTINO, RequestResult.RECUSADO)),
                Arguments.of("pedido expirado (AC-007.1)", new RequestResultEmail(DESTINO, RequestResult.EXPIRADO)),
                Arguments.of("bloqueio de senha (AC-008.4)", new PasswordLockEmail(DESTINO, BLOQUEIO_ATE_UTC)),
                Arguments.of("grupo excluído (AC-013.3)", new GroupDeletedEmail(DESTINO)),
                Arguments.of("lembrete de cadastrar o idoso (AC-014.3)", new ElderReminderEmail(DESTINO)),
                Arguments.of("conta excluída por retenção (AC-014.1, AC-014.3)", new AccountDeletedEmail(DESTINO)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todosOsEventos")
    @DisplayName("T-016 cada evento gera assunto e corpo não vazios")
    void t_016_cada_evento_gera_assunto_e_corpo(String descricao, EmailEvent evento) {
        EmailMessage mensagem = templates.render(evento);

        assertThat(mensagem.subject()).isNotBlank();
        assertThat(mensagem.body()).isNotBlank();
        assertThat(mensagem.subject()).doesNotContain("\n", "\r");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todosOsEventos")
    @DisplayName("P5 nenhum assunto ou corpo contém termo de saúde")
    void p5_nenhum_template_contem_termo_de_saude(String descricao, EmailEvent evento) {
        EmailMessage mensagem = templates.render(evento);

        assertThat(mensagem.subject()).doesNotContainPattern(TERMOS_DE_SAUDE);
        assertThat(mensagem.body()).doesNotContainPattern(TERMOS_DE_SAUDE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todosOsEventos")
    @DisplayName("T-016 corpo é texto simples pt-BR, sem HTML")
    void t_016_corpo_e_texto_simples(String descricao, EmailEvent evento) {
        EmailMessage mensagem = templates.render(evento);

        assertThat(mensagem.body()).doesNotContainPattern("<[a-zA-Z/][^>]*>");
    }

    // ---------------------------------------------------------------------------------------
    // P5 por construção: os records só carregam o que o template precisa
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("P5 EmailEvent é selado com exatamente os 9 tipos de e-mail da feature, todos records")
    void p5_email_event_e_selado_com_os_nove_tipos() {
        assertThat(EmailEvent.class.isSealed()).isTrue();
        Class<?>[] tipos = EmailEvent.class.getPermittedSubclasses();

        assertThat(tipos).extracting(Class::getSimpleName).containsExactlyInAnyOrder(
                "ConfirmationCodeEmail",
                "DuplicateSignupEmail",
                "InvitationEmail",
                "PendingRequestEmail",
                "RequestResultEmail",
                "PasswordLockEmail",
                "GroupDeletedEmail",
                "ElderReminderEmail",
                "AccountDeletedEmail");
        assertThat(tipos).allMatch(Class::isRecord);
    }

    @Test
    @DisplayName("P5 nenhum record de evento tem componente com nome ligado a saúde")
    void p5_nenhum_evento_tem_campo_de_saude() {
        List<String> nomesDeCampo = Arrays.stream(EmailEvent.class.getPermittedSubclasses())
                .flatMap(tipo -> Arrays.stream(tipo.getRecordComponents()))
                .map(c -> c.getDeclaringRecord().getSimpleName() + "." + c.getName())
                .toList();

        assertThat(nomesDeCampo).isNotEmpty();
        assertThat(nomesDeCampo).allSatisfy(nome -> assertThat(nome).doesNotContainPattern(TERMOS_DE_SAUDE));
    }

    @Test
    @DisplayName("P5 componentes dos eventos são só valores simples (String, Instant, enum), nunca entidades")
    void p5_componentes_sao_valores_simples() {
        List<RecordComponent> componentes = Arrays.stream(EmailEvent.class.getPermittedSubclasses())
                .flatMap(tipo -> Arrays.stream(tipo.getRecordComponents()))
                .toList();

        assertThat(componentes).allSatisfy(c -> assertThat(c.getType())
                .as("%s.%s", c.getDeclaringRecord().getSimpleName(), c.getName())
                .matches(t -> t == String.class || t == Instant.class || t.isEnum()));
    }

    // ---------------------------------------------------------------------------------------
    // Código de confirmação (AC-001.1, AC-005.1, AC-010.1)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-001.1 / AC-005.1 e-mail de confirmação traz o código de 6 dígitos e a validade de 15 minutos")
    void ac_001_1_confirmacao_traz_codigo_e_validade() {
        EmailMessage mensagem = templates.render(
                new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.CONFIRMACAO_EMAIL));

        assertThat(mensagem.body()).contains(CODIGO);
        assertThat(mensagem.body()).contains("15 minutos");
        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("confirm");
    }

    @Test
    @DisplayName("AC-010.1 e-mail de redefinição de senha traz o código, a validade e fala de senha")
    void ac_010_1_redefinicao_traz_codigo_e_fala_de_senha() {
        EmailMessage mensagem = templates.render(
                new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.REDEFINICAO_SENHA));

        assertThat(mensagem.body()).contains(CODIGO);
        assertThat(mensagem.body()).contains("15 minutos");
        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("senha");
    }

    @Test
    @DisplayName("AC-005 confirmação de e-mail e redefinição de senha têm assuntos diferentes")
    void ac_005_assuntos_diferem_por_finalidade() {
        EmailMessage confirmacao = templates.render(
                new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.CONFIRMACAO_EMAIL));
        EmailMessage redefinicao = templates.render(
                new ConfirmationCodeEmail(DESTINO, CODIGO, OtpPurpose.REDEFINICAO_SENHA));

        assertThat(confirmacao.subject()).isNotEqualTo(redefinicao.subject());
    }

    // ---------------------------------------------------------------------------------------
    // Cadastro duplicado (AC-001.4)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-001.4 e-mail de tentativa duplicada avisa da tentativa e orienta a entrar com a senha ou redefini-la")
    void ac_001_4_duplicado_orienta_entrar_ou_redefinir_senha() {
        EmailMessage mensagem = templates.render(new DuplicateSignupEmail(DESTINO));

        assertThat(mensagem.body()).containsIgnoringCase("tentativa");
        assertThat(mensagem.body()).containsIgnoringCase("senha");
        assertThat(mensagem.body()).containsIgnoringCase("redefin");
    }

    // ---------------------------------------------------------------------------------------
    // Convite (AC-003.1)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-003.1 convite traz código, nome do Responsável, primeiro nome do idoso e instrução da tela de login")
    void ac_003_1_convite_traz_codigo_responsavel_idoso_e_instrucao() {
        EmailMessage mensagem = templates.render(
                new InvitationEmail(DESTINO, CODIGO, "Alexandre Barreto", "Maria"));

        assertThat(mensagem.body()).contains(CODIGO);
        assertThat(mensagem.body()).contains("Alexandre Barreto");
        assertThat(mensagem.body()).contains("Maria");
        assertThat(mensagem.body()).containsIgnoringCase("login");
        assertThat(mensagem.body()).as("nome da opção da tela de login (AC-004.1)")
                .contains("Tenho um código de convite");
    }

    @Test
    @DisplayName("AC-003.1 convite informa a validade de 7 dias do código (AC-003.2)")
    void ac_003_1_convite_informa_validade_de_7_dias() {
        EmailMessage mensagem = templates.render(
                new InvitationEmail(DESTINO, CODIGO, "Alexandre Barreto", "Maria"));

        assertThat(mensagem.body()).contains("7 dias");
    }

    // ---------------------------------------------------------------------------------------
    // Pedido pendente ao Responsável (AC-006.7)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-006.7 pedido pendente traz o nome da pessoa e o prazo em America/Sao_Paulo")
    void ac_006_7_pendente_traz_nome_e_prazo_em_sao_paulo() {
        EmailMessage mensagem = templates.render(new PendingRequestEmail(DESTINO, "Joana Silva", PRAZO_UTC));

        assertThat(mensagem.body()).contains("Joana Silva");
        assertThat(mensagem.body()).contains(PRAZO_DATA_SP);
        assertThat(mensagem.body()).contains(PRAZO_HORA_SP);
        assertThat(mensagem.body()).as("o horário não pode ser o de UTC").doesNotContain(PRAZO_HORA_UTC);
    }

    @Test
    @DisplayName("AC-006.7 pedido pendente orienta a aprovar ou recusar")
    void ac_006_7_pendente_orienta_aprovar_ou_recusar() {
        EmailMessage mensagem = templates.render(new PendingRequestEmail(DESTINO, "Joana Silva", PRAZO_UTC));

        assertThat(mensagem.body()).containsIgnoringCase("aprov");
        assertThat(mensagem.body()).containsIgnoringCase("recus");
    }

    // ---------------------------------------------------------------------------------------
    // Resultado do pedido (AC-007.1, AC-007.2)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-007.1 pedido aprovado diz que foi aprovado e que a pessoa já pode entrar; não manda pedir novo convite")
    void ac_007_1_aprovado_diz_que_pode_entrar() {
        EmailMessage mensagem = templates.render(new RequestResultEmail(DESTINO, RequestResult.APROVADO));

        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("aprovad");
        assertThat(mensagem.body()).containsIgnoringCase("entrar");
        assertThat(mensagem.body()).doesNotContainIgnoringCase("novo convite");
    }

    @Test
    @DisplayName("AC-007.2 pedido recusado orienta a pedir um novo convite ao Responsável")
    void ac_007_2_recusado_orienta_pedir_novo_convite() {
        EmailMessage mensagem = templates.render(new RequestResultEmail(DESTINO, RequestResult.RECUSADO));

        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("recusad");
        assertThat(mensagem.body()).containsIgnoringCase("novo convite");
        assertThat(mensagem.body()).containsIgnoringCase("respons");
    }

    @Test
    @DisplayName("AC-007.2 pedido expirado orienta a pedir um novo convite ao Responsável")
    void ac_007_2_expirado_orienta_pedir_novo_convite() {
        EmailMessage mensagem = templates.render(new RequestResultEmail(DESTINO, RequestResult.EXPIRADO));

        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("expir");
        assertThat(mensagem.body()).containsIgnoringCase("novo convite");
        assertThat(mensagem.body()).containsIgnoringCase("respons");
    }

    @ParameterizedTest
    @EnumSource(RequestResult.class)
    @DisplayName("AC-007.1 cada resultado tem assunto próprio")
    void ac_007_1_cada_resultado_tem_assunto_proprio(RequestResult resultado) {
        EmailMessage mensagem = templates.render(new RequestResultEmail(DESTINO, resultado));

        List<String> outrosAssuntos = Arrays.stream(RequestResult.values())
                .filter(r -> r != resultado)
                .map(r -> templates.render(new RequestResultEmail(DESTINO, r)).subject())
                .toList();
        assertThat(outrosAssuntos).doesNotContain(mensagem.subject());
    }

    // ---------------------------------------------------------------------------------------
    // Bloqueio de senha (AC-008.4)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-008.4 e-mail de bloqueio diz que a conta foi bloqueada e até quando, em America/Sao_Paulo")
    void ac_008_4_bloqueio_informa_ate_quando_em_sao_paulo() {
        EmailMessage mensagem = templates.render(new PasswordLockEmail(DESTINO, BLOQUEIO_ATE_UTC));

        assertThat(mensagem.body()).containsIgnoringCase("bloquead");
        assertThat(mensagem.body()).containsIgnoringCase("senha");
        assertThat(mensagem.body()).contains(BLOQUEIO_DATA_SP);
        assertThat(mensagem.body()).contains(BLOQUEIO_HORA_SP);
        assertThat(mensagem.body()).as("a data não pode ser a de UTC (11/03)").doesNotContain("11/03/2026");
    }

    // ---------------------------------------------------------------------------------------
    // Grupo excluído (AC-013.3)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-013.3 e-mail de grupo excluído diz que o grupo foi excluído e que os dados da pessoa foram apagados")
    void ac_013_3_grupo_excluido_diz_que_dados_foram_apagados() {
        EmailMessage mensagem = templates.render(new GroupDeletedEmail(DESTINO));

        assertThat(mensagem.subject() + " " + mensagem.body()).containsIgnoringCase("exclu");
        assertThat(mensagem.body()).containsIgnoringCase("grupo");
        assertThat(mensagem.body()).containsIgnoringCase("apagad");
    }

    // ---------------------------------------------------------------------------------------
    // Retenção (AC-014.1, AC-014.3)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC-014.3 lembrete pede para concluir o cadastro do idoso e avisa que a conta será excluída em 7 dias")
    void ac_014_3_lembrete_pede_cadastro_do_idoso_em_7_dias() {
        EmailMessage mensagem = templates.render(new ElderReminderEmail(DESTINO));

        assertThat(mensagem.body()).containsIgnoringCase("idoso");
        assertThat(mensagem.body()).containsIgnoringCase("cadastr");
        assertThat(mensagem.body()).contains("7 dias");
        assertThat(mensagem.body()).containsIgnoringCase("exclu");
        // Pela spec não existe grupo antes do idoso (AC-001.2, AC-002.1): o lembrete não pode
        // falar de um grupo já criado.
        assertThat(mensagem.body()).doesNotContainIgnoringCase("criou um grupo");
        assertThat(mensagem.body()).doesNotContainPattern(Pattern.compile("o grupo ser(á|ão)", Pattern.CASE_INSENSITIVE));
    }

    @Test
    @DisplayName("AC-014.1 e-mail de exclusão é curto, diz que a conta foi excluída e os dados apagados, sem citar motivo")
    void ac_014_1_exclusao_e_curta_e_diz_que_a_conta_foi_excluida() {
        EmailMessage mensagem = templates.render(new AccountDeletedEmail(DESTINO));

        assertThat(mensagem.body()).containsIgnoringCase("conta");
        assertThat(mensagem.body()).containsIgnoringCase("exclu");
        assertThat(mensagem.body()).containsIgnoringCase("apagad");
        assertThat(mensagem.body().length()).as("e-mail curto (AC-014.1)").isLessThanOrEqualTo(500);
        // O mesmo e-mail serve para RECUSADO/EXPIRADO (AC-014.1) e para o criador sem idoso
        // (AC-014.3): não pode mencionar o motivo da exclusão.
        assertThat(mensagem.body()).doesNotContainIgnoringCase("prazo");
        assertThat(mensagem.body()).doesNotContainIgnoringCase("não ter sido concluíd");
    }
}
