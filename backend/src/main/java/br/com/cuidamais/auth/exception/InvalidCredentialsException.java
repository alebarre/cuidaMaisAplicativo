package br.com.cuidamais.auth.exception;

/**
 * "E-mail ou senha inválidos" (T-023, AC-008.4, D-15, P5): resposta única para e-mail inexistente,
 * senha errada e conta bloqueada por 5 erros seguidos — os três casos precisam ser
 * <strong>indistinguíveis</strong> na resposta (mesmo status, mesmo corpo, sem nenhum campo que
 * varie entre contas), senão alguém poderia descobrir se um e-mail tem cadastro ou se uma conta
 * está bloqueada só olhando a resposta. {@link br.com.cuidamais.shared.exception.GlobalExceptionHandler}
 * mapeia esta exceção para {@code 401} com um {@code Problem} genérico, sem nenhum dado da conta.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("E-mail ou senha inválidos (AC-008.4).");
    }
}
