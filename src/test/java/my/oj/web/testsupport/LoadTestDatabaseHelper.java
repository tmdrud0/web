package my.oj.web.testsupport;

import java.util.Arrays;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

public final class LoadTestDatabaseHelper {

    private LoadTestDatabaseHelper() {
    }

    public static void truncateTables(JdbcTemplate jdbcTemplate, String... tables) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        if (tables == null || tables.length == 0) {
            return;
        }

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        Arrays.stream(tables)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(table -> !table.isEmpty())
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}

