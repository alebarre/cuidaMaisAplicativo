package br.com.cuidamais.shared.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Faz o {@code MockMvc} de {@code @AutoConfigureMockMvc} respeitar {@code
 * server.servlet.context-path} (application.yml, T-020: {@code /api/v1}).
 *
 * <p>Em {@code @SpringBootTest} com ambiente web mock (sem servidor embarcado real), o {@code
 * MockServletContext} nasce sempre com {@code contextPath=""} — {@code
 * server.servlet.context-path} só é aplicado pelo container real (produção ou um teste com
 * {@code webEnvironment = RANDOM_PORT}). Sem este ajuste, uma requisição construída com o path
 * completo do contrato (ex. {@code /api/v1/auth/signup}, como fazem os testes de API desta
 * feature) nunca bateria em nenhum {@code @RequestMapping}, porque o {@code DispatcherServlet} de
 * teste trataria a URL inteira como "caminho dentro da aplicação", sem descontar o prefixo.
 *
 * <p>A correção é registrar um {@code MockMvcBuilderCustomizer} (ponto de extensão oficial do
 * Spring Boot, descoberto automaticamente pelo {@code MockMvcAutoConfiguration}) que define uma
 * requisição padrão só com o {@code contextPath}: {@link
 * org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder#merge}
 * propaga esse valor para toda requisição construída nos testes que não o define explicitamente,
 * fazendo o {@code MockMvc} calcular corretamente o caminho relativo ao contexto (ex. {@code
 * /auth/signup}) exatamente como o servidor real faz. Isso evita que cada controller precise
 * repetir {@code /api/v1} no próprio {@code @RequestMapping} (T-020).
 */
@TestConfiguration
public class MockMvcContextPathConfig {

    @Bean
    public MockMvcBuilderCustomizer mockMvcContextPathCustomizer(
            @Value("${server.servlet.context-path:}") String contextPath) {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").contextPath(contextPath));
    }
}
