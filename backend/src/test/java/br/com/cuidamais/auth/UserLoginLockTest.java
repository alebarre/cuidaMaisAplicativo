package br.com.cuidamais.auth;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.cuidamais.shared.Endereco;
import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-023 — regra de bloqueio de login <strong>por conta</strong> (AC-008.4, D-14 rev. 2), testada
 * em memória, sem Spring nem banco, no mesmo padrão de {@code Membership}/{@code Invitation}/
 * {@code OtpCode} (D-07: a regra mora em métodos da entidade) e com {@link MutableClock} (D-10).
 *
 * <p>Cobre: contagem de senhas erradas seguidas, início do bloqueio na 5ª, duração de 15 minutos
 * ({@code locked_until}), sinalização de <strong>um</strong> e-mail só na transição para bloqueado
 * ({@code lock_notified_at}), "zera no acerto" e "zera ao fim da janela" (verificado na leitura,
 * D-09, sem job).
 *
 * <p><strong>API esperada em {@link User}</strong> (pacote {@code br.com.cuidamais.auth}):
 *
 * <ul>
 *   <li>{@code boolean isLoginLocked(Clock clock)} — {@code true} se {@code lockedUntil != null} e
 *       {@code agora < lockedUntil} (no instante exato de {@code lockedUntil} já não está bloqueada).
 *   <li>{@code void clearExpiredLoginLock(Clock clock)} — se {@code lockedUntil != null} e {@code
 *       agora >= lockedUntil}: {@code failedLogins = 0}, {@code lockedUntil = null}, {@code
 *       lockNotifiedAt = null}. Em qualquer outro caso, não faz nada (inclusive com erros parciais
 *       sem bloqueio: sem bloqueio não há "janela" para terminar). O serviço chama este método
 *       <strong>antes</strong> de avaliar a tentativa.
 *   <li>{@code boolean registerFailedLogin(Clock clock)} — fora do bloqueio: {@code
 *       failedLogins++}; ao chegar a 5, grava {@code lockedUntil = agora + 15 min} e {@code
 *       lockNotifiedAt = agora} e devolve {@code true} (quem chamou deve publicar o {@code
 *       PasswordLockEmail} agora). Devolve {@code false} em todos os outros casos. <strong>Durante
 *       o bloqueio</strong> é no-op e devolve {@code false}: não incrementa, não estende {@code
 *       lockedUntil}, não regrava {@code lockNotifiedAt} (decisão desta tarefa: o bloqueio não se
 *       prolonga e não há segundo e-mail).
 *   <li>{@code void registerSuccessfulLogin()} — {@code failedLogins = 0}, {@code lockedUntil =
 *       null}, {@code lockNotifiedAt = null}.
 *   <li>Leitura já existente: {@code getFailedLogins()}, {@code getLockedUntil()}, {@code
 *       getLockNotifiedAt()}.
 * </ul>
 */
class UserLoginLockTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration JANELA_BLOQUEIO = Duration.ofMinutes(15);
    private static final int LIMITE_ERROS = 5;

    private MutableClock clock;
    private User user;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        user = User.signup("Maria da Silva", "maria@teste.com", "21987654321", endereco, "hash", clock);
        user.verifyEmail(clock);
    }

    /** Erra {@code vezes} vezes, devolvendo o resultado da última chamada. */
    private boolean errar(int vezes) {
        boolean ultimo = false;
        for (int i = 0; i < vezes; i++) {
            ultimo = user.registerFailedLogin(clock);
        }
        return ultimo;
    }

    // ------------------------------------------------------------ estado inicial

    /**
     * Ponto de partida de todos os cenários: conta recém-criada não tem nenhum erro contado e não
     * está bloqueada (D-14) — senão qualquer teste abaixo partiria de um estado sujo.
     */
    @Test
    @DisplayName("AC-008.4 conta nova começa sem erros e sem bloqueio")
    void ac_008_4_conta_nova_sem_erros_e_sem_bloqueio() {
        assertThat(user.getFailedLogins()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLockNotifiedAt()).isNull();
        assertThat(user.isLoginLocked(clock)).isFalse();
    }

    // ------------------------------------------------------------ AC-008.4 contagem até o limite

    /**
     * AC-008.4 "depois de 5 senhas erradas seguidas": da 1ª à 4ª o contador sobe um por vez, a
     * conta continua livre e nenhuma chamada pede e-mail (devolve {@code false}).
     */
    @Test
    @DisplayName("AC-008.4 da 1ª à 4ª senha errada o contador sobe e a conta não bloqueia")
    void ac_008_4_quatro_primeiros_erros_nao_bloqueiam() {
        for (int tentativa = 1; tentativa <= LIMITE_ERROS - 1; tentativa++) {
            // Tentativa N (1..4): incrementa, sem bloqueio, sem e-mail.
            assertThat(user.registerFailedLogin(clock)).as("%dª errada não pede e-mail", tentativa).isFalse();
            assertThat(user.getFailedLogins()).as("contador após a %dª", tentativa).isEqualTo(tentativa);
            assertThat(user.getLockedUntil()).as("sem bloqueio após a %dª", tentativa).isNull();
            assertThat(user.isLoginLocked(clock)).isFalse();
        }
    }

    /**
     * AC-008.4 / D-14: a 5ª senha errada é a transição para bloqueado — {@code lockedUntil = agora +
     * 15 min} e a conta passa a estar bloqueada.
     */
    @Test
    @DisplayName("AC-008.4 a 5ª senha errada bloqueia a conta por 15 minutos")
    void ac_008_4_quinta_errada_bloqueia_por_15_minutos() {
        errar(LIMITE_ERROS);

        assertThat(user.getFailedLogins()).isEqualTo(LIMITE_ERROS);
        assertThat(user.getLockedUntil()).isEqualTo(T0.plus(JANELA_BLOQUEIO));
        assertThat(user.isLoginLocked(clock)).isTrue();
    }

    /**
     * AC-008.4 "um e-mail no momento em que o bloqueio começa" / D-14 ({@code lock_notified_at}
     * evita repetição): a chamada que inicia o bloqueio devolve {@code true} e grava {@code
     * lockNotifiedAt = agora}, para o serviço publicar o {@code PasswordLockEmail} exatamente uma vez.
     */
    @Test
    @DisplayName("AC-008.4 / D-14 a 5ª senha errada sinaliza o e-mail de bloqueio e grava lock_notified_at")
    void ac_008_4_quinta_errada_sinaliza_email_e_grava_lock_notified_at() {
        errar(LIMITE_ERROS - 1);

        boolean deveAvisar = user.registerFailedLogin(clock);

        assertThat(deveAvisar).as("a transição para bloqueado pede o e-mail").isTrue();
        assertThat(user.getLockNotifiedAt()).isEqualTo(T0);
    }

    // ------------------------------------------------------------ AC-008.4 durante o bloqueio

    /**
     * AC-008.4: o bloqueio dura 15 minutos. Até 1 segundo antes do fim, a conta segue bloqueada,
     * mesmo com o relógio avançando (prova que a janela é medida pelo relógio injetado, D-10).
     */
    @Test
    @DisplayName("AC-008.4 conta segue bloqueada até 1 segundo antes dos 15 minutos")
    void ac_008_4_bloqueada_ate_um_segundo_antes_do_fim() {
        errar(LIMITE_ERROS);

        clock.advance(JANELA_BLOQUEIO.minusSeconds(1));

        assertThat(user.isLoginLocked(clock)).isTrue();
    }

    /**
     * AC-008.4: no instante exato de {@code lockedUntil} (15 min depois) o bloqueio já terminou
     * ({@code agora < lockedUntil} é falso) — mesmo limite fechado-aberto de {@code OtpCode}.
     */
    @Test
    @DisplayName("AC-008.4 conta deixa de estar bloqueada exatamente aos 15 minutos")
    void ac_008_4_desbloqueia_exatamente_aos_15_minutos() {
        errar(LIMITE_ERROS);

        clock.advance(JANELA_BLOQUEIO);

        assertThat(user.isLoginLocked(clock)).isFalse();
    }

    /**
     * AC-008.4 "um e-mail ... (não a cada tentativa)" + decisão desta tarefa: errar de novo durante
     * o bloqueio não pede outro e-mail, não prolonga o bloqueio, não regrava {@code lockNotifiedAt}
     * e não continua incrementando o contador (fica em 5).
     */
    @Test
    @DisplayName("AC-008.4 / D-14 senha errada durante o bloqueio não pede outro e-mail nem prolonga o bloqueio")
    void ac_008_4_erro_durante_bloqueio_e_no_op() {
        errar(LIMITE_ERROS);
        Instant bloqueadoAte = user.getLockedUntil();
        clock.advance(Duration.ofMinutes(5));

        boolean deveAvisar = user.registerFailedLogin(clock);

        assertThat(deveAvisar).as("sem segundo e-mail").isFalse();
        assertThat(user.getLockedUntil()).as("bloqueio não prolonga").isEqualTo(bloqueadoAte);
        assertThat(user.getLockNotifiedAt()).as("aviso continua o da transição").isEqualTo(T0);
        assertThat(user.getFailedLogins()).as("contador não passa de 5").isEqualTo(LIMITE_ERROS);
    }

    // ------------------------------------------------------------ AC-008.4 zera no acerto

    /**
     * AC-008.4 "um login correto zera o contador": depois de erros parciais (sem bloquear), o
     * acerto volta tudo a zero/nulo.
     */
    @Test
    @DisplayName("AC-008.4 login correto zera o contador de erros")
    void ac_008_4_acerto_zera_contador() {
        errar(3);

        user.registerSuccessfulLogin();

        assertThat(user.getFailedLogins()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLockNotifiedAt()).isNull();
    }

    /**
     * AC-008.4 "5 senhas erradas <strong>seguidas</strong>": um acerto no meio recomeça a contagem
     * — 4 erros, acerto, mais 4 erros não bloqueiam (seriam 8 erros no total, mas não seguidos).
     */
    @Test
    @DisplayName("AC-008.4 acerto no meio recomeça a contagem: 4 erros + acerto + 4 erros não bloqueiam")
    void ac_008_4_acerto_no_meio_recomeca_contagem() {
        // Etapa 1: 4 erros, a um passo do bloqueio.
        errar(LIMITE_ERROS - 1);
        // Etapa 2: acerto zera.
        user.registerSuccessfulLogin();
        // Etapa 3: mais 4 erros — contagem recomeçou do zero.
        boolean ultimo = errar(LIMITE_ERROS - 1);

        assertThat(ultimo).isFalse();
        assertThat(user.getFailedLogins()).isEqualTo(LIMITE_ERROS - 1);
        assertThat(user.isLoginLocked(clock)).isFalse();
    }

    // ------------------------------------------------------------ AC-008.4 zera ao fim da janela

    /**
     * {@code clearExpiredLoginLock} antes do fim da janela não faz nada: a conta continua
     * bloqueada com o mesmo contador e o mesmo aviso — senão o bloqueio acabaria antes da hora.
     */
    @Test
    @DisplayName("AC-008.4 limpar bloqueio antes dos 15 minutos não muda nada")
    void ac_008_4_limpar_antes_do_fim_nao_muda_nada() {
        errar(LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO.minusSeconds(1));

        user.clearExpiredLoginLock(clock);

        assertThat(user.isLoginLocked(clock)).isTrue();
        assertThat(user.getFailedLogins()).isEqualTo(LIMITE_ERROS);
        assertThat(user.getLockedUntil()).isEqualTo(T0.plus(JANELA_BLOQUEIO));
        assertThat(user.getLockNotifiedAt()).isEqualTo(T0);
    }

    /**
     * AC-008.4 "ao fim dos 15 minutos o contador também volta a zero" / D-09 (verificado na
     * leitura, sem job): no instante exato do fim, limpar zera contador, bloqueio e aviso.
     */
    @Test
    @DisplayName("AC-008.4 ao fim dos 15 minutos limpar o bloqueio zera contador, locked_until e lock_notified_at")
    void ac_008_4_fim_da_janela_zera_tudo() {
        errar(LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO);

        user.clearExpiredLoginLock(clock);

        assertThat(user.getFailedLogins()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLockNotifiedAt()).isNull();
    }

    /**
     * Sem bloqueio não há janela: erros parciais (3) não são apagados por {@code
     * clearExpiredLoginLock}, mesmo muito tempo depois. Só o acerto (ou um bloqueio que termina)
     * zera o contador — AC-008.4 não prevê expiração de erros parciais.
     */
    @Test
    @DisplayName("AC-008.4 limpar bloqueio sem bloqueio ativo não apaga erros parciais")
    void ac_008_4_limpar_sem_bloqueio_preserva_erros_parciais() {
        errar(3);
        clock.advance(Duration.ofHours(2));

        user.clearExpiredLoginLock(clock);

        assertThat(user.getFailedLogins()).isEqualTo(3);
        assertThat(user.getLockedUntil()).isNull();
    }

    /**
     * Consequência de "zera ao fim da janela": depois de um bloqueio vencido e limpo, uma senha
     * errada conta como a 1ª (não a 6ª) e não bloqueia de novo.
     */
    @Test
    @DisplayName("AC-008.4 após o fim do bloqueio, uma senha errada conta como a 1ª e não bloqueia")
    void ac_008_4_apos_fim_erro_conta_como_primeiro() {
        errar(LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO);
        user.clearExpiredLoginLock(clock);

        boolean deveAvisar = user.registerFailedLogin(clock);

        assertThat(deveAvisar).isFalse();
        assertThat(user.getFailedLogins()).isEqualTo(1);
        assertThat(user.isLoginLocked(clock)).isFalse();
    }

    /**
     * AC-008.4 "um e-mail no momento em que o bloqueio começa": um <strong>novo</strong> bloqueio
     * (depois que o anterior terminou) é uma nova transição e pede um novo e-mail, com o novo
     * {@code lockedUntil}/{@code lockNotifiedAt}.
     */
    @Test
    @DisplayName("AC-008.4 / D-14 um novo bloqueio após o fim do anterior pede um novo e-mail")
    void ac_008_4_novo_bloqueio_pede_novo_email() {
        // Etapa 1: primeiro bloqueio, termina.
        errar(LIMITE_ERROS);
        clock.advance(JANELA_BLOQUEIO);
        user.clearExpiredLoginLock(clock);
        Instant inicioSegundo = clock.instant();

        // Etapa 2: mais 5 erros seguidos — a 5ª é uma nova transição.
        errar(LIMITE_ERROS - 1);
        boolean deveAvisar = user.registerFailedLogin(clock);

        assertThat(deveAvisar).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(inicioSegundo.plus(JANELA_BLOQUEIO));
        assertThat(user.getLockNotifiedAt()).isEqualTo(inicioSegundo);
    }
}
