package br.com.cuidamais.membership.service;

import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ponto único de acesso de escrita ao contador de tentativas por e-mail (D-34, AC-003.3, R-03, R-08).
 *
 * <p>Pedidos concorrentes do mesmo e-mail sofreriam atualização perdida do contador (passando de 5
 * palpites) e a criação simultânea da 1ª linha violaria a PK. Aqui a linha é garantida com
 * {@code INSERT ... ON CONFLICT DO NOTHING} e travada com {@code SELECT ... FOR UPDATE}
 * ({@code PESSIMISTIC_WRITE}), serializando as escritas por e-mail até o fim da transação do
 * chamador. A leitura é sempre relida do banco sob o lock, mesmo que a entidade já esteja no
 * contexto de persistência (leitura prévia sem lock), para decidir sobre o valor travado.
 *
 * <p><strong>Corrida com a exclusão (R-08):</strong> a retenção de contas não aprovadas (T-049,
 * {@code RetencaoContasNaoAprovadasRoutine}) apaga o contador do e-mail. Se isso acontece e
 * comita enquanto outra transação espera o lock da mesma linha, a linha deixa de existir quando o
 * lock é concedido. Fazer {@code refresh(PESSIMISTIC_WRITE)} direto nessa situação lança {@code
 * EntityNotFoundException} (HTTP 500 no fluxo web) e, no Hibernate, marca a transação como
 * rollback-only. Por isso o lock é tomado primeiro por uma consulta {@code FOR UPDATE} que
 * simplesmente devolve lista vazia quando a linha sumiu (sem exceção, transação intacta); só com a
 * linha travada e existente é feito o {@code refresh}. Linha sumida: {@link #lock} a recria e trava
 * (uma única repetição) e {@link #lockIfPresent} devolve vazio.
 *
 * <p>Ordem global de locks: invite_code_attempts -> invitations -> memberships (todo fluxo trava
 * o contador antes de escrever em convites/vínculos, evitando deadlock).
 *
 * <p>Os métodos exigem transação ativa. Quem chama deve evitar trabalho lento (BCrypt) depois de
 * travar, para não abrir DoS por e-mail.
 */
@Component
public class InviteCodeAttemptsLocker {

    /**
     * Trava a linha do e-mail e devolve a própria chave, ou nada se ela não existe (mais). Em
     * READ COMMITTED, uma linha apagada e comitada durante a espera é descartada pelo Postgres e a
     * lista volta vazia, em vez de erro.
     */
    private static final String LOCK_SQL = "SELECT email FROM invite_code_attempts WHERE email = :email FOR UPDATE";

    private final InviteCodeAttemptsRepository repository;
    private final EntityManager entityManager;
    private final Clock clock;

    /** Injeção por construtor do repositório, do gerenciador de entidades e do relógio (D-10). */
    public InviteCodeAttemptsLocker(
            InviteCodeAttemptsRepository repository, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Devolve o contador do e-mail (criando-o zerado se faltar), travado para escrita até o fim da
     * transação e com o estado atual do banco. Se a linha for apagada por outra transação durante
     * a espera pelo lock (R-08), ela é recriada zerada e travada, com uma única repetição.
     */
    public InviteCodeAttempts lock(String email) {
        // 1. cria a linha de forma atômica se ainda não existir (sem violar a PK sob concorrência)
        // e tenta travá-la.
        repository.insertIfAbsent(email, clock.instant());
        if (!lockRow(email)) {
            // 2. a linha sumiu durante a espera (apagada e comitada por outra transação): descarta
            // a entidade antiga do contexto e repete uma única vez a criação + trava.
            detachStale(email);
            repository.insertIfAbsent(email, clock.instant());
            if (!lockRow(email)) {
                // 3. sumiu de novo: sem laço infinito, falha como o refresh de uma linha ausente.
                throw new EntityNotFoundException("Contador de tentativas não encontrado");
            }
        }
        // 4. linha existe e está travada: carrega e relê o estado atual do banco.
        return reload(email);
    }

    /**
     * Como {@link #lock}, mas sem criar a linha: vazio se o e-mail não tem contador (nada a zerar)
     * ou se ele foi apagado por outra transação enquanto esta esperava o lock (R-08).
     */
    public Optional<InviteCodeAttempts> lockIfPresent(String email) {
        // 1. tenta travar a linha; sem linha (nunca existiu ou sumiu na espera), nada a travar.
        if (!lockRow(email)) {
            // 2. remove do contexto qualquer entidade antiga da linha que não existe mais.
            detachStale(email);
            return Optional.empty();
        }
        // 3. linha existe e está travada: relê o estado atual, descartando o estado antigo do contexto.
        return Optional.of(reload(email));
    }

    /**
     * Executa o {@code SELECT ... FOR UPDATE} da linha do e-mail; devolve se ela existe e ficou
     * travada. Lista vazia (sem exceção) significa que a linha não existe, sem comprometer a
     * transação.
     */
    private boolean lockRow(String email) {
        return !entityManager
                .createNativeQuery(LOCK_SQL, String.class)
                .setParameter("email", email)
                .getResultList()
                .isEmpty();
    }

    /**
     * Carrega a entidade (do contexto, se já houver) e a relê do banco sob {@code PESSIMISTIC_WRITE};
     * só é chamado com a linha já travada e existente, então o refresh não falha.
     */
    private InviteCodeAttempts reload(String email) {
        InviteCodeAttempts attempts = entityManager.find(InviteCodeAttempts.class, email);
        entityManager.refresh(attempts, LockModeType.PESSIMISTIC_WRITE);
        return attempts;
    }

    /**
     * Desanexa do contexto uma entidade que ainda represente uma linha já apagada por outra
     * transação, para que não seja relida nem gravada por engano. Sem entidade no contexto, nada
     * a fazer.
     */
    private void detachStale(String email) {
        // 1. a linha não existe no banco (acabou de não ser travada), então o find só pode devolver
        // uma entidade que já estava no contexto (estado antigo) ou nulo.
        InviteCodeAttempts stale = entityManager.find(InviteCodeAttempts.class, email);
        // 2. desanexa a antiga, se houver.
        if (stale != null) {
            entityManager.detach(stale);
        }
    }
}
