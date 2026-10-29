package com.rensilver.ativaflow_api.repository;

import java.util.Optional;
import java.util.UUID;

import com.rensilver.ativaflow_api.model.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

	Optional<ActivationRequest> findByIdAndConsumerId(UUID id, String consumerId);
}
