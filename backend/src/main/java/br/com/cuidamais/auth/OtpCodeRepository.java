package br.com.cuidamais.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistência de {@link OtpCode} (T-020). Por ora só é usada para salvar o código emitido. */
public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {}
