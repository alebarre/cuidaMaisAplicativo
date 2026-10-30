package br.com.cuidamais.notification.listener;

import br.com.cuidamais.notification.event.EmailEvent;
import br.com.cuidamais.notification.service.EmailMessage;
import br.com.cuidamais.notification.service.EmailTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia o e-mail de cada {@link EmailEvent} <strong>depois do commit</strong> da transação que o
 * publicou e de forma assíncrona (plan D-23): nunca sai e-mail de algo que não foi gravado, e uma
 * falha no SMTP não desfaz nem atrasa quem publicou. Sem {@code fallbackExecution}: um evento
 * publicado fora de transação é descartado, porque "nunca sai e-mail de algo que não foi gravado";
 * os jobs de D-09/D-42 são sempre transacionais.
 *
 * <p>Os textos vêm de {@link EmailTemplates}; o log nunca inclui destinatário, assunto, corpo nem
 * a resposta do servidor SMTP (Constitution P5).
 */
@Component
class EmailEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmailEventListener.class);

    private final JavaMailSender mailSender;
    private final EmailTemplates templates;
    private final String from;

    EmailEventListener(JavaMailSender mailSender, EmailTemplates templates, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.from = from;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(EmailEvent event) {
        EmailMessage message = templates.render(event);

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(event.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());

        try {
            mailSender.send(mail);
            log.debug("E-mail {} enviado", event.getClass().getSimpleName());
        } catch (MailException e) {
            // Só o tipo do evento e da exceção (P5): a mensagem/stack trace do SMTP pode ecoar o
            // endereço do destinatário.
            log.error("Falha ao enviar e-mail {}: {}", event.getClass().getSimpleName(), e.getClass().getSimpleName());
        }
    }
}
