package br.com.cuidamais.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-011 "Pronto quando": testes cobrem CEP com 7/8/9 dígitos, UF inválida, número "S/N" e
 * complemento vazio (AC-011.1, AC-011.2, D-25).
 *
 * <p>D-25: {@link Endereco} é um {@code record} {@code @Embeddable} imutável e a validação fica
 * <strong>num lugar só</strong> — o construtor do record. Quando os dados são inválidos a
 * construção lança {@link IllegalArgumentException}; assim a invariante vale em qualquer caminho
 * de construção (código de aplicação, DTO ou reconstrução pelo Hibernate a partir do banco).
 *
 * <p>Ordem dos componentes (mesmos nomes das colunas {@code address_*} da T-010):
 * {@code zipCode, street, number, complement, district, city, state}. Cada teste parte de um
 * endereço válido e altera <strong>um</strong> campo, para que a recusa só possa vir da regra
 * testada.
 */
class EnderecoTest {

    private static final String CEP = "01310100";
    private static final String LOGRADOURO = "Avenida Paulista";
    private static final String NUMERO = "1578";
    private static final String COMPLEMENTO = "Apto 12";
    private static final String BAIRRO = "Bela Vista";
    private static final String CIDADE = "São Paulo";
    private static final String UF = "SP";

    // ---------------------------------------------------------------- AC-011.2 CEP

    @Test
    @DisplayName("AC-011.2 CEP com 8 dígitos numéricos é aceito (controle positivo)")
    void ac_011_2_cep_com_oito_digitos_e_aceito() {
        Endereco endereco =
                new Endereco("01310100", LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, UF);

        assertThat(endereco.zipCode()).isEqualTo("01310100");
    }

    @Test
    @DisplayName("AC-011.2 CEP com 7 dígitos é rejeitado")
    void ac_011_2_cep_com_sete_digitos_e_rejeitado() {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        "0131010", LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO,
                                        CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-011.2 CEP com 9 dígitos é rejeitado")
    void ac_011_2_cep_com_nove_digitos_e_rejeitado() {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        "013101000", LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO,
                                        CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-011.2 CEP com 8 caracteres não numéricos é rejeitado")
    void ac_011_2_cep_com_letras_e_rejeitado() {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        "0131010A", LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO,
                                        CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-011.2 CEP com máscara (01310-100) é rejeitado: só 8 dígitos numéricos")
    void ac_011_2_cep_com_hifen_e_rejeitado() {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        "01310-100", LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO,
                                        CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "CEP [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("AC-011.1/AC-011.2 CEP ausente (nulo ou em branco) é rejeitado")
    void ac_011_1_cep_ausente_e_rejeitado(String cep) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        cep, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- AC-011.2 UF

    @ParameterizedTest(name = "UF {0}")
    @ValueSource(
            strings = {
                "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
                "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
            })
    @DisplayName("AC-011.2 cada uma das 27 UFs é aceita")
    void ac_011_2_cada_uma_das_27_ufs_e_aceita(String uf) {
        Endereco endereco =
                new Endereco(CEP, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, uf);

        assertThat(endereco.state()).isEqualTo(uf);
    }

    @ParameterizedTest(name = "UF [{0}]")
    @ValueSource(strings = {"XX", "BR", "S", "SPP"})
    @DisplayName("AC-011.2 UF fora das 27 unidades da federação é rejeitada")
    void ac_011_2_uf_invalida_e_rejeitada(String uf) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, uf))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "UF [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("AC-011.1/AC-011.2 UF ausente (nula ou em branco) é rejeitada")
    void ac_011_2_uf_ausente_e_rejeitada(String uf) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, uf))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- AC-011.1 número

    @Test
    @DisplayName("AC-011.1 número \"S/N\" é aceito")
    void ac_011_1_numero_sem_numero_e_aceito() {
        Endereco endereco =
                new Endereco(CEP, LOGRADOURO, "S/N", COMPLEMENTO, BAIRRO, CIDADE, UF);

        assertThat(endereco.number()).isEqualTo("S/N");
    }

    // ---------------------------------------------------------------- AC-011.1 complemento

    @Test
    @DisplayName("AC-011.1 complemento nulo é aceito (único campo opcional)")
    void ac_011_1_complemento_nulo_e_aceito() {
        Endereco endereco = new Endereco(CEP, LOGRADOURO, NUMERO, null, BAIRRO, CIDADE, UF);

        assertThat(endereco.complement()).isNull();
    }

    @Test
    @DisplayName("AC-011.1 complemento vazio é aceito (único campo opcional)")
    void ac_011_1_complemento_vazio_e_aceito() {
        Endereco endereco = new Endereco(CEP, LOGRADOURO, NUMERO, "", BAIRRO, CIDADE, UF);

        assertThat(endereco.street()).isEqualTo(LOGRADOURO);
    }

    // ---------------------------------------------------------------- AC-011.1 obrigatórios

    @ParameterizedTest(name = "logradouro [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("AC-011.1 logradouro ausente (nulo ou em branco) é rejeitado")
    void ac_011_1_logradouro_ausente_e_rejeitado(String logradouro) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, logradouro, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "número [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("AC-011.1 número ausente (nulo ou em branco) é rejeitado")
    void ac_011_1_numero_ausente_e_rejeitado(String numero) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, LOGRADOURO, numero, COMPLEMENTO, BAIRRO, CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "bairro [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("AC-011.1 bairro ausente (nulo ou em branco) é rejeitado")
    void ac_011_1_bairro_ausente_e_rejeitado(String bairro) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, LOGRADOURO, NUMERO, COMPLEMENTO, bairro, CIDADE, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "cidade [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("AC-011.1 cidade ausente (nula ou em branco) é rejeitada")
    void ac_011_1_cidade_ausente_e_rejeitada(String cidade) {
        assertThatThrownBy(
                        () ->
                                new Endereco(
                                        CEP, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, cidade, UF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- AC-011.1 todos os campos

    @Test
    @DisplayName("AC-011.1 endereço completo guarda os 7 campos")
    void ac_011_1_endereco_completo_guarda_os_sete_campos() {
        Endereco endereco =
                new Endereco(CEP, LOGRADOURO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, UF);

        assertThat(endereco.zipCode()).isEqualTo(CEP);
        assertThat(endereco.street()).isEqualTo(LOGRADOURO);
        assertThat(endereco.number()).isEqualTo(NUMERO);
        assertThat(endereco.complement()).isEqualTo(COMPLEMENTO);
        assertThat(endereco.district()).isEqualTo(BAIRRO);
        assertThat(endereco.city()).isEqualTo(CIDADE);
        assertThat(endereco.state()).isEqualTo(UF);
    }
}
