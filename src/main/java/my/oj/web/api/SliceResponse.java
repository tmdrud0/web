package my.oj.web.api;

import org.springframework.data.domain.Slice;

import java.util.List;

/**
 * A keyset page: the rows, and whether asking again would return more.
 *
 * <p>There is no total. The queries behind these endpoints page by cursor precisely so they never
 * have to count the table, and reporting a total here would put that count back.
 */
public record SliceResponse<T>(List<T> content, int size, boolean hasNext) {

    public SliceResponse {
        content = List.copyOf(content);
    }

    public static <T> SliceResponse<T> of(Slice<T> slice) {
        return new SliceResponse<>(slice.getContent(), slice.getContent().size(), slice.hasNext());
    }
}
