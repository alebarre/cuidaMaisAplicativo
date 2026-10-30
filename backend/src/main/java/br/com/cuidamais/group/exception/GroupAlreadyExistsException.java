package br.com.cuidamais.group.exception;

/**
 * A conta chamando {@code POST /elders} já é Responsável de um grupo (AC-002.1, D-02): cada
 * conta tem no máximo uma {@code membership}, e cada grupo tem exatamente um idoso — uma segunda
 * tentativa de cadastro é conflito de negócio, não um problema da requisição em si.
 *
 * <p>{@code x-required-account-state: active-without-group} (T-024) só exige {@code ATIVO}; não
 * olha se a conta já tem grupo — essa checagem é responsabilidade de {@code ElderService}, que
 * lança esta exceção antes de criar qualquer coisa.
 *
 * <p>{@code GlobalExceptionHandler} mapeia esta exceção para {@code 409} com um {@code Problem}
 * genérico (sem {@code code}, mesmo padrão de {@code ResourceNotFoundException}): o motivo exato
 * já é óbvio pelo contexto da chamada (quem chama sabe que está tentando cadastrar de novo), não
 * precisa de um código estável para o frontend decidir nada.
 */
public class GroupAlreadyExistsException extends RuntimeException {

    public GroupAlreadyExistsException() {
        super("A conta já pertence a um grupo de cuidado.");
    }
}
