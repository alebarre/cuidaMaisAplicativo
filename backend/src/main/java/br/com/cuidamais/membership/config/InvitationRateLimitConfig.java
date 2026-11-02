package br.com.cuidamais.membership.config;

import br.com.cuidamais.shared.security.FixedWindowRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura o limitador por IP de {@code POST /invitations/redeem} (R-03, D-34), lendo
 * {@code app.invitations.redeem-rate-limit.max-requests} e {@code .window} (não são segredos).
 */
@Configuration
public class InvitationRateLimitConfig {

    /** Limitador de resgate de convite, uma janela fixa por IP, com o relógio da aplicação (D-10). */
    @Bean
    public FixedWindowRateLimiter redeemRateLimiter(
            @Value("${app.invitations.redeem-rate-limit.max-requests:30}") int maxRequests,
            @Value("${app.invitations.redeem-rate-limit.window:PT1M}") Duration window,
            Clock clock) {
        return new FixedWindowRateLimiter(maxRequests, window, clock);
    }
}
