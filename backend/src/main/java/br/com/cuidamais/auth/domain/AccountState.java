package br.com.cuidamais.auth.domain;

import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import java.util.Objects;

/**
 * Estado derivado da conta (AC-012.1, D-33): {@link #of(User, Membership)} é a única fonte da
 * verdade. O estado <strong>não é coluna</strong>; é calculado a cada chamada a partir de
 * {@code users.email_verified_at} e de {@code memberships.status}, o que evita duas colunas que
 * poderiam divergir.
 *
 * <p>Os acessores seguem os nomes do schema {@code AccountState} do contrato ({@code state},
 * {@code hasGroup}, {@code role}, {@code isAdmin}); {@code elderId} não é derivável de
 * {@code (user, membership)} e fica para a T-027.
 */
public final class AccountState {

    private final AccountStateValue state;
    private final boolean hasGroup;
    private final Role role;
    private final boolean admin;

    private AccountState(AccountStateValue state, boolean hasGroup, Role role, boolean admin) {
        this.state = state;
        this.hasGroup = hasGroup;
        this.role = role;
        this.admin = admin;
    }

    /**
     * Deriva o estado da conta (D-33):
     *
     * <ul>
     *   <li>sem {@code email_verified_at} e sem vínculo → {@code AGUARDANDO_CONFIRMACAO_EMAIL};
     *   <li>e-mail verificado e sem vínculo → {@code ATIVO} sem grupo (criador que ainda não
     *       cadastrou o idoso, AC-001.7);
     *   <li>e-mail verificado e com vínculo → o {@code status} da membership.
     * </ul>
     *
     * <p>Nenhuma outra combinação é aceita (AC-012.1): conta sem e-mail verificado não tem
     * vínculo com grupo, e a membership precisa ser da própria conta.
     *
     * @param user conta; obrigatória
     * @param membership vínculo da conta com o grupo, ou {@code null} se ela não tem vínculo
     * @throws NullPointerException se {@code user} for nulo
     * @throws IllegalArgumentException se a membership for de outra conta
     * @throws IllegalStateException se a conta sem e-mail verificado tiver membership
     */
    public static AccountState of(User user, Membership membership) {
        Objects.requireNonNull(user, "user é obrigatório para derivar o estado da conta");

        if (membership == null) {
            AccountStateValue state =
                    user.isEmailVerified()
                            ? AccountStateValue.ATIVO
                            : AccountStateValue.AGUARDANDO_CONFIRMACAO_EMAIL;
            return new AccountState(state, false, null, false);
        }

        if (!Objects.equals(membership.getUserId(), user.getId())) {
            throw new IllegalArgumentException(
                    "A membership não pertence a esta conta (AC-012.1)");
        }
        if (!user.isEmailVerified()) {
            throw new IllegalStateException(
                    "Conta sem e-mail verificado não pode ter vínculo com grupo (AC-012.1)");
        }

        // O papel só está em vigor em ATIVO: fora dele, Membership.role é provisório ou histórico.
        boolean ativo = membership.getStatus() == MembershipStatus.ATIVO;
        return new AccountState(
                stateOf(membership.getStatus()),
                true,
                ativo ? membership.getRole() : null,
                ativo && membership.isAdmin());
    }

    private static AccountStateValue stateOf(MembershipStatus status) {
        return switch (status) {
            case AGUARDANDO_APROVACAO -> AccountStateValue.AGUARDANDO_APROVACAO;
            case ATIVO -> AccountStateValue.ATIVO;
            case RECUSADO -> AccountStateValue.RECUSADO;
            case EXPIRADO -> AccountStateValue.EXPIRADO;
            case REMOVIDO -> AccountStateValue.REMOVIDO;
        };
    }

    public AccountStateValue state() {
        return state;
    }

    public boolean hasGroup() {
        return hasGroup;
    }

    /** Papel em vigor; {@code null} quando a conta não é membro {@code ATIVO} de um grupo. */
    public Role role() {
        return role;
    }

    public boolean isAdmin() {
        return admin;
    }
}
