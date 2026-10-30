package br.com.cuidamais.auth.dto;

import br.com.cuidamais.auth.domain.AccountState;
import java.util.UUID;

/**
 * Corpo de sucesso de {@code GET /me/account} (T-027), espelhando o schema {@code AccountState}
 * do contrato: o estado derivado da conta (AC-012.1, D-33) mais os campos que o guard do
 * frontend lê para decidir a tela (D-20) — {@code hasGroup}, {@code elderId}, {@code role} e
 * {@code isAdmin}.
 */
public record AccountStateResponse(String state, boolean hasGroup, UUID elderId, String role, boolean isAdmin) {

    /**
     * Monta a resposta a partir do estado já derivado ({@link AccountState}, T-017, sem
     * alteração) e do {@code elderId} resolvido à parte (esta classe não deriva nada: {@code
     * state} é o nome do enum Java, idêntico ao do contrato, sem tradução; {@code role} só não é
     * {@code null} quando {@link AccountState#role()} também não é).
     *
     * @param state estado da conta já calculado por {@link AccountState#of}
     * @param elderId id do idoso do grupo da conta, ou {@code null} quando não há grupo/idoso
     */
    public static AccountStateResponse of(AccountState state, UUID elderId) {
        String role = state.role() == null ? null : state.role().name();
        return new AccountStateResponse(state.state().name(), state.hasGroup(), elderId, role, state.isAdmin());
    }
}
