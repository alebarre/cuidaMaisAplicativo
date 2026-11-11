package br.com.cuidamais.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * T-016, plan D-24: o profile {@code prod} envia pelo Gmail ({@code smtp.gmail.com:587}, STARTTLS,
 * autenticado) com usuário e senha vindos <strong>somente</strong> de variáveis de ambiente
 * ({@code MAIL_USERNAME}, {@code MAIL_PASSWORD}); {@code dev} e {@code test} apontam para o
 * Mailpit sem autenticação. Lê os {@code application-*.yml} como YAML, sem subir o contexto, para
 * que o teste não dependa de segredos na máquina.
 */
class MailProfilesTest {

    private static Properties yaml(String arquivo) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(arquivo));
        Properties properties = factory.getObject();
        assertThat(properties).as("%s existe e é YAML válido", arquivo).isNotNull();
        return properties;
    }

    @Test
    @DisplayName("D-24 prod: host smtp.gmail.com e porta 587")
    void d_24_prod_usa_gmail_na_porta_587() {
        Properties prod = yaml("application-prod.yml");

        assertThat(prod.getProperty("spring.mail.host")).contains("smtp.gmail.com");
        assertThat(prod.getProperty("spring.mail.port")).contains("587");
    }

    @Test
    @DisplayName("D-24 prod: STARTTLS habilitado e obrigatório, com autenticação SMTP")
    void d_24_prod_exige_starttls_e_autenticacao() {
        Properties prod = yaml("application-prod.yml");

        assertThat(prod.getProperty("spring.mail.properties.mail.smtp.auth")).isEqualTo("true");
        assertThat(prod.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(prod.getProperty("spring.mail.properties.mail.smtp.starttls.required")).isEqualTo("true");
    }

    @Test
    @DisplayName("D-24 prod: usuário e senha vêm de MAIL_USERNAME/MAIL_PASSWORD, sem valor padrão nem literal no repositório")
    void d_24_prod_le_usuario_e_senha_so_de_variaveis_de_ambiente() {
        Properties prod = yaml("application-prod.yml");

        assertThat(prod.getProperty("spring.mail.username")).isEqualTo("${MAIL_USERNAME}");
        assertThat(prod.getProperty("spring.mail.password")).isEqualTo("${MAIL_PASSWORD}");
    }

    @ParameterizedTest(name = "application-{0}.yml")
    @ValueSource(strings = {"dev", "test"})
    @DisplayName("D-24 dev e test: Mailpit sem autenticação nem STARTTLS")
    void d_24_dev_e_test_nao_autenticam(String profile) {
        Properties properties = yaml("application-" + profile + ".yml");

        assertThat(properties.getProperty("spring.mail.properties.mail.smtp.auth")).isEqualTo("false");
        assertThat(properties.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("false");
    }

    @Test
    @DisplayName("D-24 dev: aponta para o Mailpit local (porta SMTP 1025) por padrão")
    void d_24_dev_aponta_para_mailpit_local() {
        Properties dev = yaml("application-dev.yml");

        assertThat(dev.getProperty("spring.mail.host")).contains("localhost");
        assertThat(dev.getProperty("spring.mail.port")).contains("1025");
    }

    @Test
    @DisplayName("D-24 remetente app.mail.from é configurável por MAIL_FROM com padrão do Cuida+")
    void d_24_remetente_configuravel_por_mail_from() {
        Properties base = yaml("application.yml");

        assertThat(base.getProperty("app.mail.from")).startsWith("${MAIL_FROM:").contains("Cuida+");
    }
}
