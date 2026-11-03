package br.com.cuidamais.membership.controller;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.dto.ApproveRequest;
import br.com.cuidamais.membership.dto.MemberPageResponse;
import br.com.cuidamais.membership.dto.MemberResponse;
import br.com.cuidamais.membership.dto.PendingCountResponse;
import br.com.cuidamais.membership.service.MemberService;
import br.com.cuidamais.shared.security.RequiresAdminPermission;
import jakarta.validation.Valid;
import java.util.UUID;
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
 * Rotas de membros do grupo (tag {@code members} do contrato). Listagem (T-043), contagem de pendentes (T-044) e decisão sobre pedidos (T-045);
 * a regra (filtro só do Responsável, visibilidade de contato) fica em {@link MemberService}.
 */
@RestController
@RequestMapping("/elders/{elderId}/members")
public class MemberController {

    private final MemberService memberService;

    /** @param memberService casos de uso de consulta de membros */
    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * {@code GET} (AC-006.2, AC-006.9, AC-009.5, AC-011.5, AC-011.8): lista paginada de membros.
     * Sem {@code x-required-permission}: qualquer membro ativo consulta; o filtro {@code status}
     * é verificado no serviço.
     */
    @GetMapping
    public ResponseEntity<MemberPageResponse> listMembers(
            @PathVariable UUID elderId,
            @RequestParam(required = false) MembershipStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(memberService.list(elderId, principal.userId(), status, page, size));
    }

    /**
     * {@code GET /pending-count} (AC-006.1, AC-006.2): quantidade de pedidos aguardando aprovação
     * vigentes, para o badge do Responsável ({@code x-required-permission: admin}).
     */
    @GetMapping("/pending-count")
    @RequiresAdminPermission
    public ResponseEntity<PendingCountResponse> pendingCount(
            @PathVariable UUID elderId, @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(memberService.pendingCount(elderId, principal.userId()));
    }

    /**
     * {@code POST /{memberId}/approve} (AC-006.3, AC-006.6, AC-006.8, AC-007.1): o Responsável aprova
     * o pedido escolhendo o papel; {@code version} protege contra decisão sobre dado desatualizado (D-08).
     */
    @PostMapping("/{memberId}/approve")
    @RequiresAdminPermission
    public ResponseEntity<MemberResponse> approve(
            @PathVariable UUID elderId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ApproveRequest body,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(
                memberService.approve(elderId, memberId, principal.userId(), body.role(), body.version()));
    }

    /**
     * {@code POST /{memberId}/reject} (AC-006.4, AC-006.6, AC-006.8, AC-007.1, AC-007.2): o
     * Responsável recusa o pedido; a rota não tem corpo.
     */
    @PostMapping("/{memberId}/reject")
    @RequiresAdminPermission
    public ResponseEntity<MemberResponse> reject(
            @PathVariable UUID elderId,
            @PathVariable UUID memberId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(memberService.reject(elderId, memberId, principal.userId()));
    }
}
