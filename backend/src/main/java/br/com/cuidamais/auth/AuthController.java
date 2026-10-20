package br.com.cuidamais.auth;

import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas públicas de autenticação (T-020). O prefixo {@code /api/v1} do contrato vem do {@code
 * server.servlet.context-path} global (application.yml), por isso o mapeamento aqui é só {@code
 * /auth}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SignupService signupService;
    private final Clock clock;

    /**
     * @param signupService regra de negócio do cadastro (T-020)
     * @param clock relógio injetado (D-10), passado adiante para {@code created_at}
     */
    public AuthController(SignupService signupService, Clock clock) {
        this.signupService = signupService;
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
}
