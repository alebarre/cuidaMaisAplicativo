package br.com.cuidamais.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Segurança HTTP (T-020, primeira tarefa com endpoint real).
 */
@Configuration
public class SecurityConfig {

    /**
     * Cadeia de filtros HTTP. A API é stateless (sem sessão de servidor, sem formulário): CSRF e
     * os logins padrão do Spring Security ({@code httpBasic}, {@code formLogin}) ficam
     * desligados, inclusive para não poluir o log com o aviso de senha gerada automaticamente.
     *
     * <p><strong>Esta cadeia libera só o necessário até a T-023</strong> ({@code /auth/signup},
     * {@code /auth/verify-email}, {@code /auth/login}, públicas) e exige autenticação em qualquer
     * outra rota. As tarefas seguintes desta fase (T-024 filtro completo que lê {@code status}/
     * {@code role}/{@code is_admin} do banco) vão estender esta classe, liberando as demais rotas
     * públicas do contrato ({@code /auth/resend-code}, {@code /auth/password-reset/*}, {@code
     * /invitations/redeem}, {@code /invitations/register}) à medida que forem implementadas
     * (D-32, D-16).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/auth/signup", "/auth/verify-email", "/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .build();
    }

    /** Mesmo mecanismo (BCrypt) usado por {@code OtpService} (D-14) para hashear a senha do usuário. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Gerenciador de usuários vazio, só para evitar que {@code UserDetailsServiceAutoConfiguration}
     * gere uma senha aleatória e a imprima no log ("Using generated security password"): esta API
     * não usa {@code UserDetailsService} (login é feito por {@code UserRepository} + senha com
     * {@link PasswordEncoder}, D-14; a sessão é por JWT, T-021), então nenhum usuário é cadastrado
     * aqui.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
