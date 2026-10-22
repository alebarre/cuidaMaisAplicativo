package br.com.cuidamais.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara, num método (ou numa classe, valendo para todos os métodos dela), a exigência de
 * estado da conta de uma rota autenticada — espelha a extensão {@code x-required-account-state}
 * do contrato (T-024).
 *
 * <p>Posta no método tem prioridade sobre a classe. Ausente em ambos, {@code
 * AccountSecurityInterceptor} aplica o padrão do contrato: exigir {@code ATIVO}
 * <strong>e</strong> vínculo ativo com um grupo.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiredAccountState {

    /** Qual das duas exigências não-padrão esta rota declara. */
    AccountStateRequirement value();
}
