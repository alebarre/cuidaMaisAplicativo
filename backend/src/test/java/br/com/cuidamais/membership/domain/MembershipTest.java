package br.com.cuidamais.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.shared.testsupport.MutableClock;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-012 "Pronto quando": um {@code @ParameterizedTest} com cada linha da tabela de AC-012.1 que
 * envolve membership, mais um teste por transição inválida (D-07).
 *
 * <p>D-07 (rev. 2): as transições ficam em métodos da entidade e lançam exceção em transição
 * inválida; a regra é testável <strong>sem banco</strong>. Por isso estes testes instanciam
 * {@link Membership} em memória, sem Spring nem Testcontainers, e controlam o tempo com
 * {@link MutableClock} (D-10).
 *
 * <p>API esperada da entidade (pacote {@code br.com.cuidamais.membership}):
 *
 * <ul>
 *   <li>{@code static Membership request(UUID groupId, UUID userId, Clock clock)} — convidado
 *       conclui o cadastro (AC-004.3): {@code AGUARDANDO_APROVACAO}, {@code requestedAt = agora},
 *       {@code expiresAt = agora + 24 h}, {@code isAdmin = false}.
 *   <li>{@code static Membership admin(UUID groupId, UUID userId, Clock clock)} — Responsável
 *       (D-05, D-29): {@code ATIVO}, {@code FAMILIAR}, {@code isAdmin = true}.
 *   <li>{@code void approve(Role role, UUID decidedBy, Clock clock)} — D-07
 *       {@code approve(role, by, now)}; grava {@code decidedBy}/{@code decidedAt}.
 *   <li>{@code void reject(UUID decidedBy, Clock clock)}
 *   <li>{@code void expire(Clock clock)}
 *   <li>{@code void remove(Clock clock)}
 *   <li>{@code void changeRole(Role newRole)}
 *   <li>{@code void reinstate(Clock clock)} — reconvite (AC-004.6).
 *   <li>Leitura: {@code getGroupId()}, {@code getUserId()}, {@code getStatus()},
 *       {@code getRole()}, {@code isAdmin()}, {@code getRequestedAt()}, {@code getExpiresAt()},
 *       {@code getDecidedBy()}, {@code getDecidedAt()}.
 * </ul>
 *
 * <p>Transição inválida lança {@link IllegalStateException} (ou subclasse — por exemplo uma
 * exceção específica de "pedido expirado" que a camada web mapeie para {@code 410
 * REQUEST_EXPIRED}, D-09). Papel ausente na aprovação/troca lança {@link
 * IllegalArgumentException} ou {@link NullPointerException}.
 *
 * <p><strong>Papel ainda não escolhido:</strong> o papel só é escolhido na aprovação (AC-006.3)
 * e é escolhido de novo após um reconvite (AC-004.6). Estes testes, de propósito,
 * <strong>não</strong> afirmam nada sobre {@code getRole()} enquanto a membership não está
 * {@code ATIVO}; só exigem que o papel passado em {@code approve} seja o que vale. Observação: a
 * coluna {@code memberships.role} da V1 é {@code NOT NULL}, então representar "sem papel" como
 * {@code null} exige mudar o schema/plan (Constitution §7) — decisão em aberto, registrada no
 * relatório da tarefa.
 *
 * <p>Prazo: o pedido vale enquanto {@code agora < expiresAt}; em {@code agora == expiresAt}
 * (24 h exatas) ele já venceu (AC-006.5, AC-006.8).
 */
class MembershipTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration PRAZO_APROVACAO = Duration.ofHours(24);
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ apoio

    /** Monta uma membership de membro comum (não admin) no status pedido, só por transições válidas. */
    private static Membership memberIn(MembershipStatus status, MutableClock clock) {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        switch (status) {
            case AGUARDANDO_APROVACAO -> { }
            case ATIVO -> m.approve(Role.CUIDADOR, ADMIN_ID, clock);
            case RECUSADO -> m.reject(ADMIN_ID, clock);
            case EXPIRADO -> {
                clock.advance(PRAZO_APROVACAO);
                m.expire(clock);
            }
            case REMOVIDO -> {
                m.approve(Role.CUIDADOR, ADMIN_ID, clock);
                m.remove(clock);
            }
        }
        assertThat(m.getStatus()).as("pré-condição do cenário").isEqualTo(status);
        return m;
    }

    @FunctionalInterface
    interface Transicao {
        void aplicar(Membership m, MutableClock clock);
    }

    // ------------------------------------------- AC-012.1 tabela (linhas de membership)

    static Stream<Arguments> transicoesValidasDaTabela() {
        return Stream.of(
                Arguments.of(
                        "AC-006.3 AGUARDANDO_APROVACAO -> ATIVO (Cuidador): Responsável aprova",
                        MembershipStatus.AGUARDANDO_APROVACAO,
                        (Transicao) (m, c) -> m.approve(Role.CUIDADOR, ADMIN_ID, c),
                        MembershipStatus.ATIVO),
                Arguments.of(
                        "AC-006.3 AGUARDANDO_APROVACAO -> ATIVO (Familiar): Responsável aprova",
                        MembershipStatus.AGUARDANDO_APROVACAO,
                        (Transicao) (m, c) -> m.approve(Role.FAMILIAR, ADMIN_ID, c),
                        MembershipStatus.ATIVO),
                Arguments.of(
                        "AC-006.4 AGUARDANDO_APROVACAO -> RECUSADO: Responsável recusa",
                        MembershipStatus.AGUARDANDO_APROVACAO,
                        (Transicao) (m, c) -> m.reject(ADMIN_ID, c),
                        MembershipStatus.RECUSADO),
                Arguments.of(
                        "AC-006.5 AGUARDANDO_APROVACAO -> EXPIRADO: 24 h sem decisão",
                        MembershipStatus.AGUARDANDO_APROVACAO,
                        (Transicao)
                                (m, c) -> {
                                    c.advance(PRAZO_APROVACAO);
                                    m.expire(c);
                                },
                        MembershipStatus.EXPIRADO),
                Arguments.of(
                        "AC-009.2 ATIVO (sem administração) -> REMOVIDO: Responsável remove",
                        MembershipStatus.ATIVO,
                        (Transicao) (m, c) -> m.remove(c),
                        MembershipStatus.REMOVIDO),
                Arguments.of(
                        "AC-004.6 RECUSADO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                        MembershipStatus.RECUSADO,
                        (Transicao) (m, c) -> m.reinstate(c),
                        MembershipStatus.AGUARDANDO_APROVACAO),
                Arguments.of(
                        "AC-004.6 EXPIRADO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                        MembershipStatus.EXPIRADO,
                        (Transicao) (m, c) -> m.reinstate(c),
                        MembershipStatus.AGUARDANDO_APROVACAO),
                Arguments.of(
                        "AC-004.6 REMOVIDO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                        MembershipStatus.REMOVIDO,
                        (Transicao) (m, c) -> m.reinstate(c),
                        MembershipStatus.AGUARDANDO_APROVACAO));
    }

    @ParameterizedTest(name = "AC-012.1 {0}")
    @MethodSource("transicoesValidasDaTabela")
    @DisplayName("AC-012.1 cada linha da tabela que envolve membership é uma transição aceita")
    void ac_012_1_transicao_valida_da_tabela(
            String linha, MembershipStatus de, Transicao transicao, MembershipStatus para) {
        Membership m = memberIn(de, clock);

        transicao.aplicar(m, clock);

        assertThat(m.getStatus()).isEqualTo(para);
    }

    // ------------------------------------------- AC-004.3 criação: (não existe) -> AGUARDANDO

    @Test
    @DisplayName("AC-004.3 / AC-012.1 (não existe) -> AGUARDANDO_APROVACAO ao concluir o cadastro")
    void ac_004_3_cadastro_do_convidado_cria_membership_aguardando_aprovacao() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
        assertThat(m.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(m.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("AC-004.3 prazo de 24 h começa a contar no cadastro")
    void ac_004_3_prazo_de_24h_comeca_no_cadastro() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        assertThat(m.getRequestedAt()).isEqualTo(T0);
        assertThat(m.getExpiresAt()).isEqualTo(T0.plus(PRAZO_APROVACAO));
    }

    @Test
    @DisplayName("AC-004.3 / AC-009.4 membership de convidado nasce sem administração")
    void ac_004_3_membership_de_convidado_nasce_sem_administracao() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        assertThat(m.isAdmin()).isFalse();
        assertThat(m.getDecidedAt()).isNull();
        assertThat(m.getDecidedBy()).isNull();
    }

    // ------------------------------------------- D-05 / D-29 membership do Responsável

    @Test
    @DisplayName("D-05 / D-29 membership do Responsável nasce ATIVO, FAMILIAR e admin")
    void d_05_membership_do_responsavel_nasce_ativa_familiar_admin() {
        Membership m = Membership.admin(GROUP_ID, ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
        assertThat(m.getRole()).isEqualTo(Role.FAMILIAR);
        assertThat(m.isAdmin()).isTrue();
        assertThat(m.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(m.getUserId()).isEqualTo(ADMIN_ID);
        assertThat(m.getRequestedAt()).isEqualTo(T0);
    }

    // ------------------------------------------- AC-006.3 aprovar exige escolher o papel

    @ParameterizedTest(name = "AC-006.3 aprovar escolhendo {0} fica ATIVO com esse papel")
    @EnumSource(Role.class)
    @DisplayName("AC-006.3 aprovar escolhendo o papel (Cuidador ou Familiar) fica ATIVO com ele")
    void ac_006_3_aprovar_escolhendo_papel_fica_ativo_com_esse_papel(Role papel) {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        m.approve(papel, ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
        assertThat(m.getRole()).isEqualTo(papel);
        assertThat(m.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("AC-006.3 Role tem exatamente os papéis Cuidador e Familiar (D-29)")
    void ac_006_3_papeis_possiveis_sao_cuidador_e_familiar() {
        assertThat(Role.values()).containsExactlyInAnyOrder(Role.CUIDADOR, Role.FAMILIAR);
    }

    @Test
    @DisplayName("AC-006.3 aprovar sem escolher o papel é rejeitado e não muda o status")
    void ac_006_3_aprovar_sem_papel_e_rejeitado() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        assertThatThrownBy(() -> m.approve(null, ADMIN_ID, clock))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
    }

    @Test
    @DisplayName("AC-006.3 / D-07 aprovar registra quem decidiu e quando")
    void ac_006_3_aprovar_registra_quem_decidiu_e_quando() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(Duration.ofHours(3));

        m.approve(Role.FAMILIAR, ADMIN_ID, clock);

        assertThat(m.getDecidedBy()).isEqualTo(ADMIN_ID);
        assertThat(m.getDecidedAt()).isEqualTo(T0.plus(Duration.ofHours(3)));
    }

    @Test
    @DisplayName("AC-006.3 aprovar 1 s antes de vencer as 24 h ainda é aceito")
    void ac_006_3_aprovar_um_segundo_antes_do_prazo_e_aceito() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO.minusSeconds(1));

        m.approve(Role.CUIDADOR, ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
    }

    // ------------------------------------------- AC-006.4 recusar

    @Test
    @DisplayName("AC-006.4 / D-07 recusar registra quem decidiu e quando")
    void ac_006_4_recusar_registra_quem_decidiu_e_quando() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(Duration.ofHours(5));

        m.reject(ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.RECUSADO);
        assertThat(m.getDecidedBy()).isEqualTo(ADMIN_ID);
        assertThat(m.getDecidedAt()).isEqualTo(T0.plus(Duration.ofHours(5)));
    }

    // ------------------------------------------- AC-006.5 / AC-006.8 prazo de 24 h

    @Test
    @DisplayName("AC-006.5 expirar antes de completar 24 h é rejeitado")
    void ac_006_5_expirar_antes_de_24h_e_rejeitado() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO.minusSeconds(1));

        assertThatThrownBy(() -> m.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
    }

    @Test
    @DisplayName("AC-006.8 aprovar pedido com 24 h vencidas falha mesmo com status ainda pendente")
    void ac_006_8_aprovar_pedido_vencido_ainda_pendente_falha() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO);

        assertThatThrownBy(() -> m.approve(Role.CUIDADOR, ADMIN_ID, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
    }

    @Test
    @DisplayName("AC-006.8 recusar pedido com 24 h vencidas falha mesmo com status ainda pendente")
    void ac_006_8_recusar_pedido_vencido_ainda_pendente_falha() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO);

        assertThatThrownBy(() -> m.reject(ADMIN_ID, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
    }

    // ------------------------------------------- AC-004.6 reconvite (reinstate)

    @ParameterizedTest(name = "AC-004.6 reconvite a partir de {0} recomeça o prazo de 24 h")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-004.6 reconvite volta para AGUARDANDO_APROVACAO e recomeça o prazo de 24 h")
    void ac_004_6_reconvite_recomeca_prazo_de_24h(MembershipStatus de) {
        Membership m = memberIn(de, clock);
        clock.advance(Duration.ofDays(3));
        Instant agora = clock.instant();

        m.reinstate(clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
        assertThat(m.getRequestedAt()).isEqualTo(agora);
        assertThat(m.getExpiresAt()).isEqualTo(agora.plus(PRAZO_APROVACAO));
    }

    @ParameterizedTest(name = "AC-004.6 após reconvite a partir de {0} o novo prazo vale para aprovar")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-004.6 / AC-006.8 após reconvite, aprovar dentro do novo prazo é aceito")
    void ac_004_6_apos_reconvite_aprovar_dentro_do_novo_prazo_e_aceito(MembershipStatus de) {
        Membership m = memberIn(de, clock);
        clock.advance(Duration.ofDays(3));
        m.reinstate(clock);
        clock.advance(PRAZO_APROVACAO.minusSeconds(1));

        m.approve(Role.FAMILIAR, ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
    }

    @Test
    @DisplayName("AC-004.6 após reconvite, aprovar depois do novo prazo de 24 h falha")
    void ac_004_6_apos_reconvite_aprovar_depois_do_novo_prazo_falha() {
        Membership m = memberIn(MembershipStatus.RECUSADO, clock);
        clock.advance(Duration.ofDays(3));
        m.reinstate(clock);
        clock.advance(PRAZO_APROVACAO);

        assertThatThrownBy(() -> m.approve(Role.FAMILIAR, ADMIN_ID, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-004.6 / AC-006.3 após reconvite o Responsável escolhe o papel de novo")
    void ac_004_6_apos_reconvite_papel_e_escolhido_de_novo() {
        // Era CUIDADOR antes de ser removido; no reconvite o Responsável escolhe FAMILIAR.
        Membership m = memberIn(MembershipStatus.REMOVIDO, clock);
        m.reinstate(clock);

        m.approve(Role.FAMILIAR, ADMIN_ID, clock);

        assertThat(m.getRole()).isEqualTo(Role.FAMILIAR);
    }

    @Test
    @DisplayName("AC-004.6 / AC-009.4 reconvite não torna ninguém administrador")
    void ac_004_6_reconvite_mantem_sem_administracao() {
        Membership m = memberIn(MembershipStatus.REMOVIDO, clock);

        m.reinstate(clock);

        assertThat(m.isAdmin()).isFalse();
    }

    // ------------------------------------------- AC-009.1 troca de papel

    @Test
    @DisplayName("AC-009.1 Responsável troca o papel de Cuidador para Familiar")
    void ac_009_1_troca_papel_de_cuidador_para_familiar() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        m.approve(Role.CUIDADOR, ADMIN_ID, clock);

        m.changeRole(Role.FAMILIAR);

        assertThat(m.getRole()).isEqualTo(Role.FAMILIAR);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
    }

    @Test
    @DisplayName("AC-009.1 Responsável troca o papel de Familiar para Cuidador")
    void ac_009_1_troca_papel_de_familiar_para_cuidador() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        m.approve(Role.FAMILIAR, ADMIN_ID, clock);

        m.changeRole(Role.CUIDADOR);

        assertThat(m.getRole()).isEqualTo(Role.CUIDADOR);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.ATIVO);
    }

    @Test
    @DisplayName("AC-009.4 trocar o papel para Familiar não promove o membro a Responsável")
    void ac_009_4_trocar_para_familiar_nao_promove_a_responsavel() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        m.approve(Role.CUIDADOR, ADMIN_ID, clock);

        m.changeRole(Role.FAMILIAR);

        assertThat(m.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("AC-009.1 trocar para papel nulo é rejeitado")
    void ac_009_1_trocar_para_papel_nulo_e_rejeitado() {
        Membership m = memberIn(MembershipStatus.ATIVO, clock);

        assertThatThrownBy(() -> m.changeRole(null))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThat(m.getRole()).isEqualTo(Role.CUIDADOR);
    }

    // ------------------------------------------- AC-009.4 Responsável protegido

    @Test
    @DisplayName("AC-009.4 remover o Responsável (admin) lança exceção")
    void ac_009_4_remover_admin_lanca_excecao() {
        Membership admin = Membership.admin(GROUP_ID, ADMIN_ID, clock);

        assertThatThrownBy(() -> admin.remove(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(admin.getStatus()).isEqualTo(MembershipStatus.ATIVO);
        assertThat(admin.isAdmin()).isTrue();
    }

    @ParameterizedTest(name = "AC-009.4 trocar o papel do Responsável para {0} lança exceção")
    @EnumSource(Role.class)
    @DisplayName("AC-009.4 trocar o papel do Responsável (admin) lança exceção")
    void ac_009_4_trocar_papel_do_admin_lanca_excecao(Role novoPapel) {
        Membership admin = Membership.admin(GROUP_ID, ADMIN_ID, clock);

        assertThatThrownBy(() -> admin.changeRole(novoPapel))
                .isInstanceOf(IllegalStateException.class);
        assertThat(admin.getRole()).isEqualTo(Role.FAMILIAR);
        assertThat(admin.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("AC-009.4 changeRole só aceita um Role: não há como tornar admin um Cuidador")
    void ac_009_4_change_role_nao_tem_sobrecarga_que_altere_admin() {
        List<Method> changeRoles =
                Arrays.stream(Membership.class.getMethods())
                        .filter(method -> method.getName().equals("changeRole"))
                        .toList();

        assertThat(changeRoles).isNotEmpty();
        assertThat(changeRoles)
                .allSatisfy(
                        method ->
                                assertThat(method.getParameterTypes())
                                        .containsExactly(Role.class));
    }

    @Test
    @DisplayName("AC-009.4 nenhum método público de instância recebe flag de administração")
    void ac_009_4_nenhum_metodo_publico_promove_a_responsavel() {
        List<Method> recebemBoolean =
                Arrays.stream(Membership.class.getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getDeclaringClass() == Membership.class)
                        .filter(
                                method ->
                                        Arrays.stream(method.getParameterTypes())
                                                .anyMatch(
                                                        type ->
                                                                type == boolean.class
                                                                        || type == Boolean.class))
                        .toList();

        assertThat(recebemBoolean)
                .as("nenhum membro pode ser promovido a Responsável (AC-009.4)")
                .isEmpty();
    }

    // ------------------------------------------- AC-012.1 / D-07 transições inválidas

    @ParameterizedTest(name = "D-07 aprovar a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"ATIVO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / D-07 aprovar fora de AGUARDANDO_APROVACAO lança exceção")
    void ac_012_1_aprovar_fora_de_aguardando_aprovacao_lanca_excecao(MembershipStatus de) {
        Membership m = memberIn(de, clock);

        assertThatThrownBy(() -> m.approve(Role.FAMILIAR, ADMIN_ID, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @ParameterizedTest(name = "D-07 recusar a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"ATIVO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / D-07 recusar fora de AGUARDANDO_APROVACAO lança exceção")
    void ac_012_1_recusar_fora_de_aguardando_aprovacao_lanca_excecao(MembershipStatus de) {
        Membership m = memberIn(de, clock);

        assertThatThrownBy(() -> m.reject(ADMIN_ID, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @ParameterizedTest(name = "D-07 expirar a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"ATIVO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / D-07 expirar fora de AGUARDANDO_APROVACAO lança exceção")
    void ac_012_1_expirar_fora_de_aguardando_aprovacao_lanca_excecao(MembershipStatus de) {
        Membership m = memberIn(de, clock);
        clock.advance(PRAZO_APROVACAO.multipliedBy(2));

        assertThatThrownBy(() -> m.expire(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @ParameterizedTest(name = "D-07 remover a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / D-07 remover fora de ATIVO lança exceção")
    void ac_012_1_remover_fora_de_ativo_lanca_excecao(MembershipStatus de) {
        Membership m = memberIn(de, clock);

        assertThatThrownBy(() -> m.remove(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @ParameterizedTest(name = "D-07 reinstaurar a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"AGUARDANDO_APROVACAO", "ATIVO"})
    @DisplayName("AC-012.1 / AC-004.6 reinstaurar fora de RECUSADO/EXPIRADO/REMOVIDO lança exceção")
    void ac_012_1_reinstaurar_fora_de_recusado_expirado_removido_lanca_excecao(
            MembershipStatus de) {
        Membership m = memberIn(de, clock);

        assertThatThrownBy(() -> m.reinstate(clock)).isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @Test
    @DisplayName("AC-012.1 / AC-009.4 reinstaurar o Responsável (ATIVO) lança exceção")
    void ac_012_1_reinstaurar_admin_lanca_excecao() {
        Membership admin = Membership.admin(GROUP_ID, ADMIN_ID, clock);

        assertThatThrownBy(() -> admin.reinstate(clock))
                .isInstanceOf(IllegalStateException.class);
        assertThat(admin.getStatus()).isEqualTo(MembershipStatus.ATIVO);
        assertThat(admin.isAdmin()).isTrue();
    }

    @ParameterizedTest(name = "AC-009.1 trocar papel a partir de {0} lança exceção")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-009.1 / D-07 trocar papel de quem não é membro ATIVO lança exceção")
    void ac_009_1_trocar_papel_fora_de_ativo_lanca_excecao(MembershipStatus de) {
        Membership m = memberIn(de, clock);

        assertThatThrownBy(() -> m.changeRole(Role.FAMILIAR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(m.getStatus()).isEqualTo(de);
    }

    @Test
    @DisplayName("AC-012.1 MembershipStatus tem exatamente os cinco status de conta no grupo")
    void ac_012_1_status_possiveis_da_membership() {
        assertThat(MembershipStatus.values())
                .containsExactlyInAnyOrder(
                        MembershipStatus.AGUARDANDO_APROVACAO,
                        MembershipStatus.ATIVO,
                        MembershipStatus.RECUSADO,
                        MembershipStatus.EXPIRADO,
                        MembershipStatus.REMOVIDO);
    }

    // ------------------------------------------------------------------ T-046: retention_deadline na expiração
    //
    // API nova esperada da entidade (criada pelo implementador da T-046): `Instant
    // getRetentionDeadline()` — o valor da coluna `memberships.retention_deadline` (V1__foundation.sql,
    // TIMESTAMPTZ NULL), que a entidade passa a mapear. Nulo enquanto a membership não expirou;
    // `expire(Clock)` o grava como `clock.instant() + 30 dias` (D-09 rev. 2 item 1, AC-014.1). A
    // contagem parte do instante em que `expire` é chamado (o "agora" do job), não de `expiresAt`.

    /**
     * AC-014.1: um pedido recém-criado (e o Responsável) ainda não tem prazo de retenção; a coluna só
     * é preenchida na expiração.
     */
    @Test
    @DisplayName("AC-014.1 retention_deadline é nulo em request() e admin()")
    void ac_014_1_retention_deadline_nasce_nulo() {
        assertThat(Membership.request(GROUP_ID, USER_ID, clock).getRetentionDeadline()).isNull();
        assertThat(Membership.admin(GROUP_ID, ADMIN_ID, clock).getRetentionDeadline()).isNull();
    }

    /**
     * AC-014.1 / AC-006.5: expirar no instante exato do vencimento (24 h) grava retention_deadline =
     * esse instante + 30 dias exatos.
     */
    @Test
    @DisplayName("AC-014.1 expire(clock) grava retention_deadline = agora + 30 dias")
    void ac_014_1_expire_grava_retention_deadline_em_30_dias() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO);

        m.expire(clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.EXPIRADO);
        assertThat(m.getRetentionDeadline()).isEqualTo(T0.plus(PRAZO_APROVACAO).plus(Duration.ofDays(30)));
    }

    /**
     * AC-014.1: se a expiração ocorre atrasada (26 h depois do pedido), a retenção conta a partir do
     * "agora" da chamada, não de expiresAt.
     */
    @Test
    @DisplayName("AC-014.1 expire(clock) atrasado conta os 30 dias do instante da chamada, não de expiresAt")
    void ac_014_1_expire_atrasado_conta_do_instante_da_chamada() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(Duration.ofHours(26));

        m.expire(clock);

        assertThat(m.getRetentionDeadline()).isEqualTo(T0.plus(Duration.ofHours(26)).plus(Duration.ofDays(30)));
        assertThat(m.getRetentionDeadline()).isNotEqualTo(m.getExpiresAt().plus(Duration.ofDays(30)));
    }

    /**
     * AC-006.5: ninguém decidiu o pedido expirado, então expire() não grava decidedBy nem decidedAt.
     */
    @Test
    @DisplayName("AC-006.5 expire(clock) não preenche decidedBy nem decidedAt")
    void ac_006_5_expire_nao_preenche_decisor() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO);

        m.expire(clock);

        assertThat(m.getDecidedBy()).isNull();
        assertThat(m.getDecidedAt()).isNull();
    }

    /**
     * AC-006.5: expirar antes de completar 24 h continua rejeitado (IllegalStateException) e NÃO
     * deixa retention_deadline gravado.
     */
    @Test
    @DisplayName("AC-006.5 expire(clock) com prazo não vencido continua rejeitado e não grava retention_deadline")
    void ac_006_5_expire_antes_do_prazo_nao_grava_retention_deadline() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO.minusMillis(1));

        assertThatThrownBy(() -> m.expire(clock)).isInstanceOf(IllegalStateException.class);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
        assertThat(m.getRetentionDeadline()).isNull();
    }

    /**
     * D-07: expirar a partir de ATIVO, RECUSADO, EXPIRADO ou REMOVIDO continua lançando
     * IllegalStateException e não altera retention_deadline (para EXPIRADO, o valor da 1a expiração é
     * preservado; para os demais segue nulo). Cada linha: o estado de partida.
     */
    @ParameterizedTest(name = "AC-012.1 expirar a partir de {0} não altera retention_deadline")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"ATIVO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / D-07 expirar fora de AGUARDANDO_APROVACAO lança exceção e preserva retention_deadline")
    void ac_012_1_expirar_fora_de_aguardando_nao_altera_retention_deadline(MembershipStatus de) {
        Membership m = memberIn(de, clock);
        Instant antes = m.getRetentionDeadline();
        clock.advance(PRAZO_APROVACAO.multipliedBy(2));

        assertThatThrownBy(() -> m.expire(clock)).isInstanceOf(IllegalStateException.class);

        assertThat(m.getStatus()).isEqualTo(de);
        assertThat(m.getRetentionDeadline()).isEqualTo(antes);
        // T-049 (AC-014.1): RECUSADO passa a ter retention_deadline, como EXPIRADO; só quem nunca foi
        // recusado nem expirou (ATIVO, REMOVIDO) segue sem retenção.
        if (de == MembershipStatus.ATIVO || de == MembershipStatus.REMOVIDO) {
            assertThat(m.getRetentionDeadline()).as("só RECUSADO/EXPIRADO têm retenção").isNull();
        }
    }

    // ------------------------------------------------------------------ T-049: retention_deadline na recusa

    /**
     * AC-014.1 (T-049, fecha a lacuna do reject da T-045): recusar grava retention_deadline = o
     * instante da recusa + 30 dias exatos, como {@code expire} já faz. Cada linha: quantas horas
     * depois do pedido o Responsável recusa (a contagem parte da recusa, não de requestedAt nem de
     * expiresAt).
     */
    @ParameterizedTest(name = "AC-014.1 recusar {0} h depois do pedido grava retention_deadline = recusa + 30 dias")
    @ValueSource(longs = {0, 1, 23})
    @DisplayName("AC-014.1 reject(decidedBy, clock) grava retention_deadline = agora + 30 dias")
    void ac_014_1_reject_grava_retention_deadline_em_30_dias(long horasDepoisDoPedido) {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        assertThat(m.getRetentionDeadline()).as("pré-condição: nulo antes da recusa").isNull();
        // Cenário: a recusa acontece horasDepoisDoPedido h depois do pedido (linhas 0, 1 e 23 h).
        clock.advance(Duration.ofHours(horasDepoisDoPedido));
        Instant instanteDaRecusa = clock.instant();

        m.reject(ADMIN_ID, clock);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.RECUSADO);
        assertThat(m.getRetentionDeadline()).isEqualTo(instanteDaRecusa.plus(Duration.ofDays(30)));
    }

    /**
     * AC-014.1: a recusa também grava decidedAt, e o prazo de retenção é decidedAt + 30 dias (a mesma
     * data base que a rotina usa como fallback para RECUSADO legado, sem retention_deadline).
     */
    @Test
    @DisplayName("AC-014.1 retention_deadline da recusa = decidedAt + 30 dias")
    void ac_014_1_reject_retention_deadline_e_decided_at_mais_30_dias() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(Duration.ofHours(7));

        m.reject(ADMIN_ID, clock);

        assertThat(m.getRetentionDeadline()).isEqualTo(m.getDecidedAt().plus(Duration.ofDays(30)));
    }

    /**
     * AC-006.8 / D-07: recusar um pedido vencido (24 h exatas) continua rejeitado e não grava
     * retention_deadline nem muda o status (uma chamada rejeitada não altera nenhum campo).
     */
    @Test
    @DisplayName("AC-006.8 reject() de pedido vencido lança exceção e não grava retention_deadline")
    void ac_006_8_reject_vencido_nao_grava_retention_deadline() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);
        clock.advance(PRAZO_APROVACAO);

        assertThatThrownBy(() -> m.reject(ADMIN_ID, clock)).isInstanceOf(IllegalStateException.class);

        assertThat(m.getStatus()).isEqualTo(MembershipStatus.AGUARDANDO_APROVACAO);
        assertThat(m.getRetentionDeadline()).isNull();
    }

    /**
     * D-07: recusar de novo um RECUSADO/EXPIRADO (ou recusar ATIVO/REMOVIDO) lança exceção e preserva
     * o retention_deadline da 1a transição (para ATIVO/REMOVIDO segue nulo). Cada linha: o estado de partida.
     */
    @ParameterizedTest(name = "AC-014.1 recusar a partir de {0} não altera retention_deadline")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"ATIVO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-014.1 / D-07 reject() fora de AGUARDANDO_APROVACAO lança exceção e preserva retention_deadline")
    void ac_014_1_reject_fora_de_aguardando_preserva_retention_deadline(MembershipStatus de) {
        Membership m = memberIn(de, clock);
        Instant antes = m.getRetentionDeadline();
        clock.advance(Duration.ofDays(3));

        assertThatThrownBy(() -> m.reject(ADMIN_ID, clock)).isInstanceOf(IllegalStateException.class);

        assertThat(m.getStatus()).isEqualTo(de);
        assertThat(m.getRetentionDeadline()).isEqualTo(antes);
    }

    // ------------------------------------------------------------------ T-049: AC-014.2 REMOVIDO não tem retenção

    /**
     * AC-014.2: aprovar e remover um membro NÃO gravam retention_deadline (a conta REMOVIDO nunca é
     * excluída automaticamente; a coluna fica nula).
     */
    @Test
    @DisplayName("AC-014.2 aprovar e remover não gravam retention_deadline (REMOVIDO nunca é excluído)")
    void ac_014_2_aprovar_e_remover_nao_gravam_retention_deadline() {
        Membership m = Membership.request(GROUP_ID, USER_ID, clock);

        // Etapa 1: aprovado.
        m.approve(Role.CUIDADOR, ADMIN_ID, clock);
        assertThat(m.getRetentionDeadline()).as("ATIVO sem retenção").isNull();

        // Etapa 2: removido.
        m.remove(clock);
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.REMOVIDO);
        assertThat(m.getRetentionDeadline()).as("REMOVIDO sem retenção").isNull();
    }
}
