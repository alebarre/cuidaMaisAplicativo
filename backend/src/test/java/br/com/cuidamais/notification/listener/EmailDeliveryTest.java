package br.com.cuidamais.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import br.com.cuidamais.notification.event.EmailEvent;
import br.com.cuidamais.notification.event.GroupDeletedEmail;
import br.com.cuidamais.notification.service.EmailMessage;
import br.com.cuidamais.notification.service.EmailTemplates;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import br.com.cuidamais.shared.testsupport.MailpitClient;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-016 "Pronto quando" (plan D-23): o e-mail de um evento publicado dentro de uma transação só
 * sai <strong>depois do commit</strong>; se a transação faz rollback, <strong>nenhum</strong>
 * e-mail sai; e um evento publicado <strong>fora</strong> de transação também não é enviado
 * ("nunca sai e-mail de algo que não foi gravado" — sem {@code fallbackExecution}; os jobs de
 * D-09/D-42 são sempre transacionais). Observa o Mailpit real (Testcontainers) pela API HTTP.
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.notification}):
 *
 * <ul>
 *   <li>Os eventos de {@link EmailEvent} são publicados com {@code
 *       ApplicationEventPublisher.publishEvent(evento)} por quem está na transação.
 *   <li>Um listener (sugestão: {@code EmailEventListener}) com {@code
 *       @TransactionalEventListener(phase = AFTER_COMMIT)} (padrão {@code fallbackExecution =
 *       false}) e {@code @Async} (exige {@code @EnableAsync}) renderiza com {@link
 *       EmailTemplates} e envia pelo {@code JavaMailSender} com remetente {@code app.mail.from}.
 * </ul>
 */
class EmailDeliveryTest extends IntegrationTest {

    private static final Duration ESPERA_ENTREGA = Duration.ofSeconds(10);
    private static final Duration ESPERA_SILENCIO = Duration.ofSeconds(3);

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Value("${app.mail.from}")
    String from;

    private final MailpitClient mailpit = mailpit();
    private final EmailTemplates templates = new EmailTemplates();

    @BeforeEach
    void limpaCaixa() {
        mailpit.deleteAll();
        assertThat(mailpit.count()).isZero();
    }

    @Test
    @DisplayName("D-23 (a) evento publicado em transação que commita chega ao Mailpit, com assunto do template e remetente app.mail.from")
    void d_23_a_email_chega_ao_mailpit_apos_commit() throws AddressException {
        var evento = new ConfirmationCodeEmail("ana@example.com", "483920", OtpPurpose.CONFIRMACAO_EMAIL);

        tx.executeWithoutResult(status -> publisher.publishEvent(evento));

        await().atMost(ESPERA_ENTREGA)
                .untilAsserted(() -> assertThat(mailpit.messagesTo("ana@example.com")).hasSize(1));

        List<MailpitClient.Summary> recebidos = mailpit.messagesTo("ana@example.com");
        assertThat(mailpit.count()).as("exatamente um e-mail, para o destinatário").isEqualTo(1);

        MailpitClient.Detail detalhe = mailpit.message(recebidos.getFirst().id());
        EmailMessage esperado = templates.render(evento);
        InternetAddress remetente = new InternetAddress(from);

        assertThat(detalhe.to()).containsExactly("ana@example.com");
        assertThat(detalhe.subject()).isEqualTo(esperado.subject());
        assertThat(detalhe.fromAddress()).isEqualTo(remetente.getAddress());
        assertThat(detalhe.fromName()).isEqualTo(remetente.getPersonal());
        assertThat(detalhe.text()).as("corpo em texto simples traz o código").contains("483920");
    }

    @Test
    @DisplayName("D-23 (b) evento publicado em transação que faz rollback: nenhum e-mail sai")
    void d_23_b_nenhum_email_sai_se_a_transacao_faz_rollback() {
        var evento = new ConfirmationCodeEmail("bruno@example.com", "112233", OtpPurpose.CONFIRMACAO_EMAIL);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(evento);
            status.setRollbackOnly();
        });

        await().during(ESPERA_SILENCIO)
                .atMost(ESPERA_SILENCIO.plusSeconds(2))
                .untilAsserted(() -> assertThat(mailpit.count()).isZero());
    }

    @Test
    @DisplayName("D-23 (c) evento publicado fora de qualquer transação NÃO é enviado (sem fallbackExecution)")
    void d_23_c_evento_fora_de_transacao_nao_e_enviado() {
        var evento = new GroupDeletedEmail("carla@example.com");

        publisher.publishEvent(evento);

        await().during(ESPERA_SILENCIO)
                .atMost(ESPERA_SILENCIO.plusSeconds(2))
                .untilAsserted(() -> assertThat(mailpit.count()).isZero());
    }
}
