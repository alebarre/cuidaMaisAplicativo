package br.com.cuidamais.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Habilita {@code @Async} para o envio de e-mail fora da thread que publicou o evento (plan D-23).
 * Fica no pacote {@code notification}, e não na classe principal, para que cada domínio ligue só
 * o que precisa (D-01). O executor é o {@code applicationTaskExecutor} do Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class NotificationConfig {}
