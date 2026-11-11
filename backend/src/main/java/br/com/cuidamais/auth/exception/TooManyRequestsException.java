package br.com.cuidamais.auth.exception;

import br.com.cuidamais.auth.domain.OtpCode;
import br.com.cuidamais.auth.service.OtpService;

/**
 * Pedido de reenvio recusado por estar antes do intervalo mínimo de 1 minuto (T-026, AC-005.3):
 * lançada quando {@link OtpCode#resend} (via {@link OtpService#resend}) recusa reenviar porque
 * ainda não passaram 60 s desde {@code otp_codes.last_sent_at}. Carrega quantos segundos faltam
 * para completar o minuto (arredondado para cima, ver {@link OtpCode#secondsUntilResendAllowed}),
 * que {@link br.com.cuidamais.shared.exception.GlobalExceptionHandler} usa para o cabeçalho HTTP {@code
 * Retry-After} da resposta {@code 429} — não é um campo do corpo, é header de resposta (contrato,
 * {@code components.responses.TooManyRequests.headers.Retry-After}).
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds segundos restantes até o próximo reenvio ser aceito (nunca negativo)
     */
    public TooManyRequestsException(long retryAfterSeconds) {
        super("Reenvio recusado; respeite o intervalo de 1 minuto (AC-005.3).");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Segundos restantes até o próximo reenvio ser aceito; vira o cabeçalho {@code Retry-After}. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
