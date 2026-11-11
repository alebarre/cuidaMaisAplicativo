package br.com.cuidamais.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.MembershipStatus;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.shared.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-017 "Pronto quando": um teste por estado, inclusive {@code ATIVO} sem grupo, e um teste de
 * que nenhuma outra combinação é aceita (AC-001.7, AC-012.1).
 *
 * <p>D-33: o estado da conta <strong>não é coluna</strong>; é derivado de
 * {@code users.email_verified_at} e de {@code memberships.status} por um único método,
 * {@code AccountState.of(user, membership)}:
 *
 * <ul>
 *   <li>sem {@code email_verified_at} → {@code AGUARDANDO_CONFIRMACAO_EMAIL};
 *   <li>verificado e sem membership → {@code ATIVO} (criador sem grupo, AC-001.7);
 *   <li>com membership → o {@code status} dela.
 * </ul>
 *
 * <p>Teste puro de domínio: sem Spring, sem banco. As memberships são montadas só pelas fábricas
 * e transições reais de {@link Membership}, com {@link MutableClock} (D-10).
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.auth}):
 *
 * <ul>
 *   <li>{@code static AccountState AccountState.of(User user, Membership membership)} —
 *       {@code membership} nula significa "conta sem vínculo com grupo".
 *   <li>Leitura, com os nomes do schema {@code AccountState} do contrato: {@code state()}
 *       ({@link AccountStateValue}), {@code hasGroup()}, {@code role()} (nulo quando não há papel
 *       em vigor), {@code isAdmin()}. {@code elderId} não é derivável de
 *       {@code (user, membership)}: fica para a T-027.
 *   <li>{@code static User User.of(UUID id, Instant emailVerifiedAt)} (visível no pacote),
 *       {@code getId()}, {@code getEmailVerifiedAt()}, {@code isEmailVerified()}.
 * </ul>
 *
 * <p>Combinação inválida lança {@link IllegalStateException} ou {@link IllegalArgumentException};
 * {@code user} nulo lança {@link NullPointerException} ou {@link IllegalArgumentException}.
 *
 * <p>As linhas de AC-012.1 que terminam em "(descartada)" ou "(excluída)" não são estados: a
 * conta deixa de existir, então não há {@code User} para derivar.
 */
