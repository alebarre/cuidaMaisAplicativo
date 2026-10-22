package br.com.cuidamais.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orquestra a emissão e a rotação de {@link RefreshToken} (T-021, AC-008.3, D-11 rev. 3): gera o
 * valor opaco que vai no cookie, aplica o hash determinístico que é persistido (D-04) e delega as
 * regras de prazo/uso único para a entidade (D-07). Também concentra a única regra que não cabe
 * na entidade sozinha — a <strong>detecção de reuso</strong>: quando o token apresentado já estava
 * revogado, significa que ele foi usado (ou roubado e usado) mais de uma vez, e a resposta é
 * revogar de uma só vez toda a família de tokens daquele login, derrubando a sessão inteira.
 *
 * <p>O token opaco é gerado com {@link SecureRandom} (32 bytes, alta entropia) e hasheado com
 * SHA-256, não BCrypt: BCrypt é lento de propósito (apropriado para senha/OTP, que têm baixa
 * entropia e precisam resistir a força bruta), mas aqui precisamos localizar a linha pelo hash
 * via {@code SELECT ... WHERE token_hash = ?} — uma busca por igualdade exige um hash
 * determinístico (o mesmo texto sempre produz o mesmo hash), o que BCrypt não oferece (cada
 * chamada usa um salt novo). SHA-256 é seguro aqui porque o segredo já tem entropia altíssima
 * (32 bytes aleatórios), diferente de uma senha ou de um OTP de 6 dígitos.
 */
@Service
public class RefreshTokenService {

