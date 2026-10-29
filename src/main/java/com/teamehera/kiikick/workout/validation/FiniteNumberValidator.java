package com.teamehera.kiikick.workout.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FiniteNumberValidator implements ConstraintValidator<FiniteNumber, Double> {

    @Override
    public boolean isValid(Double value, ConstraintValidatorContext context) {
        return value == null || Double.isFinite(value);
    }
}