class AccountStateTest {

    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration PRAZO_APROVACAO = Duration.ofHours(24);
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OUTRO_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** Os seis valores de {@code AccountStateValue} do {@code openapi.yaml} (0.2.0). */
    private static final String[] ESTADOS_DO_CONTRATO = {
        "AGUARDANDO_CONFIRMACAO_EMAIL",
        "ATIVO",
        "AGUARDANDO_APROVACAO",
        "RECUSADO",
        "EXPIRADO",
        "REMOVIDO"
    };

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = novoRelogio();
    }

    // ------------------------------------------------------------------ apoio

    private static MutableClock novoRelogio() {
        return new MutableClock(T0, ZoneOffset.UTC);
    }

    /** Conta recém-criada pelo "Criar grupo de cuidado" (AC-001.1): e-mail ainda não confirmado. */
    private static User usuarioSemEmailVerificado() {
        return User.of(USER_ID, null);
    }

    /** Conta com {@code email_verified_at} preenchido (AC-001.2 ou AC-004.3). */
    private static User usuarioComEmailVerificado() {
        return User.of(USER_ID, T0);
    }

    /** Membership de membro comum (não admin) no status pedido, só por transições válidas. */
    private static Membership membroEm(MembershipStatus status, UUID userId, MutableClock clock) {
        Membership m = Membership.request(GROUP_ID, userId, clock);
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

    /** Membership reconvidada (AC-004.6) a partir do status dado. */
    private static Membership reconvidadoDe(MembershipStatus de, MutableClock clock) {
        Membership m = membroEm(de, USER_ID, clock);
        clock.advance(Duration.ofDays(3));
        m.reinstate(clock);
        return m;
    }

    /** Par de entrada de {@code AccountState.of}; {@code membership} nula = sem vínculo. */
    private record Entrada(User user, Membership membership) {}

    @FunctionalInterface
    private interface Cenario {
        Entrada montar(MutableClock clock);
    }

    private record Linha(String descricao, Cenario cenario, AccountStateValue estadoEsperado) {}

    // ------------------------------------------- AC-012.1 tabela: um teste por estado/linha

    /** Uma linha por transição da tabela de AC-012.1 cujo destino é um estado (coluna "Para"). */
    private static final List<Linha> LINHAS_DA_TABELA =
            List.of(
                    new Linha(
                            "AC-001.1 (não existe) -> AGUARDANDO_CONFIRMACAO_EMAIL:"
                                    + " visitante cria grupo",
                            c -> new Entrada(usuarioSemEmailVerificado(), null),
                            AccountStateValue.AGUARDANDO_CONFIRMACAO_EMAIL),
                    new Linha(
                            "AC-001.2 / AC-001.7 AGUARDANDO_CONFIRMACAO_EMAIL -> ATIVO"
                                    + " (criador sem grupo): código de e-mail correto",
                            c -> new Entrada(usuarioComEmailVerificado(), null),
                            AccountStateValue.ATIVO),
                    new Linha(
                            "AC-001.2 ATIVO (criador sem grupo) -> ATIVO (Responsável):"
                                    + " cadastra o idoso",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            Membership.admin(GROUP_ID, USER_ID, c)),
                            AccountStateValue.ATIVO),
                    new Linha(
                            "AC-004.3 (não existe) -> AGUARDANDO_APROVACAO:"
                                    + " convidado conclui o cadastro",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            Membership.request(GROUP_ID, USER_ID, c)),
                            AccountStateValue.AGUARDANDO_APROVACAO),
                    new Linha(
                            "AC-004.6 RECUSADO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            reconvidadoDe(MembershipStatus.RECUSADO, c)),
                            AccountStateValue.AGUARDANDO_APROVACAO),
                    new Linha(
                            "AC-004.6 EXPIRADO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            reconvidadoDe(MembershipStatus.EXPIRADO, c)),
                            AccountStateValue.AGUARDANDO_APROVACAO),
                    new Linha(
                            "AC-004.6 REMOVIDO -> AGUARDANDO_APROVACAO: novo código de convite usado",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            reconvidadoDe(MembershipStatus.REMOVIDO, c)),
                            AccountStateValue.AGUARDANDO_APROVACAO),
                    new Linha(
                            "AC-006.3 AGUARDANDO_APROVACAO -> ATIVO (Cuidador): Responsável aprova",
                            c -> {
                                Membership m = Membership.request(GROUP_ID, USER_ID, c);
                                m.approve(Role.CUIDADOR, ADMIN_ID, c);
                                return new Entrada(usuarioComEmailVerificado(), m);
                            },
                            AccountStateValue.ATIVO),
                    new Linha(
                            "AC-006.3 AGUARDANDO_APROVACAO -> ATIVO (Familiar): Responsável aprova",
                            c -> {
                                Membership m = Membership.request(GROUP_ID, USER_ID, c);
                                m.approve(Role.FAMILIAR, ADMIN_ID, c);
                                return new Entrada(usuarioComEmailVerificado(), m);
                            },
                            AccountStateValue.ATIVO),
                    new Linha(
                            "AC-006.4 AGUARDANDO_APROVACAO -> RECUSADO: Responsável recusa",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            membroEm(MembershipStatus.RECUSADO, USER_ID, c)),
                            AccountStateValue.RECUSADO),
                    new Linha(
                            "AC-006.5 AGUARDANDO_APROVACAO -> EXPIRADO: 24 h sem decisão",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            membroEm(MembershipStatus.EXPIRADO, USER_ID, c)),
                            AccountStateValue.EXPIRADO),
                    new Linha(
                            "AC-009.2 ATIVO (sem administração) -> REMOVIDO: Responsável remove",
                            c ->
                                    new Entrada(
                                            usuarioComEmailVerificado(),
                                            membroEm(MembershipStatus.REMOVIDO, USER_ID, c)),
                            AccountStateValue.REMOVIDO));

    static Stream<Arguments> linhasDaTabela() {
        return LINHAS_DA_TABELA.stream()
                .map(l -> Arguments.of(l.descricao(), l.cenario(), l.estadoEsperado()));
    }

    @ParameterizedTest(name = "AC-012.1 {0}")
    @MethodSource("linhasDaTabela")
    @DisplayName("AC-012.1 o estado derivado é o destino de cada linha da tabela")
    void ac_012_1_estado_derivado_por_linha_da_tabela(
            String linha, Cenario cenario, AccountStateValue estadoEsperado) {
        Entrada entrada = cenario.montar(clock);

        AccountState estado = AccountState.of(entrada.user(), entrada.membership());

        assertThat(estado.state()).isEqualTo(estadoEsperado);
    }

    @ParameterizedTest(name = "AC-012.1 / D-33 com membership {0} o estado da conta é {0}")
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-012.1 / D-33 com membership, o estado da conta é o status dela")
    void ac_012_1_com_membership_o_estado_e_o_status_dela(MembershipStatus status) {
        User user = usuarioComEmailVerificado();
        Membership membership = membroEm(status, USER_ID, clock);

        AccountState estado = AccountState.of(user, membership);

        assertThat(estado.state().name()).isEqualTo(status.name());
    }

    @Test
    @DisplayName("AC-012.1 a tabela alcança exatamente os seis estados do contrato, nenhum a mais")
    void ac_012_1_tabela_alcanca_exatamente_os_seis_estados_do_contrato() {
        Set<String> estadosDerivados =
                LINHAS_DA_TABELA.stream()
                        .map(l -> l.cenario().montar(novoRelogio()))
                        .map(e -> AccountState.of(e.user(), e.membership()).state().name())
                        .collect(Collectors.toSet());

        assertThat(estadosDerivados).containsExactlyInAnyOrder(ESTADOS_DO_CONTRATO);
        assertThat(AccountStateValue.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(ESTADOS_DO_CONTRATO);
    }

    @Test
    @DisplayName("AC-012.1 / D-33 o estado é derivado a cada chamada e acompanha a membership")
    void ac_012_1_estado_acompanha_a_transicao_da_membership() {
        User user = usuarioComEmailVerificado();
        Membership membership = Membership.request(GROUP_ID, USER_ID, clock);
        AccountStateValue antes = AccountState.of(user, membership).state();

        membership.approve(Role.CUIDADOR, ADMIN_ID, clock);
        AccountStateValue depois = AccountState.of(user, membership).state();

        assertThat(antes).isEqualTo(AccountStateValue.AGUARDANDO_APROVACAO);
        assertThat(depois).isEqualTo(AccountStateValue.ATIVO);
    }

    // ------------------------------------------- AC-001.7 hasGroup

    @Test
    @DisplayName("AC-001.7 criador ATIVO que ainda não cadastrou o idoso tem hasGroup = false")
    void ac_001_7_ativo_sem_grupo_tem_has_group_falso() {
        AccountState estado = AccountState.of(usuarioComEmailVerificado(), null);

        assertThat(estado.state()).isEqualTo(AccountStateValue.ATIVO);
        assertThat(estado.hasGroup()).isFalse();
    }

    @Test
    @DisplayName("AC-001.7 depois de cadastrar o idoso o criador é ATIVO com hasGroup = true")
    void ac_001_7_criador_que_cadastrou_o_idoso_tem_has_group_verdadeiro() {
        Membership responsavel = Membership.admin(GROUP_ID, USER_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), responsavel);

        assertThat(estado.state()).isEqualTo(AccountStateValue.ATIVO);
        assertThat(estado.hasGroup()).isTrue();
    }

    @Test
    @DisplayName("AC-012.1 / AC-003.7 conta AGUARDANDO_CONFIRMACAO_EMAIL é conta sem grupo")
    void ac_012_1_aguardando_confirmacao_email_tem_has_group_falso() {
        AccountState estado = AccountState.of(usuarioSemEmailVerificado(), null);

        assertThat(estado.hasGroup()).isFalse();
    }

    @ParameterizedTest(name = "AC-012.1 conta {0} no grupo tem hasGroup = true")
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-012.1 conta em qualquer status no grupo tem hasGroup = true")
    void ac_012_1_qualquer_status_no_grupo_tem_has_group_verdadeiro(MembershipStatus status) {
        Membership membership = membroEm(status, USER_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), membership);

        assertThat(estado.hasGroup()).isTrue();
    }

    // ------------------------------------------- papel e administração (role, isAdmin)

    @Test
    @DisplayName("AC-001.2 / AC-012.1 o Responsável é ATIVO com papel FAMILIAR e isAdmin = true")
    void ac_012_1_responsavel_tem_papel_familiar_e_administracao() {
        Membership responsavel = Membership.admin(GROUP_ID, USER_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), responsavel);

        assertThat(estado.role()).isEqualTo(Role.FAMILIAR);
        assertThat(estado.isAdmin()).isTrue();
    }

    @ParameterizedTest(name = "AC-012.1 membro aprovado como {0} é ATIVO com esse papel, sem admin")
    @EnumSource(Role.class)
    @DisplayName("AC-006.3 / AC-012.1 membro aprovado é ATIVO com o papel escolhido e isAdmin = false")
    void ac_012_1_membro_ativo_tem_o_papel_escolhido_e_nao_e_admin(Role papel) {
        Membership membership = Membership.request(GROUP_ID, USER_ID, clock);
        membership.approve(papel, ADMIN_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), membership);

        assertThat(estado.role()).isEqualTo(papel);
        assertThat(estado.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("AC-001.7 criador ATIVO sem grupo ainda não é Responsável: sem papel e sem admin")
    void ac_001_7_ativo_sem_grupo_nao_tem_papel_nem_administracao() {
        AccountState estado = AccountState.of(usuarioComEmailVerificado(), null);

        assertThat(estado.role()).isNull();
        assertThat(estado.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("AC-012.1 conta AGUARDANDO_CONFIRMACAO_EMAIL não tem papel nem administração")
    void ac_012_1_aguardando_confirmacao_email_nao_tem_papel_nem_administracao() {
        AccountState estado = AccountState.of(usuarioSemEmailVerificado(), null);

        assertThat(estado.role()).isNull();
        assertThat(estado.isAdmin()).isFalse();
    }

    @ParameterizedTest(name = "AC-012.1 conta {0} tem isAdmin = false")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-012.1 / AC-009.4 conta que não está ATIVO no grupo nunca é administradora")
    void ac_012_1_fora_de_ativo_nunca_e_admin(MembershipStatus status) {
        Membership membership = membroEm(status, USER_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), membership);

        assertThat(estado.isAdmin()).isFalse();
    }

    /**
     * O papel só é escolhido na aprovação (AC-006.3) e é escolhido de novo a cada reconvite
     * (AC-004.6); fora de {@code ATIVO} o campo {@code Membership.role} é provisório ou
     * histórico e "nada o lê como válido" (Javadoc de {@link Membership}). Por isso o estado
     * derivado não expõe papel fora de {@code ATIVO}. Interpretação do autor de testes —
     * registrada como ambiguidade no relatório da T-017.
     */
    @ParameterizedTest(name = "AC-012.1 conta {0} não expõe papel")
    @EnumSource(
            value = MembershipStatus.class,
            names = {"AGUARDANDO_APROVACAO", "RECUSADO", "EXPIRADO", "REMOVIDO"})
    @DisplayName("AC-006.3 / AC-004.6 / AC-012.1 fora de ATIVO não há papel em vigor (role nulo)")
    void ac_012_1_fora_de_ativo_nao_expoe_papel(MembershipStatus status) {
        Membership membership = membroEm(status, USER_ID, clock);

        AccountState estado = AccountState.of(usuarioComEmailVerificado(), membership);

        assertThat(estado.role()).isNull();
    }

    // ------------------------------------------- AC-012.1 nenhuma outra combinação é aceita

    @Test
    @DisplayName("AC-012.1 user nulo sem membership é rejeitado")
    void ac_012_1_user_nulo_e_rejeitado() {
        assertThatThrownBy(() -> AccountState.of(null, null))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-012.1 user nulo com membership é rejeitado")
    void ac_012_1_user_nulo_com_membership_e_rejeitado() {
        Membership membership = Membership.request(GROUP_ID, USER_ID, clock);

        assertThatThrownBy(() -> AccountState.of(null, membership))
                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "AC-012.1 e-mail não verificado com membership {0} é rejeitado")
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-012.1 / D-33 conta sem e-mail verificado não pode ter membership")
    void ac_012_1_sem_email_verificado_com_membership_e_rejeitado(MembershipStatus status) {
        User user = usuarioSemEmailVerificado();
        Membership membership = membroEm(status, USER_ID, clock);

        assertThatThrownBy(() -> AccountState.of(user, membership))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-012.1 / D-33 conta sem e-mail verificado não pode ser Responsável de um grupo")
    void ac_012_1_sem_email_verificado_com_membership_de_responsavel_e_rejeitado() {
        User user = usuarioSemEmailVerificado();
        Membership responsavel = Membership.admin(GROUP_ID, USER_ID, clock);

        assertThatThrownBy(() -> AccountState.of(user, responsavel))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "AC-012.1 membership {0} de outra pessoa é rejeitada")
    @EnumSource(MembershipStatus.class)
    @DisplayName("AC-012.1 / D-33 membership de outra pessoa não define o estado desta conta")
    void ac_012_1_membership_de_outro_usuario_e_rejeitada(MembershipStatus status) {
        User user = usuarioComEmailVerificado();
        Membership deOutraPessoa = membroEm(status, OUTRO_USER_ID, clock);

        assertThatThrownBy(() -> AccountState.of(user, deOutraPessoa))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    // ------------------------------------------- D-33 User mínimo (email_verified_at)

    @Test
    @DisplayName("AC-012.1 / D-33 User sem email_verified_at não está com o e-mail verificado")
    void ac_012_1_user_sem_email_verified_at_nao_esta_verificado() {
        User user = User.of(USER_ID, null);

        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("AC-012.1 / D-33 User com email_verified_at está com o e-mail verificado")
    void ac_012_1_user_com_email_verified_at_esta_verificado() {
        User user = User.of(USER_ID, T0);

        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getEmailVerifiedAt()).isEqualTo(T0);
        assertThat(user.isEmailVerified()).isTrue();
    }
}
