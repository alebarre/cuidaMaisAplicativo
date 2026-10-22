package br.com.cuidamais.shared.config;

import br.com.cuidamais.shared.security.AccountSecurityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra {@link AccountSecurityInterceptor} no ciclo do Spring MVC (T-024): é esta classe que
 * faz o interceptor de fato ser chamado em todo {@code preHandle} de toda rota, depois do
 * roteamento, antes do método do controller.
 */
@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {

    private final AccountSecurityInterceptor accountSecurityInterceptor;

    public WebMvcSecurityConfig(AccountSecurityInterceptor accountSecurityInterceptor) {
        this.accountSecurityInterceptor = accountSecurityInterceptor;
    }

    /** Aplica o interceptor a todas as rotas; ele mesmo decide, por anotação, o que exigir. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountSecurityInterceptor);
    }
}
