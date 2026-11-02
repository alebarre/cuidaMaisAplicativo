package br.com.cuidamais.membership.dto;

import br.com.cuidamais.membership.domain.Invitation;
import br.com.cuidamais.membership.domain.InvitationOrigin;
import br.com.cuidamais.membership.domain.InvitationStatus;
import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Convite como o Responsável o enxerga (schema {@code Invitation} do contrato). O status é
 * <strong>derivado</strong> do relógio: {@code ENVIADO} com prazo esgotado sai como {@code
 * VENCIDO} (AC-003.2, D-09). Tentativas, bloqueio e reenvios automáticos vêm do contador por
 * e-mail (D-39, AC-003.8). O código nunca aparece aqui (D-04).
 */
public record InvitationResponse(
        UUID id,
        String email,
        InvitationStatus status,
        InvitationOrigin origin,
        Instant expiresAt,
        Instant createdAt,
        Instant usedAt,
        Instant cancelledAt,
        int failedAttempts,
        Instant blockedUntil,
        int autoResends) {

    /**
     * Monta a resposta a partir do convite e do contador do e-mail (que pode não existir ainda).
     *
     * @param attempts contador do e-mail, ou {@code null} quando nunca houve tentativa/convite
     */
    public static InvitationResponse of(Invitation invitation, InviteCodeAttempts attempts, Clock clock) {
        // 1. status efetivo: ENVIADO já vencido pelo relógio é apresentado como VENCIDO.
        InvitationStatus status = invitation.getStatus();
        if (status == InvitationStatus.ENVIADO && !clock.instant().isBefore(invitation.getExpiresAt())) {
            status = InvitationStatus.VENCIDO;
        }
        // 2. contadores lidos com o Clock (fim do bloqueio verificado na leitura, D-39).
        int failed = attempts == null ? 0 : attempts.failedAttempts(clock);
        Instant blockedUntil = attempts == null ? null : attempts.blockedUntil(clock);
        int autoResends = attempts == null ? 0 : attempts.getAutoResends();
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                status,
                invitation.getOrigin(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt(),
                invitation.getUsedAt(),
                invitation.getCancelledAt(),
                failed,
                blockedUntil,
                autoResends);
    }
}
