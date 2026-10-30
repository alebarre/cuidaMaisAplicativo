package br.com.cuidamais.auth.dto;

import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.auth.util.SessionCookies;

/**
 * Corpo de sucesso de rotas que abrem/renovam sessão (T-022), espelhando o schema {@code
 * AccessToken} do contrato: o JWT de acesso (D-11), o tipo fixo {@code "Bearer"} e a validade em
 * segundos ({@link JwtService#ACCESS_TOKEN_TTL}, 900 s). O refresh token nunca aparece aqui — ele
 * só viaja pelo cookie {@code Set-Cookie} ({@link SessionCookies}, D-12), nunca no corpo.
 */
public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {

    /** Monta a resposta com o {@code tokenType} fixo do contrato e o TTL real de {@link JwtService}. */
    public static AccessTokenResponse of(String accessToken) {
        return new AccessTokenResponse(accessToken, "Bearer", JwtService.ACCESS_TOKEN_TTL.getSeconds());
    }
}
