package br.com.cuidamais.notification.event;

import br.com.cuidamais.auth.domain.OtpPurpose;

/**
 * Código OTP de 6 dígitos: confirmação de e-mail (AC-001.1, AC-005) ou redefinição de senha
 * (AC-010.1).
 */
public record ConfirmationCodeEmail(String to, String code, OtpPurpose purpose) implements EmailEvent {}
