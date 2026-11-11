package br.com.cuidamais.membership.exception;

/**
 * Conflito de negócio nas rotas de convite, traduzido em 409 com o {@code code} correspondente
 * pelo {@code GlobalExceptionHandler}. A mensagem é fixa e não carrega e-mail nem nome (P5).
 */
public class InvitationConflictException extends RuntimeException {

    private final InvitationConflictCode code;

    /** @param code motivo estável do conflito, devolvido ao cliente como {@code code} */
    public InvitationConflictException(InvitationConflictCode code) {
        super("Conflito na operação de convite: " + code);
        this.code = code;
    }

    /** Motivo do conflito, usado pelo handler para escolher título/detalhe e o {@code code}. */
    public InvitationConflictCode getCode() {
        return code;
    }
}
