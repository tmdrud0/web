package my.oj.web.api;

import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A controller whose errors must be JSON.
 *
 * <p>This exists because {@link JsonApiExceptionHandler} has to be scoped to the API and not to
 * the pages, and the obvious way to scope it - a list of package names on the advice - is a list
 * that has to be edited from somewhere else every time an API package is added. That list was
 * wrong within the commit that introduced it: {@code my.oj.web.user.api} was created and not
 * added, so a malformed login body answered 500 under the perf profile and a bodyless 400 outside
 * it.
 *
 * <p>A marker cannot go stale the same way. It is meta-annotated {@link RestController}, so a
 * controller declares itself part of the JSON API with the one annotation it already needed, and
 * there is no second place that has to agree.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RestController
public @interface JsonApiController {
}
