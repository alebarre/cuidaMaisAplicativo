package br.com.cuidamais.notification.service;

import br.com.cuidamais.notification.event.AccountDeletedEmail;
import br.com.cuidamais.notification.event.ConfirmationCodeEmail;
import br.com.cuidamais.notification.event.DuplicateSignupEmail;
import br.com.cuidamais.notification.event.ElderReminderEmail;
import br.com.cuidamais.notification.event.EmailEvent;
import br.com.cuidamais.notification.event.GroupDeletedEmail;
import br.com.cuidamais.notification.event.InvitationEmail;
import br.com.cuidamais.notification.event.PasswordLockEmail;
import br.com.cuidamais.notification.event.PendingRequestEmail;
import br.com.cuidamais.notification.event.RequestResultEmail;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Templates pt-BR em texto simples, um por {@link EmailEvent} (T-016). Todos os textos ficam aqui,
 * nunca no listener (plan D-40). Datas/horas são exibidas em {@code America/Sao_Paulo}
 * (Constitution §5). Nenhum template carrega ou menciona dado de saúde (Constitution P5).
 */
@Component
public class EmailTemplates {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(SAO_PAULO);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm").withZone(SAO_PAULO);

    private static final String ASSINATURA = "\n\nEquipe Cuida+\n";

    public EmailTemplates() {
        // construtor sem argumentos: os testes de template usam {@code new EmailTemplates()}
    }

    public EmailMessage render(EmailEvent event) {
        return switch (event) {
            case ConfirmationCodeEmail e -> confirmationCode(e);
            case DuplicateSignupEmail e -> duplicateSignup();
            case InvitationEmail e -> invitation(e);
            case PendingRequestEmail e -> pendingRequest(e);
            case RequestResultEmail e -> requestResult(e);
            case PasswordLockEmail e -> passwordLock(e);
            case GroupDeletedEmail e -> groupDeleted();
            case ElderReminderEmail e -> elderReminder();
            case AccountDeletedEmail e -> accountDeleted();
        };
    }

    // AC-001.1, AC-005, AC-010.1
    private EmailMessage confirmationCode(ConfirmationCodeEmail e) {
        return switch (e.purpose()) {
            case CONFIRMACAO_EMAIL -> new EmailMessage(
                    "Cuida+: seu código de confirmação",
                    """
                    Olá!

                    Use o código abaixo para confirmar o seu e-mail no Cuida+:

                        %s

                    O código vale por 15 minutos. Se você não pediu este código, ignore esta mensagem.
                    """.formatted(e.code()) + ASSINATURA);
            case REDEFINICAO_SENHA -> new EmailMessage(
                    "Cuida+: código para redefinir sua senha",
                    """
                    Olá!

                    Recebemos um pedido para redefinir a senha da sua conta no Cuida+. Use o código abaixo:

                        %s

                    O código vale por 15 minutos. Se você não pediu para redefinir a senha, ignore esta mensagem; \
                    sua senha continua a mesma.
                    """.formatted(e.code()) + ASSINATURA);
        };
    }

    // AC-001.4
    private EmailMessage duplicateSignup() {
        return new EmailMessage(
                "Cuida+: tentativa de cadastro com o seu e-mail",
                """
                Olá!

                Recebemos uma tentativa de criar uma conta no Cuida+ com este e-mail, mas ele já está cadastrado.

                Se foi você, basta entrar com a sua senha. Se não lembra a senha, use a opção \
                "Esqueci minha senha" na tela de login para redefini-la.

                Se não foi você, não é preciso fazer nada: nenhuma conta nova foi criada.
                """ + ASSINATURA);
    }

    // AC-003.1, AC-003.2, AC-004.1
    private EmailMessage invitation(InvitationEmail e) {
        return new EmailMessage(
                "Cuida+: você foi convidado(a) para o grupo de cuidado de %s".formatted(e.elderFirstName()),
                """
                Olá!

                %s convidou você para participar do grupo de cuidado de %s no Cuida+.

                Seu código de convite:

                    %s

                Para entrar, abra o Cuida+, escolha "Tenho um código de convite" na tela de login e \
                informe o código acima. O código vale por 7 dias.
                """.formatted(e.responsibleName(), e.elderFirstName(), e.code()) + ASSINATURA);
    }

