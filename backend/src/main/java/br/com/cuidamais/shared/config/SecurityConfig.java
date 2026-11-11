package br.com.cuidamais.shared.config;

import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.security.JwtAuthenticationFilter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Segurança HTTP (T-020, primeira tarefa com endpoint real; estendida pela T-024).
 */
@Configuration
public class SecurityConfig {

    /**
     * Cadeia de filtros HTTP. A API é stateless (sem sessão de servidor, sem formulário): CSRF e
     * os logins padrão do Spring Security ({@code httpBasic}, {@code formLogin}) ficam
     * desligados, inclusive para não poluir o log com o aviso de senha gerada automaticamente.
     *
     * <p><strong>Decisão de desenho (T-024)</strong>: {@code anyRequest()} é liberado com {@code
     * permitAll()} <em>neste</em> nível — o Spring Security padrão, sem um {@code
     * AuthenticationEntryPoint} customizado, devolveria um 403 vazio para quem não está
     * autenticado, ANTES de qualquer controller/interceptor rodar, o que conflita com o {@code
     * Problem} 401 que o contrato exige. A autorização de verdade (sessão válida, estado da
     * conta, permissão de admin) passa a ser 100% responsabilidade de {@code
     * AccountSecurityInterceptor}, que roda depois, dentro do ciclo do Spring MVC — e cujas
     * exceções o {@code GlobalExceptionHandler} já sabe formatar como Problem Details. O {@link
     * JwtAuthenticationFilter} (T-024) só popula o {@code SecurityContextHolder} quando consegue
     * validar o token; ele é registrado antes do filtro padrão de autenticação do Spring Security
     * para que o contexto já esteja pronto quando o MVC despachar para o controller.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Filtro que autentica o portador do JWT a cada requisição, consultando {@code User}/{@code
     * Membership} no banco (D-13 rev. 2, T-024) — ver Javadoc de {@link JwtAuthenticationFilter}.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            Clock clock) {
        return new JwtAuthenticationFilter(jwtService, userRepository, membershipRepository, clock);
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
