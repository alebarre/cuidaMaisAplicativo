package br.com.cuidamais.membership.exception;

/**
 * Pedido de acesso vencido (24 h sem decisão, AC-006.8): vale tanto para o status {@code EXPIRADO}
 * quanto para {@code AGUARDANDO_APROVACAO} já fora do prazo, antes de o job rodar (D-09 rev.2).
 * Traduzida em 410 {@code REQUEST_EXPIRED} pelo {@code GlobalExceptionHandler}; mensagem fixa (P5).
 */
public class RequestExpiredException extends RuntimeException {

    /** Cria a exceção com mensagem fixa, sem dado de pessoa. */
    public RequestExpiredException() {
        super("Pedido de acesso expirado");
    }
}
