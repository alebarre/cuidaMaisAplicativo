package br.com.cuidamais.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import br.com.cuidamais.auth.domain.IssuedOtp;
import br.com.cuidamais.auth.domain.OtpCode;
import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.shared.testsupport.MutableClock;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T-013 — serviço de OTP: gera o código de 6 dígitos (texto puro só para o e-mail), guarda
 * somente o hash (D-04), 15 min (AC-005.1), 5 tentativas (AC-005.2), reenvio 1/min (AC-005.3).
 * Sem Spring nem banco; tempo controlado por {@link MutableClock} (D-10).
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.auth}):
 *
 * <ul>
 *   <li>{@code OtpService(PasswordEncoder encoder, Clock clock)} — o mesmo {@code
 *       PasswordEncoder} (BCrypt) das senhas (D-14).
 *   <li>{@code IssuedOtp issue(UUID userId, OtpPurpose purpose)} — gera o código e devolve
 *       {@code record IssuedOtp(OtpCode otp, String plainCode)}: {@code plainCode} vai só no
 *       e-mail; {@code otp} (persistível) guarda apenas o hash.
 *   <li>{@code boolean verify(OtpCode otp, String submittedCode)} — delega a {@code
 *       OtpCode.verify(code, encoder, clock)}; mesmas regras e exceções.
 *   <li>{@code String resend(OtpCode otp)} — gera um novo código, aplica {@code
 *       otp.resend(novoHash, clock)} e devolve o novo texto puro para o e-mail. Lança {@link
 *       IllegalStateException} (ou subclasse) antes de 60 s desde o último envio.
 * </ul>
 */
class OtpServiceTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration VALIDADE = Duration.ofMinutes(15);
    private static final Duration INTERVALO_REENVIO = Duration.ofSeconds(60);
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String SEIS_DIGITOS = "\\d{6}";

    /** Custo baixo só para o teste ficar rápido; o algoritmo é o mesmo das senhas (D-14). */
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private MutableClock clock;
    private OtpService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        service = new OtpService(encoder, clock);
    }

    private static String errado(String codigo) {
        // Um código de 6 dígitos garantidamente diferente do certo.
        char ultimo = codigo.charAt(5);
        char outro = ultimo == '9' ? '0' : (char) (ultimo + 1);
        return codigo.substring(0, 5) + outro;
    }

    // ------------------------------------------------------------ AC-005.1 geração

    @Test
    @DisplayName("AC-005.1 o código gerado tem exatamente 6 dígitos numéricos")
    void ac_005_1_codigo_gerado_tem_6_digitos() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);

        assertThat(emitido.plainCode()).matches(SEIS_DIGITOS);
    }

    @Test
    @DisplayName("AC-005.1 códigos gerados mantêm 6 dígitos, inclusive com zeros à esquerda")
    void ac_005_1_codigos_gerados_preservam_zeros_a_esquerda() {
        List<String> codigos = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            codigos.add(service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL).plainCode());
        }

        assertThat(codigos).allMatch(c -> c.matches(SEIS_DIGITOS));
        // ~10% dos códigos uniformes em 000000..999999 começam com 0; em 500, a chance de nenhum
        // aparecer é ~1e-23. Se falhar, o gerador está cortando zeros ou não é uniforme.
        assertThat(codigos).anyMatch(c -> c.startsWith("0"));
        // Algumas colisões são esperadas (paradoxo do aniversário), mas não muitas.
        assertThat(codigos.stream().distinct().count()).as("códigos devem variar").isGreaterThan(450);
    }

    @Test
    @DisplayName("AC-005.1 emissão define prazo de 15 min, 0 tentativas e último envio = agora")
    void ac_005_1_emissao_define_prazo_tentativas_e_ultimo_envio() {
        OtpCode otp = service.issue(USER_ID, OtpPurpose.REDEFINICAO_SENHA).otp();

        assertThat(otp.getUserId()).isEqualTo(USER_ID);
        assertThat(otp.getPurpose()).isEqualTo(OtpPurpose.REDEFINICAO_SENHA);
        assertThat(otp.getExpiresAt()).isEqualTo(T0.plus(VALIDADE));
        assertThat(otp.getLastSentAt()).isEqualTo(T0);
        assertThat(otp.getAttempts()).isZero();
        assertThat(otp.getConsumedAt()).isNull();
    }

    // ------------------------------------------------------------ D-04 só o hash

    @Test
    @DisplayName("AC-005.1 / D-04 o OtpCode guarda só o hash, nunca o código em texto")
    void ac_005_1_d04_otp_guarda_somente_hash() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        String hash = emitido.otp().getCodeHash();

        assertThat(hash).isNotBlank().isNotEqualTo(emitido.plainCode())
                .doesNotContain(emitido.plainCode());
        assertThat(encoder.matches(emitido.plainCode(), hash)).isTrue();
        assertThat(hash.length()).as("cabe em otp_codes.code_hash VARCHAR(100)")
                .isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("AC-005.1 / D-04 nenhum campo do OtpCode contém o código em texto")
    void ac_005_1_d04_nenhum_campo_do_otp_contem_o_codigo_em_texto() throws Exception {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        OtpCode otp = emitido.otp();

        for (Field f : OtpCode.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object valor = f.get(otp);
            if (valor != null) {
                assertThat(String.valueOf(valor))
                        .as("campo %s não pode guardar o código em texto (D-04)", f.getName())
                        .doesNotContain(emitido.plainCode());
            }
        }
    }

    // ------------------------------------------------------------ AC-005.1 verificação

    @Test
    @DisplayName("AC-005.1 o código enviado por e-mail confirma dentro do prazo")
    void ac_005_1_codigo_gerado_confirma_dentro_do_prazo() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        clock.advance(VALIDADE.minusSeconds(1));

        assertThat(service.verify(emitido.otp(), emitido.plainCode())).isTrue();
        assertThat(emitido.otp().getConsumedAt()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("AC-005.1 o código gerado é recusado no minuto 15")
    void ac_005_1_codigo_gerado_expira_no_minuto_15() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        clock.advance(VALIDADE);

        assertThatThrownBy(() -> service.verify(emitido.otp(), emitido.plainCode()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-005.1 o código gerado não pode ser usado duas vezes")
    void ac_005_1_codigo_gerado_nao_pode_ser_usado_duas_vezes() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        assertThat(service.verify(emitido.otp(), emitido.plainCode())).isTrue();

        assertThatThrownBy(() -> service.verify(emitido.otp(), emitido.plainCode()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ AC-005.2 tentativas

    @Test
    @DisplayName("AC-005.2 código errado devolve inválido e conta uma tentativa")
    void ac_005_2_codigo_errado_devolve_invalido_e_conta_tentativa() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);

        assertThat(service.verify(emitido.otp(), errado(emitido.plainCode()))).isFalse();
        assertThat(emitido.otp().getAttempts()).isEqualTo(1);
        assertThat(emitido.otp().getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("AC-005.2 após a 5ª tentativa errada o código certo é recusado")
    void ac_005_2_quinta_tentativa_errada_invalida_mesmo_com_codigo_certo_depois() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        String errado = errado(emitido.plainCode());
        for (int i = 0; i < 5; i++) {
            assertThat(service.verify(emitido.otp(), errado)).isFalse();
        }

        assertThatThrownBy(() -> service.verify(emitido.otp(), emitido.plainCode()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(emitido.otp().getConsumedAt()).isNull();
    }

    // ------------------------------------------------------------ AC-005.3 reenvio

    @Test
    @DisplayName("AC-005.3 reenvio antes de 60 s é recusado")
    void ac_005_3_reenvio_antes_de_60s_e_recusado() {
        OtpCode otp = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL).otp();
        String hashAnterior = otp.getCodeHash();
        clock.advance(INTERVALO_REENVIO.minusSeconds(1));

        assertThatThrownBy(() -> service.resend(otp)).isInstanceOf(IllegalStateException.class);
        assertThat(otp.getCodeHash()).isEqualTo(hashAnterior);
    }

    @Test
    @DisplayName("AC-005.3 reenvio com exatamente 60 s gera novo código de 6 dígitos e novo hash")
    void ac_005_3_reenvio_com_60s_gera_novo_codigo_e_novo_hash() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        OtpCode otp = emitido.otp();
        String hashAnterior = otp.getCodeHash();
        clock.advance(INTERVALO_REENVIO);

        String novoCodigo = service.resend(otp);

        assertThat(novoCodigo).matches(SEIS_DIGITOS);
        assertThat(otp.getCodeHash()).isNotEqualTo(hashAnterior).doesNotContain(novoCodigo);
        assertThat(encoder.matches(novoCodigo, otp.getCodeHash())).isTrue();
        assertThat(otp.getLastSentAt()).isEqualTo(T0.plus(INTERVALO_REENVIO));
        assertThat(otp.getExpiresAt()).isEqualTo(T0.plus(INTERVALO_REENVIO).plus(VALIDADE));
    }

    @Test
    @DisplayName("AC-005.3 reenvio zera tentativas e o novo código confirma")
    void ac_005_3_reenvio_zera_tentativas_e_novo_codigo_confirma() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        OtpCode otp = emitido.otp();
        String errado = errado(emitido.plainCode());
        for (int i = 0; i < 5; i++) {
            service.verify(otp, errado);
        }
        clock.advance(INTERVALO_REENVIO);

        String novoCodigo = service.resend(otp);

        assertThat(otp.getAttempts()).isZero();
        assertThat(service.verify(otp, novoCodigo)).isTrue();
    }

    @Test
    @DisplayName("AC-005.3 depois do reenvio o código anterior deixa de valer")
    void ac_005_3_codigo_anterior_nao_vale_apos_reenvio() {
        IssuedOtp emitido = service.issue(USER_ID, OtpPurpose.CONFIRMACAO_EMAIL);
        clock.advance(INTERVALO_REENVIO);

        String novoCodigo = service.resend(emitido.otp());
        assumeThat(novoCodigo).as("coincidência 1 em 1 milhão").isNotEqualTo(emitido.plainCode());

        assertThat(service.verify(emitido.otp(), emitido.plainCode())).isFalse();
    }
}
