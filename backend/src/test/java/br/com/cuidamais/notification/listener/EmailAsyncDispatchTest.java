package br.com.cuidamais.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import br.com.cuidamais.auth.domain.OtpPurpose;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import br.com.cuidamais.notification.event.PasswordLockEmail;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-016, plan D-23: o envio é <strong>assíncrono</strong> — "uma falha no SMTP não desfaz o
 * cadastro" e quem publicou não espera o SMTP. Aqui o {@link JavaMailSender} é um mock para
 * simular lentidão e falha de SMTP sem mexer no Mailpit.
 */
class EmailAsyncDispatchTest extends IntegrationTest {

    private static final Duration LIMITE_PUBLICADOR = Duration.ofSeconds(2);
    private static final long SMTP_LENTO_SEGUNDOS = 5;

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("D-23 envio assíncrono: a transação que publicou termina sem esperar o SMTP")
    void d_23_transacao_nao_espera_o_smtp() throws InterruptedException {
        CountDownLatch envioComecou = new CountDownLatch(1);
        CountDownLatch liberaSmtp = new CountDownLatch(1);
        Answer<Void> smtpLento = invocation -> {
            envioComecou.countDown();
            liberaSmtp.await(SMTP_LENTO_SEGUNDOS, TimeUnit.SECONDS);
            return null;
        };
        doAnswer(smtpLento).when(mailSender).send(any(SimpleMailMessage.class));
        doAnswer(smtpLento).when(mailSender).send(any(MimeMessage.class));

        long inicio = System.nanoTime();
        tx.executeWithoutResult(status -> publisher.publishEvent(
                new ConfirmationCodeEmail("dora@example.com", "654321", OtpPurpose.CONFIRMACAO_EMAIL)));
        Duration decorrido = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(envioComecou.await(SMTP_LENTO_SEGUNDOS, TimeUnit.SECONDS))
                .as("o listener chegou a chamar o JavaMailSender")
                .isTrue();
        liberaSmtp.countDown();
        assertThat(decorrido)
                .as("quem publicou não ficou preso esperando o SMTP (envio assíncrono)")
                .isLessThan(LIMITE_PUBLICADOR);
    }

    @Test
    @DisplayName("D-23 falha no SMTP não desfaz nem quebra a transação que publicou")
    void d_23_falha_no_smtp_nao_desfaz_a_transacao() throws InterruptedException {
        CountDownLatch envioTentado = new CountDownLatch(1);
        Answer<Void> smtpFalha = invocation -> {
            envioTentado.countDown();
            throw new MailSendException("SMTP indisponível (simulado)");
        };
        doAnswer(smtpFalha).when(mailSender).send(any(SimpleMailMessage.class));
        doAnswer(smtpFalha).when(mailSender).send(any(MimeMessage.class));

        AtomicInteger statusFinal = new AtomicInteger(-1);

        assertThatCode(() -> tx.executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            statusFinal.set(status);
                        }
                    });
                    publisher.publishEvent(new PasswordLockEmail("edu@example.com", clock.instant()));
                }))
                .as("a exceção do SMTP não chega a quem publicou")
                .doesNotThrowAnyException();

        assertThat(statusFinal.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(envioTentado.await(SMTP_LENTO_SEGUNDOS, TimeUnit.SECONDS))
                .as("o listener tentou enviar (e falhou) depois do commit")
                .isTrue();
    }
}
