package com.rensilver.ativaflow_api.controller;

import java.net.URI;
import java.security.Principal;

import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
public class ActivationRequestController {

	private final ActivationRequestService service;

	@PostMapping
	public ResponseEntity<ActivationRequestResponse> create(
			@Valid @RequestBody CreateActivationRequest request, Principal principal) {
		ActivationRequestResponse created = service.create(request, principal.getName());
		URI location = UriComponentsBuilder.fromPath("/api/v1/requests/{id}")
				.buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}
}
