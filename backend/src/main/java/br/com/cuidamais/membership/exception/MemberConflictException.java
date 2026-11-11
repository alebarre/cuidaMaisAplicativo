package br.com.cuidamais.membership.exception;

/**
 * Conflito de negócio nas rotas de decisão sobre membros (aprovar/recusar), traduzido em 409 com
 * o {@code code} correspondente pelo {@code GlobalExceptionHandler}. A mensagem é fixa e não
 * carrega nome, e-mail nem dado do idoso (P5).
 */
public class MemberConflictException extends RuntimeException {

    private final MemberConflictCode code;

    /** @param code motivo estável do conflito, devolvido ao cliente como {@code code} */
    public MemberConflictException(MemberConflictCode code) {
        super("Conflito na decisão sobre o membro: " + code);
        this.code = code;
    }

    /** Motivo do conflito, usado pelo handler para escolher o detalhe e o {@code code}. */
    public MemberConflictCode getCode() {
        return code;
    }
}
