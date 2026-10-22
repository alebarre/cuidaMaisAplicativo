/**
 * Transversal (plan D-01, T-024): autorização por requisição — o filtro que autentica o portador
 * do JWT consultando o banco a cada chamada (D-13 rev. 2) e o interceptor que aplica as extensões
 * do contrato {@code x-required-account-state} e {@code x-required-permission}.
 */
package br.com.cuidamais.shared.security;
