package br.com.cuidamais.auth.controller;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.auth.domain.AccountState;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.dto.AccountStateResponse;
import br.com.cuidamais.auth.dto.AddressRequest;
import br.com.cuidamais.auth.dto.MeMembershipResponse;
import br.com.cuidamais.auth.dto.MeResponse;
import br.com.cuidamais.auth.dto.UpdateMeRequest;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.group.domain.Elder;
import br.com.cuidamais.group.repository.ElderRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.security.AccountStateRequirement;
import br.com.cuidamais.shared.security.RequiredAccountState;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota "sobre a própria conta" usada pelos guards do frontend (T-027, D-20) e, desde a T-032,
 * pela leitura/edição dos próprios dados de contato (AC-011.6). O prefixo {@code /api/v1} vem do
 * {@code server.servlet.context-path} global (application.yml), por isso o mapeamento aqui é só
 * {@code /me}.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final ElderRepository elders;

    /**
     * @param users busca/persiste a conta autenticada (T-032: {@code GET}/{@code PUT /me}
     *     precisam do {@link User} completo, que {@link AccountPrincipal} não carrega)
     * @param memberships busca a membership da conta para achar o {@code group_id} (D-02: no
     *     máximo uma por conta)
     * @param elders busca o idoso do grupo (entidade/repositório completos desde a T-030)
     */
    public MeController(UserRepository users, MembershipRepository memberships, ElderRepository elders) {
        this.users = users;
        this.memberships = memberships;
        this.elders = elders;
    }

    /**
     * AC-001.7, AC-012.1 (D-20, D-33): devolve o estado derivado da conta — {@code any}, então
     * qualquer sessão válida recebe 200, até uma conta {@code REMOVIDO} (AC-015), diferente do
     * padrão das demais rotas autenticadas (T-024).
     */
    @RequiredAccountState(AccountStateRequirement.ANY)
    @GetMapping("/account")
    public ResponseEntity<AccountStateResponse> getMyAccountState(@AuthenticationPrincipal AccountPrincipal principal) {
        // elderId só é resolvido quando há grupo (hasGroup=true), qualquer que seja o status da
        // membership — decisão do orquestrador da T-027, mais simples que tratar por estado.
        UUID elderId = principal.state().hasGroup() ? elderIdDoGrupo(principal.userId()) : null;
        return ResponseEntity.ok(AccountStateResponse.of(principal.state(), elderId));
    }

    /**
     * Busca o {@code group_id} da membership da conta e, com ele, o idoso daquele grupo
     * (D-02: a membership é única por conta; {@link AccountState#hasGroup()} só é verdadeiro
     * quando ela existe, então o {@code orElseThrow} nunca deveria disparar na prática).
     */
    private UUID elderIdDoGrupo(UUID userId) {
        Membership membership = memberships.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Conta com hasGroup=true sem membership (invariante de AccountState violada)"));
        return elders.findByGroupId(membership.getGroupId()).map(Elder::getId).orElse(null);
    }

    /**
     * AC-011.6, contrato {@code x-required-account-state: any}: devolve os dados da própria
     * conta e o vínculo com o grupo, se houver — igual a {@code any} em {@link
     * #getMyAccountState}, vale até para membership {@code REMOVIDO}/{@code
     * AGUARDANDO_APROVACAO}, porque ler/editar os próprios dados de contato não depende do
     * estado do vínculo com o grupo.
     */
    @RequiredAccountState(AccountStateRequirement.ANY)
    @GetMapping
    public ResponseEntity<MeResponse> getMe(@AuthenticationPrincipal AccountPrincipal principal) {
        User user = carregarUsuario(principal.userId());
        return ResponseEntity.ok(MeResponse.of(user, membershipDaConta(principal.userId())));
    }

    /**
     * AC-011.6 "o membro pode alterar o próprio telefone e endereço a qualquer momento": grava o
     * telefone e o endereço novos (já validados por Bean Validation) e devolve a conta
     * atualizada. {@code x-required-account-state: any} pelo mesmo motivo de {@link #getMe}: a
     * edição vale para qualquer estado de vínculo com o grupo, não só {@code ATIVO}.
     */
    @RequiredAccountState(AccountStateRequirement.ANY)
    @PutMapping
    public ResponseEntity<MeResponse> updateMe(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody UpdateMeRequest request) {
        // 1. carrega a conta e aplica a mudança na própria entidade (D-07: a regra mora no User).
        User user = carregarUsuario(principal.userId());
        user.updateContactInfo(request.phone(), toEndereco(request.address()));
        // 2. persiste e monta a resposta com os dados já atualizados.
        users.save(user);
        return ResponseEntity.ok(MeResponse.of(user, membershipDaConta(principal.userId())));
    }

    /**
     * Busca a conta autenticada pelo id do {@code sub} do JWT (D-13). Nunca deveria faltar: o
     * {@code JwtAuthenticationFilter} já confirmou que a conta existe para montar o {@link
     * AccountPrincipal} desta mesma requisição.
     */
    private User carregarUsuario(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Conta autenticada sem registro em users (sub do JWT inválido)"));
    }

    /**
     * Monta o sub-objeto {@code membership} da resposta a partir do vínculo da conta, se houver
     * (D-02: no máximo um por conta) — {@code null} enquanto a conta não pertence a um grupo
     * (AC-001.7). Quando existe, o {@code elderId} vem do mesmo caminho de {@link
     * #elderIdDoGrupo}: toda membership tem um idoso (D-05), então o resultado nunca deveria ser
     * {@code null} na prática.
     */
    private MeMembershipResponse membershipDaConta(UUID userId) {
        return memberships.findByUserId(userId)
                .map(membership -> MeMembershipResponse.of(
                        membership, elders.findByGroupId(membership.getGroupId()).map(Elder::getId).orElse(null)))
                .orElse(null);
    }

    /** Converte o {@code AddressRequest} validado para o objeto de valor de domínio (D-25). */
    private static Endereco toEndereco(AddressRequest address) {
        return new Endereco(
                address.zipCode(),
                address.street(),
                address.number(),
                address.complement(),
                address.district(),
                address.city(),
                address.state());
    }
}
