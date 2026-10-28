package com.rensilver.ativaflow_api.model;

import java.time.Instant;
import java.util.UUID;

import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.domain.exception.InvalidActivationStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "activation_requests", uniqueConstraints =
		@UniqueConstraint(name = "uk_activation_requests_external_id", columnNames = "external_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
public class ActivationRequest {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "external_id", nullable = false, length = 100, updatable = false)
	private String externalId;

	@Column(name = "customer_id", nullable = false, length = 100, updatable = false)
	private String customerId;

	@Column(name = "product_code", nullable = false, length = 100, updatable = false)
	private String productCode;

	@Column(name = "consumer_id", nullable = false, length = 100, updatable = false)
	private String consumerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ActivationStatus status;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "claimed_by", length = 100)
	private String claimedBy;

	@Column(name = "last_error_code", length = 100)
	private String lastErrorCode;

	@Column(name = "reconciliation_required", nullable = false)
	private boolean reconciliationRequired;

	protected ActivationRequest() {
		// Required by Hibernate; application code uses the validated constructor.
	}

	/**
	 * consumerId must come from the authenticated identity, never from the API payload.
	 */
	public ActivationRequest(String externalId, String customerId, String productCode, String consumerId) {
		this.externalId = requireText(externalId, "externalId");
		this.customerId = requireText(customerId, "customerId");
		this.productCode = requireText(productCode, "productCode");
		this.consumerId = requireText(consumerId, "consumerId");
		this.id = UUID.randomUUID();
		this.status = ActivationStatus.PENDING;
	}

	public void startProcessing() {
		requireStatus(ActivationStatus.PENDING, ActivationStatus.PROCESSING);
		status = ActivationStatus.PROCESSING;
	}

	public void complete() {
		requireStatus(ActivationStatus.PROCESSING, ActivationStatus.COMPLETED);
		status = ActivationStatus.COMPLETED;
	}

	/** Records a categorized error code, not a raw provider response. */
	public void fail(String errorCode) {
		requireStatus(ActivationStatus.PROCESSING, ActivationStatus.FAILED);
		lastErrorCode = requireText(errorCode, "errorCode");
		status = ActivationStatus.FAILED;
	}

	private void requireStatus(ActivationStatus expected, ActivationStatus target) {
		if (status != expected) {
			throw new InvalidActivationStatusTransitionException(status, target);
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank() || value.length() > 100) {
			throw new IllegalArgumentException(field + " must contain non-blank text of at most 100 characters");
		}
		return value;
	}
}
