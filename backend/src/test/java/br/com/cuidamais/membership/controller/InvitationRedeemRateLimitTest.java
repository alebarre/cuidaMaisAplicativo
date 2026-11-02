package br.com.cuidamais.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.cuidamais.shared.testsupport.ContractSchemas;
import br.com.cuidamais.shared.testsupport.ContractValidation;
import br.com.cuidamais.shared.testsupport.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-041 / R-03 — limite de chamadas por IP em {@code POST /api/v1/invitations/redeem}.
 *
 * <p>Contrato de configuração definido por este teste (o implementador cria as propriedades e
 * escolhe um padrão alto o bastante para não atrapalhar os demais testes): {@code
 * app.invitations.redeem-rate-limit.max-requests} (inteiro) e {@code
 * app.invitations.redeem-rate-limit.window} (Duration). Aqui o limite é baixado para 3 por minuto.
 * O tempo da janela é o do {@link java.time.Clock} (D-10), então {@link
 * br.com.cuidamais.shared.testsupport.MutableClock} prova a reabertura sem esperar.
 *
 * <p>A resposta excedente é 429 {@code Problem} com cabeçalho {@code Retry-After} (segundos), e o
 * excedente não pode consumir tentativa do e-mail (o contador {@code invite_code_attempts} fica
 * como estava). O limite é por IP: outro IP não é afetado.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.invitations.redeem-rate-limit.max-requests=3",
    "app.invitations.redeem-rate-limit.window=PT1M"
})
class InvitationRedeemRateLimitTest extends IntegrationTest {

    private static final String REDEEM = "/api/v1/invitations/redeem";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * R-03: 3 chamadas do mesmo IP passam (400 de código errado), a 4ª é 429 Problem conforme o
     * contrato com {@code Retry-After} entre 1 e 60 s; o 429 não conta como tentativa do e-mail
     * (contador em 3); outro IP no mesmo instante não é limitado; e, avançado o Clock além da
     * janela (61 s), o IP original volta a ser atendido (4ª tentativa errada => 400 com
     * remainingAttempts=1).
     */
    @Test
    @DisplayName("R-03: o 4º pedido do mesmo IP na janela recebe 429 com Retry-After, não conta tentativa e a janela reabre com o Clock")
    void r_03_limite_por_ip_responde_429_e_reabre_a_janela() throws Exception {
        String email = "limite-" + UUID.randomUUID() + "@teste.com";
        String ip = "10.77." + (int) (Math.random() * 200 + 1) + ".9";

        // Etapa: pedidos 1 a 3 do IP passam pelo limitador (código errado => 400).
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(resgatar(email, "654321", ip)).andExpect(status().isBadRequest());
        }

        // Etapa: o 4º excede o limite => 429 Problem com Retry-After.
        MvcResult limitado = mockMvc.perform(resgatar(email, "654321", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(ContractValidation.matchesContract())
                .andReturn();
        JsonNode body = json.readTree(limitado.getResponse().getContentAsString());
        assertThat(ContractSchemas.schemaFor("Problem").validate(body)).isEmpty();
        assertThat(limitado.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        long retryAfter = Long.parseLong(limitado.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);

        // Etapa: o 429 não tocou no contador do e-mail (continua com as 3 falhas anteriores).
        List<Integer> falhas = jdbc.queryForList(
                "SELECT failed_attempts FROM invite_code_attempts WHERE email = ?", Integer.class, email);
        assertThat(falhas).containsExactly(3);

        // Etapa: o limite é por IP; outro IP é atendido normalmente.
        mockMvc.perform(resgatar(email, "654321", "10.78.0.1")).andExpect(status().isBadRequest());

        // Etapa: passada a janela, o IP original volta a ser atendido (4ª errada do e-mail, mas o
        // outro IP já gastou a 4ª; portanto esta é a 5ª => 423, que prova que o pedido foi processado).
        clock.advance(Duration.ofSeconds(61));
        mockMvc.perform(resgatar(email, "654321", ip)).andExpect(status().isLocked());
    }

    private MockHttpServletRequestBuilder resgatar(String email, String codigo, String ip) throws Exception {
        return post(REDEEM)
                .contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content(json.writeValueAsString(Map.of("email", email, "code", codigo)));
    }
}
