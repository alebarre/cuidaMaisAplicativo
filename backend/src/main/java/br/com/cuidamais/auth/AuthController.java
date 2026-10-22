package br.com.cuidamais.auth;

import br.com.cuidamais.shared.security.AccountStateRequirement;
import br.com.cuidamais.shared.security.PublicEndpoint;
import br.com.cuidamais.shared.security.RequiredAccountState;
import br.com.cuidamais.shared.security.UnauthorizedSessionException;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas públicas de autenticação (T-020, T-022, T-023, T-025). O prefixo {@code /api/v1} do
 * contrato vem do {@code server.servlet.context-path} global (application.yml), por isso o
 * mapeamento aqui é só {@code /auth}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SignupService signupService;
    private final VerifyEmailService verifyEmailService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final Clock clock;

    /**
     * @param signupService regra de negócio do cadastro (T-020)
     * @param verifyEmailService regra de negócio da confirmação de e-mail (T-022)
     * @param loginService regra de negócio do login (T-023)
     * @param refreshTokenService regra de negócio de rotação/revogação do refresh token (T-021, T-025)
     * @param jwtService emite o access token novo que acompanha a rotação (T-025)
     * @param clock relógio injetado (D-10), passado adiante para {@code created_at}/cookies
     */
    public AuthController(
            SignupService signupService,
            VerifyEmailService verifyEmailService,
            LoginService loginService,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            Clock clock) {
        this.signupService = signupService;
        this.verifyEmailService = verifyEmailService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.clock = clock;
    }

    /**
     * AC-001.1, AC-001.3, AC-001.4 (D-15): sempre 202 sem corpo, exista o e-mail ou não — o
     * serviço nunca lança nada em nenhum dos dois caminhos.
     */
    @PublicEndpoint
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
    @PublicEndpoint
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

    /**
     * AC-008.1, AC-008.2, AC-008.4 (D-11, D-12, D-14 rev. 2, D-15, D-33): com e-mail, senha e
     * estado {@code ATIVO} corretos, devolve 200 com o {@code AccessToken} no corpo e o refresh
     * token no cookie {@code Set-Cookie} (D-12), mesmo padrão de {@link #verifyEmail}. Qualquer
     * recusa (e-mail inexistente, senha errada, bloqueio por conta ou estado não-{@code ATIVO}) é
     * tratada pelas exceções mapeadas em {@code GlobalExceptionHandler} ({@link
     * InvalidCredentialsException}, {@link AccountNotActiveException}) — este método não trata
     * nenhum caso de erro.
     */
    @PublicEndpoint
    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginSession session = loginService.login(request.email(), request.password());
        IssuedRefreshToken refreshToken = session.refreshToken();
        var cookie = SessionCookies.refreshTokenCookie(
                refreshToken.plainToken(), refreshToken.token().getExpiresAt(), clock);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AccessTokenResponse.of(session.accessToken()));
    }

    /**
     * AC-008.3 (D-11, D-12, D-13): renova a sessão a partir do cookie {@code refresh_token} — o
     * contrato exige só esse cookie ({@code security: [refreshCookie]}), nunca o bearer, porque o
     * motivo de chamar esta rota é justamente o access token ter vencido ({@code @PublicEndpoint}
     * deixa o filtro de segurança ignorar qualquer {@code Authorization} presente, válido ou não).
     *
     * <p>Fluxo: 1) sem cookie, recusa direto com 401 (o {@code @CookieValue} opcional evita o 400
     * padrão do Spring para cookie ausente); 2) delega a rotação a {@link RefreshTokenService},
     * cujo {@code IllegalStateException} (token desconhecido, expirado ou reuso) é reaproveitado
     * como {@link UnauthorizedSessionException} (401 Problem, já mapeada); 3) em sucesso, emite um
     * access token novo e devolve o cookie rotacionado, no mesmo formato de login/verify-email.
     */
    @PublicEndpoint
    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String token) {
        // 1. sem cookie, a própria requisição já viola o contrato (security: [refreshCookie]).
        if (token == null || token.isBlank()) {
            throw new UnauthorizedSessionException();
        }

        // 2. a rotação (AC-008.3, D-11) valida prazos e detecção de reuso; qualquer recusa dela é
        // reportada como sessão inválida, sem revelar o motivo exato (P5).
        IssuedRefreshToken rotacionado;
        try {
            rotacionado = refreshTokenService.rotate(token);
        } catch (IllegalStateException e) {
            throw new UnauthorizedSessionException();
        }

        // 3. sucesso: novo access token (mesma identidade da família) e cookie rotacionado.
        String accessToken = jwtService.issue(rotacionado.token().getUserId(), clock);
        var cookie = SessionCookies.refreshTokenCookie(
                rotacionado.plainToken(), rotacionado.token().getExpiresAt(), clock);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AccessTokenResponse.of(accessToken));
    }

    /**
     * AC-008.5 (D-13): encerra a sessão deste aparelho. Exige sessão válida (bearer), mas nenhum
     * estado específico ({@code x-required-account-state: any}) — até uma conta {@code REMOVIDO}
     * precisa conseguir sair. Sempre responde 204 com o cookie de limpeza, mesmo sem cookie de
     * refresh ou com um cookie desconhecido/de outra conta: a revogação em si (incluindo a checagem
     * de dono e a cascata de família) é responsabilidade de {@link RefreshTokenService}, e o
     * resultado nunca é revelado na resposta (idempotente, P5).
     */
    @RequiredAccountState(AccountStateRequirement.ANY)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String token,
            @AuthenticationPrincipal AccountPrincipal principal) {
        refreshTokenService.revokeDeviceByPlainToken(token, principal.userId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookies.clearRefreshTokenCookie().toString())
                .build();
    }
}
