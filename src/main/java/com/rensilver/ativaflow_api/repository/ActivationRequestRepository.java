package com.rensilver.ativaflow_api.repository;

import java.time.Instant;
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
			  AND attempt_count < :maxAttempts
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
	Optional<ActivationRequest> findNextClaimCandidate(@Param("maxAttempts") int maxAttempts);

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
			  AND attempt_count < :maxAttempts
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
			@Param("workerId") String workerId, @Param("leaseMillis") long leaseMillis,
			@Param("maxAttempts") int maxAttempts);

	/** Libera apenas a posse vencida; nenhum resultado externo é inferido pela expiração. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			WITH expired AS (
			    SELECT id, version FROM activation_requests
			    WHERE status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP
			    ORDER BY lease_until, id
			    LIMIT :batchSize
			    FOR UPDATE SKIP LOCKED
			)
			UPDATE activation_requests AS request
			SET claimed_by = NULL, lease_until = NULL,
			    version = request.version + 1, updated_at = CURRENT_TIMESTAMP
			FROM expired
			WHERE request.id = expired.id AND request.version = expired.version
			  AND request.status = 'PROCESSING' AND request.lease_until <= CURRENT_TIMESTAMP
			""", nativeQuery = true)
	int releaseExpiredLeases(@Param("batchSize") int batchSize);

	/** Registra a incerteza antes do HTTP, na mesma escrita que consome a tentativa. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE activation_requests
			SET attempt_count = attempt_count + 1, next_attempt_at = NULL,
			    reconciliation_required = true, version = version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = :id AND version = :expectedVersion
			  AND status = 'PROCESSING' AND claimed_by = :workerId
			  AND lease_until > CURRENT_TIMESTAMP
			  AND reconciliation_required = false AND attempt_count < :maxAttempts
			  AND (next_attempt_at <= CURRENT_TIMESTAMP OR (attempt_count = 0 AND next_attempt_at IS NULL))
			""", nativeQuery = true)
	int tryPrepareAttempt(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
			@Param("workerId") String workerId, @Param("maxAttempts") int maxAttempts);

	/** Resultado desconhecido não agenda retry nem encerra a solicitação por esgotamento de tentativas. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE activation_requests
			SET reconciliation_required = true, next_attempt_at = NULL, last_error_code = :errorCode,
			    claimed_by = NULL, lease_until = NULL,
			    version = version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = :id AND version = :expectedVersion
			  AND status = 'PROCESSING' AND claimed_by = :workerId
			  AND lease_until > CURRENT_TIMESTAMP
			  AND reconciliation_required = true AND attempt_count > 0
			""", nativeQuery = true)
	int tryRecordAmbiguousOutcome(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
			@Param("workerId") String workerId, @Param("errorCode") String errorCode);

	/** Somente resultado confirmado pode remover a proteção preventiva e, se seguro, agendar retry. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE activation_requests
			SET status = CASE
			        WHEN :success THEN 'COMPLETED'
			        WHEN :retryable AND attempt_count < :maxAttempts THEN 'PROCESSING'
			        ELSE 'FAILED' END,
			    next_attempt_at = CASE
			        WHEN :retryable AND NOT :success AND attempt_count < :maxAttempts
			        THEN GREATEST(CURRENT_TIMESTAMP + (:delayMillis * INTERVAL '1 millisecond'),
			                      CAST(:retryAfter AS TIMESTAMP WITH TIME ZONE))
			        ELSE NULL END,
			    reconciliation_required = false, last_error_code = :errorCode,
			    claimed_by = NULL, lease_until = NULL,
			    version = version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = :id AND version = :expectedVersion
			  AND status = 'PROCESSING' AND claimed_by = :workerId
			  AND lease_until > CURRENT_TIMESTAMP
			  AND reconciliation_required = true AND attempt_count BETWEEN 1 AND 3
			  AND attempt_count = :attemptCount
			  AND (NOT :retryable OR :success OR attempt_count >= :maxAttempts OR :delayMillis >= 1)
			""", nativeQuery = true)
	int tryRecordConfirmedOutcome(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
			@Param("workerId") String workerId, @Param("attemptCount") int attemptCount,
			@Param("success") boolean success, @Param("retryable") boolean retryable,
			@Param("errorCode") @Nullable String errorCode, @Param("delayMillis") long delayMillis,
			@Param("retryAfter") @Nullable Instant retryAfter, @Param("maxAttempts") int maxAttempts);

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
