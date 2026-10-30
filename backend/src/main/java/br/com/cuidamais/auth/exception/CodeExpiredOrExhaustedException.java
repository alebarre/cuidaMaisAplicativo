package br.com.cuidamais.auth.exception;

/**
 * "Não há nada válido a confirmar agora" (T-022, AC-005.1/AC-005.2): o código expirou (15 min),
 * já foi consumido, esgotou as 5 tentativas, nunca existiu (nenhum código {@code
 * CONFIRMACAO_EMAIL} pendente para a conta) ou o e-mail informado não corresponde a conta
 * nenhuma. Os cinco motivos respondem com o mesmo {@code 410} genérico (sem {@code
 * remainingAttempts}), de propósito: por decisão do orquestrador (P5), um e-mail sem conta nunca
 * pode ser distinguido de um código expirado de uma conta real, senão a resposta revelaria se
 * aquele e-mail tem cadastro. {@link br.com.cuidamais.shared.exception.GlobalExceptionHandler} mapeia esta
 * exceção para {@code 410}.
 */
public class CodeExpiredOrExhaustedException extends RuntimeException {

    public CodeExpiredOrExhaustedException() {
        super("Código expirado, esgotado ou inexistente; peça um código novo (AC-005).");
    }
}
