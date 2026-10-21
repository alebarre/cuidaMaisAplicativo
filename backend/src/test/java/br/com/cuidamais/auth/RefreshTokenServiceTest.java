package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-021 "Pronto quando" (parte persistida): rotação gravada no banco e <strong>revogação da
 * família inteira no reuso</strong> (D-11 rev. 3, coluna {@code refresh_tokens.family_id} da
 * migração V2; contrato {@code /auth/refresh}: "reapresentar um refresh token já usado revoga a
 * família inteira de tokens"). AC-008.3. Também prova D-04 (só o hash do token vai para o banco)
 * e que a família não é o usuário (outro aparelho do mesmo usuário sobrevive à revogação).
 *
 * <p>Teste de serviço com banco real (PostgreSQL via {@link IntegrationTest}), sem HTTP: as rotas
 * são das tarefas T-022/T-023/T-025. O efeito é conferido por SQL puro ({@link JdbcTemplate}). A
 * classe de teste <strong>não</strong> é {@code @Transactional}: cada chamada ao serviço comete
 * (ou não) a sua própria transação, como numa requisição real.
 *
 * <p><strong>API esperada</strong> (pacote {@code br.com.cuidamais.auth}):
 *
 * <ul>
 *   <li>{@code interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>}.
 *   <li>{@code @Service RefreshTokenService(RefreshTokenRepository, Clock, ...)} — usa o {@code
 *       Clock} do contexto (D-10; nos testes, o {@code MutableClock}).
 *   <li>{@code record IssuedRefreshToken(RefreshToken token, String plainToken)}: {@code
 *       plainToken} é o valor opaco que vai no cookie; o banco guarda só {@code token_hash}, que
 *       precisa permitir localizar o token a partir do texto apresentado (hash determinístico).
 *   <li>{@code IssuedRefreshToken issue(UUID userId)}: login/confirmação de e-mail — família nova.
 *   <li>{@code IssuedRefreshToken rotate(String presentedPlainToken)}: localiza pelo hash e aplica
 *       {@code RefreshToken.rotate}, gravando o antigo revogado e o novo. Lança {@link
 *       IllegalStateException} (ou subclasse) para token desconhecido, expirado ou revogado. Se o
 *       token apresentado <strong>já estava revogado</strong> (reuso), antes de lançar revoga
 *       <strong>todas</strong> as linhas com o mesmo {@code family_id} — e essa revogação
 *       <strong>precisa ficar gravada</strong> mesmo com a exceção (não pode ser desfeita por
 *       rollback da transação).
 * </ul>
 *
 * <p>O banco não é limpo entre testes: cada teste cria o seu próprio usuário e consulta só as
 * famílias que criou.
 */
class RefreshTokenServiceTest extends IntegrationTest {

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void criarUsuario() {
        // refresh_tokens.user_id é FK para users: cada teste tem a sua própria conta.
        userId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO users (id, name, email, phone, address_zip_code, address_street, address_number,
                                   address_district, address_city, address_state, password_hash, email_verified_at)
                VALUES (?, 'Pessoa Sessão', ?, '21900000000', '01001000', 'Praça da Sé', '1',
                        'Sé', 'São Paulo', 'SP', 'hash-senha', now())
                """,
                userId,
                "sessao-" + userId + "@teste.com");
    }

    private List<Map<String, Object>> linhasDaFamilia(UUID familyId) {
        return jdbc.queryForList(
                "SELECT id, token_hash, revoked_at, absolute_expires_at FROM refresh_tokens WHERE family_id = ?",
                familyId);
    }

    /** {@code revoked_at} da linha, lido direto do banco ({@code null} enquanto ativa). */
    private Timestamp revokedAt(UUID id) {
        return jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE id = ?", Timestamp.class, id);
    }

    @Test
    @DisplayName("D-04 o banco guarda só o hash do refresh token, nunca o texto do cookie")
    void d_04_banco_guarda_so_o_hash_do_refresh_token() {
        // Um vazamento do banco não pode entregar sessões prontas: o valor do cookie não aparece
        // em nenhuma coluna da linha gravada.
        IssuedRefreshToken emitido = service.issue(userId);

        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT user_id, family_id, token_hash, revoked_at FROM refresh_tokens WHERE id = ?",
                emitido.token().getId());

        assertThat(emitido.plainToken()).isNotBlank();
        assertThat(linha.get("token_hash")).isNotEqualTo(emitido.plainToken());
        assertThat(linha.values()).doesNotContain(emitido.plainToken());
        assertThat(linha.get("user_id")).isEqualTo(userId);
        assertThat(linha.get("family_id")).isEqualTo(emitido.token().getId());
        assertThat(linha.get("revoked_at")).isNull();
    }

    @Test
    @DisplayName("AC-008.3 renovar pelo serviço grava o token antigo revogado e o novo na mesma família")
    void ac_008_3_renovacao_persistida_na_mesma_familia() {
        // A rotação persistida: o cookie apresentado vira inútil e o novo valor é diferente, com a
        // mesma família e o mesmo teto absoluto gravados no banco.
        IssuedRefreshToken t1 = service.issue(userId);
        clock.advance(Duration.ofMinutes(10));

        IssuedRefreshToken t2 = service.rotate(t1.plainToken());

        assertThat(t2.plainToken()).isNotEqualTo(t1.plainToken());
        UUID familia = t1.token().getId();
        assertThat(t2.token().getFamilyId()).isEqualTo(familia);
        List<Map<String, Object>> linhas = linhasDaFamilia(familia);
        assertThat(linhas).hasSize(2);
        Map<String, Object> antiga = jdbc.queryForMap(
                "SELECT revoked_at, absolute_expires_at FROM refresh_tokens WHERE id = ?", t1.token().getId());
        Map<String, Object> nova = jdbc.queryForMap(
                "SELECT revoked_at, absolute_expires_at FROM refresh_tokens WHERE id = ?", t2.token().getId());
        assertThat(antiga.get("revoked_at")).isNotNull();
        assertThat(nova.get("revoked_at")).isNull();
        assertThat(((Timestamp) nova.get("absolute_expires_at")).toInstant())
                .isEqualTo(((Timestamp) antiga.get("absolute_expires_at")).toInstant());
    }

    @Test
    @DisplayName("D-11 reuso revoga a família inteira")
    void d_11_reuso_revoga_a_familia_inteira() {
        // Cenário de token roubado: token1 -> token2 -> token3 (família A). Alguém reapresenta o
        // token2, que já foi usado. O serviço não sabe quem é o legítimo, então derruba a família
        // inteira — inclusive o token3, que estava ativo — e as duas partes precisam entrar de
        // novo com e-mail e senha.

        // Etapa 1: login e duas renovações legítimas.
        IssuedRefreshToken t1 = service.issue(userId);
        UUID familiaA = t1.token().getFamilyId();
        clock.advance(Duration.ofMinutes(10));
        IssuedRefreshToken t2 = service.rotate(t1.plainToken());
        clock.advance(Duration.ofMinutes(10));
        IssuedRefreshToken t3 = service.rotate(t2.plainToken());
        assertThat(t3.token().getFamilyId()).isEqualTo(familiaA);
        assertThat(revokedAt(t3.token().getId()))
                .as("token3 ativo antes do reuso")
                .isNull();

        // Etapa 2: reuso do token2 é recusado.
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> service.rotate(t2.plainToken())).isInstanceOf(IllegalStateException.class);

        // Etapa 3: todas as linhas da família A ficaram revogadas no banco (gravado apesar da
        // exceção), inclusive o token3.
        List<Map<String, Object>> linhas = linhasDaFamilia(familiaA);
        assertThat(linhas).hasSize(3);
        assertThat(linhas).allSatisfy(linha -> assertThat(linha.get("revoked_at"))
                .as("revoked_at da linha %s", linha.get("id"))
                .isNotNull());
        assertThat(repository.findById(t3.token().getId()))
                .hasValueSatisfying(token -> assertThat(token.isRevoked()).isTrue());

        // Etapa 4: o token3, revogado por cascata, também não renova mais a sessão.
        assertThatThrownBy(() -> service.rotate(t3.plainToken())).isInstanceOf(IllegalStateException.class);
        assertThat(linhasDaFamilia(familiaA)).as("nenhum token novo foi criado na família").hasSize(3);
    }

    @Test
    @DisplayName("D-11 reuso numa família não revoga a sessão de outro aparelho do mesmo usuário")
    void d_11_reuso_nao_afeta_outra_familia_do_mesmo_usuario() {
        // family_id nunca é o user_id: o reuso detectado no celular derruba só a família do
        // celular; o notebook do mesmo usuário continua conseguindo renovar.
        IssuedRefreshToken celular1 = service.issue(userId);
        IssuedRefreshToken notebook = service.issue(userId);
        clock.advance(Duration.ofMinutes(5));
        service.rotate(celular1.plainToken());

        assertThatThrownBy(() -> service.rotate(celular1.plainToken())).isInstanceOf(IllegalStateException.class);

        assertThat(revokedAt(notebook.token().getId())).isNull();
        IssuedRefreshToken notebookRenovado = service.rotate(notebook.plainToken());
        assertThat(notebookRenovado.token().getFamilyId()).isEqualTo(notebook.token().getFamilyId());
    }

    @Test
    @DisplayName("D-11 token desconhecido é recusado sem gravar nada")
    void d_11_token_desconhecido_e_recusado() {
        // Um cookie inventado não corresponde a nenhum hash gravado: é recusado e nenhuma linha
        // nova aparece para o usuário.
        service.issue(userId);
        Integer antes = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);

        assertThatThrownBy(() -> service.rotate("valor-que-nunca-foi-emitido-" + UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(antes);
    }
}
