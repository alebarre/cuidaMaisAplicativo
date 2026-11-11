package br.com.cuidamais.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-018 "Pronto quando": testes com {@code Clock} cobrem 4ª e 5ª tentativa, tentativa com código
 * certo durante o bloqueio, fim dos 30 min, zeragem por convite novo e o 4º reenvio automático
 * negado (AC-003.3, AC-003.9, AC-003.11, AC-004.7; D-34, D-09 item 4, D-16).
 *
 * <p>Teste puro de domínio: sem Spring, sem banco. O tempo é controlado com {@link MutableClock}
 * (D-10). Fora do escopo: o endpoint {@code redeem} (T-041), a criação e o reenvio de convites
 * (T-040), o job de reenvio automático (T-048) e o envio de e-mail; aqui só se prova o que essas
 * tarefas vão usar.
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.membership}); todo método que depende do "agora"
 * recebe o {@code Clock} (D-10), inclusive as leituras, porque o fim do bloqueio é verificado
 * <strong>na leitura</strong> (D-09) e não depende de o job já ter rodado:
 *
 * <ul>
 *   <li>{@code static InviteCodeAttempts forEmail(String email, Clock clock)}: contador novo,
 *       zerado, para o e-mail <strong>normalizado</strong> (sem espaços nas pontas e em
 *       minúsculas). E-mail nulo lança {@link NullPointerException} ou {@link
 *       IllegalArgumentException}; vazio ou em branco lança {@link IllegalArgumentException}.
 *   <li>{@code String getEmail()}: o e-mail normalizado (chave do contador).
 *   <li>{@code int failedAttempts(Clock)}: erros acumulados que valem agora, de 0 a 5 (AC-003.8,
 *       D-39); 5 durante o bloqueio, 0 a partir do fim dele.
 *   <li>{@code boolean isBlocked(Clock)}: bloqueado enquanto {@code agora < fim do bloqueio}.
 *   <li>{@code Instant blockedUntil(Clock)}: fim do bloqueio enquanto bloqueado; {@code null} fora
 *       do bloqueio (contrato: "preenchido enquanto o e-mail está bloqueado").
 *   <li>{@code int getAutoResends()}: reenvios automáticos já feitos sem uso de código, de 0 a 3.
 *   <li>{@code InviteCodeAttemptOutcome registerFailedAttempt(Clock)}: código errado ou e-mail sem
 *       convite (AC-004.7). Da 1ª à 4ª devolve {@code blocked = false} e {@code remainingAttempts}
 *       4, 3, 2, 1. Na 5ª bloqueia por 30 minutos e devolve {@code blocked = true}, {@code
 *       remainingAttempts = 0}, {@code blockedUntil = agora + 30 min}. Durante o bloqueio devolve o
 *       mesmo resultado bloqueado <strong>sem alterar nada</strong> (não conta, não estende).
 *   <li>{@code void registerCodeUsed(Clock)}: um código daquele e-mail foi usado com sucesso
 *       (convite {@code USADO}): zera o contador <strong>e</strong> o limite de reenvios
 *       automáticos, e cancela o reenvio automático devido. Durante o bloqueio lança {@link
 *       IllegalStateException} (ou subclasse) sem alterar nada: nenhum código pode ser usado
 *       enquanto o e-mail está bloqueado, mesmo certo (AC-004.7).
 *   <li>{@code void registerInvitationCreated(Clock)}: um convite foi criado para o e-mail (envio,
 *       reenvio manual ou automático): zera o contador, encerra o bloqueio na hora e cancela o
 *       reenvio automático devido (AC-003.9, AC-003.11); <strong>não</strong> mexe em {@code
 *       autoResends}.
 *   <li>{@code boolean isAutoResendDue(Clock)}: {@code true} quando um bloqueio terminou ({@code
 *       agora >= fim}) e o job (D-09 item 4, T-048) ainda não o tratou. Continua {@code true}
 *       mesmo que a pessoa erre de novo depois do fim do bloqueio; deixa de ser {@code true} com
 *       convite criado, código usado, {@code consumeAutoResend} ou {@code dismissAutoResend}.
 *   <li>{@code boolean consumeAutoResend(Clock)}: o job trata o reenvio devido de um e-mail que
 *       tem convite {@code ENVIADO}. Devolve {@code true} e soma 1 em {@code autoResends} se ainda
 *       há menos de 3; devolve {@code false} sem somar se o teto de 3 já foi atingido. Nos dois
 *       casos o reenvio deixa de estar devido. Quando {@code true}, o job cria o convite {@code
 *       AUTO} e chama {@code registerInvitationCreated}. Sem reenvio devido lança {@link
 *       IllegalStateException} sem alterar nada.
 *   <li>{@code void dismissAutoResend(Clock)}: o job trata o reenvio devido de um e-mail
 *       <strong>sem</strong> convite {@code ENVIADO} (AC-003.3: "se não há convite, nada é
 *       enviado"): o reenvio deixa de estar devido e {@code autoResends} não muda. Sem reenvio
 *       devido lança {@link IllegalStateException} sem alterar nada.
 * </ul>
 *
 * <p><strong>Borda do bloqueio (AC-003.3):</strong> bloqueado enquanto {@code agora < fim}; com 30
 * minutos exatos o bloqueio já acabou, mesmo critério de {@link Invitation} (AC-003.2) e de {@link
 * Membership} (AC-006.8).
 *
 * <p>Os valores 5, 30 minutos e 3 são escritos aqui a partir da spec, de propósito sem ler
 * constantes da classe de produção.
 */
class InviteCodeAttemptsTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration BLOQUEIO = Duration.ofMinutes(30);
    private static final String EMAIL = "convidada@example.com";

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ apoio

    private static InviteCodeAttempts novoContador(MutableClock clock) {
        return InviteCodeAttempts.forEmail(EMAIL, clock);
    }

    private static void errar(InviteCodeAttempts attempts, int vezes, MutableClock clock) {
        for (int i = 0; i < vezes; i++) {
            attempts.registerFailedAttempt(clock);
        }
    }

    /** Cinco erros agora; devolve o fim esperado do bloqueio (agora + 30 min). */
    private static Instant bloquear(InviteCodeAttempts attempts, MutableClock clock) {
        errar(attempts, 5, clock);
        assertThat(attempts.isBlocked(clock)).as("pré-condição: e-mail bloqueado").isTrue();
        return clock.instant().plus(BLOQUEIO);
    }

    private static void bloquearEEsperarOFim(InviteCodeAttempts attempts, MutableClock clock) {
        bloquear(attempts, clock);
        clock.advance(BLOQUEIO);
    }

    /**
     * Uma rodada do job (D-09 item 4) para um e-mail com convite {@code ENVIADO}: bloqueio, fim
     * dos 30 min, consumo do reenvio e, se concedido, o convite {@code AUTO} criado.
     */
    private static boolean cicloDoJobComConviteEnviado(
            InviteCodeAttempts attempts, MutableClock clock) {
        bloquearEEsperarOFim(attempts, clock);
        boolean concedido = attempts.consumeAutoResend(clock);
        if (concedido) {
            attempts.registerInvitationCreated(clock);
        }
        return concedido;
    }

    @FunctionalInterface
    interface Cenario {
        void montar(InviteCodeAttempts attempts, MutableClock clock);
    }

    // ------------------------------------------- AC-003.11 contador por e-mail

    @Test
    @DisplayName("AC-003.11 contador novo nasce zerado, sem bloqueio e sem reenvios automáticos")
    void ac_003_11_contador_novo_nasce_zerado_e_sem_bloqueio() {
        InviteCodeAttempts attempts = novoContador(clock);

        assertThat(attempts.failedAttempts(clock)).isZero();
        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.blockedUntil(clock)).isNull();
        assertThat(attempts.getAutoResends()).isZero();
    }

    @ParameterizedTest(name = "AC-003.11 e-mail \"{0}\" vira convidada@example.com")
    @ValueSource(
            strings = {
                "convidada@example.com",
                "Convidada@Example.COM",
                "  convidada@example.com  ",
                "\tCONVIDADA@EXAMPLE.COM\n"
            })
    @DisplayName("AC-003.11 o contador é do e-mail normalizado: sem espaços nas pontas e minúsculo")
    void ac_003_11_contador_guarda_o_email_normalizado(String digitado) {
        InviteCodeAttempts attempts = InviteCodeAttempts.forEmail(digitado, clock);

        assertThat(attempts.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("AC-003.11 contador sem e-mail (nulo) é rejeitado")
    void ac_003_11_email_nulo_e_rejeitado() {
        assertThatThrownBy(() -> InviteCodeAttempts.forEmail(null, clock))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "AC-003.11 e-mail \"{0}\" é rejeitado")
    @ValueSource(strings = {"", " ", "   ", "\t\n"})
    @DisplayName("AC-003.11 contador com e-mail vazio ou em branco é rejeitado")
    void ac_003_11_email_vazio_ou_em_branco_e_rejeitado(String email) {
        assertThatThrownBy(() -> InviteCodeAttempts.forEmail(email, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-003.11 / AC-004.7 erros de um e-mail não contam para outro e-mail")
    void ac_003_11_contadores_de_emails_diferentes_sao_independentes() {
        InviteCodeAttempts daConvidada = novoContador(clock);
        InviteCodeAttempts deOutraPessoa = InviteCodeAttempts.forEmail("outra@example.com", clock);

        bloquear(daConvidada, clock);

        assertThat(deOutraPessoa.failedAttempts(clock)).isZero();
        assertThat(deOutraPessoa.isBlocked(clock)).isFalse();
    }

    // ------------------------------------------- AC-004.7 da 1ª à 4ª tentativa errada

    @ParameterizedTest(name = "AC-004.7 {0}ª tentativa errada: restam {1} tentativas")
    @CsvSource({"1, 4", "2, 3", "3, 2", "4, 1"})
    @DisplayName("AC-004.7 da 1ª à 4ª tentativa errada a resposta informa quantas restam")
    void ac_004_7_tentativa_errada_informa_tentativas_restantes(int tentativa, int restam) {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, tentativa - 1, clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.remainingAttempts()).isEqualTo(restam);
        assertThat(outcome.blockedUntil()).isNull();
    }

    @ParameterizedTest(name = "AC-003.11 depois de {0} erro(s) o contador mostra {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("AC-003.11 / D-39 as tentativas erradas ficam acumuladas no contador do e-mail")
    void ac_003_11_tentativas_erradas_ficam_acumuladas(int erros) {
        InviteCodeAttempts attempts = novoContador(clock);

        errar(attempts, erros, clock);

        assertThat(attempts.failedAttempts(clock)).isEqualTo(erros);
    }

    @Test
    @DisplayName("AC-004.7 a 4ª tentativa errada ainda não bloqueia o e-mail")
    void ac_004_7_quarta_tentativa_errada_ainda_nao_bloqueia() {
        InviteCodeAttempts attempts = novoContador(clock);

        errar(attempts, 4, clock);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.blockedUntil(clock)).isNull();
    }

    // ------------------------------------------- AC-003.3 / AC-004.7 a 5ª tentativa errada

    @Test
    @DisplayName("AC-004.7 a 5ª tentativa errada responde bloqueado, com o fim do bloqueio")
    void ac_004_7_quinta_tentativa_errada_responde_bloqueado() {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, 4, clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.remainingAttempts()).isZero();
        assertThat(outcome.blockedUntil()).isEqualTo(T0.plus(BLOQUEIO));
    }

    @Test
    @DisplayName("AC-003.3 os 30 minutos de bloqueio contam a partir da 5ª tentativa errada")
    void ac_003_3_bloqueio_de_30_minutos_conta_a_partir_da_quinta_tentativa() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 4; i++) {
            attempts.registerFailedAttempt(clock);
            clock.advance(Duration.ofMinutes(2));
        }
        Instant quintaTentativa = clock.instant();

        attempts.registerFailedAttempt(clock);

        assertThat(attempts.isBlocked(clock)).isTrue();
        assertThat(attempts.blockedUntil(clock)).isEqualTo(quintaTentativa.plus(BLOQUEIO));
    }

    @Test
    @DisplayName("AC-003.8 / D-39 durante o bloqueio o contador mostra as 5 tentativas erradas")
    void ac_003_3_durante_o_bloqueio_o_contador_mostra_5_tentativas() {
        InviteCodeAttempts attempts = novoContador(clock);

        bloquear(attempts, clock);

        assertThat(attempts.failedAttempts(clock)).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-003.11 sem bloqueio os erros não caducam: 4 erros e, dias depois, o 5º bloqueia")
    void ac_003_11_erros_nao_caducam_com_o_tempo_sem_bloqueio() {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, 4, clock);
        clock.advance(Duration.ofDays(3));

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.blockedUntil()).isEqualTo(T0.plus(Duration.ofDays(3)).plus(BLOQUEIO));
    }

    // ------------------------------------------- AC-004.7 qualquer tentativa durante o bloqueio

    @ParameterizedTest(name = "AC-004.7 código errado {0} s depois do bloqueio: bloqueado")
    @ValueSource(longs = {0, 60, 900, 1799})
    @DisplayName("AC-004.7 tentativa errada durante o bloqueio é recusada como bloqueada")
    void ac_004_7_tentativa_errada_durante_o_bloqueio_e_recusada_como_bloqueada(long segundos) {
        InviteCodeAttempts attempts = novoContador(clock);
        Instant fimDoBloqueio = bloquear(attempts, clock);
        clock.advance(Duration.ofSeconds(segundos));

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.remainingAttempts()).isZero();
        assertThat(outcome.blockedUntil()).isEqualTo(fimDoBloqueio);
    }

    @Test
    @DisplayName("AC-004.7 tentativa errada durante o bloqueio não estende os 30 minutos")
    void ac_004_7_tentativa_errada_durante_o_bloqueio_nao_estende_o_bloqueio() {
        InviteCodeAttempts attempts = novoContador(clock);
        Instant fimDoBloqueio = bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(20));

        attempts.registerFailedAttempt(clock);

        assertThat(attempts.blockedUntil(clock)).isEqualTo(fimDoBloqueio);
        clock.set(fimDoBloqueio);
        assertThat(attempts.isBlocked(clock)).isFalse();
    }

    @Test
    @DisplayName("AC-004.7 tentativa errada durante o bloqueio não altera o contador")
    void ac_004_7_tentativa_errada_durante_o_bloqueio_nao_altera_o_contador() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(5));

        errar(attempts, 3, clock);

        assertThat(attempts.failedAttempts(clock)).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-004.7 código certo durante o bloqueio é recusado e o bloqueio continua igual")
    void ac_004_7_codigo_certo_durante_o_bloqueio_e_recusado() {
        InviteCodeAttempts attempts = novoContador(clock);
        Instant fimDoBloqueio = bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> attempts.registerCodeUsed(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.isBlocked(clock)).isTrue();
        assertThat(attempts.blockedUntil(clock)).isEqualTo(fimDoBloqueio);
        assertThat(attempts.failedAttempts(clock)).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-004.7 código certo 1 s antes do fim do bloqueio ainda é recusado")
    void ac_004_7_codigo_certo_um_segundo_antes_do_fim_do_bloqueio_e_recusado() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(BLOQUEIO.minusSeconds(1));

        assertThatThrownBy(() -> attempts.registerCodeUsed(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.isBlocked(clock)).isTrue();
    }

    @Test
    @DisplayName("AC-003.3 código certo recusado no bloqueio não zera o limite de reenvios")
    void ac_003_3_codigo_certo_recusado_no_bloqueio_nao_zera_o_limite_de_reenvios() {
        InviteCodeAttempts attempts = novoContador(clock);
        cicloDoJobComConviteEnviado(attempts, clock);
        assertThat(attempts.getAutoResends()).as("pré-condição: 1 reenvio feito").isEqualTo(1);
        bloquear(attempts, clock);

        assertThatThrownBy(() -> attempts.registerCodeUsed(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.getAutoResends()).isEqualTo(1);
    }

    // ------------------------------------------- AC-003.3 / AC-003.11 (a) fim dos 30 minutos

    @Test
    @DisplayName("AC-003.3 com 29 min 59 s o e-mail ainda está bloqueado")
    void ac_003_3_um_segundo_antes_dos_30_minutos_ainda_esta_bloqueado() {
        InviteCodeAttempts attempts = novoContador(clock);
        Instant fimDoBloqueio = bloquear(attempts, clock);
        clock.advance(BLOQUEIO.minusSeconds(1));

        assertThat(attempts.isBlocked(clock)).isTrue();
        assertThat(attempts.blockedUntil(clock)).isEqualTo(fimDoBloqueio);
    }

    @Test
    @DisplayName("AC-003.3 com 30 minutos exatos o bloqueio acabou, sem depender do job")
    void ac_003_3_com_30_minutos_exatos_o_bloqueio_acabou() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(BLOQUEIO);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.blockedUntil(clock)).isNull();
    }

    @Test
    @DisplayName("AC-003.11 (a) ao fim dos 30 minutos o contador volta a zero")
    void ac_003_11_contador_volta_a_zero_ao_fim_do_bloqueio() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(BLOQUEIO);

        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.3 depois do bloqueio a 1ª tentativa errada é a 1ª de 5: restam 4")
    void ac_003_3_depois_do_bloqueio_a_primeira_tentativa_errada_deixa_4() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.remainingAttempts()).isEqualTo(4);
        assertThat(attempts.failedAttempts(clock)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-003.3 depois do bloqueio são 5 tentativas de novo: a 5ª bloqueia por 30 min")
    void ac_003_3_depois_do_bloqueio_a_quinta_tentativa_errada_bloqueia_de_novo() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        clock.advance(Duration.ofHours(1));
        errar(attempts, 4, clock);
        assertThat(attempts.isBlocked(clock)).as("4 erros no novo ciclo não bloqueiam").isFalse();
        Instant quintaTentativa = clock.instant();

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.blockedUntil()).isEqualTo(quintaTentativa.plus(BLOQUEIO));
    }

    @Test
    @DisplayName("AC-004.7 quando o bloqueio termina, um código certo volta a ser aceito")
    void ac_004_7_depois_do_bloqueio_codigo_certo_e_aceito() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);

        attempts.registerCodeUsed(clock);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    // ------------------------------------------- AC-003.9 / AC-003.11 (b) convite criado

    @ParameterizedTest(name = "AC-003.11 (b) convite criado com {0} erro(s) acumulado(s)")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("AC-003.11 (b) criar um convite para o e-mail zera o contador")
    void ac_003_11_criar_convite_zera_o_contador(int erros) {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, erros, clock);

        attempts.registerInvitationCreated(clock);

        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.11 (b) depois de convite criado a 1ª tentativa errada deixa 4")
    void ac_003_11_depois_de_convite_criado_a_primeira_tentativa_errada_deixa_4() {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, 4, clock);
        attempts.registerInvitationCreated(clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.remainingAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("AC-003.9 criar convite durante o bloqueio encerra o bloqueio na hora")
    void ac_003_9_criar_convite_durante_o_bloqueio_encerra_o_bloqueio_na_hora() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(10));

        attempts.registerInvitationCreated(clock);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.blockedUntil(clock)).isNull();
        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.9 convite criado no bloqueio: a tentativa errada seguinte só conta 1")
    void ac_003_9_depois_de_convite_criado_no_bloqueio_tentativa_errada_nao_e_bloqueada() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(10));
        attempts.registerInvitationCreated(clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.remainingAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("AC-003.11 e-mail sem convite bloqueado não inutiliza o convite que vier depois")
    void ac_003_11_bloqueio_de_email_sem_convite_nao_inutiliza_o_convite_que_vier_depois() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(Duration.ofMinutes(1));
        attempts.registerInvitationCreated(clock);

        attempts.registerCodeUsed(clock);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.3 criar convite (reenvio manual) não zera o limite de reenvios automáticos")
    void ac_003_3_criar_convite_nao_zera_o_limite_de_reenvios_automaticos() {
        InviteCodeAttempts attempts = novoContador(clock);
        cicloDoJobComConviteEnviado(attempts, clock);
        cicloDoJobComConviteEnviado(attempts, clock);
        assertThat(attempts.getAutoResends()).as("pré-condição: 2 reenvios feitos").isEqualTo(2);

        attempts.registerInvitationCreated(clock);

        assertThat(attempts.getAutoResends()).isEqualTo(2);
    }

    // ------------------------------------------- AC-003.3 / AC-003.11 (c) código usado

    @ParameterizedTest(name = "AC-003.11 (c) código usado com {0} erro(s) acumulado(s)")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("AC-003.11 (c) usar um código com sucesso zera o contador")
    void ac_003_11_usar_codigo_zera_o_contador(int erros) {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, erros, clock);

        attempts.registerCodeUsed(clock);

        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.3 só erros seguidos bloqueiam: 4 erros, código usado e 1 erro deixam 4")
    void ac_003_3_erros_seguidos_recomecam_depois_de_codigo_usado() {
        InviteCodeAttempts attempts = novoContador(clock);
        errar(attempts, 4, clock);
        attempts.registerCodeUsed(clock);

        InviteCodeAttemptOutcome outcome = attempts.registerFailedAttempt(clock);

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.remainingAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("AC-003.3 usar um código zera o limite de reenvios automáticos do e-mail")
    void ac_003_3_usar_codigo_zera_o_limite_de_reenvios_automaticos() {
        InviteCodeAttempts attempts = novoContador(clock);
        cicloDoJobComConviteEnviado(attempts, clock);
        cicloDoJobComConviteEnviado(attempts, clock);
        assertThat(attempts.getAutoResends()).as("pré-condição: 2 reenvios feitos").isEqualTo(2);

        attempts.registerCodeUsed(clock);

        assertThat(attempts.getAutoResends()).isZero();
    }

    @Test
    @DisplayName("AC-003.3 com o teto atingido, código usado devolve os 3 reenvios automáticos")
    void ac_003_3_depois_de_codigo_usado_ha_3_reenvios_automaticos_de_novo() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 3; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }
        attempts.registerCodeUsed(clock);

        List<Boolean> concedidos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            concedidos.add(cicloDoJobComConviteEnviado(attempts, clock));
        }

        assertThat(concedidos).containsExactly(true, true, true);
        assertThat(attempts.getAutoResends()).isEqualTo(3);
    }

    // ------------------------------------------- AC-003.3 / D-09 item 4: reenvio devido

    @Test
    @DisplayName("AC-003.3 ao fim dos 30 minutos há um reenvio automático devido para o e-mail")
    void ac_003_3_ao_fim_do_bloqueio_ha_reenvio_automatico_devido() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquear(attempts, clock);
        clock.advance(BLOQUEIO);

        assertThat(attempts.isAutoResendDue(clock)).isTrue();
    }

    @ParameterizedTest(name = "AC-003.3 / D-09 {0} s depois do fim do bloqueio o reenvio segue devido")
    @ValueSource(longs = {1, 299, 300, 7200})
    @DisplayName("AC-003.3 / D-09 o reenvio fica devido até o job tratar, não só no instante do fim")
    void ac_003_3_reenvio_continua_devido_ate_o_job_tratar(long segundosDepoisDoFim) {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        clock.advance(Duration.ofSeconds(segundosDepoisDoFim));

        assertThat(attempts.isAutoResendDue(clock)).isTrue();
    }

    @Test
    @DisplayName(
            "AC-003.3 / D-09 tentativa errada entre o fim do bloqueio e o job não perde o reenvio")
    void ac_003_3_tentativa_errada_depois_do_bloqueio_nao_perde_o_reenvio_devido() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        clock.advance(Duration.ofSeconds(1));

        attempts.registerFailedAttempt(clock);

        assertThat(attempts.isAutoResendDue(clock)).isTrue();
    }

    @Test
    @DisplayName(
            "AC-003.3 / D-09 várias tentativas erradas sem novo bloqueio não perdem o reenvio"
                    + " devido")
    void ac_003_3_quatro_tentativas_erradas_depois_do_bloqueio_nao_perdem_o_reenvio_devido() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        clock.advance(Duration.ofMinutes(1));

        errar(attempts, 4, clock);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.isAutoResendDue(clock)).isTrue();
    }

    static Stream<Arguments> cenariosSemReenvioDevido() {
        return Stream.of(
                Arguments.of("AC-003.3 contador novo, sem nenhum erro", (Cenario) (a, c) -> { }),
                Arguments.of(
                        "AC-003.3 4 erros e 2 h depois, sem nunca ter bloqueado",
                        (Cenario)
                                (a, c) -> {
                                    errar(a, 4, c);
                                    c.advance(Duration.ofHours(2));
                                }),
                Arguments.of(
                        "AC-003.3 no instante em que o bloqueio começa",
                        (Cenario) (a, c) -> bloquear(a, c)),
                Arguments.of(
                        "AC-003.3 bloqueado, 1 s antes do fim dos 30 minutos",
                        (Cenario)
                                (a, c) -> {
                                    bloquear(a, c);
                                    c.advance(BLOQUEIO.minusSeconds(1));
                                }),
                Arguments.of(
                        "AC-003.9 convite criado durante o bloqueio, já passados os 30 minutos",
                        (Cenario)
                                (a, c) -> {
                                    bloquear(a, c);
                                    c.advance(Duration.ofMinutes(10));
                                    a.registerInvitationCreated(c);
                                    c.advance(Duration.ofMinutes(20));
                                }),
                Arguments.of(
                        "AC-003.9 convite criado durante o bloqueio, horas depois",
                        (Cenario)
                                (a, c) -> {
                                    bloquear(a, c);
                                    c.advance(Duration.ofMinutes(29));
                                    a.registerInvitationCreated(c);
                                    c.advance(Duration.ofHours(5));
                                }),
                Arguments.of(
                        "AC-003.9 / D-09 convite criado depois do fim do bloqueio e antes do job",
                        (Cenario)
                                (a, c) -> {
                                    bloquearEEsperarOFim(a, c);
                                    c.advance(Duration.ofMinutes(2));
                                    a.registerInvitationCreated(c);
                                }),
                Arguments.of(
                        "AC-003.3 código usado depois do fim do bloqueio e antes do job",
                        (Cenario)
                                (a, c) -> {
                                    bloquearEEsperarOFim(a, c);
                                    c.advance(Duration.ofMinutes(1));
                                    a.registerCodeUsed(c);
                                }),
                Arguments.of(
                        "AC-003.3 / D-42 reenvio automático já consumido pelo job",
                        (Cenario)
                                (a, c) -> {
                                    bloquearEEsperarOFim(a, c);
                                    a.consumeAutoResend(c);
                                }),
                Arguments.of(
                        "AC-003.3 / D-42 reenvio automático negado pelo teto de 3",
                        (Cenario)
                                (a, c) -> {
                                    for (int i = 0; i < 3; i++) {
                                        cicloDoJobComConviteEnviado(a, c);
                                    }
                                    bloquearEEsperarOFim(a, c);
                                    a.consumeAutoResend(c);
                                }),
                Arguments.of(
                        "AC-003.3 / D-42 reenvio dispensado por não haver convite ENVIADO",
                        (Cenario)
                                (a, c) -> {
                                    bloquearEEsperarOFim(a, c);
                                    a.dismissAutoResend(c);
                                }));
    }

    @ParameterizedTest(name = "{0}: não há reenvio automático devido")
    @MethodSource("cenariosSemReenvioDevido")
    @DisplayName("AC-003.3 / AC-003.9 situações em que não há reenvio automático devido")
    void ac_003_3_nao_ha_reenvio_automatico_devido(String situacao, Cenario cenario) {
        InviteCodeAttempts attempts = novoContador(clock);

        cenario.montar(attempts, clock);

        assertThat(attempts.isAutoResendDue(clock)).isFalse();
    }

    @ParameterizedTest(name = "{0}: consumir o reenvio é rejeitado")
    @MethodSource("cenariosSemReenvioDevido")
    @DisplayName("AC-003.3 / AC-003.9 consumir reenvio automático que não está devido é rejeitado")
    void ac_003_3_consumir_reenvio_que_nao_esta_devido_e_rejeitado(
            String situacao, Cenario cenario) {
        InviteCodeAttempts attempts = novoContador(clock);
        cenario.montar(attempts, clock);
        int reenviosAntes = attempts.getAutoResends();
        boolean bloqueadoAntes = attempts.isBlocked(clock);
        int errosAntes = attempts.failedAttempts(clock);

        assertThatThrownBy(() -> attempts.consumeAutoResend(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.getAutoResends()).isEqualTo(reenviosAntes);
        assertThat(attempts.isBlocked(clock)).isEqualTo(bloqueadoAntes);
        assertThat(attempts.failedAttempts(clock)).isEqualTo(errosAntes);
    }

    @ParameterizedTest(name = "{0}: dispensar o reenvio é rejeitado")
    @MethodSource("cenariosSemReenvioDevido")
    @DisplayName("AC-003.3 / AC-003.9 dispensar reenvio automático que não está devido é rejeitado")
    void ac_003_3_dispensar_reenvio_que_nao_esta_devido_e_rejeitado(
            String situacao, Cenario cenario) {
        InviteCodeAttempts attempts = novoContador(clock);
        cenario.montar(attempts, clock);
        int reenviosAntes = attempts.getAutoResends();
        boolean bloqueadoAntes = attempts.isBlocked(clock);
        int errosAntes = attempts.failedAttempts(clock);

        assertThatThrownBy(() -> attempts.dismissAutoResend(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.getAutoResends()).isEqualTo(reenviosAntes);
        assertThat(attempts.isBlocked(clock)).isEqualTo(bloqueadoAntes);
        assertThat(attempts.failedAttempts(clock)).isEqualTo(errosAntes);
    }

    // ------------------------------------------- AC-003.3 teto de 3 reenvios automáticos

    @Test
    @DisplayName("AC-003.3 consumir o reenvio devido é concedido e conta 1 reenvio automático")
    void ac_003_3_consumir_reenvio_devido_e_concedido_e_conta_um() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);

        boolean concedido = attempts.consumeAutoResend(clock);

        assertThat(concedido).isTrue();
        assertThat(attempts.getAutoResends()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-003.3 / D-09 o job consome o reenvio mesmo rodando minutos depois do fim")
    void ac_003_3_job_consome_o_reenvio_minutos_depois_do_fim_do_bloqueio() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        clock.advance(Duration.ofSeconds(30));
        attempts.registerFailedAttempt(clock);
        clock.advance(Duration.ofMinutes(4));

        boolean concedido = attempts.consumeAutoResend(clock);

        assertThat(concedido).isTrue();
        assertThat(attempts.getAutoResends()).isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-003.3 o {0}º reenvio automático é concedido")
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("AC-003.3 até 3 reenvios automáticos são concedidos sem uso de código")
    void ac_003_3_ate_3_reenvios_automaticos_sao_concedidos(int ordem) {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 1; i < ordem; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }

        boolean concedido = cicloDoJobComConviteEnviado(attempts, clock);

        assertThat(concedido).isTrue();
        assertThat(attempts.getAutoResends()).isEqualTo(ordem);
    }

    @Test
    @DisplayName("AC-003.3 o 4º reenvio automático sem uso de código é negado")
    void ac_003_3_quarto_reenvio_automatico_e_negado() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 3; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }
        bloquearEEsperarOFim(attempts, clock);
        assertThat(attempts.isAutoResendDue(clock)).as("o 4º bloqueio também terminou").isTrue();

        boolean concedido = attempts.consumeAutoResend(clock);

        assertThat(concedido).isFalse();
    }

    @Test
    @DisplayName("AC-003.3 o 4º reenvio negado não passa do teto: continuam 3 reenvios contados")
    void ac_003_3_quarto_reenvio_negado_nao_passa_do_teto_de_3() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 3; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }
        bloquearEEsperarOFim(attempts, clock);

        attempts.consumeAutoResend(clock);

        assertThat(attempts.getAutoResends()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-003.3 depois do 4º negado, o 5º reenvio automático também é negado")
    void ac_003_3_depois_do_teto_todo_reenvio_automatico_e_negado() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 4; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }

        boolean concedido = cicloDoJobComConviteEnviado(attempts, clock);

        assertThat(concedido).isFalse();
        assertThat(attempts.getAutoResends()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-003.3 com o teto atingido o bloqueio continua terminando em 30 minutos")
    void ac_003_3_com_o_teto_atingido_o_bloqueio_continua_terminando_em_30_minutos() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 4; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }
        bloquear(attempts, clock);

        clock.advance(BLOQUEIO);

        assertThat(attempts.isBlocked(clock)).isFalse();
        assertThat(attempts.failedAttempts(clock)).isZero();
    }

    @Test
    @DisplayName("AC-003.3 reenvio manual do Responsável não devolve reenvios automáticos")
    void ac_003_3_reenvio_manual_nao_devolve_reenvios_automaticos() {
        InviteCodeAttempts attempts = novoContador(clock);
        for (int i = 0; i < 3; i++) {
            cicloDoJobComConviteEnviado(attempts, clock);
        }
        attempts.registerInvitationCreated(clock);

        boolean concedido = cicloDoJobComConviteEnviado(attempts, clock);

        assertThat(concedido).isFalse();
    }

    @Test
    @DisplayName("AC-003.3 sem convite ENVIADO o reenvio é dispensado sem gastar o limite de 3")
    void ac_003_3_dispensar_reenvio_sem_convite_nao_gasta_o_limite() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);

        attempts.dismissAutoResend(clock);

        assertThat(attempts.getAutoResends()).isZero();
    }

    @Test
    @DisplayName("AC-003.3 reenvio dispensado num bloqueio não impede o reenvio do bloqueio seguinte")
    void ac_003_3_reenvio_dispensado_nao_impede_o_reenvio_do_bloqueio_seguinte() {
        InviteCodeAttempts attempts = novoContador(clock);
        bloquearEEsperarOFim(attempts, clock);
        attempts.dismissAutoResend(clock);

        bloquearEEsperarOFim(attempts, clock);

        assertThat(attempts.isAutoResendDue(clock)).isTrue();
    }
}
