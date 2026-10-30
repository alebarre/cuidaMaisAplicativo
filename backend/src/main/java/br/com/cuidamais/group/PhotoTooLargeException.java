package br.com.cuidamais.group;

/**
 * Upload de foto do idoso ({@code PUT /elders/{id}/photo}, AC-002.6, T-031, D-30) maior que o
 * limite de 5 MB ({@code 5 * 1024 * 1024 = 5_242_880} bytes) — a borda exata ainda é aceita, só
 * um byte além já lança esta exceção.
 *
 * <p>{@code GlobalExceptionHandler} mapeia esta exceção para {@code 413}, com um {@code Problem}
 * genérico (sem {@code code}, mesmo padrão de {@link GroupAlreadyExistsException}).
 */
public class PhotoTooLargeException extends RuntimeException {

    public PhotoTooLargeException() {
        super("O arquivo enviado é maior que o limite permitido de 5 MB.");
    }
}
