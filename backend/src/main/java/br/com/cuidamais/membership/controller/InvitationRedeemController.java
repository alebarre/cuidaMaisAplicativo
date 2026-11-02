package br.com.cuidamais.membership.controller;

import br.com.cuidamais.auth.exception.TooManyRequestsException;
import br.com.cuidamais.membership.dto.RedeemInvitationRequest;
import br.com.cuidamais.membership.dto.RedeemInvitationResponse;
import br.com.cuidamais.membership.service.InvitationRedeemService;
import br.com.cuidamais.shared.security.FixedWindowRateLimiter;
import br.com.cuidamais.shared.security.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota pública {@code POST /invitations/redeem} (T-041, tag {@code invitations-public}): passo 1
 * do convidado. Só HTTP: aplica o limite por IP (R-03) e delega a regra a {@link
 * InvitationRedeemService}. O IP é {@code getRemoteAddr()}; {@code X-Forwarded-For} não é
 * considerado porque, sem proxy confiável configurado, o cabeçalho é forjável pelo cliente e
 * permitiria burlar o limite (configurar proxy confiável é tarefa de implantação).
 */
@RestController
@RequestMapping("/invitations")
@PublicEndpoint
public class InvitationRedeemController {

    private final InvitationRedeemService redeemService;
    private final FixedWindowRateLimiter redeemRateLimiter;

    /**
     * @param redeemService casos de uso de resgate de convite
     * @param redeemRateLimiter limitador por IP (bean {@code redeemRateLimiter})
     */
    public InvitationRedeemController(
            InvitationRedeemService redeemService, FixedWindowRateLimiter redeemRateLimiter) {
        this.redeemService = redeemService;
        this.redeemRateLimiter = redeemRateLimiter;
    }

    /** Valida e-mail + código (AC-004.1, AC-004.5 a AC-004.7); 429 antes de qualquer consulta ao contador (R-03). */
    @PostMapping("/redeem")
    public ResponseEntity<RedeemInvitationResponse> redeem(
            @Valid @RequestBody RedeemInvitationRequest request, HttpServletRequest http) {
        // 1. limite por IP: excedeu => 429 com Retry-After, sem tocar no contador do e-mail.
        long retryAfter = redeemRateLimiter.tryAcquire(http.getRemoteAddr());
        if (retryAfter > 0) {
            throw new TooManyRequestsException(retryAfter);
        }
        // 2. regra de negócio no serviço.
        return ResponseEntity.ok(redeemService.redeem(request.email(), request.code()));
    }
}
