package br.com.cuidamais.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-021 "Pronto quando" (parte de domínio): renovação, expiração após 7 dias sem uso, expiração
 * no dia 30 mesmo com uso diário e recusa de um token já usado. AC-008.3; decisões D-11 (rev. 3:
 * janela deslizante de 7 dias, teto absoluto de 30 dias herdado na rotação, {@code family_id}),
 * D-04 (só o hash do token chega à entidade) e D-10 (tempo por {@link MutableClock}).
 *
 * <p>Teste puro de domínio: sem Spring, sem banco. A revogação em cascata da família inteira no
 * reuso varre várias linhas persistidas e fica em {@code RefreshTokenServiceTest} (integração).
 *
 * <p><strong>API esperada</strong> — {@code br.com.cuidamais.auth.domain.RefreshToken}, {@code @Entity}
 * sobre {@code refresh_tokens} (V1 + V2), mesmo padrão de {@code Invitation}: transições como
 * métodos, {@code Clock} por parâmetro, {@link IllegalStateException} em transição inválida.
 *
 * <ul>
 *   <li>{@code static RefreshToken issue(UUID userId, String tokenHash, Clock clock)}: família
 *       NOVA — {@code id} gerado na hora e {@code familyId == id}; {@code createdAt = agora};
 *       {@code expiresAt = agora + 7 dias}; {@code absoluteExpiresAt = agora + 30 dias};
 *       {@code revokedAt = null}.
 *   <li>{@code RefreshToken rotate(String newTokenHash, Clock clock)}: aceito só se não revogado,
 *       {@code agora < expiresAt} e {@code agora < absoluteExpiresAt}; senão lança {@link
 *       IllegalStateException} (ou subclasse) <strong>sem alterar nada</strong>. Se aceito: marca
 *       {@code this.revokedAt = agora} e devolve um token novo (outro {@code id}) com o mesmo
 *       {@code userId}, o mesmo {@code familyId}, o mesmo {@code absoluteExpiresAt} (herdado,
 *       nunca recalculado), {@code expiresAt = min(agora + 7 dias, absoluteExpiresAt)}, {@code
 *       tokenHash = newTokenHash}, {@code createdAt = agora}, {@code revokedAt = null}.
 *   <li>{@code void revoke(Clock clock)}: <strong>idempotente</strong> (decisão do autor dos
 *       testes) — a primeira chamada grava {@code revokedAt = agora}; chamadas seguintes não lançam
 *       e preservam o {@code revokedAt} original. Assim a revogação da família pode simplesmente
 *       chamar {@code revoke} em todas as linhas, inclusive nas que já tinham sido rotacionadas,
 *       sem apagar o instante em que cada uma foi usada.
 *   <li>{@code boolean isRevoked()} e getters: {@code getId}, {@code getUserId}, {@code
 *       getFamilyId}, {@code getTokenHash}, {@code getExpiresAt}, {@code getAbsoluteExpiresAt},
 *       {@code getRevokedAt}, {@code getCreatedAt}.
 * </ul>
 */
class RefreshTokenTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration JANELA = Duration.ofDays(7);
    private static final Duration TETO = Duration.ofDays(30);
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
    }

    private RefreshToken emitir() {
        return RefreshToken.issue(USER_ID, "hash-0", clock);
    }

    // ------------------------------------------------------------ emissão (início da família)

    @Test
    @DisplayName("D-11 token emitido no login inicia uma família nova (family_id = id) com 7 e 30 dias")
    void d_11_emissao_inicia_familia_nova_com_janela_de_7_e_teto_de_30_dias() {
        // O primeiro token de um login é a raiz da família (D-11 rev. 3): family_id aponta para
        // ele mesmo, nunca para o user_id. Os dois prazos partem do instante do login.
        RefreshToken token = emitir();

        assertThat(token.getId()).isNotNull();
        assertThat(token.getFamilyId()).isEqualTo(token.getId());
        assertThat(token.getFamilyId()).isNotEqualTo(USER_ID);
        assertThat(token.getUserId()).isEqualTo(USER_ID);
        assertThat(token.getTokenHash()).isEqualTo("hash-0");
        assertThat(token.getCreatedAt()).isEqualTo(T0);
        assertThat(token.getExpiresAt()).isEqualTo(T0.plus(JANELA));
        assertThat(token.getAbsoluteExpiresAt()).isEqualTo(T0.plus(TETO));
        assertThat(token.getRevokedAt()).isNull();
        assertThat(token.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("D-11 dois logins do mesmo usuário geram famílias diferentes")
    void d_11_dois_logins_do_mesmo_usuario_geram_familias_diferentes() {
        // Cada aparelho/login tem a sua família: revogar uma (reuso ou logout) não pode atingir a
        // outra — por isso family_id não pode ser derivado do usuário.
        RefreshToken celular = RefreshToken.issue(USER_ID, "hash-celular", clock);
        RefreshToken notebook = RefreshToken.issue(USER_ID, "hash-notebook", clock);

        assertThat(celular.getFamilyId()).isNotEqualTo(notebook.getFamilyId());
    }

    // ------------------------------------------------------------ renovação dentro do prazo

    @Test
    @DisplayName("AC-008.3 rotacionar dentro do prazo de 7 dias renova a sessão")
    void ac_008_3_rotacionar_dentro_do_prazo_de_7_dias() {
        // Uso do app 3 dias depois do login: a sessão é renovada. O token antigo fica revogado
        // (uso único), o novo pertence à mesma família, herda o teto de 30 dias do login e ganha
        // uma nova janela de 7 dias contada a partir de agora (sessão de até 7 dias sem uso).
        RefreshToken original = emitir();
        clock.advance(Duration.ofDays(3));
        Instant agora = clock.instant();

        RefreshToken novo = original.rotate("hash-1", clock);

        assertThat(original.getRevokedAt()).isEqualTo(agora);
        assertThat(original.isRevoked()).isTrue();
        assertThat(novo.getId()).isNotEqualTo(original.getId());
        assertThat(novo.getUserId()).isEqualTo(USER_ID);
        assertThat(novo.getFamilyId()).isEqualTo(original.getFamilyId());
        assertThat(novo.getAbsoluteExpiresAt()).isEqualTo(original.getAbsoluteExpiresAt());
        assertThat(novo.getExpiresAt()).isEqualTo(agora.plus(JANELA));
        assertThat(novo.getTokenHash()).isEqualTo("hash-1");
        assertThat(novo.getCreatedAt()).isEqualTo(agora);
        assertThat(novo.getRevokedAt()).isNull();
    }

    // ------------------------------------------------------------ 7 dias sem uso

    @Test
    @DisplayName("AC-008.3 um segundo antes de 7 dias sem uso a sessão ainda pode ser renovada")
    void ac_008_3_um_segundo_antes_de_7_dias_sem_uso_ainda_renova() {
        // Controle positivo da janela deslizante: o limite é "agora < expiresAt", então o último
        // segundo da janela ainda vale.
        RefreshToken original = emitir();
        clock.advance(JANELA.minusSeconds(1));

        RefreshToken novo = original.rotate("hash-1", clock);

        assertThat(novo.getFamilyId()).isEqualTo(original.getFamilyId());
    }

    @ParameterizedTest(name = "{0} segundos sem uso")
    @ValueSource(longs = {
        7L * 24 * 3600, // exatamente 7 dias: expiresAt é exclusivo, já expirou
        7L * 24 * 3600 + 1, // 7 dias e 1 segundo
        10L * 24 * 3600 // 10 dias: bem depois da janela, ainda antes do teto de 30
    })
    @DisplayName("AC-008.3 expiração após 7 dias sem uso")
    void ac_008_3_expiracao_apos_7_dias_sem_uso(long segundosSemUso) {
        // Nenhuma renovação no meio: a janela de 7 dias contada do login acabou, então a sessão
        // expira mesmo faltando muito para o teto de 30 dias — é preciso entrar de novo.
        RefreshToken original = emitir();
        clock.advance(Duration.ofSeconds(segundosSemUso));

        assertThatThrownBy(() -> original.rotate("hash-1", clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-008.3 tentativa de renovar um token expirado não altera o token")
    void ac_008_3_renovacao_recusada_por_expiracao_nao_altera_o_token() {
        // Uma rotação recusada não pode ter efeito colateral: o token expirado não deve aparecer
        // como "usado" (revokedAt), senão a próxima apresentação seria confundida com reuso.
        RefreshToken original = emitir();
        clock.advance(JANELA);

        assertThatThrownBy(() -> original.rotate("hash-1", clock))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.getRevokedAt()).isNull();
        assertThat(original.getExpiresAt()).isEqualTo(T0.plus(JANELA));
        assertThat(original.getTokenHash()).isEqualTo("hash-0");
    }

    // ------------------------------------------------------------ teto absoluto de 30 dias

    @Test
    @DisplayName("D-11 expiração no dia 30 mesmo com uso diário (teto herdado, nunca recalculado)")
    void d_11_expiracao_no_dia_30_mesmo_com_uso_diario() {
        // Uso diário encadeado: cada dia a sessão é renovada com o token devolvido na véspera. A
        // janela de 7 dias sozinha deixaria a sessão viver para sempre; o teto absoluto de 30 dias
        // desde o login (herdado em toda rotação) é o que encerra a sessão.
        RefreshToken primeiro = emitir();
        Instant tetoDoLogin = primeiro.getAbsoluteExpiresAt();
        RefreshToken atual = primeiro;

        // Etapa 1: dias 1 a 29 — toda renovação diária é aceita, mantém a família e o teto do
        // login, e a janela nova nunca passa do teto.
        for (int dia = 1; dia <= 29; dia++) {
            clock.advance(Duration.ofDays(1));
            RefreshToken anterior = atual;
            atual = anterior.rotate("hash-" + dia, clock);

            assertThat(anterior.isRevoked()).as("dia %d: token anterior revogado", dia).isTrue();
            assertThat(atual.getFamilyId()).as("dia %d: mesma família", dia)
                    .isEqualTo(primeiro.getFamilyId());
            assertThat(atual.getAbsoluteExpiresAt()).as("dia %d: teto herdado", dia)
                    .isEqualTo(tetoDoLogin);
            Instant janelaNormal = clock.instant().plus(JANELA);
            Instant esperado = janelaNormal.isBefore(tetoDoLogin) ? janelaNormal : tetoDoLogin;
            assertThat(atual.getExpiresAt()).as("dia %d: expiresAt = min(agora+7d, teto)", dia)
                    .isEqualTo(esperado);
        }

        // Etapa 2: dia 30 — o último token foi emitido há só 24 h (dentro da janela de 7 dias),
        // mas o teto absoluto chegou: a renovação é recusada.
        clock.advance(Duration.ofDays(1));
        assertThat(clock.instant()).isEqualTo(tetoDoLogin);
        RefreshToken ultimo = atual;
        assertThatThrownBy(() -> ultimo.rotate("hash-30", clock))
                .isInstanceOf(IllegalStateException.class);

        // Etapa 3: o teto do token mais recente é idêntico ao do primeiro token do login.
        assertThat(ultimo.getAbsoluteExpiresAt()).isEqualTo(primeiro.getAbsoluteExpiresAt());
    }

    @Test
    @DisplayName("D-11 a janela de 7 dias renovada perto do fim nunca passa do teto de 30 dias")
    void d_11_janela_renovada_nunca_passa_do_teto_absoluto() {
        // Renovação no dia 25: "agora + 7 dias" seria o dia 32, mas expiresAt é limitado ao teto
        // (dia 30) — o cookie e a sessão não podem prometer mais do que o teto permite.
        RefreshToken original = emitir();
        clock.advance(Duration.ofDays(5));
        RefreshToken dia5 = original.rotate("hash-5", clock);
        clock.advance(Duration.ofDays(5));
        RefreshToken dia10 = dia5.rotate("hash-10", clock);
        clock.advance(Duration.ofDays(5));
        RefreshToken dia15 = dia10.rotate("hash-15", clock);
        clock.advance(Duration.ofDays(5));
        RefreshToken dia20 = dia15.rotate("hash-20", clock);
        clock.advance(Duration.ofDays(5));

        RefreshToken dia25 = dia20.rotate("hash-25", clock);

        assertThat(dia25.getExpiresAt()).isEqualTo(T0.plus(TETO));
        assertThat(dia25.getAbsoluteExpiresAt()).isEqualTo(T0.plus(TETO));
    }

    @Test
    @DisplayName("D-11 um segundo antes do teto de 30 dias a sessão ainda é renovada")
    void d_11_um_segundo_antes_do_teto_ainda_renova() {
        // Controle positivo do teto: "agora < absoluteExpiresAt". Renovado no dia 29 (janela
        // limitada ao dia 30), o token ainda é aceito no último segundo antes do teto, e o novo
        // token já nasce expirando no teto.
        RefreshToken original = emitir();
        clock.advance(Duration.ofDays(6));
        RefreshToken a = original.rotate("hash-a", clock);
        clock.advance(Duration.ofDays(6));
        RefreshToken b = a.rotate("hash-b", clock);
        clock.advance(Duration.ofDays(6));
        RefreshToken c = b.rotate("hash-c", clock);
        clock.advance(Duration.ofDays(6));
        RefreshToken d = c.rotate("hash-d", clock);
        clock.set(T0.plus(TETO).minusSeconds(1));

        RefreshToken ultimo = d.rotate("hash-e", clock);

        assertThat(ultimo.getExpiresAt()).isEqualTo(T0.plus(TETO));
    }

    // ------------------------------------------------------------ reuso (nível da entidade)

    @Test
    @DisplayName("D-11 rotacionar um token já rotacionado (reuso) lança exceção")
    void d_11_rotacionar_token_ja_usado_lanca_excecao() {
        // Um refresh token é de uso único: depois de rotacionado, reapresentá-lo é reuso, ainda
        // que dentro dos prazos. Aqui só se prova a recusa do próprio token; a revogação da
        // família inteira é responsabilidade do serviço (RefreshTokenServiceTest).
        RefreshToken original = emitir();
        clock.advance(Duration.ofHours(1));
        original.rotate("hash-1", clock);
        Instant usadoEm = original.getRevokedAt();
        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> original.rotate("hash-2", clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(original.getRevokedAt()).isEqualTo(usadoEm);
    }

    @Test
    @DisplayName("D-11 rotacionar um token revogado diretamente lança exceção")
    void d_11_rotacionar_token_revogado_lanca_excecao() {
        // revoke() (usado na cascata da família e, no futuro, no logout) torna o token inútil
        // mesmo dentro da janela de 7 dias.
        RefreshToken token = emitir();
        token.revoke(clock);
        clock.advance(Duration.ofMinutes(1));

        assertThatThrownBy(() -> token.rotate("hash-1", clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("D-11 revogar é idempotente e preserva o instante da primeira revogação")
    void d_11_revogar_e_idempotente() {
        // A revogação da família chama revoke() em todas as linhas, inclusive nas já rotacionadas:
        // isso não pode falhar nem sobrescrever quando cada token foi usado.
        RefreshToken token = emitir();
        clock.advance(Duration.ofHours(2));
        token.revoke(clock);
        Instant primeiraRevogacao = clock.instant();

        clock.advance(Duration.ofHours(1));
        token.revoke(clock);

        assertThat(token.getRevokedAt()).isEqualTo(primeiraRevogacao);
        assertThat(token.isRevoked()).isTrue();
    }
}
