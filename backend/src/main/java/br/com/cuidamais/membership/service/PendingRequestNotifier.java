package br.com.cuidamais.membership.service;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.notification.event.PendingRequestEmail;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Avisa os Responsáveis (admins ativos) do grupo de que há um pedido de acesso aguardando decisão
 * (AC-006.7, D-23). Compartilhado pelo reingresso (T-041) e pelo cadastro por convite (T-042) para
 * a regra "quem é avisado" existir num lugar só. O evento é publicado dentro da transação do
 * chamador e só vira e-mail após o commit; carrega apenas o nome de quem pediu e o prazo, nunca
 * dado do idoso (P5).
 */
@Component
public class PendingRequestNotifier {

    private final MembershipRepository memberships;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    /** Injeção por construtor dos repositórios de vínculos e contas e do publicador de eventos. */
    public PendingRequestNotifier(
            MembershipRepository memberships, UserRepository users, ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.users = users;
        this.events = events;
    }

    /**
     * Publica um {@link PendingRequestEmail} para cada admin ATIVO do grupo do pedido.
     *
     * @param request vínculo recém-colocado em AGUARDANDO_APROVACAO (traz grupo e prazo)
     * @param requester conta de quem pediu (só o nome vai no e-mail)
     */
    public void notifyAdmins(Membership request, User requester) {
        for (Membership admin : memberships.findByGroupId(request.getGroupId())) {
            if (admin.isAdmin() && admin.getStatus() == MembershipStatus.ATIVO) {
                users.findById(admin.getUserId()).ifPresent(responsible -> events.publishEvent(
                        new PendingRequestEmail(responsible.getEmail(), requester.getName(), request.getExpiresAt())));
            }
        }
    }
}
