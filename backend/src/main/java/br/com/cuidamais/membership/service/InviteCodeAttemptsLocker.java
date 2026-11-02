package br.com.cuidamais.membership.service;

import br.com.cuidamais.membership.domain.InviteCodeAttempts;
import br.com.cuidamais.membership.repository.InviteCodeAttemptsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Ponto único de acesso de escrita ao contador de tentativas por e-mail (D-34, AC-003.3, R-03).
 *
 * <p>Pedidos concorrentes do mesmo e-mail sofreriam atualização perdida do contador (passando de 5
 * palpites) e a criação simultânea da 1ª linha violaria a PK. Aqui a linha é garantida com
 * {@code INSERT ... ON CONFLICT DO NOTHING} e lida com {@code SELECT ... FOR UPDATE}
 * ({@code PESSIMISTIC_WRITE}), serializando as escritas por e-mail até o fim da transação do
 * chamador. A leitura é sempre relida do banco sob o lock, mesmo que a entidade já esteja no
 * contexto de persistência (leitura prévia sem lock), para decidir sobre o valor travado.
 *
 * <p>Ordem global de locks: invite_code_attempts -> invitations -> memberships (todo fluxo trava
 * o contador antes de escrever em convites/vínculos, evitando deadlock).
 *
 * <p>Os métodos exigem transação ativa. Quem chama deve evitar trabalho lento (BCrypt) depois de
 * travar, para não abrir DoS por e-mail.
 */
@Component
public class InviteCodeAttemptsLocker {

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
     * transação e com o estado atual do banco.
     */
    public InviteCodeAttempts lock(String email) {
        // 1. cria a linha de forma atômica se ainda não existir (sem violar a PK sob concorrência).
        repository.insertIfAbsent(email, clock.instant());
        // 2. carrega e relê sob lock (a linha existe, então o find nunca devolve nulo).
        InviteCodeAttempts attempts = entityManager.find(InviteCodeAttempts.class, email);
        entityManager.refresh(attempts, LockModeType.PESSIMISTIC_WRITE);
        return attempts;
    }

    /**
     * Como {@link #lock}, mas sem criar a linha: vazio se o e-mail não tem contador (nada a zerar).
     */
    public Optional<InviteCodeAttempts> lockIfPresent(String email) {
        // 1. sem linha, nada a travar.
        InviteCodeAttempts attempts = entityManager.find(InviteCodeAttempts.class, email);
        if (attempts == null) {
            return Optional.empty();
        }
        // 2. relê sob lock, descartando qualquer estado antigo do contexto.
        entityManager.refresh(attempts, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(attempts);
    }
}
