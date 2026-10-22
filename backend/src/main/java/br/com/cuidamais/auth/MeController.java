package br.com.cuidamais.auth;

import br.com.cuidamais.membership.Membership;
import br.com.cuidamais.membership.MembershipRepository;
import br.com.cuidamais.shared.security.AccountStateRequirement;
import br.com.cuidamais.shared.security.RequiredAccountState;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rota "sobre a própria conta" usada pelos guards do frontend (T-027, D-20). Hoje só tem
 * {@code GET /me/account}; o prefixo {@code /api/v1} vem do {@code server.servlet.context-path}
 * global (application.yml), por isso o mapeamento aqui é só {@code /me}.
 */
@RestController
@RequestMapping("/me")
public class MeController {

    private final MembershipRepository memberships;
    private final ElderIdLookup elderIdLookup;

    /**
     * @param memberships busca a membership da conta para achar o {@code group_id} (D-02: no
     *     máximo uma por conta)
     * @param elderIdLookup consulta pontual do idoso do grupo (T-027; entidade completa só na T-030)
     */
    public MeController(MembershipRepository memberships, ElderIdLookup elderIdLookup) {
        this.memberships = memberships;
        this.elderIdLookup = elderIdLookup;
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
        return elderIdLookup.findByGroupId(membership.getGroupId());
    }
}
