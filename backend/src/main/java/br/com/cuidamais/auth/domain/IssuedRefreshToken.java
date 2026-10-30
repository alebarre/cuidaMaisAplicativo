package br.com.cuidamais.auth.domain;

import br.com.cuidamais.auth.util.SessionCookies;

/**
 * Resultado de emitir ou rotacionar um refresh token (T-021, D-04): junta a entidade persistida
 * ({@link RefreshToken}, que só guarda o hash) com o {@code plainToken} em texto puro, que é o
 * valor opaco que de fato vai no cookie {@code refresh_token} (ver {@link SessionCookies}).
 *
 * <p>{@code plainToken} existe só em memória, pelo tempo de vida desta resposta: nunca é
 * persistido em nenhuma tabela (D-04) — quem precisa dele é só o navegador do usuário.
 *
 * @param token entidade persistida, com {@code tokenHash} e os prazos da sessão
 * @param plainToken valor opaco em texto puro, nunca gravado no banco
 */
public record IssuedRefreshToken(RefreshToken token, String plainToken) {}
