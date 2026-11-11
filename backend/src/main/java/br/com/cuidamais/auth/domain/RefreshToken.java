package br.com.cuidamais.auth.domain;

import br.com.cuidamais.auth.service.RefreshTokenService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão de longa duração de um login (T-021, D-11 rev. 3), mapeada como {@code @Entity} sobre
 * {@code refresh_tokens} (V1 + V2). Resolve AC-008.3: um usuário autenticado continua logado
 * enquanto usar o app pelo menos uma vez a cada 7 dias (janela deslizante, renovada a cada
 * rotação), mas nunca por mais de 30 dias contínuos desde o login (teto absoluto, herdado em toda
 * rotação e nunca recalculado) — assim uma sessão esquecida sempre expira num prazo previsível.
 *
 * <p>A máquina de estados fica na entidade (D-07): emissão, rotação e revogação são métodos
 * desta classe, testáveis em memória, sem Spring nem banco, e recebem o {@link Clock} por
 * parâmetro (D-10) para os testes controlarem o "agora". Guarda só o hash do token (D-04); o
 * texto puro apresentado no cookie nunca é persistido (ver {@link IssuedRefreshToken}).
 *
 * <p>{@code family_id} (coluna da migração V2) identifica a cadeia de rotação de um mesmo login:
 * o primeiro token de uma família aponta para o próprio {@code id}; cada renovação gera um token
 * novo com o mesmo {@code family_id}. Isso é o que permite ao {@link RefreshTokenService} revogar
 * de uma vez só todos os tokens de uma sessão roubada (reuso de um token já usado) sem afetar as
 * demais sessões/aparelhos do mesmo usuário, já que {@code family_id} nunca é derivado de
 * {@code user_id}.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    /** Janela deslizante (D-11): prazo sem uso após o qual a sessão expira mesmo com rotação. */
    private static final Duration JANELA_DESLIZANTE = Duration.ofDays(7);

    /** Teto absoluto (D-11): prazo máximo de vida da sessão, contado do login, nunca estendido. */
    private static final Duration TETO_ABSOLUTO = Duration.ofDays(30);

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "absolute_expires_at", nullable = false, updatable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Exigido pelo Hibernate; instâncias de domínio nascem por {@link #issue}. */
    protected RefreshToken() {}

    /**
     * Emite o primeiro token de uma família nova (login ou confirmação de e-mail, AC-008.3):
     * {@code family_id} aponta para o próprio {@code id} (raiz da família, D-11 rev. 3, nunca para
     * {@code user_id}), a janela de 7 dias e o teto de 30 dias partem ambos do instante do login.
     *
     * @param userId dono da sessão
     * @param tokenHash hash do token opaco gerado pelo serviço (D-04); o texto puro não chega aqui
     * @param clock relógio injetado (D-10), usado para {@code createdAt}/{@code expiresAt}/{@code absoluteExpiresAt}
     */
    public static RefreshToken issue(UUID userId, String tokenHash, Clock clock) {
        Instant agora = clock.instant();
        RefreshToken token = new RefreshToken();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.familyId = token.id;
        token.tokenHash = tokenHash;
        token.createdAt = agora;
        token.expiresAt = agora.plus(JANELA_DESLIZANTE);
        token.absoluteExpiresAt = agora.plus(TETO_ABSOLUTO);
        token.revokedAt = null;
        return token;
    }

    /**
     * Renova a sessão (AC-008.3): troca este token de uso único por um novo da mesma família.
     * Aceito só se este token ainda não foi usado/revogado e os dois prazos — janela de 7 dias e
     * teto absoluto de 30 dias — ainda não venceram ({@code agora} estritamente menor que cada
     * limite, exclusivo); caso contrário lança {@link IllegalStateException} <strong>sem alterar
     * nada</strong> neste token, para que uma rotação recusada por expiração nunca seja confundida
     * com reuso (que exigiria {@code revokedAt} preenchido).
     *
     * <p>Quando aceito: marca {@code this.revokedAt = agora} (uso único) e devolve um token novo,
     * com outro {@code id}, mesmo {@code userId} e {@code familyId}, e o <strong>mesmo</strong>
     * {@code absoluteExpiresAt} herdado deste token (nunca recalculado — é o que garante o teto de
     * 30 dias desde o login original, mesmo com uso diário). A nova janela de 7 dias nunca
     * ultrapassa o teto absoluto ({@code expiresAt = min(agora + 7 dias, absoluteExpiresAt)}).
     *
     * @param newTokenHash hash do novo token opaco gerado pelo serviço (D-04)
     * @param clock relógio injetado (D-10)
     * @throws IllegalStateException se este token já estiver revogado, ou se a janela de 7 dias
     *     ou o teto de 30 dias já tiverem vencido
     */
    public RefreshToken rotate(String newTokenHash, Clock clock) {
        Instant agora = clock.instant();

        // 1. recusa sem efeito colateral: já usado/revogado (reuso), janela de 7 dias vencida, ou
        // teto absoluto de 30 dias vencido.
        if (isRevoked()) {
            throw new IllegalStateException("Refresh token já foi usado ou revogado (D-11)");
        }
        if (!agora.isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "Refresh token expirou pela janela de 7 dias sem uso (AC-008.3)");
        }
        if (!agora.isBefore(absoluteExpiresAt)) {
            throw new IllegalStateException(
                    "Refresh token atingiu o teto absoluto de 30 dias (D-11)");
        }

        // 2. este token é de uso único: marca como usado agora.
        this.revokedAt = agora;

        // 3. o novo token herda a família e o teto absoluto (nunca recalculado); a janela nova é
        // de 7 dias a partir de agora, mas nunca passa do teto herdado.
        RefreshToken novo = new RefreshToken();
        novo.id = UUID.randomUUID();
        novo.userId = this.userId;
        novo.familyId = this.familyId;
        novo.tokenHash = newTokenHash;
        novo.createdAt = agora;
        novo.absoluteExpiresAt = this.absoluteExpiresAt;
        Instant janelaNormal = agora.plus(JANELA_DESLIZANTE);
        novo.expiresAt = janelaNormal.isBefore(this.absoluteExpiresAt) ? janelaNormal : this.absoluteExpiresAt;
        novo.revokedAt = null;
        return novo;
    }

    /**
     * Revoga este token (logout explícito, ou cascata de reuso em {@link RefreshTokenService}).
     * <strong>Idempotente</strong>: chamadas depois da primeira não lançam erro e preservam o
     * {@code revokedAt} da primeira revogação — necessário porque a cascata de reuso chama este
     * método em todas as linhas da família, inclusive nas que já tinham {@code revokedAt} de uma
     * rotação normal, e não deve apagar o instante em que cada uma foi de fato usada.
     *
     * @param clock relógio injetado (D-10), usado apenas na primeira chamada
     */
    public void revoke(Clock clock) {
        if (revokedAt == null) {
            revokedAt = clock.instant();
        }
    }

    /** Indica se este token já foi usado (rotacionado) ou revogado diretamente. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Identificador único desta linha (um por token emitido, inclusive após rotação). */
    public UUID getId() {
        return id;
    }

    /** Dono da sessão; o mesmo em toda a família. */
    public UUID getUserId() {
        return userId;
    }

    /** Identifica a cadeia de rotação deste login (D-11 rev. 3); nunca é o {@code user_id}. */
    public UUID getFamilyId() {
        return familyId;
    }

    /** Hash do token opaco (D-04); o texto puro nunca é persistido. */
    public String getTokenHash() {
        return tokenHash;
    }

    /** Fim da janela deslizante de 7 dias sem uso; cada rotação a desloca, limitada ao teto. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Teto absoluto de 30 dias desde o login original da família; herdado, nunca recalculado. */
    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    /** Instante da revogação (uso ou logout); {@code null} enquanto o token está ativo. */
    public Instant getRevokedAt() {
        return revokedAt;
    }

    /** Instante em que este token (esta linha específica) foi emitido. */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
