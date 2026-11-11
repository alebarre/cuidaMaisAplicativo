package br.com.cuidamais.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.cuidamais.membership.domain.Role;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-015 "Pronto quando": {@code @ParameterizedTest} com <strong>cada célula</strong> da matriz de
 * AC-011.5, para os três campos de contato (endereço, telefone e e-mail — AC-011.8).
 *
 * <p>Matriz da spec (AC-011.5), válida entre membros {@code ATIVO}:
 *
 * <pre>
 * | Quem vê ↓ / contato de → | si mesmo | Cuidadores | Familiares (inclui o Responsável) |
 * | Cuidador                 | sim      | não        | não                               |
 * | Familiar (inclui Resp.)  | sim      | sim        | sim                               |
 * </pre>
 *
 * <p>D-28 (rev. 2): a regra fica numa <strong>única</strong> política, {@code
 * canSeeContact(viewer, target)} = "é o próprio <strong>ou</strong> o viewer é {@code FAMILIAR}",
 * aplicada a endereço, telefone e e-mail <strong>de uma vez</strong>. Não há regra diferente por
 * campo: AC-011.8 diz que telefone e e-mail seguem "a mesma matriz de AC-011.5". Por isso a tabela
 * de teste é o produto cartesiano (célula da matriz × campo), e para os três campos a decisão vem
 * da <strong>mesma chamada</strong> — o enum {@link ContactField} existe só no teste, para deixar
 * explícito que cada célula foi verificada para cada campo.
 *
 * <p>D-29: o Responsável é sempre {@code FAMILIAR} com {@code is_admin = true}; por isso "Familiar
 * (inclui o Responsável)" está coberto por "viewer é FAMILIAR", sem caso especial. Filtrar membros
 * não {@code ATIVO} é de outra camada (AC-006.9, AC-009.5) e não é testado aqui.
 *
 * <p>API esperada (pacote {@code br.com.cuidamais.membership}):
 *
 * <ul>
 *   <li>{@code public final class MemberVisibilityPolicy} sem estado.
 *   <li>{@code public record Viewer(UUID userId, Role role)} aninhado em {@code
 *       MemberVisibilityPolicy} — quem está olhando: identificador (para "é o próprio") e papel
 *       (reaproveita {@link Role}).
 *   <li>{@code public static boolean canSeeContact(Viewer viewer, UUID targetUserId)} — o papel
 *       do target não entra na fórmula; as colunas "Cuidadores"/"Familiares" da matriz são
 *       exercitadas variando o papel do target nos dados de teste, para provar que ele não muda
 *       o resultado.
 * </ul>
 */
class MemberVisibilityPolicyTest {

    /** Os três campos de contato de um membro regidos pela matriz (AC-011.5 e AC-011.8). */
    enum ContactField {
        ENDERECO,
        TELEFONE,
        EMAIL
    }

    /**
     * Cada linha = uma célula da matriz: papel do viewer, se o target é o próprio viewer, papel do
     * target (quando é o próprio, é o mesmo do viewer) e o resultado esperado.
     */
    private static final Object[][] MATRIZ = {
        // AC-011.5/AC-011.8 linha "Cuidador", coluna "si mesmo" -> sim
        {Role.CUIDADOR, true, Role.CUIDADOR, true},
        // AC-011.5/AC-011.8 linha "Cuidador", coluna "Cuidadores" -> não
        {Role.CUIDADOR, false, Role.CUIDADOR, false},
        // AC-011.5/AC-011.8 linha "Cuidador", coluna "Familiares (inclui o Responsável)" -> não
        {Role.CUIDADOR, false, Role.FAMILIAR, false},
        // AC-011.5/AC-011.8 linha "Familiar (inclui o Responsável)", coluna "si mesmo" -> sim
        {Role.FAMILIAR, true, Role.FAMILIAR, true},
        // AC-011.5/AC-011.8 linha "Familiar (inclui o Responsável)", coluna "Cuidadores" -> sim
        {Role.FAMILIAR, false, Role.CUIDADOR, true},
        // AC-011.5/AC-011.8 linha "Familiar (inclui o Responsável)", coluna "Familiares" -> sim
        {Role.FAMILIAR, false, Role.FAMILIAR, true},
    };

    static Stream<Arguments> matrizPorCampo() {
        return Arrays.stream(MATRIZ)
                .flatMap(
                        celula ->
                                Arrays.stream(ContactField.values())
                                        .map(
                                                campo ->
                                                        Arguments.of(
                                                                celula[0],
                                                                celula[1],
                                                                celula[2],
                                                                campo,
                                                                celula[3])));
    }

    @ParameterizedTest(
            name =
                    "AC-011.5/AC-011.8 viewer {0}, é o próprio = {1}, target {2}, campo {3} -> vê ="
                            + " {4}")
    @MethodSource("matrizPorCampo")
    @DisplayName("AC-011.5 / AC-011.8 matriz de visibilidade de endereço, telefone e e-mail")
    void ac_011_5_e_ac_011_8_matriz_de_visibilidade_de_contato(
            Role viewerRole,
            boolean targetEhOProprio,
            Role targetRole,
            ContactField campo,
            boolean esperado) {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = targetEhOProprio ? viewerId : UUID.randomUUID();
        // targetRole documenta a coluna da matriz; a política não o recebe (D-28): ele não pode
        // alterar o resultado, o que a variação CUIDADOR/FAMILIAR nas colunas comprova.
        var viewer = new MemberVisibilityPolicy.Viewer(viewerId, viewerRole);

        // D-28: a mesma chamada decide os três campos (endereço, telefone, e-mail).
        boolean podeVer = MemberVisibilityPolicy.canSeeContact(viewer, targetId);

        assertThat(podeVer)
                .as(
                        "viewer %s %s target %s, campo %s",
                        viewerRole,
                        targetEhOProprio ? "vendo a si mesmo como" : "vendo outro membro",
                        targetRole,
                        campo)
                .isEqualTo(esperado);
    }
}
