package br.com.cuidamais.group.service;

import br.com.cuidamais.group.domain.Elder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lê, grava e remove em disco a única foto de cada idoso (AC-002.6, T-031, D-30): o banco guarda
 * só o caminho do arquivo em {@code elders.photo_path} ({@link Elder#getPhotoPath()}); os bytes
 * da imagem em si nunca entram no PostgreSQL, vivem num diretório configurável por ambiente
 * ({@code app.storage.elder-photos-dir}, variável {@code ELDER_PHOTOS_DIR}).
 *
 * <p>Cada idoso tem no máximo um arquivo, nomeado só pelo seu {@code elderId} (nenhum
 * identificador extra é necessário — D-30). Quem decide apagar o arquivo antigo antes de gravar
 * um novo (inclusive ao trocar JPEG↔PNG, que muda a extensão) é {@code ElderService}, não esta
 * classe: {@link #save} sempre grava um arquivo novo, nunca sabe se havia um anterior.
 */
@Component
public class ElderPhotoStorage {

    private final Path baseDir;

    /**
     * Resolve o diretório configurado e garante que ele existe antes de qualquer gravação —
     * falhar rápido na inicialização da aplicação é melhor do que descobrir em produção, no
     * primeiro upload, que o caminho configurado não pode ser criado.
     *
     * @param elderPhotosDir diretório (absoluto ou relativo ao diretório de trabalho) onde cada
     *     foto é gravada como um arquivo próprio
     */
    public ElderPhotoStorage(@Value("${app.storage.elder-photos-dir}") String elderPhotosDir) {
        this.baseDir = Paths.get(elderPhotosDir);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Não foi possível criar o diretório de armazenamento das fotos dos idosos.", e);
        }
    }

    /**
     * Grava os bytes recebidos num arquivo novo ({@code {elderId}{extensao}}) e devolve o nome
     * desse arquivo — é exatamente esse valor que {@code ElderService} grava em {@code
     * Elder.photoPath} (AC-002.6).
     *
     * @param elderId idoso ao qual a foto pertence (chave do nome do arquivo)
     * @param bytes conteúdo já validado (formato e tamanho, D-30) pelo chamador
     * @param extension extensão com o ponto, ex. {@code ".jpg"}/{@code ".png"}
     * @return o nome do arquivo gravado, para ser persistido como {@code Elder.photoPath}
     */
    public String save(UUID elderId, byte[] bytes, String extension) {
        String fileName = elderId + extension;
        try {
            Files.write(baseDir.resolve(fileName), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar a foto do idoso.", e);
        }
        return fileName;
    }

    /**
     * Remove o arquivo apontado por {@code photoPath}, se existir — idempotente de propósito
     * (AC-002.6: {@code DELETE} sem nenhuma foto gravada não é erro, e {@code photoPath} pode já
     * ter sido removido por uma tentativa anterior). {@code photoPath == null} também é um
     * no-op, para o chamador não precisar checar antes de chamar.
     */
    public void delete(String photoPath) {
        if (photoPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(baseDir.resolve(photoPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível remover a foto antiga do idoso.", e);
        }
    }

    /**
     * Lê o conteúdo gravado de {@code photoPath}, para servir no {@code GET /elders/{id}/photo}
     * (AC-002.3). Quem já confirmou que {@code photoPath} não é {@code null} (idoso tem foto) é
     * {@code ElderService}, antes de chamar este método.
     */
    public byte[] read(String photoPath) {
        try {
            return Files.readAllBytes(baseDir.resolve(photoPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler a foto do idoso.", e);
        }
    }
}
