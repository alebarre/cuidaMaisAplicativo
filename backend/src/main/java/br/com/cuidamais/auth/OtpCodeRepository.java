package br.com.cuidamais.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistência de {@link OtpCode} (T-020). {@link #findFirstByUserIdAndPurposeOrderByLastSentAtDesc}
 * (T-022) localiza o código ativo de uma finalidade para um usuário: como o histórico de códigos
 * (ex. reenvios, confirmações antigas) nunca é apagado, "ativo" é sempre o emitido mais
 * recentemente ({@code last_sent_at} mais alto) — o único que {@link OtpCode#verify} pode aceitar.
 */
public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /**
     * Busca o código mais recentemente emitido para o usuário e a finalidade informados. Usado
     * por {@link VerifyEmailService} para achar o código {@code CONFIRMACAO_EMAIL} pendente de
     * confirmação; devolve vazio quando o usuário nunca recebeu um código daquela finalidade.
     */
    Optional<OtpCode> findFirstByUserIdAndPurposeOrderByLastSentAtDesc(UUID userId, OtpPurpose purpose);
}
