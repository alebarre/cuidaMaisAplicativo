package br.com.cuidamais.shared.exception;

/**
 * Nenhuma sessão válida para a rota autenticada chamada (T-024): cobre tanto "nenhum {@code
 * AccountPrincipal} no contexto" (token ausente, mal formado, assinado com outro segredo,
 * adulterado, expirado, ou de um {@code sub} sem conta no banco) quanto "a conta existe, tem
 * token no prazo, mas a membership virou {@code REMOVIDO}" (AC-009.2) — nos dois casos o
 * contrato pede o mesmo {@code Problem} genérico 401, sem nenhum campo {@code code} e sem nenhum
 * dado da conta (P5), porque revelar a diferença ("seu token é inválido" vs. "você foi removido")
 * já seria informação demais para quem não está autenticado de verdade.
 *
 * <p>{@code GlobalExceptionHandler} mapeia esta exceção para {@code 401}.
 */
public class UnauthorizedSessionException extends RuntimeException {

    public UnauthorizedSessionException() {
        super("Sessão inválida, expirada ou associada a um vínculo que deixou de existir.");
    }
}
