package br.com.cuidamais.membership.controller;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.dto.InvitationPageResponse;
import br.com.cuidamais.membership.dto.InvitationRequest;
import br.com.cuidamais.membership.dto.InvitationResponse;
import br.com.cuidamais.membership.service.InvitationService;
import br.com.cuidamais.shared.security.RequiresAdminPermission;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de convite do Responsável (T-040, tag {@code invitations} do contrato): criar, listar,
 * reenviar e cancelar. Todas exigem {@code x-required-permission: admin}; só HTTP aqui, a regra
 * fica em {@link InvitationService}. O prefixo {@code /api/v1} vem do context-path global.
 */
@RestController
@RequestMapping("/elders/{elderId}/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    /** @param invitationService casos de uso de convite */
    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** {@code POST} (AC-003.1): convida um e-mail; responde 201 com o convite criado. */
    @RequiresAdminPermission
    @PostMapping
    public ResponseEntity<InvitationResponse> createInvitation(
            @PathVariable UUID elderId,
            @Valid @RequestBody InvitationRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.create(elderId, principal.userId(), request.email()));
    }

    /** {@code GET} (AC-003.4, AC-003.8): lista paginada, com filtro opcional {@code status}. */
    @RequiresAdminPermission
    @GetMapping
    public ResponseEntity<InvitationPageResponse> listInvitations(
            @PathVariable UUID elderId,
            @RequestParam(required = false) InvitationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(invitationService.list(elderId, principal.userId(), status, page, size));
    }

    /** {@code POST .../{invitationId}/resend} (AC-003.4, AC-003.9): cria convite novo, 201. */
    @RequiresAdminPermission
    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<InvitationResponse> resendInvitation(
            @PathVariable UUID elderId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.resend(elderId, invitationId, principal.userId()));
    }

    /** {@code POST .../{invitationId}/cancel} (AC-003.4): cancela convite ENVIADO, 200. */
    @RequiresAdminPermission
    @PostMapping("/{invitationId}/cancel")
    public ResponseEntity<InvitationResponse> cancelInvitation(
            @PathVariable UUID elderId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(invitationService.cancel(elderId, invitationId, principal.userId()));
    }
}
