package br.com.cuidamais.membership.repository;

import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistência de {@link Invitation} (T-040). Concentra as consultas de convites sempre
 * escopadas por grupo (D-32: convite de outro grupo é indistinguível de inexistente) e as de
 * listagem que tratam o {@code VENCIDO} como estado derivado do relógio (AC-003.2, D-09): um
 * convite {@code ENVIADO} cujo prazo já passou aparece como {@code VENCIDO} mesmo que o job ainda
 * não tenha atualizado a linha.
 */
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /**
     * Busca um convite pelo id restrito ao grupo informado; vazio se o convite não existe ou é de
     * outro grupo (D-32), o que vira 404 genérico.
     */
    Optional<Invitation> findByIdAndGroupId(UUID id, UUID groupId);

    /**
     * Convites {@code ENVIADO} do grupo para o e-mail, usados para detectar o "convidar de novo =
     * reenvio" (AC-003.12, D-31). Devolve lista para tolerar qualquer duplicidade histórica.
     */
    List<Invitation> findByGroupIdAndEmailAndStatus(UUID groupId, String email, InvitationStatus status);

    /** Todos os convites do grupo, sem filtro de status (paginado e ordenado pelo chamador). */
    Page<Invitation> findByGroupId(UUID groupId, Pageable pageable);

    /** Convites do grupo com exatamente o status gravado (usado para USADO e CANCELADO). */
    Page<Invitation> findByGroupIdAndStatus(UUID groupId, InvitationStatus status, Pageable pageable);

    /** Convites {@code ENVIADO} ainda dentro do prazo ({@code agora < expiresAt}). */
    @Query("select i from Invitation i where i.groupId = :groupId and i.status = 'ENVIADO'"
            + " and i.expiresAt > :now")
    Page<Invitation> findSentActive(
            @Param("groupId") UUID groupId, @Param("now") Instant now, Pageable pageable);

    /**
     * Convites efetivamente vencidos: gravados {@code VENCIDO} ou {@code ENVIADO} com prazo já
     * esgotado ({@code agora >= expiresAt}), derivados do relógio sem depender do job.
     */
    @Query("select i from Invitation i where i.groupId = :groupId and (i.status = 'VENCIDO'"
            + " or (i.status = 'ENVIADO' and i.expiresAt <= :now))")
    Page<Invitation> findExpired(
            @Param("groupId") UUID groupId, @Param("now") Instant now, Pageable pageable);
}
