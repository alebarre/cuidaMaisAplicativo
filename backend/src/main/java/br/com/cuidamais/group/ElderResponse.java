package br.com.cuidamais.group;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Corpo de sucesso de {@code POST /elders}, {@code GET /elders/{elderId}}, {@code PUT
 * /elders/{elderId}} (T-030) e {@code PUT /elders/{elderId}/photo} (T-031), espelhando o schema
 * {@code Elder} do contrato ({@code ElderInput} mais {@code id}, {@code photoUrl} e {@code
 * updatedAt}).
 *
 * <p>{@code photoUrl} é calculado aqui, não em cada chamador, exatamente porque o schema é o
 * mesmo em toda rota que devolve um {@code Elder}: {@code null} enquanto {@code
 * Elder.photoPath} for {@code null} (nenhuma foto enviada ainda, ou removida); quando há foto,
 * aponta para {@code GET /elders/{elderId}/photo} (AC-002.6, D-30) com o mesmo padrão de
 * {@code ServletUriComponentsBuilder.fromCurrentContextPath()} já usado no {@code Location} de
 * {@code POST /elders} ({@code ElderController#locationOf}).
 */
public record ElderResponse(
        UUID id,
        String name,
        LocalDate birthDate,
        AddressResponse address,
        String allergies,
        String conditions,
        List<EmergencyContactResponse> emergencyContacts,
        String photoUrl,
        Instant updatedAt) {

    /**
     * Converte a entidade de domínio ({@link Elder}) e seus contatos (já carregados, em ordem —
     * {@code ElderRepository}/{@code EmergencyContactRepository}) para o formato do contrato.
     */
    public static ElderResponse of(Elder elder, List<EmergencyContact> contacts) {
        List<EmergencyContactResponse> contatos =
                contacts.stream().map(EmergencyContactResponse::of).toList();
        return new ElderResponse(
                elder.getId(),
                elder.getName(),
                elder.getBirthDate(),
                AddressResponse.of(elder.getAddress()),
                elder.getAllergies(),
                elder.getConditions(),
                contatos,
                photoUrlOf(elder),
                elder.getUpdatedAt());
    }

    /**
     * Monta a URL de {@code GET /elders/{elderId}/photo} (inclusive {@code context-path}) quando
     * o idoso tem uma foto gravada; {@code null} quando não tem (AC-002.6) — é o único lugar do
     * código que decide esse valor, para toda resposta {@code Elder} ficar consistente.
     */
    private static String photoUrlOf(Elder elder) {
        if (elder.getPhotoPath() == null) {
            return null;
        }
        // Só o caminho (com context-path, sem esquema/host): o contrato documenta photoUrl como
        // um path relativo ao mesmo servidor, não uma URL absoluta (diferente do Location de
        // POST /elders, que é mesmo uma URI completa por convenção HTTP).
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/elders/{elderId}/photo")
                .buildAndExpand(elder.getId())
                .getPath();
    }
}
