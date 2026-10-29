package com.rensilver.ativaflow_api.service;

import java.sql.SQLException;

import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.exception.ActivationRequestConflictException;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTests {

	private static final String CONSUMER = "consumer-a";
	private static final CreateActivationRequest REQUEST =
			new CreateActivationRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1");

	@Mock
	private ActivationRequestRepository repository;

	@Mock
	private ActivationRequestCreationTransaction creationTransaction;

	@Test
	void returnsGenericConflictWhenUniqueViolationCannotBeResolvedToAnAccessibleRecord() {
		var sqlException = new SQLException("private database details", "23505");
		var violation = new ConstraintViolationException("private database details", sqlException,
				"uk_activation_requests_external_id");
		when(creationTransaction.insert(REQUEST, CONSUMER))
				.thenThrow(new DataIntegrityViolationException("private database details", violation));
		var service = new ActivationRequestService(repository, Mappers.getMapper(ActivationRequestMapper.class),
				creationTransaction);

		assertThatThrownBy(() -> service.createActivationRequest(REQUEST, CONSUMER))
				.isInstanceOf(ActivationRequestConflictException.class)
				.hasMessage("Activation request conflict")
				.hasNoCause();
	}

	@Test
	void doesNotTreatOtherIntegrityViolationsAsExternalIdConflicts() {
		var sqlException = new SQLException("other constraint", "23514");
		var violation = new ConstraintViolationException("other constraint", sqlException,
				"ck_activation_requests_status");
		var failure = new DataIntegrityViolationException("other constraint", violation);
		when(creationTransaction.insert(REQUEST, CONSUMER)).thenThrow(failure);
		var service = new ActivationRequestService(repository, Mappers.getMapper(ActivationRequestMapper.class),
				creationTransaction);

		assertThatThrownBy(() -> service.createActivationRequest(REQUEST, CONSUMER)).isSameAs(failure);
	}
}
