package br.com.cuidamais.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T-013 — regras de estado do OTP (AC-005.1, AC-005.2, AC-005.3), testadas <strong>sem
 * banco</strong> e sem Spring, no mesmo padrão de {@code Membership} (D-07: transições em métodos
 * da classe, exceção em operação inválida) e com {@link MutableClock} (D-10).
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.auth}), compatível com a tabela {@code
 * otp_codes} da V1 ({@code user_id, purpose, code_hash, attempts, expires_at, consumed_at,
 * last_sent_at}):
 *
 * <ul>
 *   <li>{@code enum OtpPurpose { CONFIRMACAO_EMAIL, REDEFINICAO_SENHA }} — coluna {@code purpose}
 *       (AC-005: "criador do grupo e redefinição de senha").
 *   <li>{@code static OtpCode issue(UUID userId, OtpPurpose purpose, String codeHash, Clock
 *       clock)} — recebe <strong>só o hash</strong> (D-04); {@code attempts = 0}, {@code expiresAt
 *       = agora + 15 min}, {@code lastSentAt = agora}, {@code consumedAt = null}.
 *   <li>{@code boolean verify(String submittedCode, PasswordEncoder encoder, Clock clock)} —
 *       código certo: marca {@code consumedAt = agora} e devolve {@code true}; código errado:
 *       {@code attempts++} e devolve {@code false}. Lança {@link IllegalStateException} (ou
 *       subclasse) se o código já expirou, já foi consumido ou já esgotou as 5 tentativas.
 *   <li>{@code void resend(String newCodeHash, Clock clock)} — troca o hash, {@code attempts = 0},
 *       {@code expiresAt = agora + 15 min}, {@code lastSentAt = agora}. Lança {@link
 *       IllegalStateException} (ou subclasse) se {@code agora < lastSentAt + 60 s}.
 *   <li>Leitura: {@code getUserId()}, {@code getPurpose()}, {@code getCodeHash()}, {@code
 *       getAttempts()}, {@code getExpiresAt()}, {@code getConsumedAt()}, {@code getLastSentAt()}.
 * </ul>
 *
 * <p>Limites: o código vale enquanto {@code agora < expiresAt} (em 15 min exatos já venceu); o
 * reenvio é aceito quando {@code agora >= lastSentAt + 60 s} (em 60 s exatos já pode).
 */
class OtpCodeTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration VALIDADE = Duration.ofMinutes(15);
    private static final Duration INTERVALO_REENVIO = Duration.ofSeconds(60);
    private static final int MAX_TENTATIVAS = 5;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String CODIGO = "123456";
    private static final String CODIGO_ERRADO = "654321";
    private static final String NOVO_CODIGO = "777888";

    /** Custo baixo só para o teste ficar rápido; o algoritmo é o mesmo das senhas (D-14). */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private MutableClock clock;
    private OtpCode otp;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        otp = OtpCode.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL, encoder.encode(CODIGO), clock);
    }

    private void errar(int vezes) {
        for (int i = 0; i < vezes; i++) {
            assertThat(otp.verify(CODIGO_ERRADO, encoder, clock)).isFalse();
        }
    }

    // ------------------------------------------------------------ AC-005.1 emissão e prazo

    @Test
    @DisplayName("AC-005.1 código emitido vence 15 minutos depois do envio")
    void ac_005_1_codigo_emitido_vence_15_minutos_apos_envio() {
        assertThat(otp.getExpiresAt()).isEqualTo(T0.plus(VALIDADE));
        assertThat(otp.getLastSentAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("AC-005.1 código emitido nasce sem tentativas e não consumido")
    void ac_005_1_codigo_emitido_nasce_sem_tentativas_e_nao_consumido() {
        assertThat(otp.getAttempts()).isZero();
        assertThat(otp.getConsumedAt()).isNull();
        assertThat(otp.getUserId()).isEqualTo(USER_ID);
        assertThat(otp.getPurpose()).isEqualTo(OtpPurpose.CONFIRMACAO_EMAIL);
    }

    @Test
    @DisplayName("AC-005.1 código certo dentro do prazo é aceito e marcado como consumido")
    void ac_005_1_codigo_certo_dentro_do_prazo_e_aceito_e_consumido() {
        clock.advance(Duration.ofMinutes(5));

        boolean ok = otp.verify(CODIGO, encoder, clock);

        assertThat(ok).isTrue();
        assertThat(otp.getConsumedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("AC-005.1 código certo um segundo antes dos 15 minutos ainda é aceito")
    void ac_005_1_codigo_certo_um_segundo_antes_de_15_minutos_e_aceito() {
        clock.advance(VALIDADE.minusSeconds(1));

        assertThat(otp.verify(CODIGO, encoder, clock)).isTrue();
    }

    @Test
    @DisplayName("AC-005.1 código certo no minuto 15 exato é recusado por expiração")
    void ac_005_1_codigo_expira_no_minuto_15() {
        clock.advance(VALIDADE);

        assertThatThrownBy(() -> otp.verify(CODIGO, encoder, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("AC-005.1 código certo depois dos 15 minutos é recusado por expiração")
    void ac_005_1_codigo_certo_apos_15_minutos_e_recusado() {
        clock.advance(VALIDADE.plusMinutes(1));

        assertThatThrownBy(() -> otp.verify(CODIGO, encoder, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("AC-005.1 código já usado não pode ser usado de novo, mesmo certo e no prazo")
    void ac_005_1_codigo_ja_consumido_e_recusado_na_segunda_vez() {
        assertThat(otp.verify(CODIGO, encoder, clock)).isTrue();

        assertThatThrownBy(() -> otp.verify(CODIGO, encoder, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ AC-005.2 tentativas

    @Test
    @DisplayName("AC-005.2 código errado conta uma tentativa e não consome o código")
    void ac_005_2_codigo_errado_incrementa_tentativas_e_nao_consome() {
        boolean ok = otp.verify(CODIGO_ERRADO, encoder, clock);

        assertThat(ok).isFalse();
        assertThat(otp.getAttempts()).isEqualTo(1);
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("AC-005.2 depois de 4 tentativas erradas o código certo ainda é aceito")
    void ac_005_2_quatro_erros_e_codigo_certo_ainda_e_aceito() {
        errar(MAX_TENTATIVAS - 1);

        assertThat(otp.verify(CODIGO, encoder, clock)).isTrue();
    }

    @Test
    @DisplayName("AC-005.2 a 5ª tentativa errada leva o contador a 5")
    void ac_005_2_quinta_tentativa_errada_leva_contador_a_cinco() {
        errar(MAX_TENTATIVAS);

        assertThat(otp.getAttempts()).isEqualTo(MAX_TENTATIVAS);
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("AC-005.2 após 5 erros o código certo é recusado, mesmo dentro do prazo")
    void ac_005_2_quinta_tentativa_errada_invalida_mesmo_com_codigo_certo_depois() {
        errar(MAX_TENTATIVAS);

        assertThatThrownBy(() -> otp.verify(CODIGO, encoder, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(otp.getConsumedAt()).isNull();
        assertThat(otp.getExpiresAt()).as("ainda dentro do prazo").isAfter(clock.instant());
    }

    @Test
    @DisplayName("AC-005.2 após 5 erros uma 6ª tentativa errada também é recusada")
    void ac_005_2_sexta_tentativa_errada_tambem_e_recusada() {
        errar(MAX_TENTATIVAS);

        assertThatThrownBy(() -> otp.verify(CODIGO_ERRADO, encoder, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-005.2 após 5 erros, pedir um novo código volta a permitir a confirmação")
    void ac_005_2_novo_codigo_apos_esgotar_tentativas_e_aceito() {
        errar(MAX_TENTATIVAS);
        clock.advance(INTERVALO_REENVIO);

        otp.resend(encoder.encode(NOVO_CODIGO), clock);

        assertThat(otp.verify(NOVO_CODIGO, encoder, clock)).isTrue();
    }

    // ------------------------------------------------------------ AC-005.3 reenvio 1/min

    @Test
    @DisplayName("AC-005.3 reenvio antes de 60 s é recusado")
    void ac_005_3_reenvio_antes_de_60s_e_recusado() {
        clock.advance(INTERVALO_REENVIO.minusSeconds(1));
        String hashAnterior = otp.getCodeHash();

        assertThatThrownBy(() -> otp.resend(encoder.encode(NOVO_CODIGO), clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(otp.getCodeHash()).isEqualTo(hashAnterior);
        assertThat(otp.getLastSentAt()).isEqualTo(T0);
        assertThat(otp.getExpiresAt()).isEqualTo(T0.plus(VALIDADE));
    }

    @Test
    @DisplayName("AC-005.3 reenvio imediato (0 s) é recusado")
    void ac_005_3_reenvio_imediato_e_recusado() {
        assertThatThrownBy(() -> otp.resend(encoder.encode(NOVO_CODIGO), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-005.3 reenvio com exatamente 60 s é aceito")
    void ac_005_3_reenvio_com_exatamente_60s_e_aceito() {
        clock.advance(INTERVALO_REENVIO);
        String novoHash = encoder.encode(NOVO_CODIGO);

        otp.resend(novoHash, clock);

        assertThat(otp.getCodeHash()).isEqualTo(novoHash);
        assertThat(otp.getLastSentAt()).isEqualTo(T0.plus(INTERVALO_REENVIO));
    }

    @Test
    @DisplayName("AC-005.3 reenvio reinicia o prazo de 15 min a partir do novo envio")
    void ac_005_3_reenvio_reinicia_prazo_de_15_minutos() {
        clock.advance(Duration.ofMinutes(10));
        Instant reenvio = clock.instant();

        otp.resend(encoder.encode(NOVO_CODIGO), clock);

        assertThat(otp.getExpiresAt()).isEqualTo(reenvio.plus(VALIDADE));
        clock.advance(VALIDADE.minusSeconds(1));
        assertThat(otp.verify(NOVO_CODIGO, encoder, clock)).isTrue();
    }

    @Test
    @DisplayName("AC-005.3 reenvio zera o contador de tentativas")
    void ac_005_3_reenvio_zera_tentativas() {
        errar(3);
        clock.advance(INTERVALO_REENVIO);

        otp.resend(encoder.encode(NOVO_CODIGO), clock);

        assertThat(otp.getAttempts()).isZero();
    }

    @Test
    @DisplayName("AC-005.3 depois do reenvio o código antigo deixa de valer")
    void ac_005_3_codigo_antigo_nao_vale_apos_reenvio() {
        clock.advance(INTERVALO_REENVIO);

        otp.resend(encoder.encode(NOVO_CODIGO), clock);

        assertThat(otp.verify(CODIGO, encoder, clock)).isFalse();
    }

    @Test
    @DisplayName("AC-005.3 / AC-005.1 reenvio de um código já expirado é aceito e gera novo prazo")
    void ac_005_3_reenvio_de_codigo_expirado_e_aceito() {
        clock.advance(VALIDADE.plusMinutes(5));

        otp.resend(encoder.encode(NOVO_CODIGO), clock);

        assertThat(otp.verify(NOVO_CODIGO, encoder, clock)).isTrue();
    }

    @Test
    @DisplayName("AC-005.3 o intervalo de 60 s conta a partir do último reenvio")
    void ac_005_3_intervalo_conta_a_partir_do_ultimo_reenvio() {
        clock.advance(INTERVALO_REENVIO);
        otp.resend(encoder.encode(NOVO_CODIGO), clock);
        clock.advance(INTERVALO_REENVIO.minusSeconds(1));

        assertThatThrownBy(() -> otp.resend(encoder.encode("111222"), clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
