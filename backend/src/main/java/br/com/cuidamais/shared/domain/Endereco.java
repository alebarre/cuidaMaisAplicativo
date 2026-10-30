package br.com.cuidamais.shared.domain;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Set;

/**
 * Objeto de valor imutável que representa um endereço brasileiro (D-25).
 *
 * <p>Embutido futuramente em {@code User}, {@code Elder} e {@code EmergencyContact} com colunas
 * prefixadas {@code address_}. Não tem identidade nem vida fora da pessoa a quem pertence, por
 * isso é um {@code record} {@code @Embeddable} e não uma tabela própria.
 *
 * <p>A validação (AC-011.1, AC-011.2) fica num lugar só: o construtor compacto do record. Isso
 * garante a invariante em qualquer caminho de construção, inclusive quando o Hibernate reconstrói
 * a instância a partir do banco. As anotações de Bean Validation abaixo documentam a mesma regra
 * para reaproveitamento em DTOs futuros validados com {@code @Valid}.
 */
@Embeddable
public record Endereco(
        @NotBlank @Pattern(regexp = "\\d{8}") String zipCode,
        @NotBlank String street,
        @NotBlank String number,
        String complement,
        @NotBlank String district,
        @NotBlank String city,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String state) {

    private static final Set<String> UNIDADES_DA_FEDERACAO =
            Set.of(
                    "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
                    "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE",
                    "TO");

    public Endereco {
        requireNonBlank(zipCode, "zipCode");
        if (!zipCode.matches("\\d{8}")) {
            throw new IllegalArgumentException(
                    "zipCode deve ter exatamente 8 dígitos numéricos");
        }
        requireNonBlank(street, "street");
        requireNonBlank(number, "number");
        requireNonBlank(district, "district");
        requireNonBlank(city, "city");
        requireNonBlank(state, "state");
        if (!UNIDADES_DA_FEDERACAO.contains(state)) {
            throw new IllegalArgumentException(
                    "state deve ser uma das 27 unidades da federação");
        }
        if (complement != null && complement.isBlank()) {
            complement = null;
        }
    }

    private static void requireNonBlank(String valor, String nomeDoCampo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(nomeDoCampo + " é obrigatório");
        }
    }
}
