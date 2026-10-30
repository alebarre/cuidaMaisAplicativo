package br.com.cuidamais.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-014 "Pronto quando": um {@code @ParameterizedTest} com cada linha da tabela de AC-003.10 que é
 * transição da entidade, mais um teste por transição inválida (D-07).
 *
 * <p>As duas últimas linhas da tabela de AC-003.10 ("(excluído)") são exclusão de linha feita por
 * jobs/cascade de outras tarefas (AC-014.1 / T-033, AC-013.2 / T-049) e não fazem parte desta
 * entidade; por isso não aparecem aqui.
 *
 * <p>D-07 (rev. 2): transições são métodos da entidade e lançam exceção em transição inválida; a
 * regra é testável <strong>sem banco</strong>. O tempo é controlado com {@link MutableClock}
 * (D-10).
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.membership}, o mesmo de {@link Membership}):
 *
 * <ul>
 *   <li>{@code enum InvitationStatus { ENVIADO, USADO, VENCIDO, CANCELADO }}
 *   <li>{@code enum InvitationOrigin { MANUAL, AUTO }} (D-34: {@code MANUAL} = Responsável,
 *       {@code AUTO} = reenvio automático do job).
 *   <li>{@code static Invitation send(UUID groupId, String email, String codeHash,
 *       InvitationOrigin origin, UUID createdBy, Clock clock)} — nasce {@code ENVIADO},
 *       {@code createdAt = agora}, {@code expiresAt = agora + 7 dias}, {@code usedAt} e
 *       {@code cancelledAt} nulos. Recebe o código <strong>já em hash</strong> (D-04); a entidade
 *       nunca vê o código em texto.
 *   <li>{@code static Invitation resend(Invitation previous, String newCodeHash,
 *       InvitationOrigin origin, UUID createdBy, Clock clock)} — D-31 / AC-003.4 / AC-003.12:
 *       {@code USADO} lança exceção; {@code ENVIADO} é cancelado; {@code VENCIDO}/{@code
 *       CANCELADO} ficam como estão; devolve um convite novo ({@code send}) para o mesmo grupo e
 *       e-mail do anterior.
 *   <li>{@code void use(Clock clock)}, {@code void expire(Clock clock)}, {@code void cancel(Clock
 *       clock)}.
 *   <li>Leitura: {@code getId()}, {@code getGroupId()}, {@code getEmail()}, {@code getCodeHash()},
 *       {@code getStatus()}, {@code getOrigin()}, {@code getExpiresAt()}, {@code getCreatedBy()},
 *       {@code getCreatedAt()}, {@code getUsedAt()}, {@code getCancelledAt()}.
 * </ul>
 *
 * <p>Transição inválida lança {@link IllegalStateException} (ou subclasse — ex. uma exceção
 * específica que a camada web mapeie para {@code 410 Gone}, D-16).
 *
 * <p><strong>Prazo (AC-003.2):</strong> o convite vale enquanto {@code agora < expiresAt}; em
 * {@code agora == expiresAt} (7 dias exatos) ele já venceu — mesmo critério de {@link Membership}
 * (AC-006.8). {@code use} depois do prazo falha mesmo que o job (D-09) ainda não tenha mudado o
 * status para {@code VENCIDO}; {@code expire} só é aceito a partir de {@code agora >= expiresAt}.
 *
 * <p><strong>Fora do escopo (D-34):</strong> tentativas erradas e bloqueio de 30 minutos
 * (AC-003.3, AC-003.11) vivem em {@code invite_code_attempts} (T-018), não nesta entidade. A
 * conferência "o código vale só para o e-mail convidado" (AC-003.2) é feita pelo resgate, que
 * busca o convite pelo e-mail (T-018); aqui só se prova que o convite guarda o e-mail convidado e
 * que o reenvio o preserva.
 */
class InvitationTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration PRAZO_CONVITE = Duration.ofDays(7);
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String EMAIL = "convidada@example.com";
    private static final String CODE_HASH = "$2a$10$hashDoCodigoOriginal";
    private static final String NEW_CODE_HASH = "$2a$10$hashDoCodigoNovo";

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ apoio

    private static Invitation sent(MutableClock clock) {
        return Invitation.send(GROUP_ID, EMAIL, CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);
    }

    /** Monta um convite no status pedido, só por transições válidas. */
    private static Invitation invitationIn(InvitationStatus status, MutableClock clock) {
        Invitation inv = sent(clock);
        switch (status) {
            case ENVIADO -> { }
            case USADO -> inv.use(clock);
            case VENCIDO -> {
                clock.advance(PRAZO_CONVITE);
                inv.expire(clock);
            }
            case CANCELADO -> inv.cancel(clock);
        }
        assertThat(inv.getStatus()).as("pré-condição do cenário").isEqualTo(status);
        return inv;
    }

    @FunctionalInterface
    interface Transicao {
        void aplicar(Invitation inv, MutableClock clock);
    }

    // ------------------------------------------- AC-003.10 tabela (linhas da entidade)

    static Stream<Arguments> transicoesValidasDaTabela() {
        return Stream.of(
                Arguments.of(
                        "AC-004.3 / AC-004.6 ENVIADO -> USADO: e-mail e código válidos usados",
                        (Transicao) (inv, c) -> inv.use(c),
                        InvitationStatus.USADO),
                Arguments.of(
                        "AC-003.2 ENVIADO -> VENCIDO: 7 dias desde o envio",
                        (Transicao)
                                (inv, c) -> {
                                    c.advance(PRAZO_CONVITE);
                                    inv.expire(c);
                                },
                        InvitationStatus.VENCIDO),
                Arguments.of(
                        "AC-003.4 ENVIADO -> CANCELADO: Responsável cancela",
                        (Transicao) (inv, c) -> inv.cancel(c),
                        InvitationStatus.CANCELADO),
                Arguments.of(
                        "AC-003.4 / AC-003.12 ENVIADO -> CANCELADO: Responsável reenvia",
                        (Transicao)
                                (inv, c) ->
                                        Invitation.resend(
                                                inv,
                                                NEW_CODE_HASH,
                                                InvitationOrigin.MANUAL,
                                                ADMIN_ID,
                                                c),
                        InvitationStatus.CANCELADO),
                Arguments.of(
                        "AC-003.3 ENVIADO -> CANCELADO: reenvio automático",
                        (Transicao)
                                (inv, c) ->
                                        // created_by é NOT NULL na V1: o job (T-048) repassa o
                                        // autor do convite anterior.
                                        Invitation.resend(
                                                inv,
                                                NEW_CODE_HASH,
                                                InvitationOrigin.AUTO,
                                                inv.getCreatedBy(),
                                                c),
                        InvitationStatus.CANCELADO));
    }

    @ParameterizedTest(name = "AC-003.10 {0}")
    @MethodSource("transicoesValidasDaTabela")
    @DisplayName("AC-003.10 cada linha da tabela que parte de ENVIADO é uma transição aceita")
    void ac_003_10_transicao_valida_da_tabela(
            String linha, Transicao transicao, InvitationStatus para) {
        Invitation inv = sent(clock);

        transicao.aplicar(inv, clock);

        assertThat(inv.getStatus()).isEqualTo(para);
    }

    static Stream<Arguments> criacoesDaTabela() {
        return Stream.of(
                Arguments.of("AC-003.1 Responsável convida", InvitationOrigin.MANUAL),
                Arguments.of("AC-003.4 / AC-003.12 Responsável reenvia", InvitationOrigin.MANUAL),
                Arguments.of("AC-003.3 reenvio automático", InvitationOrigin.AUTO));
    }

    @ParameterizedTest(name = "AC-003.10 (não existe) -> ENVIADO: {0} ({1})")
    @MethodSource("criacoesDaTabela")
    @DisplayName("AC-003.10 (não existe) -> ENVIADO: envio e reenvio criam convite ENVIADO")
    void ac_003_10_criacao_nasce_enviado(String linha, InvitationOrigin origin) {
        Invitation inv =
                Invitation.send(GROUP_ID, EMAIL, CODE_HASH, origin, ADMIN_ID, clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
        assertThat(inv.getOrigin()).isEqualTo(origin);
    }

    // ------------------------------------------- criação: campos

    @Test
    @DisplayName("AC-003.1 / D-04 envio guarda grupo, e-mail, hash do código e quem convidou")
    void ac_003_1_envio_guarda_dados_do_convite() {
        Invitation inv = sent(clock);

        assertThat(inv.getId()).isNotNull();
        assertThat(inv.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(inv.getEmail()).isEqualTo(EMAIL);
        assertThat(inv.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(inv.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(inv.getOrigin()).isEqualTo(InvitationOrigin.MANUAL);
    }

    @Test
    @DisplayName("AC-003.2 o prazo de 7 dias começa no envio")
    void ac_003_2_prazo_de_7_dias_comeca_no_envio() {
        Invitation inv = sent(clock);

        assertThat(inv.getCreatedAt()).isEqualTo(T0);
        assertThat(inv.getExpiresAt()).isEqualTo(T0.plus(PRAZO_CONVITE));
    }

    @Test
    @DisplayName("AC-003.10 convite recém-enviado não tem uso nem cancelamento")
    void ac_003_10_convite_enviado_nasce_sem_uso_nem_cancelamento() {
        Invitation inv = sent(clock);

        assertThat(inv.getUsedAt()).isNull();
        assertThat(inv.getCancelledAt()).isNull();
    }

    // ------------------------------------------- AC-003.2 uso único e prazo de 7 dias

    @Test
    @DisplayName("AC-003.2 usar o convite registra quando foi usado")
    void ac_003_2_usar_registra_quando_foi_usado() {
        Invitation inv = sent(clock);
        clock.advance(Duration.ofDays(2));

        inv.use(clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
        assertThat(inv.getUsedAt()).isEqualTo(T0.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("AC-003.2 usar 1 dia antes de vencer é aceito")
    void ac_003_2_usar_um_dia_antes_de_vencer_e_aceito() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE.minusDays(1));

        inv.use(clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
    }

    @Test
    @DisplayName("AC-003.2 usar 1 s antes de completar 7 dias ainda é aceito")
    void ac_003_2_usar_um_segundo_antes_do_prazo_e_aceito() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE.minusSeconds(1));

        inv.use(clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
    }

    @Test
    @DisplayName("AC-003.2 usar com 7 dias exatos falha mesmo com status ainda ENVIADO")
    void ac_003_2_usar_com_7_dias_exatos_falha_mesmo_ainda_enviado() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE);

        assertThatThrownBy(() -> inv.use(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
        assertThat(inv.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("AC-003.2 usar depois de 7 dias falha mesmo com status ainda ENVIADO")
    void ac_003_2_usar_depois_do_prazo_falha_mesmo_ainda_enviado() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE.plusDays(3));

        assertThatThrownBy(() -> inv.use(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
    }

    @Test
    @DisplayName("AC-003.2 usar um convite VENCIDO é rejeitado")
    void ac_003_2_usar_convite_vencido_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.VENCIDO, clock);

        assertThatThrownBy(() -> inv.use(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.VENCIDO);
        assertThat(inv.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("AC-003.2 uso único: usar de novo um convite USADO é rejeitado")
    void ac_003_2_usar_convite_ja_usado_e_rejeitado() {
        Invitation inv = sent(clock);
        inv.use(clock);
        Instant primeiroUso = inv.getUsedAt();
        clock.advance(Duration.ofHours(1));

        assertThatThrownBy(() -> inv.use(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
        assertThat(inv.getUsedAt()).isEqualTo(primeiroUso);
    }

    @Test
    @DisplayName("AC-003.4 usar um convite CANCELADO é rejeitado")
    void ac_003_4_usar_convite_cancelado_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.CANCELADO, clock);

        assertThatThrownBy(() -> inv.use(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
        assertThat(inv.getUsedAt()).isNull();
    }

    // ------------------------------------------- AC-003.2 expiração (job, D-09)

    @Test
    @DisplayName("AC-003.2 expirar com 7 dias exatos desde o envio é aceito")
    void ac_003_2_expirar_com_7_dias_exatos_e_aceito() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE);

        inv.expire(clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.VENCIDO);
    }

    @Test
    @DisplayName("AC-003.2 expirar antes de completar 7 dias é rejeitado")
    void ac_003_2_expirar_antes_de_7_dias_e_rejeitado() {
        Invitation inv = sent(clock);
        clock.advance(PRAZO_CONVITE.minusSeconds(1));

        assertThatThrownBy(() -> inv.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
    }

    @Test
    @DisplayName("AC-003.10 expirar um convite USADO é rejeitado")
    void ac_003_10_expirar_convite_usado_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.USADO, clock);
        clock.advance(PRAZO_CONVITE.multipliedBy(2));

        assertThatThrownBy(() -> inv.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
    }

    @Test
    @DisplayName("AC-003.10 expirar um convite CANCELADO é rejeitado")
    void ac_003_10_expirar_convite_cancelado_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.CANCELADO, clock);
        clock.advance(PRAZO_CONVITE.multipliedBy(2));

        assertThatThrownBy(() -> inv.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
    }

    @Test
    @DisplayName("AC-003.10 expirar de novo um convite VENCIDO é rejeitado")
    void ac_003_10_expirar_convite_ja_vencido_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.VENCIDO, clock);
        clock.advance(PRAZO_CONVITE);

        assertThatThrownBy(() -> inv.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.VENCIDO);
    }

    // ------------------------------------------- AC-003.4 cancelar

    @Test
    @DisplayName("AC-003.4 cancelar um convite ENVIADO registra quando foi cancelado")
    void ac_003_4_cancelar_registra_quando_foi_cancelado() {
        Invitation inv = sent(clock);
        clock.advance(Duration.ofHours(5));

        inv.cancel(clock);

        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
        assertThat(inv.getCancelledAt()).isEqualTo(T0.plus(Duration.ofHours(5)));
    }

    @Test
    @DisplayName("AC-003.4 cancelar um convite USADO é rejeitado")
    void ac_003_4_cancelar_convite_usado_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.USADO, clock);

        assertThatThrownBy(() -> inv.cancel(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.USADO);
        assertThat(inv.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("AC-003.4 / AC-003.10 cancelar um convite VENCIDO é rejeitado")
    void ac_003_4_cancelar_convite_vencido_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.VENCIDO, clock);

        assertThatThrownBy(() -> inv.cancel(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.VENCIDO);
        assertThat(inv.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("AC-003.4 / AC-003.10 cancelar de novo um convite CANCELADO é rejeitado")
    void ac_003_4_cancelar_convite_ja_cancelado_e_rejeitado() {
        Invitation inv = invitationIn(InvitationStatus.CANCELADO, clock);
        Instant primeiroCancelamento = inv.getCancelledAt();
        clock.advance(Duration.ofHours(1));

        assertThatThrownBy(() -> inv.cancel(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(inv.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
        assertThat(inv.getCancelledAt()).isEqualTo(primeiroCancelamento);
    }

    // ------------------------------------------- D-31 / AC-003.4 / AC-003.12 reenvio

    @Test
    @DisplayName("D-31 / AC-003.4 reenviar um convite ENVIADO cancela o anterior")
    void d_31_reenviar_convite_enviado_cancela_o_anterior() {
        Invitation anterior = sent(clock);
        clock.advance(Duration.ofDays(2));

        Invitation.resend(anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThat(anterior.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
        assertThat(anterior.getCancelledAt()).isEqualTo(T0.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName(
            "D-31 / AC-003.10 reenviar convite ENVIADO mas vencido pelo tempo não cancela o"
                    + " anterior")
    void d_31_reenviar_convite_enviado_mas_vencido_pelo_tempo_nao_cancela_o_anterior() {
        Invitation anterior = sent(clock);
        clock.advance(PRAZO_CONVITE);
        assertThat(anterior.getStatus()).as("pré-condição: job ainda não rodou").isEqualTo(
                InvitationStatus.ENVIADO);

        Invitation novo =
                Invitation.resend(
                        anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThat(anterior.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
        assertThat(anterior.getCancelledAt()).isNull();
        assertThat(novo.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
    }

    @Test
    @DisplayName("AC-003.12 convidar e-mail com convite ENVIADO equivale a reenviar: sem erro")
    void ac_003_12_convidar_email_com_convite_enviado_equivale_a_reenviar() {
        Invitation anterior = sent(clock);
        clock.advance(Duration.ofDays(1));

        Invitation novo =
                Invitation.resend(
                        anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThat(anterior.getStatus()).isEqualTo(InvitationStatus.CANCELADO);
        assertThat(novo.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
        assertThat(novo.getEmail()).isEqualTo(EMAIL);
    }

    @ParameterizedTest(name = "AC-003.4 reenviar a partir de {0} cria convite novo com prazo novo")
    @EnumSource(
            value = InvitationStatus.class,
            names = {"ENVIADO", "VENCIDO", "CANCELADO"})
    @DisplayName("AC-003.4 reenviar cria convite novo ENVIADO, código novo e novo prazo de 7 dias")
    void ac_003_4_reenviar_cria_convite_novo_com_codigo_e_prazo_novos(InvitationStatus de) {
        Invitation anterior = invitationIn(de, clock);
        clock.advance(Duration.ofDays(10));
        Instant agora = clock.instant();

        Invitation novo =
                Invitation.resend(
                        anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThat(novo).isNotSameAs(anterior);
        assertThat(novo.getId()).isNotEqualTo(anterior.getId());
        assertThat(novo.getStatus()).isEqualTo(InvitationStatus.ENVIADO);
        assertThat(novo.getCodeHash()).isEqualTo(NEW_CODE_HASH);
        assertThat(novo.getCreatedAt()).isEqualTo(agora);
        assertThat(novo.getExpiresAt()).isEqualTo(agora.plus(PRAZO_CONVITE));
        assertThat(novo.getUsedAt()).isNull();
        assertThat(novo.getCancelledAt()).isNull();
    }

    @ParameterizedTest(name = "AC-003.2 reenviar a partir de {0} mantém grupo e e-mail convidado")
    @EnumSource(
            value = InvitationStatus.class,
            names = {"ENVIADO", "VENCIDO", "CANCELADO"})
    @DisplayName("AC-003.2 / AC-003.4 o convite reenviado vale para o mesmo e-mail e grupo")
    void ac_003_2_reenvio_mantem_grupo_e_email_convidado(InvitationStatus de) {
        Invitation anterior = invitationIn(de, clock);
        UUID outroResponsavel = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

        Invitation novo =
                Invitation.resend(
                        anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, outroResponsavel, clock);

        assertThat(novo.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(novo.getEmail()).isEqualTo(EMAIL);
        assertThat(novo.getCreatedBy()).isEqualTo(outroResponsavel);
    }

    @ParameterizedTest(name = "AC-003.4 reenviar a partir de {0} não altera o anterior")
    @EnumSource(
            value = InvitationStatus.class,
            names = {"VENCIDO", "CANCELADO"})
    @DisplayName("AC-003.4 reenviar convite VENCIDO ou CANCELADO não mexe no anterior (terminal)")
    void ac_003_4_reenviar_vencido_ou_cancelado_nao_altera_o_anterior(InvitationStatus de) {
        Invitation anterior = invitationIn(de, clock);
        Instant cancelledAtAntes = anterior.getCancelledAt();
        clock.advance(Duration.ofDays(1));

        Invitation.resend(anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThat(anterior.getStatus()).isEqualTo(de);
        assertThat(anterior.getCancelledAt()).isEqualTo(cancelledAtAntes);
    }

    @Test
    @DisplayName("AC-003.4 reenviar um convite USADO é rejeitado e não altera o anterior")
    void ac_003_4_reenviar_convite_usado_e_rejeitado() {
        Invitation anterior = invitationIn(InvitationStatus.USADO, clock);

        assertThatThrownBy(
                        () ->
                                Invitation.resend(
                                        anterior,
                                        NEW_CODE_HASH,
                                        InvitationOrigin.MANUAL,
                                        ADMIN_ID,
                                        clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(anterior.getStatus()).isEqualTo(InvitationStatus.USADO);
        assertThat(anterior.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("AC-003.4 o código do convite anterior não serve mais depois do reenvio")
    void ac_003_4_convite_anterior_nao_pode_ser_usado_apos_reenvio() {
        Invitation anterior = sent(clock);
        Invitation novo =
                Invitation.resend(
                        anterior, NEW_CODE_HASH, InvitationOrigin.MANUAL, ADMIN_ID, clock);

        assertThatThrownBy(() -> anterior.use(clock)).isInstanceOf(IllegalStateException.class);
        novo.use(clock);
        assertThat(novo.getStatus()).isEqualTo(InvitationStatus.USADO);
    }

    @ParameterizedTest(name = "D-34 reenvio com origin {0} é registrado no convite novo")
    @EnumSource(InvitationOrigin.class)
    @DisplayName("D-34 / AC-003.3 o convite reenviado registra a origem (MANUAL ou AUTO)")
    void d_34_reenvio_registra_origem(InvitationOrigin origin) {
        Invitation anterior = sent(clock);

        Invitation novo = Invitation.resend(anterior, NEW_CODE_HASH, origin, ADMIN_ID, clock);

        assertThat(novo.getOrigin()).isEqualTo(origin);
    }

    // ------------------------------------------- enums

    @Test
    @DisplayName("AC-003.4 / AC-003.10 InvitationStatus tem exatamente os quatro status do convite")
    void ac_003_10_status_possiveis_do_convite() {
        assertThat(InvitationStatus.values())
                .containsExactlyInAnyOrder(
                        InvitationStatus.ENVIADO,
                        InvitationStatus.USADO,
                        InvitationStatus.VENCIDO,
                        InvitationStatus.CANCELADO);
    }

    @Test
    @DisplayName("D-34 InvitationOrigin tem exatamente MANUAL e AUTO")
    void d_34_origens_possiveis_do_convite() {
        assertThat(InvitationOrigin.values())
                .containsExactlyInAnyOrder(InvitationOrigin.MANUAL, InvitationOrigin.AUTO);
    }
}
