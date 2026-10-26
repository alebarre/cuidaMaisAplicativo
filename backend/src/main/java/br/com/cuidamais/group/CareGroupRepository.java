package br.com.cuidamais.group;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link CareGroup} (T-030). Sem métodos extras: todo acesso a um grupo específico
 * nesta tarefa parte do {@code groupId} já resolvido pela {@code membership} do chamador (D-02),
 * nunca de uma busca direta por {@code care_groups}.
 */
public interface CareGroupRepository extends JpaRepository<CareGroup, UUID> {
}
