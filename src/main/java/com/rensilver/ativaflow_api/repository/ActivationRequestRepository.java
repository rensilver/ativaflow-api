package com.rensilver.ativaflow_api.repository;

import java.util.Optional;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {

	Optional<ActivationRequest> findByIdAndConsumerId(UUID id, String consumerId);

	@Query("""
			SELECT request FROM ActivationRequest request
			WHERE request.consumerId = :consumerId
			  AND (:status IS NULL OR request.status = :status)
			  AND (:customerId IS NULL OR request.customerId = :customerId)
			""")
	Page<ActivationRequest> findAllByFilters(@Param("consumerId") String consumerId,
			@Param("status") @Nullable ActivationStatus status,
			@Param("customerId") @Nullable String customerId, Pageable pageable);
}
