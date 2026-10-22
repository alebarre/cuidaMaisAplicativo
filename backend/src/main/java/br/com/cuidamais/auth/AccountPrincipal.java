package br.com.cuidamais.auth;

import java.util.UUID;

/**
 * Identidade autenticada da requisição atual (T-024, D-13 rev. 2): é o {@code
 * Authentication#getPrincipal()} que o {@code JwtAuthenticationFilter} coloca no {@code
 * SecurityContextHolder} depois de validar o JWT e consultar o banco "agora" — por isso
 * {@link #state()} nunca vem do token, sempre de uma leitura fresca de {@code User}/{@code
 * Membership} feita na própria requisição (AC-009.1, AC-009.2).
 *
 * <p>Resolvível em qualquer controller via {@code @AuthenticationPrincipal AccountPrincipal
 * principal}, exatamente como os dois controllers de teste de {@code RequestAuthorizationTest}
 * fazem.
 *
 * @param userId identidade da conta ({@code sub} do JWT, D-13: o único dado que o token carrega)
 * @param state estado da conta (status, grupo, papel, administração) lido do banco nesta
 *     requisição, via {@link AccountState#of(User, br.com.cuidamais.membership.Membership)}
 */
public record AccountPrincipal(UUID userId, AccountState state) {
}
