package br.com.cuidamais.shared.exception;

/**
 * Recurso que não existe <strong>ou</strong> que existe mas pertence a outro grupo de cuidado
 * (plan D-32, AC-015.3).
 *
 * <p>Deliberadamente não tem nenhum construtor que aceite uma mensagem ou motivo: os dois casos
 * — "outro grupo" e "inexistente" — devem lançar exatamente esta exceção, sem nenhuma informação
 * que permita diferenciá-los, para que {@link GlobalExceptionHandler} produza o mesmo corpo de
 * resposta nos dois casos (P5 — nunca revelar qual dos dois ocorreu).
 *
 * <p>{@link GlobalExceptionHandler} mapeia esta exceção para {@code 404}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("Recurso não encontrado.");
    }
}
