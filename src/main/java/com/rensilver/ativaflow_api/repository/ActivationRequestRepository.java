package com.rensilver.ativaflow_api.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID>,
		JpaSpecificationExecutor<ActivationRequest> {

	/** Mantém o lock até o término da transação curta que também executa tryClaim. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Query(value = """
			SELECT * FROM activation_requests
			WHERE reconciliation_required = false
			  AND attempt_count < 3
			  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
			  AND (
			      (status = 'PENDING' AND attempt_count = 0 AND next_attempt_at IS NULL)
			      OR (status = 'PROCESSING' AND (
			          next_attempt_at <= CURRENT_TIMESTAMP
			          OR (attempt_count = 0 AND next_attempt_at IS NULL)
			      ))
			  )
			ORDER BY created_at, id
			LIMIT 1
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<ActivationRequest> findNextClaimCandidate();

	/** SQL direto controla versão/auditoria e descarta a entidade anterior ao update. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE activation_requests
			SET status = 'PROCESSING', claimed_by = :workerId,
			    lease_until = CURRENT_TIMESTAMP + (:leaseMillis * INTERVAL '1 millisecond'),
			    version = version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = :id AND version = :expectedVersion
			  AND reconciliation_required = false
			  AND attempt_count < 3
			  AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
			  AND (
			      (status = 'PENDING' AND attempt_count = 0 AND next_attempt_at IS NULL)
			      OR (status = 'PROCESSING' AND (
			          next_attempt_at <= CURRENT_TIMESTAMP
			          OR (attempt_count = 0 AND next_attempt_at IS NULL)
			      ))
			  )
			""", nativeQuery = true)
	int tryClaim(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
			@Param("workerId") String workerId, @Param("leaseMillis") long leaseMillis);

	Optional<ActivationRequest> findByIdAndConsumerId(UUID id, String consumerId);

	@Transactional(readOnly = true)
	Optional<ActivationRequest> findByExternalIdAndConsumerId(String externalId, String consumerId);

	/** Relê o vencedor sem aderir à transação que sofreu a violação de unicidade. */
	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	Optional<ActivationRequest> findWinnerByExternalIdAndConsumerId(String externalId, String consumerId);

	/** Aplica o mesmo escopo de consumidor ao conteúdo e à contagem, apenas com os filtros presentes. */
	default Page<ActivationRequest> findAllByFilters(String consumerId, @Nullable ActivationStatus status,
			@Nullable String customerId, Pageable pageable) {
		return findAll((root, query, builder) -> {
			List<Predicate> filters = new ArrayList<>();
			filters.add(builder.equal(root.get("consumerId"), consumerId));
			if (status != null) {
				filters.add(builder.equal(root.get("status"), status));
			}
			if (customerId != null) {
				filters.add(builder.equal(root.get("customerId"), customerId));
			}
			return builder.and(filters.toArray(Predicate[]::new));
		}, pageable);
	}
}
