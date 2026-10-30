package br.com.cuidamais.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emite e valida o access token JWT de curta duração (T-021, D-11): autentica o portador por 15
 * minutos sem precisar consultar o banco a cada requisição. Por desenho (D-13 rev. 2) o token
 * carrega <strong>só</strong> a identidade do usuário ({@code sub}) — nunca papel, status ou
 * flag de admin — porque esses dados mudam no banco (ex. remoção de um membro) e, se estivessem
 * no token, a mudança só valeria quando ele expirasse; papel e status são sempre lidos do banco a
 * cada requisição pelo filtro de segurança.
 *
 * <p>Assinatura HS256 (HMAC) com um segredo simétrico de pelo menos 32 bytes (256 bits), que em
 * produção vem de {@code app.jwt.secret}/{@code JWT_SECRET} (nunca comitado). O {@link Clock} é
 * sempre recebido por parâmetro (D-10): tanto para gravar {@code iat}/{@code exp} em
 * {@link #issue} quanto para decidir "agora" na validação em {@link #parseSubject}, de modo que
 * os testes controlem o tempo sem esperar 15 minutos de verdade. A biblioteca (JJWT) por padrão
 * já não dá nenhuma tolerância de relógio na expiração; aqui isso é reforçado ao não usar o
 * relógio de sistema da biblioteca, e sim o {@link Clock} informado, tornando qualquer tolerância
 * impossível de ocorrer por acidente.
 */
@Service
public class JwtService {

    /** Validade do access token (D-11); é também o {@code expiresIn} de {@code AccessToken} no contrato (900 s). */
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private final SecretKey key;

    /**
     * @param secret segredo HMAC (D-11); em produção injetado de {@code app.jwt.secret}
     *     ({@code JWT_SECRET}), precisa ter pelo menos 32 bytes para HS256
     */
    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Emite um JWT assinado com exatamente três claims (D-13): {@code sub} (o {@code userId}),
     * {@code iat} (agora) e {@code exp} (agora + 15 minutos). Nenhum outro dado — de papel,
     * status ou qualquer outra natureza — é incluído.
     */
    public String issue(UUID userId, Clock clock) {
        Instant agora = clock.instant();
        Instant expira = agora.plus(ACCESS_TOKEN_TTL);
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(agora))
                .expiration(Date.from(expira))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Valida a assinatura e o prazo do token e devolve o {@code userId} do claim {@code sub}.
     * Usa o {@link Clock} informado (não o relógio de sistema) como "agora" da validação, para
     * que {@code exp} seja comparado sem nenhuma tolerância além do próprio prazo de 15 minutos.
     * Qualquer motivo de recusa — expirado, assinatura adulterada ou de outro segredo, ou texto
     * que não é um JWT — é relatado com a mesma exceção, para que quem chama (o filtro de
     * segurança) trate tudo igual, como "não autenticado", sem expor detalhes do motivo.
     *
     * @throws IllegalArgumentException se o token for inválido por qualquer motivo
     */
    public UUID parseSubject(String token, Clock clock) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // JJWT só recusa como expirado quando "agora" é estritamente POSTERIOR a exp; D-11
            // exige exp exclusivo (o próprio instante de exp já não vale mais), então o limite é
            // verificado de novo aqui, explicitamente, sem depender da tolerância da biblioteca.
            if (!clock.instant().isBefore(claims.getExpiration().toInstant())) {
                throw new IllegalArgumentException("Access token expirado (D-11)");
            }
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Access token inválido ou expirado", e);
        }
    }
}
