package br.com.cuidamais.membership.controller;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.dto.MemberPageResponse;
import br.com.cuidamais.membership.service.MemberService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de membros do grupo (tag {@code members} do contrato). Por ora só a listagem (T-043);
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
}
