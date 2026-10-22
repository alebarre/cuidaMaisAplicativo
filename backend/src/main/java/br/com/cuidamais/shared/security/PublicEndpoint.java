package br.com.cuidamais.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma rota (método) ou todas as rotas de um controller (classe) que o contrato declara com
 * {@code security: []} — nenhuma exigência de sessão, de estado ou de permissão (T-024).
 *
 * <p>{@code AccountSecurityInterceptor} deixa passar qualquer requisição para um método anotado
 * (ou cujo controller esteja anotado) sem nem consultar o {@code SecurityContextHolder}: mesmo um
 * header {@code Authorization} presente, mas inválido, não bloqueia a rota — ela simplesmente não
 * exige sessão (ex. {@code /auth/signup}, {@code /auth/verify-email}, {@code /auth/login}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {
}
