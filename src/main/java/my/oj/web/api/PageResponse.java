package my.oj.web.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * An offset page, serialised on this application's terms.
 *
 * <p>Returning Spring Data's {@code Page} directly would work and is what the model attributes
 * did, but its JSON is the shape of {@code PageImpl}'s getters rather than a contract - Boot warns
 * about exactly that - and it carries a {@code Pageable} and a {@code Sort} that say nothing a
 * caller of this API needs. This carries the four numbers a client pages with.
 */
public record PageResponse<T>(List<T> content,
                              int page,
                              int size,
                              long totalElements,
                              int totalPages,
                              boolean hasNext) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page, Function.identity());
    }
}
