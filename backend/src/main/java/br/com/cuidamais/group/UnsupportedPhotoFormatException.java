package br.com.cuidamais.group;

/**
 * Upload de foto do idoso ({@code PUT /elders/{id}/photo}, AC-002.6, T-031, D-30) com {@code
 * Content-Type} diferente de {@code image/jpeg}/{@code image/png} — os dois únicos formatos
 * aceitos pelo contrato.
 *
 * <p>{@code GlobalExceptionHandler} mapeia esta exceção para {@code 415}, com um {@code Problem}
 * genérico (sem {@code code}, mesmo padrão de {@link GroupAlreadyExistsException}): o motivo já
 * é evidente pela própria resposta, não precisa de um código estável para o frontend decidir
 * nada.
 */
public class UnsupportedPhotoFormatException extends RuntimeException {

    public UnsupportedPhotoFormatException() {
        super("Formato de arquivo não suportado; envie JPEG ou PNG.");
    }
}
