package br.com.cuidamais.auth;

/**
 * Login com e-mail <strong>e</strong> senha corretos, mas a conta não está {@code ATIVO} (T-023,
 * AC-008.2, D-33). Só é lançada depois da senha confirmada — por isso pode revelar o estado da
 * conta no {@code code} da resposta, diferente de {@link InvalidCredentialsException} (que nunca
 * revela nada). Nunca é lançada com {@link AccountStateValue#ATIVO} (esse estado sempre abre
 * sessão, 200). {@link br.com.cuidamais.shared.GlobalExceptionHandler} mapeia esta exceção para
 * {@code 403} com {@code AccountStateProblem}.
 */
public class AccountNotActiveException extends RuntimeException {

    private final AccountStateValue state;

    /**
     * @param state estado atual da conta (nunca {@code ATIVO})
     */
    public AccountNotActiveException(AccountStateValue state) {
        super("A conta não está ativa (AC-008.2): " + state);
        this.state = state;
    }

    /** Estado da conta no momento do login; vira o {@code code} do {@code AccountStateProblem}. */
    public AccountStateValue getState() {
        return state;
    }
}
