package br.com.cuidamais.membership.dto;

import java.util.List;

/** Página de convites (schema {@code InvitationPage}: PageMeta + {@code items}). */
public record InvitationPageResponse(
        int page, int size, long totalItems, int totalPages, List<InvitationResponse> items) {}
