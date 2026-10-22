package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * T-021 — cookie do refresh token (D-12): {@code HttpOnly; Secure; SameSite=Strict}, nome exato
 * {@code refresh_token} (contrato, {@code securitySchemes.refreshCookie}) e validade coerente com
 * o {@code expiresAt} do refresh token — que já vem limitado ao teto de 30 dias (D-11, AC-008.3).
 *
 * <p>Teste puro, sem HTTP real: só a montagem do {@code Set-Cookie}. As rotas que o enviam
 * (verify-email, login, refresh, logout) são das tarefas T-022/T-023/T-025.
 *
 * <p><strong>API esperada</strong> — {@code br.com.cuidamais.auth.SessionCookies}:
 *
 * <ul>
 *   <li>{@code static ResponseCookie refreshTokenCookie(String value, Instant validoAte, Clock
 *       clock)} ({@code org.springframework.http.ResponseCookie}): {@code Max-Age} = segundos
 *       entre {@code agora} e {@code validoAte}. O {@code Clock} é necessário porque {@code
 *       Max-Age} é relativo ao instante da resposta (D-10). O {@code Path} fica a critério do
 *       implementador e não é verificado aqui.
 * </ul>
 */
class SessionCookiesTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("D-12 cookie do refresh token tem os atributos exigidos")
    void d_12_cookie_do_refresh_token_tem_os_atributos_exigidos() {
        // HttpOnly impede leitura por JavaScript (XSS), Secure só trafega em HTTPS e
        // SameSite=Strict impede envio em requisições vindas de outro site (CSRF).
        ResponseCookie cookie = SessionCookies.refreshTokenCookie("valor-opaco", T0.plus(Duration.ofDays(7)), clock);

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getValue()).isEqualTo("valor-opaco");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    @Test
    @DisplayName("D-12 o cabeçalho Set-Cookie serializado carrega HttpOnly, Secure e SameSite=Strict")
    void d_12_set_cookie_serializado_tem_os_atributos() {
        // O que chega ao navegador é o texto do Set-Cookie; os atributos precisam aparecer nele.
        String setCookie = SessionCookies.refreshTokenCookie("valor-opaco", T0.plus(Duration.ofDays(7)), clock)
                .toString();

        assertThat(setCookie).startsWith("refresh_token=valor-opaco");
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
    }

    @Test
    @DisplayName("AC-008.3 Max-Age do cookie acompanha a janela de 7 dias do refresh token")
    void ac_008_3_max_age_acompanha_a_janela_de_7_dias() {
        // Token recém-emitido ou renovado: o cookie vive exatamente o mesmo que o refresh token.
        ResponseCookie cookie = SessionCookies.refreshTokenCookie("valor-opaco", T0.plus(Duration.ofDays(7)), clock);

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("AC-008.3 Max-Age do cookie respeita o teto de 30 dias perto do fim da sessão")
    void ac_008_3_max_age_respeita_o_teto_perto_do_fim() {
        // Renovação no dia 28: o expiresAt do token foi limitado ao teto (dia 30), então o cookie
        // vale só 2 dias, não 7 — o navegador não guarda o cookie além do que a sessão permite.
        clock.set(T0.plus(Duration.ofDays(28)));

        ResponseCookie cookie = SessionCookies.refreshTokenCookie("valor-opaco", T0.plus(Duration.ofDays(30)), clock);

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(2));
    }
}
