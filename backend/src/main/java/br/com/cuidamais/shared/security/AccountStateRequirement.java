package br.com.cuidamais.shared.security;

/**
 * Valores que a extensão {@code x-required-account-state} do contrato pode assumir numa rota
 * autenticada (T-024), usados por {@link RequiredAccountState}.
 *
 * <p>A ausência de {@link RequiredAccountState} numa rota equivale ao comportamento padrão do
 * contrato — exigir conta {@code ATIVO} <strong>e</strong> com vínculo ativo a um grupo — e por
 * isso não tem um valor próprio aqui; só os dois casos que o contrato declara explicitamente
 * precisam de anotação.
 */
public enum AccountStateRequirement {

    /**
     * {@code x-required-account-state: any}: nenhuma exigência de estado (ex. {@code
     * /auth/logout}, {@code /me/account}) — só a sessão válida já basta, mesmo para uma conta
     * {@code REMOVIDO}.
     */
    ANY,

    /**
     * {@code x-required-account-state: active-without-group}: exige {@code ATIVO}, mas não olha
     * se a conta já tem grupo (ex. {@code POST /elders}, onde "já tem grupo" é conflito de
     * negócio do serviço, não do filtro de segurança).
     */
    ACTIVE_WITHOUT_GROUP
}
