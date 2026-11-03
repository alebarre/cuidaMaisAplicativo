package br.com.cuidamais.membership.dto;

import java.util.List;

/** Página de membros (schema {@code MemberPage}: PageMeta + {@code items}), T-043. */
public record MemberPageResponse(int page, int size, long totalItems, int totalPages, List<MemberResponse> items) {}
