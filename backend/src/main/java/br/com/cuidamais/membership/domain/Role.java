package br.com.cuidamais.membership.domain;

/**
 * Papel de um membro dentro do grupo de cuidado (D-29).
 *
 * <p>Só existem estes dois valores; o Responsável (administrador do grupo) é sempre {@link
 * #FAMILIAR} com a flag de administração ligada — não é um papel próprio (AC-006.3, AC-009.1).
 */
public enum Role {
    CUIDADOR,
    FAMILIAR
}
