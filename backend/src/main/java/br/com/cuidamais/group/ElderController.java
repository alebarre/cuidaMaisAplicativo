package br.com.cuidamais.group;

import br.com.cuidamais.auth.AccountPrincipal;
import br.com.cuidamais.shared.security.AccountStateRequirement;
import br.com.cuidamais.shared.security.RequiredAccountState;
import br.com.cuidamais.shared.security.RequiresAdminPermission;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Rotas de cadastro, leitura e edição do idoso (T-030, contrato tag {@code elders}). O prefixo
 * {@code /api/v1} vem do {@code server.servlet.context-path} global (application.yml), por isso
 * o mapeamento aqui é só {@code /elders} (mesmo padrão de {@code AuthController}/{@code
 * MeController}).
 *
 * <p>{@code DELETE /elders/{id}} (T-033) e {@code PUT}/{@code DELETE /elders/{id}/photo}
 * (T-031) são de tarefas futuras — propositalmente não implementadas aqui, mesmo já existindo no
 * contrato.
 */
@RestController
@RequestMapping("/elders")
public class ElderController {

    private final ElderService elderService;
    private final Clock clock;

    /** @param clock relógio injetado (D-10), repassado ao serviço em cada chamada */
    public ElderController(ElderService elderService, Clock clock) {
        this.elderService = elderService;
        this.clock = clock;
    }

    /**
     * {@code POST /elders} (AC-001.2, AC-002.1, D-05 rev. 2): só uma conta {@code ATIVO} sem
     * grupo ({@code x-required-account-state: active-without-group}) pode chamar; "já tem
     * grupo" é 409 decidido pelo serviço, não pelo filtro de segurança. Devolve {@code 201} com
     * {@code Location} apontando para o idoso recém-criado.
     */
    @RequiredAccountState(AccountStateRequirement.ACTIVE_WITHOUT_GROUP)
    @PostMapping
    public ResponseEntity<ElderResponse> createElder(
            @Valid @RequestBody ElderRequest request, @AuthenticationPrincipal AccountPrincipal principal) {
        ElderResponse response = elderService.createElder(principal.userId(), request, clock);
        return ResponseEntity.created(locationOf(response.id())).body(response);
    }

    /**
     * {@code GET /elders/{elderId}} (AC-002.3, AC-011.7): sem anotação de estado nem de
     * permissão — padrão do contrato (conta {@code ATIVO} com grupo, qualquer papel); qualquer
     * membro ativo do grupo, inclusive Cuidador, vê os dados completos do idoso.
     */
    @GetMapping("/{elderId}")
    public ResponseEntity<ElderResponse> getElder(
            @PathVariable UUID elderId, @AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok(elderService.getElder(elderId, principal.userId()));
    }

    /**
     * {@code PUT /elders/{elderId}} (AC-002.3, AC-002.9, D-36): {@code x-required-permission:
     * admin} — só o Responsável administrador do grupo edita. Substitui todos os dados do
     * idoso, inclusive a lista completa de contatos de emergência.
     */
    @RequiresAdminPermission
    @PutMapping("/{elderId}")
    public ResponseEntity<ElderResponse> updateElder(
            @PathVariable UUID elderId,
            @Valid @RequestBody ElderRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        ElderResponse response = elderService.updateElder(elderId, principal.userId(), request, clock);
        return ResponseEntity.ok(response);
    }

    /**
     * Monta a URL completa do idoso recém-criado (inclusive {@code context-path}) para o
     * cabeçalho {@code Location} da resposta {@code 201} (AC-001.2).
     */
    private static URI locationOf(UUID elderId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/elders/{elderId}")
                .buildAndExpand(elderId)
                .toUri();
    }
}
