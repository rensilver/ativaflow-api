package com.rensilver.ativaflow_api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Rejeita texto nulo ou em branco usando a mesma semântica Unicode de String.isBlank(). */
@Documented
@Constraint(validatedBy = NotBlankTextValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE,
		ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlankText {

	String message() default "must contain non-blank text";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
