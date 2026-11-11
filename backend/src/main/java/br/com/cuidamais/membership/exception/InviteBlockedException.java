package br.com.cuidamais.membership.exception;

import java.time.Instant;

/**
 * E-mail bloqueado por 5 tentativas erradas de código de convite (AC-003.3, AC-004.7). Vira 423
 * com {@code code=INVITE_CODE_BLOCKED} e {@code blockedUntil}; a mensagem não cita e-mail (P5).
 */
public class InviteBlockedException extends RuntimeException {

    private final Instant blockedUntil;

    /** @param blockedUntil fim do bloqueio de 30 minutos, devolvido ao cliente */
    public InviteBlockedException(Instant blockedUntil) {
        super("E-mail bloqueado por tentativas erradas de código de convite");
        this.blockedUntil = blockedUntil;
    }

    /** Fim do bloqueio, usado pelo handler para montar {@code blockedUntil} do 423. */
    public Instant getBlockedUntil() {
        return blockedUntil;
    }
}
