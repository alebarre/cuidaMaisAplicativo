package br.com.cuidamais.shared.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de teste de integração (T-003): sobe PostgreSQL e Mailpit uma única vez por execução de
 * testes (padrão "singleton container" do Testcontainers — sem {@code @Testcontainers}/
 * {@code @Container}, para não parar os containers ao final de cada classe) e expõe um
 * {@link MutableClock} (D-10) para os testes avançarem o tempo.
 *
 * <p>Toda classe de teste de integração deve estender esta classe em vez de subir seus próprios
 * containers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MutableClockConfig.class, MockMvcContextPathConfig.class})
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    protected static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.29.7"))
                    .withExposedPorts(1025, 8025);

    static {
        POSTGRES.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired
    protected MutableClock clock;

    /** Cliente da API HTTP do Mailpit (porta 8025), para ler e apagar os e-mails recebidos. */
    protected static MailpitClient mailpit() {
        return new MailpitClient(MAILPIT.getHost(), MAILPIT.getMappedPort(8025));
    }
}
