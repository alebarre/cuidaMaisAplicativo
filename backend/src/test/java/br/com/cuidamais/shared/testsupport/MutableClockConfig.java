package br.com.cuidamais.shared.testsupport;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Substitui o {@code Clock} de produção (plan D-10) por um {@link MutableClock} nos testes de
 * integração, para que cada teste possa avançar o relógio em vez de esperar.
 */
@TestConfiguration
public class MutableClockConfig {

    /**
     * Semeia o relógio truncado em microssegundos: o PostgreSQL (`TIMESTAMPTZ`) só guarda essa
     * precisão, enquanto {@code Instant.now()} do ambiente tem nanossegundos. Sem o truncamento,
     * um teste que compara um {@code Instant} recém-persistido/lido de volta com
     * {@code clock.instant()} em memória pode falhar por uma diferença de poucos nanossegundos
     * que nunca sobreviveria a uma volta ao banco (achado na T-022).
     */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
