package br.com.cuidamais.notification.service;

/** Assunto e corpo (texto simples, pt-BR) prontos para envio. */
public record EmailMessage(String subject, String body) {}
