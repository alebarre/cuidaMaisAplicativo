package br.com.cuidamais.group.dto;

import org.springframework.http.MediaType;

/**
 * Bytes da foto do idoso e o {@code Content-Type} com o qual devem ser servidos (AC-002.6,
 * AC-002.3, T-031) — devolvido por {@code ElderService#getPhoto} e repassado sem alteração pelo
 * {@code GET /elders/{id}/photo} do controller. O {@code Content-Type} é derivado da extensão do
 * arquivo gravado ({@code Elder.photoPath}), nunca do conteúdo em si (D-30: formato já foi
 * validado no upload, não há necessidade de inspecionar os bytes de novo na leitura).
 */
public record ElderPhotoContent(byte[] bytes, MediaType contentType) {
}
