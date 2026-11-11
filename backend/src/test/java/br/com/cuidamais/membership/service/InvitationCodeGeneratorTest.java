package br.com.cuidamais.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste unitario do {@link InvitationCodeGenerator} (AC-003.1, D-04): o codigo de convite tem sempre
 * exatamente 6 digitos numericos ({@code %06d}, faixa 000000 a 999999) e preserva zeros a esquerda.
 *
 * <p>O gerador sorteia com um {@link SecureRandom} interno (campo privado, sem construtor nem
 * costura). Sem mudar producao, a prova tem duas partes: (1) estatistica, com 20.000 codigos reais
 * (todos {@code \d{6}}; pelo menos um comeca com '0' - a chance de nenhum entre 20.000 e
 * 0,9^20000, praticamente zero; variedade, para detectar um gerador constante); (2) deterministica,
 * substituindo por reflexao o gerador aleatorio interno por um falso que devolve valores de borda
 * (0, 7, 123456, 999999), o que prova o preenchimento com zeros e os limites da faixa. A parte (2)
 * acopla o teste ao fato de o gerador ter UM campo de instancia do tipo {@link java.util.Random}; se
 * a implementacao ganhar uma costura propria, basta trocar a injecao.
 */
class InvitationCodeGeneratorTest {

    private static final int AMOSTRAS = 20_000;

    /**
     * AC-003.1: em 20.000 sorteios reais todo codigo tem exatamente 6 digitos numericos, algum
     * comeca com zero (zeros a esquerda preservados, nao tratados como numero sem padding) e os
     * codigos variam entre si (nao e constante).
     */
    @Test
    @DisplayName("AC-003.1: 20.000 códigos reais têm sempre 6 dígitos numéricos, alguns com zero à esquerda, e variam")
    void ac_003_1_codigos_reais_tem_sempre_seis_digitos() {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();
        Set<String> distintos = new HashSet<>();
        boolean algumComZeroAEsquerda = false;

        for (int i = 0; i < AMOSTRAS; i++) {
            String codigo = generator.generate();
            // Etapa por amostra: exatamente 6 digitos (isso tambem garante a faixa 000000..999999).
            assertThat(codigo).matches("\\d{6}");
            distintos.add(codigo);
            algumComZeroAEsquerda |= codigo.charAt(0) == '0';
        }

        // Etapa final 1: zeros a esquerda aparecem (probabilidade de falso negativo ~ 0).
        assertThat(algumComZeroAEsquerda).as("algum codigo comeca com '0'").isTrue();
        // Etapa final 2: nao e constante (esperado ~19.800 distintos; limite folgado).
        assertThat(distintos.size()).as("variedade de codigos").isGreaterThan(15_000);
    }

    /**
     * AC-003.1, deterministico: com o sorteio forcado a 0, 7, 123456 e 999999, o codigo sai
     * "000000", "000007", "123456" e "999999": zeros a esquerda preservados e limites da faixa
     * (000000 e 999999) respeitados. Tambem confere que o sorteio e pedido com limite 1.000.000
     * (nunca produz 1.000.000, que teria 7 digitos).
     */
    @Test
    @DisplayName("AC-003.1: com sorteio forçado nas bordas, o código vira 000000, 000007, 123456 e 999999 (zeros à esquerda e faixa exata)")
    void ac_003_1_zeros_a_esquerda_e_limites_da_faixa() throws Exception {
        InvitationCodeGenerator generator = new InvitationCodeGenerator();
        int[] limiteRecebido = {-1};
        int[] proximo = {0};
        injetaSorteio(generator, new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                limiteRecebido[0] = bound;
                return proximo[0];
            }
        });

        // Etapa 1: valor 0 => 000000 (limite inferior).
        proximo[0] = 0;
        assertThat(generator.generate()).isEqualTo("000000");
        // Etapa 2: valor 7 => 000007 (varios zeros a esquerda).
        proximo[0] = 7;
        assertThat(generator.generate()).isEqualTo("000007");
        // Etapa 3: valor sem zeros a esquerda permanece igual.
        proximo[0] = 123_456;
        assertThat(generator.generate()).isEqualTo("123456");
        // Etapa 4: valor maximo possivel => 999999 (limite superior).
        proximo[0] = 999_999;
        assertThat(generator.generate()).isEqualTo("999999");
        // Etapa 5: o limite exclusivo pedido ao sorteio e 1.000.000 (faixa 0..999.999).
        assertThat(limiteRecebido[0]).isEqualTo(1_000_000);
    }

    /** Troca por reflexao o unico campo de instancia {@code Random} do gerador pelo falso. */
    private static void injetaSorteio(InvitationCodeGenerator generator, SecureRandom falso) throws Exception {
        for (Field campo : InvitationCodeGenerator.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(campo.getModifiers())
                    && java.util.Random.class.isAssignableFrom(campo.getType())) {
                campo.setAccessible(true);
                campo.set(generator, falso);
                return;
            }
        }
        throw new AssertionError("o gerador nao tem campo de instancia do tipo Random para substituir");
    }
}
