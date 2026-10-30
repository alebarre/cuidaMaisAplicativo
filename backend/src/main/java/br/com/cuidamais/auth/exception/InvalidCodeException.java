package br.com.cuidamais.auth.exception;

/**
 * Código OTP de formato válido, mas que não é o código certo pendente para a conta (AC-005.2,
 * T-022). Carrega {@code remainingAttempts} (quantas tentativas erradas ainda restam antes do
 * código se esgotar, D-07/{@code OtpCode#verify}) para o corpo {@code InvalidCodeProblem} do
 * contrato — {@link br.com.cuidamais.shared.exception.GlobalExceptionHandler} mapeia esta exceção para
 * {@code 400}.
 *
 * <p>Quem lança esta exceção já persistiu o incremento de {@code attempts} antes de lançá-la
 * (ver {@code VerifyEmailService#verify}): a tentativa errada precisa ficar gravada mesmo com a
 * resposta de erro, senão o contador de 5 tentativas nunca andaria (AC-005.2).
 */
public class InvalidCodeException extends RuntimeException {

    private final int remainingAttempts;

    /**
     * @param remainingAttempts tentativas erradas que ainda restam (de 4 a 0, nunca negativo)
     */
    public InvalidCodeException(int remainingAttempts) {
        super("Código informado está incorreto (AC-005.2).");
        this.remainingAttempts = remainingAttempts;
    }

    /** Quantas tentativas erradas ainda restam antes do código ser esgotado (AC-005.2). */
    public int getRemainingAttempts() {
        return remainingAttempts;
    }
}
