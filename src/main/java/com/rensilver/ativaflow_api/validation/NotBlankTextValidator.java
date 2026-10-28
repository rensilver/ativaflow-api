package com.rensilver.ativaflow_api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotBlankTextValidator implements ConstraintValidator<NotBlankText, CharSequence> {

	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		return value != null && !value.toString().isBlank();
	}
}
