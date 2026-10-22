package br.com.cuidamais.shared.security;

/**
 * Sessão válida, mas o estado atual da conta não satisfaz a exigência da rota (T-024) — ex. conta
 * {@code ATIVO} sem vínculo com grupo chamando uma rota que exige grupo (regra transversal do
 * contrato: "toda operação autenticada exige conta {@code ATIVO} com vínculo ativo, salvo
 * indicação").
 *
 * <p>Não cobre o caso {@code REMOVIDO}, tratado como {@link UnauthorizedSessionException} (401,
 * sem {@code code}) em vez desta exceção (403, {@code code: ACCOUNT_STATE}) — ver {@code
 * AccountSecurityInterceptor}.
 *
 * <p>{@code GlobalExceptionHandler} mapeia esta exceção para {@code 403} com {@code code:
 * ACCOUNT_STATE}.
 */
public class AccountStateException extends RuntimeException {

    public AccountStateException() {
        super("O estado atual da conta não permite esta operação.");
    }
}
