package br.com.cuidamais.membership.exception;

/**
 * Código de convite conferido, mas o convite está vencido, cancelado ou já usado (AC-004.5).
 * Traduzida em 410 pelo {@code GlobalExceptionHandler}; a mensagem é fixa (P5).
 */
public class InvitationUnavailableException extends RuntimeException {

    private final InvitationUnavailableCode code;

    /** @param code motivo estável devolvido ao cliente como {@code code} */
    public InvitationUnavailableException(InvitationUnavailableCode code) {
        super("Convite indisponível: " + code);
        this.code = code;
    }

    /** Motivo da indisponibilidade, usado pelo handler. */
    public InvitationUnavailableCode getCode() {
        return code;
    }
}
