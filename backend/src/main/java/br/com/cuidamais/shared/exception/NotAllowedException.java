package br.com.cuidamais.shared.exception;

/**
 * Ação sobre um recurso do <strong>próprio grupo de cuidado</strong> que o papel do usuário
 * autenticado não permite (plan D-32, AC-015.2).
 *
 * <p>Deve ser lançada pelos controllers/serviços da Fase 2+ quando a verificação de papel falha
 * para um recurso que existe e pertence ao grupo do usuário — nunca para "recurso de outro grupo"
 * ou "recurso inexistente", que usam {@link ResourceNotFoundException} em vez disso (D-32).
 *
 * <p>{@link GlobalExceptionHandler} mapeia esta exceção para {@code 403} com
 * {@code code = NOT_ALLOWED}. O texto exato exibido ao usuário (AC-015.2) é responsabilidade do
 * catálogo de mensagens do frontend (D-40); esta exceção não carrega nenhum texto de UI.
 */
public class NotAllowedException extends RuntimeException {

    public NotAllowedException() {
        super("Ação não permitida para o papel do usuário autenticado.");
    }
}
