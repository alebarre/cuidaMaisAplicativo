package br.com.cuidamais.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.shared.testsupport.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-007 "Pronto quando": (a) o cenário de validação devolve um corpo que casa com o schema
 * {@code ValidationProblem} do {@code openapi.yaml} real, e (b) os três casos de AC-015.3 —
 * ação vedada sobre recurso do <strong>próprio grupo</strong> (403 {@code NOT_ALLOWED}), recurso
 * de <strong>outro grupo</strong> (404) e recurso <strong>inexistente</strong> (404) — têm teste,
 * provando que os dois 404 são byte-a-byte idênticos exceto {@code instance}.
 *
 * <p>Usa {@code MockMvcBuilders.standaloneSetup}, registrando manualmente
 * {@link GlobalExceptionHandler} junto do controller só de teste (nunca em {@code src/main}),
 * seguindo o mesmo padrão de {@code ContractValidationTest} (T-006): não é um teste de contrato
 * de rota (os endpoints abaixo não existem no {@code openapi.yaml} — só os schemas de erro
 * existem), então não usamos {@link br.com.cuidamais.shared.testsupport.ContractValidation} aqui,
 * e sim {@link ContractSchemas}, que extrai só o schema {@code ValidationProblem} do documento
 * real e o valida com {@code com.networknt:json-schema-validator} (já presente no classpath de
 * teste via {@code com.atlassian.oai:openapi-request-validator-mockmvc}, T-006) — abordagem
 * rigorosa preferida no lugar da alternativa manual de asserções campo a campo.
 */
class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TesteController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void validacaoInvalidaDevolveValidationProblemConformeOContrato() throws Exception {
        MvcResult result = mockMvc.perform(post("/teste/validacao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        // allOf [Problem, {errors}] achatado: type/title/status/code/errors no nível raiz do JSON.
        assertThat(body.has("type")).isTrue();
        assertThat(body.has("title")).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.has("errors")).isTrue();
        assertThat(body.get("errors")).isNotEmpty();
        assertThat(body.get("errors").get(0).has("field")).isTrue();
        assertThat(body.get("errors").get(0).has("message")).isTrue();

        List<Error> violations = ContractSchemas.schemaFor("ValidationProblem").validate(body);
        assertThat(violations)
                .as("corpo deve casar com #/components/schemas/ValidationProblem do openapi.yaml: %s",
                        violations)
                .isEmpty();

        // P5: nem a mensagem bruta da exceção nem o valor rejeitado aparecem no corpo.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("MethodArgumentNotValidException")
                .doesNotContain("rejected value");
    }

    @Test
    void acaoVedadaSobreRecursoDoProprioGrupoRecebe403NotAllowed() throws Exception {
        JsonNode body = perform(get("/teste/proprio-grupo"), status().isForbidden());

        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("code").asText()).isEqualTo("NOT_ALLOWED");
    }

    @Test
    void recursoDeOutroGrupoERecursoInexistenteRecebemCorpoIdenticoDe404() throws Exception {
        MvcResult outroGrupo = mockMvc.perform(get("/teste/outro-grupo"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        MvcResult inexistente = mockMvc.perform(get("/teste/inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        JsonNode outroGrupoBody = objectMapper.readTree(outroGrupo.getResponse().getContentAsString());
        JsonNode inexistenteBody = objectMapper.readTree(inexistente.getResponse().getContentAsString());

        // instance reflete o path da requisição — é o único campo que pode (e deve) variar.
        assertThat(outroGrupoBody.get("instance").asText()).isEqualTo("/teste/outro-grupo");
        assertThat(inexistenteBody.get("instance").asText()).isEqualTo("/teste/inexistente");

        JsonNode outroGrupoSemInstance = ((com.fasterxml.jackson.databind.node.ObjectNode) outroGrupoBody)
                .deepCopy().without("instance");
        JsonNode inexistenteSemInstance = ((com.fasterxml.jackson.databind.node.ObjectNode) inexistenteBody)
                .deepCopy().without("instance");

        assertThat(outroGrupoSemInstance)
                .as("corpo de 'outro grupo' e 'inexistente' deve ser idêntico, exceto instance")
                .isEqualTo(inexistenteSemInstance);
        assertThat(outroGrupoBody.get("status").asInt()).isEqualTo(404);
        assertThat(outroGrupoBody.has("code")).isFalse();
    }

    private JsonNode perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(expectedStatus)
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Controller só de teste (nunca em {@code src/main}), análogo a
     * {@code ContractValidationTest.RotaForaDoContratoController} (T-006): simula as situações do
     * "Pronto quando" da T-007 sem precisar de nenhum endpoint real (que só chega na Fase 2).
     */
    @RestController
    @RequestMapping("/teste")
    @Validated
    static class TesteController {

        @PostMapping(path = "/validacao", consumes = MediaType.APPLICATION_JSON_VALUE)
        void validacao(@RequestBody @jakarta.validation.Valid TesteRequest request) {
            // corpo vazio: só precisa disparar MethodArgumentNotValidException quando inválido.
        }

        /** AC-015.2: ação sobre recurso do próprio grupo vedada ao papel do usuário. */
        @org.springframework.web.bind.annotation.GetMapping("/proprio-grupo")
        void proprioGrupo() {
            throw new NotAllowedException();
        }

        /** AC-015.3: recurso de outro grupo de cuidado — mesma exceção que "inexistente". */
        @org.springframework.web.bind.annotation.GetMapping("/outro-grupo")
        void outroGrupo() {
            throw new ResourceNotFoundException();
        }

        /** AC-015.3: recurso que não existe — mesma exceção que "outro grupo". */
        @org.springframework.web.bind.annotation.GetMapping("/inexistente")
        void inexistente() {
            throw new ResourceNotFoundException();
        }
    }

    record TesteRequest(@NotBlank(message = "nome é obrigatório") String nome) {
    }
}
