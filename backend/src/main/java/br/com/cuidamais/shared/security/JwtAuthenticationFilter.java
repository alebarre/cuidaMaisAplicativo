package br.com.cuidamais.shared.security;

import br.com.cuidamais.auth.domain.AccountPrincipal;
import br.com.cuidamais.auth.domain.AccountState;
import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.repository.MembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Camada 1 da autorização por requisição (T-024, D-13 rev. 2): autentica o portador do access
 * token lendo o header {@code Authorization: Bearer <token>} e, se válido, consulta {@code User}
 * e {@code Membership} <strong>no banco, nesta requisição</strong> — nunca confia em nada que não
 * seja o {@code sub} do próprio JWT (D-13: o token não carrega papel, status nem {@code
 * is_admin}). Essa consulta a cada chamada é o que faz uma remoção (AC-009.2) ou uma troca de
 * papel (AC-009.1) valerem imediatamente, mesmo com o mesmo token ainda dentro dos 15 minutos de
 * validade.
 *
 * <p>Esta camada <strong>nunca decide se a ausência de autenticação é um problema</strong> — ela
 * só tenta autenticar e, quando não consegue por qualquer motivo (sem header, token mal formado,
 * assinatura de outro segredo, token adulterado, expirado, ou {@code sub} de uma conta que não
 * existe mais), segue a cadeia de filtros sem preencher o {@code SecurityContextHolder}. Quem
 * decide se isso é um 401 é a camada 2, {@link AccountSecurityInterceptor}, que roda depois,
 * dentro do ciclo do Spring MVC (e cujas exceções o {@code GlobalExceptionHandler} formata como
 * Problem Details) — por isso o {@code SecurityConfig} libera {@code anyRequest()} no nível do
 * Spring Security: a autorização de verdade é 100% responsabilidade da camada 2.
 *
 * <p>Registrado antes do filtro padrão de autenticação do Spring Security
 * ({@code UsernamePasswordAuthenticationFilter}), para que o contexto de segurança já esteja
 * populado quando o Spring MVC despachar para o controller.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final Clock clock;

    /**
     * @param jwtService valida a assinatura e o prazo do token (T-021)
     * @param userRepository busca a conta do {@code sub}, agora, no banco
     * @param membershipRepository busca o vínculo com grupo da conta, agora, no banco (D-02: zero
     *     ou um por conta)
     * @param clock relógio injetado (D-10), usado na validação do prazo do token em vez do
     *     relógio de sistema, para os testes de expiração controlarem o tempo
     */
    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            Clock clock) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.clock = clock;
    }

    /**
     * Tenta autenticar a requisição e sempre segue a cadeia de filtros — nunca interrompe nem
     * rejeita nada aqui (ver Javadoc da classe).
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1. extrai o token do header Authorization; sem o esquema Bearer, segue sem autenticar.
        String token = bearerTokenOf(request);
        if (token != null) {
            // 2. valida assinatura e prazo (D-10, D-11); token inválido/expirado é tratado como
            //    "sem token" (nenhuma exceção escapa daqui).
            UUID userId = parseSubjectOrNull(token);
            if (userId != null) {
                // 3. busca a conta e o vínculo atuais no banco (D-13): é aqui que uma remoção ou
                //    troca de papel recentes já valem, mesmo com o mesmo token ainda válido.
                Optional<User> user = userRepository.findById(userId);
                if (user.isPresent()) {
                    Membership membership = membershipRepository.findByUserId(userId).orElse(null);
                    AccountState state = AccountState.of(user.get(), membership);
                    // 4. popula o contexto de segurança com o principal da aplicação (D-13).
                    authenticate(userId, state);
                }
                // sub sem conta no banco (ex. apagada depois de emitido o token): segue sem autenticar.
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Extrai o token do header {@code Authorization}, ou {@code null} sem o esquema Bearer. */
    private static String bearerTokenOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    /**
     * Valida o token e devolve o {@code userId}, ou {@code null} se ele for inválido ou expirado
     * por qualquer motivo — o único tratamento possível para quem chama é "sem autenticação".
     */
    private UUID parseSubjectOrNull(String token) {
        try {
            return jwtService.parseSubject(token, clock);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Popula o {@code SecurityContextHolder} com o {@link AccountPrincipal} resolvido: a flag
     * {@code is_admin} do estado se torna a authority {@code ROLE_ADMIN}, usada só como atalho de
     * leitura — a exigência de {@code x-required-permission: admin} de verdade é checada por
     * {@link AccountSecurityInterceptor}, não por esta authority.
     */
    private void authenticate(UUID userId, AccountState state) {
        AccountPrincipal principal = new AccountPrincipal(userId, state);
        List<GrantedAuthority> authorities =
                state.isAdmin() ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of();
        var authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
