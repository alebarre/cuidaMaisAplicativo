package br.com.cuidamais.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de taxa de janela fixa por chave (ex. IP), infraestrutura transversal usada em rotas
 * públicas sensíveis a força bruta (R-03, ex. {@code POST /invitations/redeem}).
 *
 * <p>Cada chave tem uma janela que começa na primeira chamada e dura {@code window}; até {@code
 * maxRequests} chamadas passam, as demais recebem o tempo restante até a janela reabrir. O "agora"
 * vem do {@link Clock} injetado (D-10), então os testes avançam o tempo sem esperar. As entradas
 * ficam em um {@link ConcurrentHashMap} atualizado de forma atômica ({@code compute}) e as
 * janelas vencidas são varridas no máximo uma vez por janela, para a memória não crescer sem
 * limite.
 *
 * <p>Limitação conhecida (R-06): o estado é local ao processo. Com várias réplicas do backend
 * seria necessário um armazenamento compartilhado (ex. Redis ou tabela) para um limite global.
 */
public class FixedWindowRateLimiter {

    /** Janela de uma chave: instante de início e quantas chamadas já foram contadas nela. */
    private record Window(Instant start, int count) {}

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile Instant lastSweep;

    /**
     * @param maxRequests chamadas permitidas por chave em cada janela
     * @param window duração da janela
     * @param clock relógio da aplicação (D-10)
     */
    public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    /**
     * Tenta consumir uma chamada da chave.
     *
     * @return {@code 0} se a chamada foi aceita; caso contrário, os segundos (no mínimo 1) até a
     *     janela da chave reabrir, para o cabeçalho {@code Retry-After}
     */
    public long tryAcquire(String key) {
        // 1. captura "agora" uma vez e limpa janelas antigas de vez em quando.
        Instant now = clock.instant();
        sweep(now);

        // 2. atualização atômica da janela da chave: reinicia se venceu, senão conta.
        long[] retryAfter = {0L};
        windows.compute(key, (k, current) -> {
            if (current == null || !now.isBefore(current.start().plus(window))) {
                return new Window(now, 1);
            }
            if (current.count() >= maxRequests) {
                long remainingMillis = Duration.between(now, current.start().plus(window)).toMillis();
                retryAfter[0] = Math.max(1L, (remainingMillis + 999) / 1000);
                return current;
            }
            return new Window(current.start(), current.count() + 1);
        });
        return retryAfter[0];
    }

    /** Remove as janelas já vencidas, no máximo uma varredura por duração de janela. */
    private void sweep(Instant now) {
        if (now.isBefore(lastSweep.plus(window))) {
            return;
        }
        lastSweep = now;
        windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start().plus(window)));
    }
}
