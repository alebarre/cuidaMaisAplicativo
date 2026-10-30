package br.com.cuidamais.auth.domain;

/**
 * Finalidade de um código OTP (coluna {@code otp_codes.purpose}, V1__foundation.sql): confirmação
 * de e-mail no cadastro (AC-005) ou redefinição de senha (D-14, tarefa futura).
 */
public enum OtpPurpose {
    CONFIRMACAO_EMAIL,
    REDEFINICAO_SENHA
}
