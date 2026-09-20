package my.oj.web.contest.scoreboard;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

/**
 * {@link PositiveDuration}, as a comparison against zero.
 *
 * <p>A missing value passes. Nothing is bound to null here - a property with a {@code @DefaultValue}
 * is never absent - and if one ever were, "not configured" and "configured to zero" are different
 * mistakes and only the second one is this constraint's.</p>
 */
public class PositiveDurationValidator implements ConstraintValidator<PositiveDuration, Duration> {

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        return value == null || (!value.isZero() && !value.isNegative());
    }
}
