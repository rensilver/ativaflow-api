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
import org.springframework.lang.Nullable;

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
	@Nullable
	private Long version;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	@Nullable
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	@Nullable
	private Instant updatedAt;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	@Nullable
	private Instant nextAttemptAt;

	@Column(name = "lease_until")
	@Nullable
	private Instant leaseUntil;

	@Column(name = "claimed_by", length = 100)
	@Nullable
	private String claimedBy;

	@Column(name = "last_error_code", length = 100)
	@Nullable
	private String lastErrorCode;

	@Column(name = "reconciliation_required", nullable = false)
	private boolean reconciliationRequired;

	protected ActivationRequest() {
		// Exigido pelo Hibernate; o código da aplicação usa a fábrica de domínio.
	}

	/**
	 * Cria uma solicitação pendente com um identificador gerado pelo servidor. A aplicação deve
	 * fornecer trustedConsumerId a partir da identidade autenticada, nunca do corpo da requisição.
	 * A persistência gerencia as datas de auditoria e a versão; os mapeadores apenas produzem respostas.
	 * Valores nulos são recebidos pela fronteira de validação e rejeitados com IllegalArgumentException.
	 * O retorno, quando a criação é válida, nunca é nulo.
	 */
	public static ActivationRequest create(@Nullable String externalId, @Nullable String customerId,
			@Nullable String productCode, @Nullable String trustedConsumerId) {
		return new ActivationRequest(externalId, customerId, productCode, trustedConsumerId);
	}

	private ActivationRequest(@Nullable String externalId, @Nullable String customerId,
			@Nullable String productCode, @Nullable String consumerId) {
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

	/** Registra um código de erro categorizado, e não a resposta bruta do provedor. */
	public void fail(@Nullable String errorCode) {
		requireStatus(ActivationStatus.PROCESSING, ActivationStatus.FAILED);
		lastErrorCode = requireText(errorCode, "errorCode");
		status = ActivationStatus.FAILED;
	}

	private void requireStatus(ActivationStatus expected, ActivationStatus target) {
		if (status != expected) {
			throw new InvalidActivationStatusTransitionException(status, target);
		}
	}

	private static String requireText(@Nullable String value, String field) {
		if (value == null || value.isBlank() || value.length() > 100) {
			throw new IllegalArgumentException(field + " must contain non-blank text of at most 100 characters");
		}
		return value;
	}
}
