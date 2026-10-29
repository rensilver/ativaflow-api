package com.rensilver.ativaflow_api.dto.response;

import java.util.List;

/** Contrato público de paginação, independente da serialização de Page do Spring Data. */
public record ActivationRequestPageResponse(
		List<ActivationRequestResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
