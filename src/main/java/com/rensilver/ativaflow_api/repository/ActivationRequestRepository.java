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
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID>,
		JpaSpecificationExecutor<ActivationRequest> {

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
