package br.com.cuidamais.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método (ou uma classe, valendo para todos os métodos dela) que o contrato declara com
 * {@code x-required-permission: admin} (T-024, AC-015.2, D-32): só quem tem a flag de
 * administração do grupo ({@code AccountState#isAdmin()}) pode chamar a rota.
 *
 * <p>{@code AccountSecurityInterceptor} verifica esta anotação <strong>depois</strong> da
 * exigência de estado (a exigência padrão, {@code ATIVO} e com grupo, continua valendo mesmo numa
 * rota de admin) e, se a conta não for administradora, lança {@code NotAllowedException}
 * (já existente, T-007) — 403 {@code code: NOT_ALLOWED}, sem revelar o papel nem o estado da
 * conta no corpo da resposta (P5).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresAdminPermission {
}
