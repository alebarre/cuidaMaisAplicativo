package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-021 — access token JWT de 15 minutos (D-11) que só identifica o usuário: não carrega papel
 * nem status (D-13 rev. 2), e expõe a validade em segundos usada em {@code AccessToken.expiresIn}
 * do contrato (900). AC-008.3 depende deste token curto combinado ao refresh token.
 *
 * <p>Teste puro de domínio: sem Spring, sem banco; tempo por {@link MutableClock} (D-10). A
 * biblioteca JWT fica a critério do implementador — o teste só valida o comportamento observável
 * e decodifica o payload manualmente (Base64URL da parte do meio).
 *
 * <p><strong>API esperada</strong> — {@code br.com.cuidamais.auth.JwtService}:
 *
 * <ul>
 *   <li>{@code JwtService(String secret)}: segredo HMAC (no mínimo 32 bytes, HS256). Em produção
 *       virá de configuração; aqui é passado direto.
 *   <li>{@code static final Duration ACCESS_TOKEN_TTL = 15 min}.
 *   <li>{@code String issue(UUID userId, Clock clock)}: JWT assinado com exatamente os claims
 *       {@code sub} (= {@code userId.toString()}), {@code iat} e {@code exp} (= {@code iat} + 900
 *       s), em segundos epoch.
 *   <li>{@code UUID parseSubject(String token, Clock clock)}: devolve o {@code userId} se a
 *       assinatura confere e {@code agora < exp}; senão lança {@link IllegalArgumentException} (ou
 *       subclasse) — inclusive token expirado, adulterado ou assinado com outro segredo. Sem
 *       tolerância de relógio ("clock skew") além do prazo de 15 minutos.
 * </ul>
 */
class JwtServiceTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration VALIDADE = Duration.ofMinutes(15);
    private static final String SEGREDO = "segredo-de-teste-do-cuida-mais-com-mais-de-32-bytes";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private final ObjectMapper json = new ObjectMapper();

    private MutableClock clock;
    private JwtService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        service = new JwtService(SEGREDO);
    }

    private JsonNode payload(String token) throws Exception {
        String[] partes = token.split("\\.");
        assertThat(partes).as("JWT tem três partes separadas por ponto").hasSize(3);
        byte[] bytes = Base64.getUrlDecoder().decode(partes[1]);
        return json.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("D-11 a validade do access token é 15 minutos (expiresIn = 900 no contrato)")
    void d_11_validade_do_access_token_e_15_minutos() {
        // AccessToken.expiresIn do openapi.yaml é a validade em segundos (exemplo 900): a mesma
        // constante deve alimentar a resposta das rotas de sessão das próximas tarefas.
        assertThat(JwtService.ACCESS_TOKEN_TTL).isEqualTo(VALIDADE);
        assertThat(JwtService.ACCESS_TOKEN_TTL.toSeconds()).isEqualTo(900L);
    }

    @Test
    @DisplayName("D-11 um segundo antes dos 15 minutos o token ainda devolve o usuário")
    void d_11_token_valido_um_segundo_antes_de_15_minutos() {
        // Controle positivo: dentro da validade, o token identifica o usuário que o recebeu.
        String token = service.issue(USER_ID, clock);
        clock.advance(VALIDADE.minusSeconds(1));

        assertThat(service.parseSubject(token, clock)).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("D-11 aos 15 minutos exatos o token já expirou")
    void d_11_token_expira_aos_15_minutos_exatos() {
        // exp é exclusivo: no instante exato de exp o token não vale mais. Uma tolerância de
        // relógio (comum nas bibliotecas JWT) estenderia a sessão além do prazo de D-11.
        String token = service.issue(USER_ID, clock);
        clock.advance(VALIDADE);

        assertThatThrownBy(() -> service.parseSubject(token, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-11 depois dos 15 minutos o token continua expirado")
    void d_11_token_expirado_depois_de_15_minutos() {
        // Bem depois do prazo (1 h) o token continua recusado: só o refresh token renova a sessão.
        String token = service.issue(USER_ID, clock);
        clock.advance(Duration.ofHours(1));

        assertThatThrownBy(() -> service.parseSubject(token, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-11 token assinado com outro segredo é recusado")
    void d_11_token_assinado_com_outro_segredo_e_recusado() {
        // A assinatura é o que impede alguém de forjar um token com o userId de outra pessoa.
        JwtService outro = new JwtService("outro-segredo-qualquer-tambem-com-mais-de-32-bytes");
        String forjado = outro.issue(USER_ID, clock);

        assertThatThrownBy(() -> service.parseSubject(forjado, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-11 token com payload adulterado é recusado")
    void d_11_token_com_payload_adulterado_e_recusado() throws Exception {
        // Troca o sub do payload mantendo cabeçalho e assinatura originais: a assinatura deixa de
        // conferir e o token não pode ser aceito como sendo de outro usuário.
        String token = service.issue(USER_ID, clock);
        String[] partes = token.split("\\.");
        UUID outroUsuario = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
        String payloadAdulterado = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace(USER_ID.toString(), outroUsuario.toString());
        String adulterado = partes[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payloadAdulterado.getBytes(StandardCharsets.UTF_8))
                + "." + partes[2];

        assertThatThrownBy(() -> service.parseSubject(adulterado, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-11 texto que não é JWT é recusado com a mesma exceção")
    void d_11_texto_que_nao_e_jwt_e_recusado() {
        // Lixo no cabeçalho Authorization não pode estourar com outra exceção (ex. de parsing da
        // biblioteca), para que o filtro de segurança trate tudo como "não autenticado".
        assertThatThrownBy(() -> service.parseSubject("isto-nao-e-um-jwt", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-13 o payload do JWT só tem sub, iat e exp — nenhum papel ou status")
    void d_13_payload_do_jwt_nao_carrega_papel_nem_status() throws Exception {
        // D-13 rev. 2: papel e status são lidos do banco a cada requisição; se estivessem no
        // token, uma remoção/alteração de papel só valeria quando o token expirasse.
        String token = service.issue(USER_ID, clock);

        JsonNode payload = payload(token);
        List<String> claims = new ArrayList<>();
        payload.fieldNames().forEachRemaining(claims::add);

        assertThat(claims).containsExactlyInAnyOrder("sub", "iat", "exp");
        assertThat(claims).doesNotContain("role", "roles", "status", "isAdmin", "admin", "authorities", "scope");
    }

    @Test
    @DisplayName("D-11 sub é o id do usuário e exp - iat é 900 segundos")
    void d_11_sub_e_o_usuario_e_exp_menos_iat_e_900_segundos() throws Exception {
        // O conteúdo dos claims é o contrato com o filtro de segurança e com o frontend: sub
        // identifica o usuário, iat é o instante da emissão e exp vence 15 minutos depois.
        String token = service.issue(USER_ID, clock);

        JsonNode payload = payload(token);

        assertThat(payload.get("sub").asText()).isEqualTo(USER_ID.toString());
        assertThat(payload.get("iat").asLong()).isEqualTo(T0.getEpochSecond());
        assertThat(payload.get("exp").asLong()).isEqualTo(T0.plus(VALIDADE).getEpochSecond());
    }
}
