package br.com.cuidamais.group.controller;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.group.dto.ElderPhotoContent;
import br.com.cuidamais.group.dto.ElderRequest;
import br.com.cuidamais.group.dto.ElderResponse;
import br.com.cuidamais.group.service.ElderService;
import br.com.cuidamais.shared.security.AccountStateRequirement;
import br.com.cuidamais.shared.security.RequiredAccountState;
import br.com.cuidamais.shared.security.RequiresAdminPermission;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Rotas de cadastro, leitura, edição e foto do idoso (T-030, T-031, contrato tag {@code
 * elders}). O prefixo {@code /api/v1} vem do {@code server.servlet.context-path} global
 * (application.yml), por isso o mapeamento aqui é só {@code /elders} (mesmo padrão de {@code
 * AuthController}/{@code MeController}).
 *
 * <p>{@code DELETE /elders/{id}} (T-033) exclui o grupo de cuidado inteiro — idoso, contatos,
 * memberships e as contas ({@code users}) de todo o grupo (D-38).
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
     * {@code PUT /elders/{elderId}/photo} (AC-002.6, T-031, D-30): {@code x-required-permission:
     * admin} — só o Responsável administrador envia/substitui a foto. O formato (JPEG/PNG) e o
     * tamanho (até 5 MB) são validados pelo serviço, não aqui (D-07).
     */
    @RequiresAdminPermission
    @PutMapping("/{elderId}/photo")
    public ResponseEntity<ElderResponse> uploadPhoto(
            @PathVariable UUID elderId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AccountPrincipal principal) {
        ElderResponse response = elderService.uploadPhoto(elderId, principal.userId(), file, clock);
        return ResponseEntity.ok(response);
    }

    /**
     * {@code DELETE /elders/{elderId}/photo} (AC-002.6, T-031): {@code x-required-permission:
     * admin}; idempotente — remover quando não há foto nenhuma ainda responde {@code 204} do
     * mesmo jeito, nunca um erro.
     */
    @RequiresAdminPermission
    @DeleteMapping("/{elderId}/photo")
    public ResponseEntity<Void> deletePhoto(
            @PathVariable UUID elderId, @AuthenticationPrincipal AccountPrincipal principal) {
        elderService.removePhoto(elderId, principal.userId(), clock);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code GET /elders/{elderId}/photo} (AC-002.3, AC-002.6, T-031): sem {@code
     * x-required-permission} no contrato — qualquer membro ativo do grupo, inclusive Cuidador,
     * baixa a foto, mesma visibilidade dos demais dados do idoso. Devolve os bytes crus com o
     * {@code Content-Type} correspondente ao formato gravado ({@code image/jpeg}/{@code
     * image/png}); idoso sem foto nenhuma ainda é {@code 404} (decidido pelo serviço).
     */
    @GetMapping("/{elderId}/photo")
    public ResponseEntity<byte[]> getPhoto(
            @PathVariable UUID elderId, @AuthenticationPrincipal AccountPrincipal principal) {
        ElderPhotoContent photo = elderService.getPhoto(elderId, principal.userId());
        return ResponseEntity.ok().contentType(photo.contentType()).body(photo.bytes());
    }

    /**
     * {@code DELETE /elders/{elderId}} (T-033, AC-013.2, AC-013.3, AC-013.4, AC-013.5, D-38):
     * {@code x-required-permission: admin} — só o Responsável administrador exclui; um membro
     * comum recebe {@code 403 NOT_ALLOWED} antes de chegar aqui (checagem do interceptor). Apaga
     * o grupo de cuidado inteiro (idoso, contatos, memberships e as contas do grupo) e dispara o
     * e-mail de aviso a todas elas; não há corpo de resposta.
     */
    @RequiresAdminPermission
    @DeleteMapping("/{elderId}")
    public ResponseEntity<Void> deleteCareGroup(
            @PathVariable UUID elderId, @AuthenticationPrincipal AccountPrincipal principal) {
        elderService.deleteCareGroup(elderId, principal.userId());
        return ResponseEntity.noContent().build();
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
