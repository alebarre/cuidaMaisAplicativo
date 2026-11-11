package br.com.cuidamais.auth.domain;

/**
 * Resultado da emissão de um código OTP (T-013): {@code otp} é a entidade persistível, que guarda
 * apenas o hash (D-04); {@code plainCode} é o código em texto puro, que existe só em memória para
 * ir no e-mail enviado à pessoa e nunca é persistido.
 */
public record IssuedOtp(OtpCode otp, String plainCode) {}
