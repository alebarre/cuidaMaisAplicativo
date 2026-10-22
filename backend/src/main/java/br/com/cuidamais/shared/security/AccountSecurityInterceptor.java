package br.com.cuidamais.shared.security;

import br.com.cuidamais.auth.AccountPrincipal;
import br.com.cuidamais.auth.AccountState;
import br.com.cuidamais.auth.AccountStateValue;
import br.com.cuidamais.shared.NotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Camada 2 da autorização por requisição (T-024): aplica, dentro do ciclo do Spring MVC (depois
 * de {@link JwtAuthenticationFilter} ter tentado popular o {@code SecurityContextHolder}), as
 * extensões do contrato {@code x-required-account-state} ({@link RequiredAccountState}) e
 * {@code x-required-permission: admin} ({@link RequiresAdminPermission}), além da regra
 * transversal "toda operação autenticada exige conta {@code ATIVO} com vínculo ativo, salvo
 * indicação".
 *
 * <p>Ordem de avaliação, sempre nesta sequência (D-13, AC-009.1, AC-009.2, AC-015.2):
 *
 * <ol>
 *   <li>{@link PublicEndpoint} no método ou na classe → passa sempre, sem nem olhar o contexto de
 *       segurança.
 *   <li>Sem {@link AccountPrincipal} autenticado → {@link UnauthorizedSessionException} (401,
 *       {@code Problem} genérico, sem {@code code} — P5).
 *   <li>Exigência de estado ({@link RequiredAccountState}, método depois classe, padrão = exigir
 *       {@code ATIVO} e grupo) não satisfeita: se o estado atual é {@code REMOVIDO}, trata como
 *       sessão inválida (401); qualquer outro caso (ex. {@code ATIVO} sem grupo numa rota padrão)
 *       → {@link AccountStateException} (403, {@code code: ACCOUNT_STATE}).
 *   <li>{@link RequiresAdminPermission} (método depois classe) presente e a conta não é
 *       administradora → {@link NotAllowedException} (403, {@code code: NOT_ALLOWED}, já mapeada
 *       desde T-007).
 * </ol>
 *
 * <p>Registrado como interceptor de MVC (não como filtro de servlet) porque precisa resolver o
 * {@code HandlerMethod} da rota — as anotações consultadas vivem no método/classe do controller,
 * algo que só o Spring MVC, depois do roteamento, sabe apontar.
 */
@Component
public class AccountSecurityInterceptor implements HandlerInterceptor {

    /**
     * Confere as quatro etapas da ordem de avaliação descrita no Javadoc da classe antes de
     * deixar o Spring MVC despachar para o método do controller.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 0. recursos sem HandlerMethod (ex. estáticos) não têm anotação nenhuma para ler.
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 1. rota pública: nenhuma exigência, nem de sessão.
        if (isPublic(handlerMethod)) {
            return true;
        }

        // 2. precisa de uma sessão válida (D-13): o AccountPrincipal só existe se o filtro JWT
        //    conseguiu validar o token E encontrar a conta no banco nesta requisição.
        AccountPrincipal principal = principalOf();
        if (principal == null) {
            throw new UnauthorizedSessionException();
        }

        // 3. exigência de estado (padrão do contrato ou a declarada na anotação).
        checkAccountState(handlerMethod, principal.state());

        // 4. exigência de permissão de administração (x-required-permission: admin).
        if (requiresAdmin(handlerMethod) && !principal.state().isAdmin()) {
            throw new NotAllowedException();
        }

        return true;
    }

    /** {@code @PublicEndpoint}: método tem prioridade; senão olha a classe do controller. */
    private static boolean isPublic(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(PublicEndpoint.class)
                || handlerMethod.getBeanType().isAnnotationPresent(PublicEndpoint.class);
    }

    /** Lê o principal autenticado pelo {@link JwtAuthenticationFilter}, ou {@code null}. */
    private static AccountPrincipal principalOf() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            return null;
        }
        return principal;
    }

    /**
     * Resolve a exigência de estado da rota (método tem prioridade sobre a classe; ausência de
     * anotação nos dois níveis é o padrão do contrato) e lança a exceção certa quando ela não é
     * satisfeita pelo estado atual — 401 se a conta foi removida, 403 {@code ACCOUNT_STATE} nos
     * demais casos.
     */
    private static void checkAccountState(HandlerMethod handlerMethod, AccountState state) {
        AccountStateRequirement requirement = requirementOf(handlerMethod);

        // x-required-account-state: any → nenhuma exigência de estado, nem para conta REMOVIDO.
        if (requirement == AccountStateRequirement.ANY) {
            return;
        }

        boolean satisfeita =
                requirement == AccountStateRequirement.ACTIVE_WITHOUT_GROUP
                        ? state.state() == AccountStateValue.ATIVO
                        : state.state() == AccountStateValue.ATIVO && state.hasGroup();

        if (satisfeita) {
            return;
        }

        // Conta removida: o vínculo que sustentava a sessão deixou de existir (AC-009.2) — mesmo
        // tratamento de sessão inválida (401), não um 403 de estado.
        if (state.state() == AccountStateValue.REMOVIDO) {
            throw new UnauthorizedSessionException();
        }
        throw new AccountStateException();
    }

    /** {@code @RequiredAccountState}: método tem prioridade; senão a classe; senão o padrão. */
    private static AccountStateRequirement requirementOf(HandlerMethod handlerMethod) {
        RequiredAccountState annotation = handlerMethod.getMethodAnnotation(RequiredAccountState.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiredAccountState.class);
        }
        // Ausência nos dois níveis é o padrão do contrato: ATIVO e com grupo (tratado em
        // checkAccountState como "nem ANY nem ACTIVE_WITHOUT_GROUP").
        return annotation == null ? null : annotation.value();
    }

    /** {@code @RequiresAdminPermission}: método ou classe (herdada por todos os métodos dela). */
    private static boolean requiresAdmin(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(RequiresAdminPermission.class)
                || handlerMethod.getBeanType().isAnnotationPresent(RequiresAdminPermission.class);
    }
}
