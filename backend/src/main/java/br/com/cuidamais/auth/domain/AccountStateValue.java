package br.com.cuidamais.auth.domain;

/**
 * Estado derivado da conta (AC-012.1, D-33) — os mesmos seis valores do schema
 * {@code AccountStateValue} do {@code openapi.yaml}.
 *
 * <p>Não é coluna de banco: é calculado por {@link AccountState#of} a partir de
 * {@code users.email_verified_at} e de {@code memberships.status}.
 */
public enum AccountStateValue {
    AGUARDANDO_CONFIRMACAO_EMAIL,
    ATIVO,
    AGUARDANDO_APROVACAO,
    RECUSADO,
    EXPIRADO,
    REMOVIDO
}
