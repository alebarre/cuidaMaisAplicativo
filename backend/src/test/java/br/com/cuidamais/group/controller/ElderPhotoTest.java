package br.com.cuidamais.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.auth.domain.User;
import br.com.cuidamais.auth.repository.UserRepository;
import br.com.cuidamais.auth.service.JwtService;
import br.com.cuidamais.membership.domain.Membership;
import br.com.cuidamais.membership.domain.Role;
import br.com.cuidamais.membership.repository.MembershipRepository;
import br.com.cuidamais.shared.domain.Endereco;
import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T-031 "Pronto quando" — {@code PUT}/{@code GET}/{@code DELETE /api/v1/elders/{elderId}/photo}
 * (contrato {@code operationId: uploadElderPhoto}/{@code getElderPhoto}/{@code
 * deleteElderPhoto}, seção {@code /elders/{elderId}/photo} do {@code openapi.yaml} incluída hoje
 * junto com esta tarefa): o idoso tem no máximo uma foto, em JPEG ou PNG, até 5 MB (AC-002.6);
 * enviar uma nova substitui a anterior e a foto pode ser removida.
 *
 * <p>Checagem de formato pelo {@code Content-Type} declarado no {@code multipart/form-data} (não
 * pelos bytes reais do arquivo — {@link MockMultipartFile} permite declarar qualquer {@code
 * Content-Type} independente do conteúdo, por isso os "JPEG"/"PNG" usados aqui são apenas
 * sequências de bytes arbitrárias com o {@code Content-Type} certo, nunca uma imagem real
 * decodificada). Limite de 5 MB = {@code 5 * 1024 * 1024 = 5_242_880} bytes (D-30); a borda exata
 * é aceita, um byte a mais já é {@code 413}.
 *
 * <p>ACs cobertos: AC-002.6 (formato/tamanho/substituição/remoção da única foto), AC-002.3
 * (visibilidade do {@code GET} da foto — qualquer membro ativo do grupo, mesmo critério dos
 * demais dados do idoso, não só o Responsável), AC-015.3/D-32 (404 idêntico para "foto de outro
 * grupo" e "idoso inexistente", nas 3 operações).
 *
 * <p>Caixa-preta pela rota HTTP real (mesmo padrão de {@code ElderRetrievalTest}/{@code
 * ElderUpdateTest}: grupo + idoso fabricados direto no banco por SQL puro + {@link Membership}
 * JPA). Nenhuma rota de foto existe ainda em {@code ElderController} — hoje {@code PUT}/{@code
 * GET}/{@code DELETE /elders/{elderId}/photo} não têm nenhum {@code @RequestMapping} que bata,
 * então toda chamada destes testes falha agora com {@code 404} do próprio Spring (rota não
 * mapeada), nunca pelo status específico que cada cenário abaixo documenta — esse é o motivo
 * correto de falha nesta fase (funcionalidade ausente), não um erro de configuração do teste.
 *
 * <p>Não usa {@link br.com.cuidamais.shared.testsupport.ContractValidation#matchesContract()}
 * aqui: o validador de contrato usado no projeto (openapi-request-validator-mockmvc) é pensado
 * para corpos JSON, e o upload é {@code multipart/form-data} com corpo binário — por isso a
 * conformidade com o contrato é conferida diretamente (status, {@code Content-Type}, bytes do
 * corpo, schema {@code Problem} dos erros), do mesmo jeito que os demais testes de erro deste
 * pacote já fazem com {@code ContractSchemas}.
 *
 * <p>O banco não é limpo entre testes: cada teste cria sua própria conta/grupo/idoso.
 */
@AutoConfigureMockMvc
class ElderPhotoTest extends IntegrationTest {

    /** Exatamente 5 MB (D-30): a borda aceita pelo limite de tamanho. */
    private static final int CINCO_MB = 5 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    JwtService jwtService;

    // ============================================================ AC-002.6 upload válido (caminho feliz)

    /**
     * Cenário central do "Pronto quando": o Responsável envia um JPEG válido (≤ 5 MB). O
     * {@code PUT} devolve {@code 200} com {@code ElderResponse.photoUrl} apontando exatamente
     * para {@code GET /api/v1/elders/{elderId}/photo} (path completo, com o context-path global —
     * é o que o contrato documenta em {@code Elder.photoUrl}); e esse {@code GET}, chamado depois,
     * devolve {@code 200} com {@code Content-Type: image/jpeg} e o corpo byte a byte igual ao
     * arquivo enviado (prova de que nada foi alterado/recodificado no caminho).
     */
    @Test
    @DisplayName("AC-002.6 PUT com JPEG válido devolve photoUrl; GET devolve os mesmos bytes como image/jpeg")
    void ac_002_6_put_jpeg_valido_devolve_photourl_e_get_devolve_os_mesmos_bytes() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] conteudo = "conteudo-jpeg-fake-nao-e-imagem-real".getBytes(StandardCharsets.UTF_8);

        MvcResult putResult = mockMvc.perform(
                        enviarFoto(grupo.elderId(), jpeg(conteudo), grupo.responsavel()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode putBody = json.readTree(putResult.getResponse().getContentAsString());
        assertThat(putBody.path("photoUrl").asText())
                .as("photoUrl aponta para o GET da própria foto, com o context-path /api/v1")
                .isEqualTo("/api/v1/elders/" + grupo.elderId() + "/photo");

        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray())
                .as("GET devolve exatamente os bytes enviados no PUT")
                .isEqualTo(conteudo);
    }

    /** Mesmo fluxo com PNG: o {@code GET} devolve {@code Content-Type: image/png}. */
    @Test
    @DisplayName("AC-002.6 PUT com PNG válido devolve photoUrl; GET devolve os mesmos bytes como image/png")
    void ac_002_6_put_png_valido_devolve_content_type_png_no_get() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] conteudo = "conteudo-png-fake-nao-e-imagem-real".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(enviarFoto(grupo.elderId(), png(conteudo), grupo.responsavel()))
                .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray()).isEqualTo(conteudo);
    }

    // ============================================================ AC-002.6 "substitui a anterior"

    /**
     * "Enviar uma nova substitui a anterior" (AC-002.6): depois de um JPEG e, em seguida, um PNG
     * diferente, o {@code GET} final devolve o conteúdo e o {@code Content-Type} do PNG (o
     * segundo envio), nunca o do JPEG original — prova de substituição, não de acúmulo (o idoso
     * tem no máximo uma foto).
     */
    @Test
    @DisplayName("AC-002.6 segunda foto (PNG) substitui a primeira (JPEG): GET final devolve só a PNG")
    void ac_002_6_segunda_foto_substitui_a_anterior() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] jpegOriginal = "conteudo-jpeg-original".getBytes(StandardCharsets.UTF_8);
        byte[] pngNovo = "conteudo-png-substituto-diferente".getBytes(StandardCharsets.UTF_8);

        // Etapa 1: primeiro envio, JPEG.
        mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(jpegOriginal), grupo.responsavel()))
                .andExpect(status().isOk());

        // Etapa 2: segundo envio, PNG diferente — deve substituir, não somar.
        mockMvc.perform(enviarFoto(grupo.elderId(), png(pngNovo), grupo.responsavel()))
                .andExpect(status().isOk());

        // Etapa 3: o GET final só pode devolver o PNG do segundo envio.
        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray())
                .as("a foto antiga (JPEG) não deve mais aparecer em nenhum lugar")
                .isEqualTo(pngNovo)
                .isNotEqualTo(jpegOriginal);
    }

    // ============================================================ AC-002.6 415 formato inválido

    /**
     * Formato diferente de JPEG/PNG (aqui, {@code text/plain}) é recusado com {@code 415} e corpo
     * {@code Problem} genérico (sem {@code code} — o motivo já é evidente pela própria resposta);
     * como já havia uma foto válida enviada antes, ela continua intacta depois da tentativa
     * rejeitada (conferido pelo {@code GET} seguinte) — upload rejeitado não altera a foto
     * existente.
     */
    @Test
    @DisplayName("AC-002.6 Content-Type fora de JPEG/PNG é recusado com 415; foto anterior continua intacta")
    void ac_002_6_formato_invalido_e_rejeitado_com_415_e_foto_anterior_intacta() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] fotoValida = "conteudo-jpeg-que-deve-permanecer".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(fotoValida), grupo.responsavel()))
                .andExpect(status().isOk());

        MockMultipartFile arquivoTexto = new MockMultipartFile(
                "file", "nota.txt", MediaType.TEXT_PLAIN_VALUE, "isto nao e imagem".getBytes(StandardCharsets.UTF_8));
        MvcResult rejeicao = mockMvc.perform(enviarFoto(grupo.elderId(), arquivoTexto, grupo.responsavel()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(rejeicao.getResponse().getContentAsString());
        assertThat(body.has("code")).as("415 é Problem genérico, sem code").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();

        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray())
                .as("a tentativa de formato inválido não mudou a foto existente")
                .isEqualTo(fotoValida);
    }

    // ============================================================ AC-002.6 413 limite de 5 MB (borda)

    /** Exatamente {@code 5_242_880} bytes (5 MB) ainda está dentro do limite → {@code 200} aceito. */
    @Test
    @DisplayName("AC-002.6 arquivo de exatamente 5 MB (5.242.880 bytes) é aceito (200)")
    void ac_002_6_arquivo_de_exatamente_5mb_e_aceito() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] noLimite = bytesDe(CINCO_MB, (byte) 0x7A);

        mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(noLimite), grupo.responsavel()))
                .andExpect(status().isOk());

        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray())
                .as("os 5.242.880 bytes exatos foram aceitos e persistidos sem truncar")
                .isEqualTo(noLimite);
    }

    /**
     * Um byte além do limite ({@code 5_242_881} bytes) é recusado com {@code 413}; se já havia uma
     * foto válida, ela continua intacta depois da tentativa rejeitada.
     */
    @Test
    @DisplayName("AC-002.6 arquivo de 5.242.881 bytes (1 byte acima do limite) é recusado com 413, foto anterior intacta")
    void ac_002_6_arquivo_acima_do_limite_e_rejeitado_com_413_e_foto_anterior_intacta() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] fotoValida = "conteudo-jpeg-pequeno-que-deve-permanecer".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(fotoValida), grupo.responsavel()))
                .andExpect(status().isOk());

        byte[] acimaDoLimite = bytesDe(CINCO_MB + 1, (byte) 0x7A);
        MvcResult rejeicao = mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(acimaDoLimite), grupo.responsavel()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(rejeicao.getResponse().getContentAsString());
        assertThat(body.has("code")).as("413 é Problem genérico, sem code").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();

        MvcResult getResult = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getResult.getResponse().getContentAsByteArray())
                .as("a tentativa acima do limite não mudou a foto existente")
                .isEqualTo(fotoValida);
    }

    // ============================================================ AC-002.3 visibilidade do GET

    /**
     * AC-002.3, mesma visibilidade dos demais dados do idoso: um Cuidador do mesmo grupo (sem
     * administração) também consegue baixar a foto que o Responsável enviou — o {@code GET} não
     * tem {@code x-required-permission: admin} no contrato, ao contrário do {@code PUT}/{@code
     * DELETE}.
     */
    @Test
    @DisplayName("AC-002.3 Cuidador do grupo consegue GET a foto enviada pelo Responsável")
    void ac_002_3_cuidador_do_grupo_consegue_ver_foto_enviada_pelo_responsavel() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        byte[] conteudo = "conteudo-jpeg-visivel-a-todo-membro".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(enviarFoto(grupo.elderId(), jpeg(conteudo), grupo.responsavel()))
                .andExpect(status().isOk());
        User cuidador = cuidadorDoGrupo(grupo);

        MvcResult result = mockMvc.perform(buscarFoto(grupo.elderId(), cuidador))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(conteudo);
    }

    // ============================================================ AC-002.6 403 Cuidador não administra a foto

    /** AC-002.6, {@code x-required-permission: admin}: Cuidador tentando {@code PUT} recebe 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-002.6 Cuidador (sem administração) tentando PUT a foto recebe 403 NOT_ALLOWED")
    void ac_002_6_cuidador_tentando_put_foto_recebe_403_not_allowed() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        User cuidador = cuidadorDoGrupo(grupo);

        MvcResult result = mockMvc.perform(enviarFoto(
                        grupo.elderId(), jpeg("conteudo-qualquer".getBytes(StandardCharsets.UTF_8)), cuidador))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    /** Mesma regra para {@code DELETE}: Cuidador tentando remover a foto recebe 403 NOT_ALLOWED. */
    @Test
    @DisplayName("AC-002.6 Cuidador (sem administração) tentando DELETE a foto recebe 403 NOT_ALLOWED")
    void ac_002_6_cuidador_tentando_delete_foto_recebe_403_not_allowed() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        User cuidador = cuidadorDoGrupo(grupo);

        MvcResult result = mockMvc.perform(removerFoto(grupo.elderId(), cuidador))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    // ============================================================ AC-015.3 / D-32 outro grupo

    /**
     * AC-015.3 / D-32: as 3 operações de foto ({@code GET}/{@code PUT}/{@code DELETE}) usam o
     * mesmo critério de posse de {@code ElderService#resolveOwnElder} — uma conta Responsável de
     * OUTRO grupo (grupo B) chamando qualquer uma delas para o idoso do grupo A recebe 404, corpo
     * {@code Problem} genérico, sem {@code code} (indistinguível de "idoso inexistente"). Cada
     * valor do enum é uma etapa própria do mesmo cenário: uma operação HTTP diferente contra o
     * mesmo recurso de outro grupo.
     */
    @ParameterizedTest(name = "AC-015.3 {0} da foto do grupo A por Responsável do grupo B → 404")
    @EnumSource(OperacaoFoto.class)
    @DisplayName("AC-015.3 Responsável de outro grupo recebe 404 em GET/PUT/DELETE da foto do idoso do grupo A")
    void ac_015_3_responsavel_de_outro_grupo_recebe_404(OperacaoFoto operacao) throws Exception {
        Grupo grupoA = grupoComIdosoSemFoto();
        Grupo grupoB = grupoComIdosoSemFoto();

        MvcResult result = mockMvc.perform(operacao.requisicao(this, grupoA.elderId(), grupoB.responsavel()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("code")).as("404 é Problem genérico, sem code").isFalse();
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();
    }

    /** As 3 operações de foto cobertas pelo teste parametrizado de AC-015.3/D-32 acima. */
    enum OperacaoFoto {
        /** {@code GET /elders/{elderId}/photo} de outro grupo. */
        GET {
            @Override
            org.springframework.test.web.servlet.RequestBuilder requisicao(ElderPhotoTest t, UUID elderId, User autor) {
                return t.buscarFoto(elderId, autor);
            }
        },
        /** {@code PUT /elders/{elderId}/photo} de outro grupo. */
        PUT {
            @Override
            org.springframework.test.web.servlet.RequestBuilder requisicao(ElderPhotoTest t, UUID elderId, User autor) {
                return t.enviarFoto(
                        elderId, t.jpeg("conteudo-qualquer".getBytes(StandardCharsets.UTF_8)), autor);
            }
        },
        /** {@code DELETE /elders/{elderId}/photo} de outro grupo. */
        DELETE {
            @Override
            org.springframework.test.web.servlet.RequestBuilder requisicao(ElderPhotoTest t, UUID elderId, User autor) {
                return t.removerFoto(elderId, autor);
            }
        };

        abstract org.springframework.test.web.servlet.RequestBuilder requisicao(
                ElderPhotoTest t, UUID elderId, User autor);
    }

    // ============================================================ AC-002.6 GET sem foto nenhuma

    /** AC-002.6: idoso que nunca teve foto enviada → {@code GET /elders/{elderId}/photo} é 404. */
    @Test
    @DisplayName("AC-002.6 GET da foto de um idoso que nunca teve foto enviada responde 404")
    void ac_002_6_get_foto_quando_idoso_nunca_teve_foto_e_404() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();

        MvcResult result = mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<Error> violacoes = ContractSchemas.schemaFor("Problem").validate(body);
        assertThat(violacoes).as("corpo casa com Problem do contrato: %s", violacoes).isEmpty();
    }

    // ============================================================ AC-002.6 remoção da foto

    /**
     * "A foto pode ser removida" (AC-002.6), cenário completo: o Responsável remove a foto
     * existente ({@code DELETE} → 204); o {@code GET} da foto, depois, passa a 404 (não há mais
     * nada para servir); e o {@code GET} do idoso em JSON volta a mostrar {@code photoUrl: null},
     * exatamente como antes de qualquer foto ter sido enviada.
     */
    @Test
    @DisplayName("AC-002.6 Responsável remove a foto: DELETE 204, GET da foto 404 depois, photoUrl volta a null no JSON")
    void ac_002_6_responsavel_remove_foto_existente() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();
        mockMvc.perform(enviarFoto(
                        grupo.elderId(), jpeg("conteudo-a-ser-removido".getBytes(StandardCharsets.UTF_8)),
                        grupo.responsavel()))
                .andExpect(status().isOk());

        // Etapa 1: remove a foto existente.
        mockMvc.perform(removerFoto(grupo.elderId(), grupo.responsavel())).andExpect(status().isNoContent());

        // Etapa 2: a foto removida não é mais servida.
        mockMvc.perform(buscarFoto(grupo.elderId(), grupo.responsavel())).andExpect(status().isNotFound());

        // Etapa 3: o JSON do idoso reflete a ausência de foto outra vez.
        MvcResult elderResult = mockMvc.perform(
                        get("/api/v1/elders/{elderId}", grupo.elderId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(grupo.responsavel())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode elderBody = json.readTree(elderResult.getResponse().getContentAsString());
        assertThat(elderBody.path("photoUrl").isNull())
                .as("photoUrl volta a null depois da remoção")
                .isTrue();
    }

    /** AC-002.6 "a foto pode ser removida", idempotência: DELETE sem nenhuma foto ainda é 204, não erro. */
    @Test
    @DisplayName("AC-002.6 DELETE quando o idoso nunca teve foto nenhuma é 204 (idempotente, não é erro)")
    void ac_002_6_delete_sem_foto_nenhuma_e_204_idempotente() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();

        mockMvc.perform(removerFoto(grupo.elderId(), grupo.responsavel())).andExpect(status().isNoContent());
    }

    // ============================================================ autenticação

    /**
     * Controle básico: a rota de foto também é autenticada — sem token nenhum, 401 (a exaustão
     * das 3 operações e dos demais motivos de sessão inválida já está coberta genericamente em
     * {@code RequestAuthorizationTest}; aqui só confirmamos que esta família de rotas não ficou de
     * fora da exigência de sessão).
     */
    @Test
    @DisplayName("sem sessão (sem token) na rota de foto responde 401")
    void sem_sessao_na_rota_de_foto_responde_401() throws Exception {
        Grupo grupo = grupoComIdosoSemFoto();

        mockMvc.perform(get("/api/v1/elders/{elderId}/photo", grupo.elderId()))
                .andExpect(status().isUnauthorized());
    }

    // ============================================================ apoio

    /** Conta + grupo + idoso fabricados para um cenário. */
    private record Grupo(User responsavel, UUID groupId, UUID elderId) {
    }

    /** Conta {@code ATIVO} sem grupo, pronta para receber uma membership (mesmo padrão de T-024/T-030). */
    private User contaAtivaSemGrupo() {
        Endereco endereco = new Endereco(
                "22041001", "Rua Barata Ribeiro", "370", "Apto 802", "Copacabana", "Rio de Janeiro", "RJ");
        User user = User.signup(
                "Maria da Silva", "elder-foto-" + UUID.randomUUID() + "@teste.com", "21987654321",
                endereco, "hash-irrelevante-nenhum-login-por-senha-aqui", clock);
        user.verifyEmail(clock);
        return user;
    }

    /**
     * Grupo novo com Responsável admin ATIVO e um idoso sem foto (mesmo padrão de SQL puro usado
     * em {@code ElderRetrievalTest}/{@code ElderUpdateTest}), sem contatos de emergência — não são
     * relevantes para nenhum cenário deste arquivo.
     */
    private Grupo grupoComIdosoSemFoto() {
        User responsavel = users.save(contaAtivaSemGrupo());
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO care_groups (id, created_by) VALUES (?, ?)", groupId, responsavel.getId());
        memberships.save(Membership.admin(groupId, responsavel.getId(), clock));

        UUID elderId = inserirIdoso(groupId);
        return new Grupo(responsavel, groupId, elderId);
    }

    /** Cuidador ATIVO do mesmo grupo, aprovado pelo Responsável (AC-006.3), sem administração. */
    private User cuidadorDoGrupo(Grupo grupo) {
        User user = users.save(contaAtivaSemGrupo());
        Membership m = Membership.request(grupo.groupId(), user.getId(), clock);
        m.approve(Role.CUIDADOR, grupo.responsavel().getId(), clock);
        memberships.save(m);
        return user;
    }

    private UUID inserirIdoso(UUID groupId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO elders (id, group_id, name, birth_date, address_zip_code, address_street,
                    address_number, address_complement, address_district, address_city, address_state)
                VALUES (?, ?, 'Francisca Oliveira', ?, '22041001', 'Rua Barata Ribeiro', '370', 'Apto 802',
                    'Copacabana', 'Rio de Janeiro', 'RJ')
                """,
                id,
                groupId,
                Date.valueOf(LocalDate.now(clock).minusYears(80)));
        return id;
    }

    private String tokenDe(User user) {
        return jwtService.issue(user.getId(), clock);
    }

    /** {@code byte[]} de {@code tamanho} bytes, todos iguais a {@code valor} — conteúdo irrelevante para os testes de borda. */
    private static byte[] bytesDe(int tamanho, byte valor) {
        byte[] bytes = new byte[tamanho];
        Arrays.fill(bytes, valor);
        return bytes;
    }

    /** Arquivo multipart com {@code Content-Type: image/jpeg} (aceito pelo AC-002.6). */
    private MockMultipartFile jpeg(byte[] conteudo) {
        return new MockMultipartFile("file", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, conteudo);
    }

    /** Arquivo multipart com {@code Content-Type: image/png} (aceito pelo AC-002.6). */
    private MockMultipartFile png(byte[] conteudo) {
        return new MockMultipartFile("file", "foto.png", MediaType.IMAGE_PNG_VALUE, conteudo);
    }

    /**
     * {@code PUT /elders/{elderId}/photo} com o arquivo multipart autenticado como {@code autor} —
     * {@code x-required-permission: admin} no contrato (só o Responsável administrador chama).
     */
    private MockMultipartHttpServletRequestBuilder enviarFoto(
            UUID elderId, MockMultipartFile arquivo, User autor) {
        return (MockMultipartHttpServletRequestBuilder) multipart(
                        HttpMethod.PUT, "/api/v1/elders/{elderId}/photo", elderId)
                .file(arquivo)
                .with(autenticadoComo(autor));
    }

    /** {@code GET /elders/{elderId}/photo} autenticado como {@code autor} (qualquer membro ativo, AC-002.3). */
    private org.springframework.test.web.servlet.RequestBuilder buscarFoto(UUID elderId, User autor) {
        return get("/api/v1/elders/{elderId}/photo", elderId).with(autenticadoComo(autor));
    }

    /** {@code DELETE /elders/{elderId}/photo} autenticado como {@code autor} ({@code x-required-permission: admin}). */
    private org.springframework.test.web.servlet.RequestBuilder removerFoto(UUID elderId, User autor) {
        return delete("/api/v1/elders/{elderId}/photo", elderId).with(autenticadoComo(autor));
    }

    /** Acrescenta o header {@code Authorization: Bearer <token>} de {@code autor} a qualquer requisição. */
    private RequestPostProcessor autenticadoComo(User autor) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(autor));
            return request;
        };
    }
}
