package br.com.cuidamais.auth.domain;

import br.com.cuidamais.auth.controller.AuthController;
import br.com.cuidamais.auth.service.VerifyEmailService;
import br.com.cuidamais.auth.util.SessionCookies;

/**
 * Sessão aberta por {@link VerifyEmailService#verify} assim que o código certo confirma o e-mail
 * (AC-001.2, D-11): junta o JWT de acesso (texto já assinado, pronto para o corpo da resposta) e o
 * refresh token recém-emitido (entidade + texto puro) que {@link AuthController} usa para montar
 * o cookie {@code refresh_token} ({@link SessionCookies}).
 *
 * @param accessToken JWT de 15 min (D-11), já serializado
 * @param refreshToken refresh token opaco recém-emitido (D-11), entidade + texto puro
 */
public record VerifiedEmailSession(String accessToken, IssuedRefreshToken refreshToken) {
}