    // AC-006.7
    private EmailMessage pendingRequest(PendingRequestEmail e) {
        return new EmailMessage(
                "Cuida+: pedido de entrada no grupo aguarda sua decisão",
                """
                Olá!

                %s pediu para entrar no seu grupo de cuidado no Cuida+ e aguarda a sua decisão.

                Entre no Cuida+ para aprovar ou recusar o pedido até %s às %s (horário de Brasília). \
                Depois desse prazo, o pedido expira automaticamente.
                """.formatted(e.requesterName(), DATA.format(e.deadline()), HORA.format(e.deadline())) + ASSINATURA);
    }

    // AC-007.1, AC-007.2
    private EmailMessage requestResult(RequestResultEmail e) {
        return switch (e.result()) {
            case APROVADO -> new EmailMessage(
                    "Cuida+: seu pedido foi aprovado",
                    """
                    Olá!

                    O Responsável aprovou o seu pedido de entrada no grupo de cuidado. \
                    Você já pode entrar no Cuida+ com o seu e-mail e senha.
                    """ + ASSINATURA);
            case RECUSADO -> new EmailMessage(
                    "Cuida+: seu pedido foi recusado",
                    """
                    Olá!

                    O Responsável recusou o seu pedido de entrada no grupo de cuidado.

                    Se acredita que houve um engano, peça um novo convite ao Responsável pelo grupo.
                    """ + ASSINATURA);
            case EXPIRADO -> new EmailMessage(
                    "Cuida+: seu pedido expirou",
                    """
                    Olá!

                    O seu pedido de entrada no grupo de cuidado expirou sem resposta do Responsável.

                    Para tentar de novo, peça um novo convite ao Responsável pelo grupo.
                    """ + ASSINATURA);
        };
    }

    // AC-008.4
    private EmailMessage passwordLock(PasswordLockEmail e) {
        return new EmailMessage(
                "Cuida+: sua conta foi bloqueada temporariamente",
                """
                Olá!

                Sua conta no Cuida+ foi bloqueada temporariamente após várias tentativas de entrar com a \
                senha errada.

                O bloqueio vai até %s às %s (horário de Brasília). Depois disso, você poderá tentar de novo.

                Se não foi você que tentou entrar, recomendamos redefinir a sua senha assim que o bloqueio terminar.
                """.formatted(DATA.format(e.lockedUntil()), HORA.format(e.lockedUntil())) + ASSINATURA);
    }

    // AC-013.3
    private EmailMessage groupDeleted() {
        return new EmailMessage(
                "Cuida+: o grupo de cuidado foi excluído",
                """
                Olá!

                O Responsável excluiu o grupo de cuidado do qual você participava no Cuida+. \
                Os seus dados nesse grupo foram apagados.

                Se quiser voltar a usar o Cuida+, você pode criar um novo grupo ou pedir um convite a \
                outro Responsável.
                """ + ASSINATURA);
    }

    // AC-014.3
    private EmailMessage elderReminder() {
        return new EmailMessage(
                "Cuida+: conclua o cadastro do idoso",
                """
                Olá!

                Você criou sua conta no Cuida+ para organizar o cuidado de um idoso, mas ainda não \
                cadastrou o idoso.

                Entre no Cuida+ e conclua o cadastro do idoso em até 7 dias. Depois desse prazo, a sua \
                conta será excluída automaticamente.
                """ + ASSINATURA);
    }

    // AC-014.1, AC-014.3 — mesmo e-mail para RECUSADO/EXPIRADO retidos e para o criador sem idoso:
    // não cita motivo.
    private EmailMessage accountDeleted() {
        return new EmailMessage(
                "Cuida+: sua conta foi excluída",
                """
                Olá!

                Sua conta no Cuida+ foi excluída e os seus dados foram apagados. \
                Se quiser usar o Cuida+, basta fazer um novo cadastro.
                """ + ASSINATURA);
    }
}
