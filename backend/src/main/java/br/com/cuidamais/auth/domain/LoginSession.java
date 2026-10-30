package br.com.cuidamais.auth.domain;

import br.com.cuidamais.auth.controller.AuthController;
import br.com.cuidamais.auth.service.LoginService;
import br.com.cuidamais.auth.util.SessionCookies;

/**
 * Sessão aberta por {@link LoginService#login} quando a conta está {@code ATIVO} (T-023, AC-008.1,
 * D-11): junta o JWT de acesso (texto já assinado, pronto para o corpo da resposta) e o refresh
 * token recém-emitido (entidade + texto puro) que {@link AuthController} usa para montar o cookie
 * {@code refresh_token} ({@link SessionCookies}) — mesmo formato de {@link VerifiedEmailSession}
 * (T-022), em um record próprio porque pertence a um fluxo diferente (login, não confirmação de
 * e-mail).
 *
 * @param accessToken JWT de 15 min (D-11), já serializado
 * @param refreshToken refresh token opaco recém-emitido (D-11), entidade + texto puro
 */
public record LoginSession(String accessToken, IssuedRefreshToken refreshToken) {
}
