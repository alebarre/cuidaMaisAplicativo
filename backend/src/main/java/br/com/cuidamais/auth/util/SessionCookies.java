package br.com.cuidamais.auth.util;

import br.com.cuidamais.auth.domain.RefreshToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/**
 * Monta o cookie {@code Set-Cookie} do refresh token (T-021, D-12), com exatamente os atributos
 * exigidos pelo contrato ({@code securitySchemes.refreshCookie}): {@code HttpOnly} (o
 * JavaScript do front nunca lê o valor, mitigando XSS), {@code Secure} (só trafega em HTTPS) e
 * {@code SameSite=Strict} (o navegador nunca envia o cookie em requisições originadas de outro
 * site, mitigando CSRF). As rotas que de fato enviam este cookie (login, verificação de e-mail,
 * refresh, logout) são das tarefas T-022/T-023/T-025; esta classe só monta o valor do cabeçalho.
 *
 * <p>Classe utilitária sem estado: um único método estático, sem necessidade de bean Spring.
 */
public final class SessionCookies {

    private static final String NOME_COOKIE = "refresh_token";

    private SessionCookies() {}

    /**
     * Monta o cookie do refresh token com {@code Max-Age} coerente com o prazo real da sessão
     * (D-12, AC-008.3): o tempo entre "agora" e {@code validoAte}, que já chega limitado ao teto
     * absoluto de 30 dias ({@link RefreshToken#getExpiresAt()}). Nunca devolve um {@code Max-Age}
     * negativo — se {@code validoAte} já passou, o navegador recebe {@link Duration#ZERO}, que
     * descarta o cookie imediatamente em vez de guardá-lo por um tempo "negativo" sem sentido.
     *
     * @param value valor opaco do token (D-04); o texto puro, nunca o hash
     * @param validoAte instante em que a sessão deixa de valer ({@code expiresAt} do refresh token)
     * @param clock relógio injetado (D-10): {@code Max-Age} é relativo ao instante da resposta
     */
    public static ResponseCookie refreshTokenCookie(String value, Instant validoAte, Clock clock) {
        Instant agora = clock.instant();
        Duration maxAge = validoAte.isAfter(agora) ? Duration.between(agora, validoAte) : Duration.ZERO;
        return ResponseCookie.from(NOME_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(maxAge)
                // Contrato (responses.SessionStarted): o cookie só é enviado de volta ao
                // navegador para requisições sob /api/v1/auth (ex. /auth/refresh, /auth/logout),
                // nunca para as demais rotas da API — restringe onde o refresh token circula.
                .path("/api/v1/auth")
                .build();
    }

    /**
     * Monta o cookie de limpeza do logout (T-025, AC-008.5): valor vazio e {@code Max-Age=0}, para
     * que o navegador descarte o refresh token imediatamente, com os mesmos atributos de segurança
     * (D-12) e o mesmo {@code Path} do cookie original — um {@code Path} diferente faria o
     * navegador tratar como um cookie novo em vez de apagar o existente.
     */
    public static ResponseCookie clearRefreshTokenCookie() {
        return ResponseCookie.from(NOME_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .path("/api/v1/auth")
                .build();
    }
}
