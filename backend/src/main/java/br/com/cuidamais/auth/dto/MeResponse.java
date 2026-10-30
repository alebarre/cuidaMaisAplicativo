package br.com.cuidamais.auth.dto;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.group.dto.AddressResponse;
import java.util.UUID;

/**
 * Corpo de sucesso de {@code GET}/{@code PUT /me} (T-032), espelhando o schema {@code Me} do
 * contrato: os dados da própria conta (nome, e-mail, telefone, endereço) e o vínculo com o
 * grupo, se houver (AC-011.6). {@code membership} é {@code null} enquanto a conta não pertence a
 * um grupo (criador que ainda não cadastrou o idoso, AC-001.7).
 *
 * <p>Reaproveita {@link AddressResponse} (T-030) por import cruzado do pacote {@code group} —
 * mesmos 7 campos do schema {@code Address}, nenhuma duplicação necessária.
 *
 * @param userId identificador da conta
 * @param name nome da conta ({@code name}/{@code email} não são editáveis por {@code PUT /me})
 * @param email e-mail da conta
 * @param phone telefone atual (editável por {@code PUT /me}, AC-011.6)
 * @param address endereço atual (editável por {@code PUT /me}, AC-011.6)
 * @param membership vínculo com o grupo, ou {@code null} se a conta ainda não tem grupo
 */
public record MeResponse(
        UUID userId, String name, String email, String phone, AddressResponse address, MeMembershipResponse membership) {

    /**
     * Monta a resposta a partir da conta já carregada e do vínculo (quando existe) — esta classe
     * não busca nada em repositório, só converte o que {@code MeController} já leu.
     *
     * @param user conta autenticada
     * @param membership vínculo com o grupo, ou {@code null} se a conta não tem grupo
     */
    public static MeResponse of(User user, MeMembershipResponse membership) {
        return new MeResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                AddressResponse.of(user.getAddress()),
                membership);
    }
}
