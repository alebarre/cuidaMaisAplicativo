package br.com.cuidamais.group;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link Elder} (T-030). {@link #findByGroupId} devolve no máximo um registro
 * porque {@code elders.group_id} é {@code UNIQUE} (D-02/AC-002.1: um idoso por grupo) — é assim
 * que {@code ElderService} resolve "o idoso do chamador" a partir da {@code membership} dele.
 */
public interface ElderRepository extends JpaRepository<Elder, UUID> {

    /**
     * Busca o idoso do grupo informado, se já cadastrado (D-02). {@code Optional.empty()} nunca
     * deveria ocorrer para um grupo que já existe (todo grupo nasce com seu idoso, D-05), mas o
     * tipo de retorno deixa essa invariante explícita para quem chama.
     */
    Optional<Elder> findByGroupId(UUID groupId);
}