    private static final int TAMANHO_TOKEN_EM_BYTES = 32;
    private static final String ALGORITMO_HASH = "SHA-256";

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Transação isolada (REQUIRES_NEW, mesmo padrão de {@code JobRunner}), usada só para a
     * revogação da família no reuso: precisa confirmar no banco mesmo que a transação do método
     * que a chamou seja desfeita por causa da exceção lançada depois (ver {@link #rotate}). */
    private final TransactionTemplate transacaoIsolada;

    /**
     * @param repository persiste e busca as linhas de {@link RefreshToken}
     * @param clock relógio injetado (D-10), repassado à entidade em toda operação
     * @param transactionManager usado para montar a transação isolada da revogação em cascata
     */
    public RefreshTokenService(
            RefreshTokenRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.transacaoIsolada = new TransactionTemplate(transactionManager);
        this.transacaoIsolada.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Emite o primeiro token de uma família nova (login ou confirmação de e-mail, AC-008.3): gera
     * o valor opaco, persiste só o hash (D-04) e devolve os dois — a entidade salva e o texto
     * puro, que é o único momento em que ele existe fora da memória desta chamada.
     */
    @Transactional
    public IssuedRefreshToken issue(UUID userId) {
        String plainToken = gerarTokenOpaco();
        RefreshToken token = RefreshToken.issue(userId, hash(plainToken), clock);
        repository.save(token);
        return new IssuedRefreshToken(token, plainToken);
    }

    /**
     * Renova a sessão a partir do token apresentado no cookie (AC-008.3). Fluxo:
     *
     * <ol>
     *   <li>hasheia o texto apresentado com o mesmo SHA-256 determinístico usado na emissão;
     *   <li>busca a linha correspondente; token desconhecido é recusado sem gravar nada;
     *   <li>se a linha encontrada <strong>já estava revogada</strong> antes desta chamada, é reuso
     *       (D-11): revoga toda a família numa transação própria — que confirma mesmo que esta
     *       chamada termine em exceção — e então lança a mesma exceção de sessão inválida;
     *   <li>senão, aplica {@link RefreshToken#rotate} (que já valida os prazos) e persiste o token
     *       antigo (agora revogado) e o novo, devolvendo o novo par entidade/texto puro.
     * </ol>
     *
     * @param presentedPlainToken valor opaco lido do cookie {@code refresh_token}
     * @throws IllegalStateException para token desconhecido, expirado, ou reuso de um token já usado
     */
    @Transactional
    public IssuedRefreshToken rotate(String presentedPlainToken) {
        // 1. hasheia o texto apresentado (mesmo SHA-256 determinístico da emissão).
        String tokenHash = hash(presentedPlainToken);

        // 2. busca a linha; token desconhecido é recusado sem gravar nada.
        RefreshToken encontrado = repository
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalStateException("Refresh token desconhecido (AC-008.3)"));

        // 3. reuso: o token encontrado já estava revogado antes desta chamada — token roubado ou
        // reapresentado por engano. Revoga a família inteira numa transação isolada, que precisa
        // ficar gravada mesmo que esta transação seja desfeita pela exceção lançada a seguir.
        if (encontrado.isRevoked()) {
            revogarFamiliaEmTransacaoIsolada(encontrado.getFamilyId());
            throw new IllegalStateException(
                    "Reuso de refresh token detectado; família de sessões revogada (D-11)");
        }

        // 4. rotação normal: a entidade valida os prazos e devolve o novo token; persiste os dois
        // lados (o antigo, agora revogado, e o novo) na mesma transação.
        String newPlainToken = gerarTokenOpaco();
        RefreshToken novo = encontrado.rotate(hash(newPlainToken), clock);
        repository.save(encontrado);
        repository.save(novo);
        return new IssuedRefreshToken(novo, newPlainToken);
    }

    /**
     * Encerra a sessão de <strong>um aparelho</strong> a partir do cookie apresentado em
     * {@code /auth/logout} (T-025, AC-008.5). Decisões do orquestrador, deliberadamente mais
     * permissivas que {@link #rotate} porque aqui não existe reuso a detectar, só "sair":
     *
     * <ol>
     *   <li>cookie nulo/vazio (ex. já tinha sido limpo) → não faz nada;
     *   <li>hasheia e busca por {@link RefreshTokenRepository#findByTokenHash}; token desconhecido
     *       → não faz nada (idempotente, nunca revela se o token existe, P5);
     *   <li>token de <strong>outra conta</strong> ({@code token.getUserId()} diferente de {@code
     *       callerUserId}) → não faz nada (quem está logado como A não derruba a sessão de B,
     *       D-13);
     *   <li>encontrado e da própria conta → revoga a <strong>família inteira</strong> do token
     *       (mesma lógica de {@link #revogarFamiliaEmTransacaoIsolada}, mas aqui dentro da
     *       transação normal deste método, sem precisar de transação isolada: não há exceção
     *       lançada depois que pudesse desfazer a revogação). Revogar a família, não só a linha
     *       apresentada, é o que garante que "Sair" funciona mesmo que o aparelho apresente um
     *       token já rotacionado (antigo) daquele login — o token atual da cadeia também precisa
     *       parar de renovar.
     * </ol>
     *
     * @param plainToken valor opaco lido do cookie {@code refresh_token}; pode ser nulo/vazio
     * @param callerUserId dono da sessão autenticada (bearer) que está chamando {@code logout}
     */
    @Transactional
    public void revokeDeviceByPlainToken(String plainToken, UUID callerUserId) {
        // 1. sem cookie, não há o que revogar.
        if (plainToken == null || plainToken.isBlank()) {
            return;
        }

        // 2. token desconhecido: não faz nada, idempotente.
        var encontrado = repository.findByTokenHash(hash(plainToken));
        if (encontrado.isEmpty()) {
            return;
        }

        // 3. cookie de outra conta: nunca revoga a sessão de quem não é o chamador (D-13).
        RefreshToken token = encontrado.get();
        if (!token.getUserId().equals(callerUserId)) {
            return;
        }

        // 4. dono confere: revoga a família inteira (não só esta linha), para que o aparelho pare
        // de renovar mesmo que tenha apresentado um token antigo já rotacionado da mesma família.
        for (RefreshToken daFamilia : repository.findByFamilyId(token.getFamilyId())) {
            daFamilia.revoke(clock);
            repository.save(daFamilia);
        }
    }

    /**
     * Encerra <strong>todas</strong> as sessões da conta, de <strong>todas</strong> as famílias
     * (D-37, AC-010.4, T-026) — chamado por {@code password-reset/confirm} depois que a senha é
     * trocada, já que a pessoa precisa entrar de novo em todo aparelho. Diferente de {@link
     * #revokeDeviceByPlainToken}, que só derruba o aparelho do cookie apresentado (um aparelho,
     * T-025): aqui não há cookie nenhum para identificar "este aparelho", é a conta inteira.
     * {@link RefreshToken#revoke} é idempotente, então uma linha que já estivesse revogada (de um
     * reuso detectado antes, por exemplo) não tem o {@code revokedAt} original sobrescrito.
     *
     * @param userId dono das sessões a encerrar
     */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        // 1. busca todas as linhas da conta, de todas as famílias (D-37).
        List<RefreshToken> todas = repository.findByUserId(userId);
        // 2. revoga cada uma; idempotente para as que já estavam revogadas.
        for (RefreshToken token : todas) {
            token.revoke(clock);
            repository.save(token);
        }
    }

    /**
     * Revoga todas as linhas de uma família (D-11) numa transação própria (REQUIRES_NEW), para
     * que o efeito sobreviva ao rollback da transação de {@link #rotate} quando a exceção de
     * reuso é lançada depois desta chamada. {@link RefreshToken#revoke} é idempotente, então
     * revogar uma linha que já estava revogada (ex. a própria linha reapresentada) não tem efeito
     * colateral e preserva o {@code revokedAt} original dela.
     */
    private void revogarFamiliaEmTransacaoIsolada(UUID familyId) {
        transacaoIsolada.executeWithoutResult(status -> {
            List<RefreshToken> familia = repository.findByFamilyId(familyId);
            for (RefreshToken token : familia) {
                token.revoke(clock);
                repository.save(token);
            }
        });
    }

    /** Gera o valor opaco do cookie: 32 bytes de alta entropia, codificados em Base64URL sem padding (D-04). */
    private String gerarTokenOpaco() {
        byte[] bytes = new byte[TAMANHO_TOKEN_EM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash determinístico (SHA-256) do texto apresentado, para permitir a busca por igualdade no
     * banco ({@code findByTokenHash}) — ver a nota da classe sobre por que não é BCrypt aqui.
     */
    private String hash(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITMO_HASH);
            byte[] hashBytes = digest.digest(plainToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é garantido por toda JVM compatível; isto nunca deveria ocorrer em produção.
            throw new IllegalStateException("Algoritmo de hash indisponível na JVM", e);
        }
    }
}
