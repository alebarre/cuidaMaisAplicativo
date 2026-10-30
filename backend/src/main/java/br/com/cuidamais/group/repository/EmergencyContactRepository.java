package br.com.cuidamais.group.repository;

import br.com.cuidamais.group.domain.EmergencyContact;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link EmergencyContact} (T-030). {@link #findByElderIdOrderByPosition}
 * devolve a lista de um idoso na mesma ordem em que foi cadastrada/editada (AC-002.5); {@link
 * #deleteByElderId} apaga a lista inteira de uma vez — usado por {@code ElderService.updateElder}
 * para substituir todos os contatos de um idoso numa edição (AC-002.3, AC-002.9: sem histórico,
 * sem acréscimo, a lista nova sempre recomeça do zero).
 */
public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, UUID> {

    /** Contatos do idoso, na ordem de cadastro/edição (AC-002.5). */
    List<EmergencyContact> findByElderIdOrderByPosition(UUID elderId);

    /** Remove todos os contatos do idoso, de uma vez (substituição completa no {@code PUT}). */
    void deleteByElderId(UUID elderId);
}
