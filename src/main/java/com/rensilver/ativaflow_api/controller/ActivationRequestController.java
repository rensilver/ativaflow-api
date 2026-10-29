package com.rensilver.ativaflow_api.controller;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestPageResponse;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
public class ActivationRequestController {

	private final ActivationRequestService service;

	@GetMapping
	public ResponseEntity<ActivationRequestPageResponse> listActivationRequests(
			@RequestParam(required = false) @Nullable ActivationStatus status,
			@RequestParam(required = false) @Nullable @Size(max = 100)
			@Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must contain non-blank text") String customerId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Principal principal) {
		return ResponseEntity.ok(service.listActivationRequests(status, customerId, page, size, principal.getName()));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ActivationRequestResponse> getActivationRequestById(@PathVariable UUID id, Principal principal) {
		return ResponseEntity.ok(service.getActivationRequestById(id, principal.getName()));
	}

	@PostMapping
	public ResponseEntity<ActivationRequestResponse> createActivationRequest(
			@Valid @RequestBody CreateActivationRequest request, Principal principal) {
		ActivationRequestResponse created = service.createActivationRequest(request, principal.getName());
		URI location = UriComponentsBuilder.fromPath("/api/v1/requests/{id}")
				.buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}
}
