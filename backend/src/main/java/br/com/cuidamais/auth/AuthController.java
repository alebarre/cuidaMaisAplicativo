package br.com.cuidamais.auth;

import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas públicas de autenticação (T-020, T-022). O prefixo {@code /api/v1} do contrato vem do
 * {@code server.servlet.context-path} global (application.yml), por isso o mapeamento aqui é só
 * {@code /auth}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SignupService signupService;
    private final VerifyEmailService verifyEmailService;
    private final Clock clock;

    /**
     * @param signupService regra de negócio do cadastro (T-020)
     * @param verifyEmailService regra de negócio da confirmação de e-mail (T-022)
     * @param clock relógio injetado (D-10), passado adiante para {@code created_at}/cookies
     */
    public AuthController(SignupService signupService, VerifyEmailService verifyEmailService, Clock clock) {
        this.signupService = signupService;
        this.verifyEmailService = verifyEmailService;
        this.clock = clock;
    }

    /**
     * AC-001.1, AC-001.3, AC-001.4 (D-15): sempre 202 sem corpo, exista o e-mail ou não — o
     * serviço nunca lança nada em nenhum dos dois caminhos.
     */
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        signupService.signup(request, clock);
        return ResponseEntity.accepted().build();
    }

    /**
     * AC-001.2, AC-005.1, AC-005.2 (D-05 rev. 2, D-11, D-12): com o código certo, ativa a conta e
     * devolve 200 com o {@code AccessToken} no corpo e o refresh token no cookie {@code
     * Set-Cookie} (D-12); código errado ou expirado/esgotado respondem 400/410 pelas exceções
     * mapeadas em {@code GlobalExceptionHandler} ({@link InvalidCodeException}, {@link
     * CodeExpiredOrExhaustedException}) — este método não trata nenhum dos dois casos de erro.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<AccessTokenResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        VerifiedEmailSession session = verifyEmailService.verify(request.email(), request.code());
        IssuedRefreshToken refreshToken = session.refreshToken();
        var cookie = SessionCookies.refreshTokenCookie(
                refreshToken.plainToken(), refreshToken.token().getExpiresAt(), clock);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AccessTokenResponse.of(session.accessToken()));
    }
}
