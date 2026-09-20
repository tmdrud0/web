package my.oj.web.contest.scoreboard;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A configured duration that is longer than nothing at all.
 *
 * <p>Jakarta Bean Validation has no constraint that fits a {@link java.time.Duration}, and Spring
 * Boot's {@code @DurationMin} is not on this classpath, so the bound is stated here. It exists
 * because the alternative this codebase uses elsewhere - a {@code Math.max} normaliser - is wrong for
 * a period: a check interval of zero means the operator asked for a cadence that cannot be
 * delivered, and quietly running the check on some other cadence hides that rather than reporting
 * it. The value is refused at context startup, the same way the integer bounds beside it are.</p>
 *
 * <p>One millisecond rather than one second, because several of these periods are sub-second by
 * default or in tests - a receive timeout of 50ms, a retry backoff of 10ms - so a coarser floor
 * would refuse configurations that work.</p>
 */
@Target({
        ElementType.METHOD,
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR
})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PositiveDurationValidator.class)
public @interface PositiveDuration {

    String message() default "must be a positive duration";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
