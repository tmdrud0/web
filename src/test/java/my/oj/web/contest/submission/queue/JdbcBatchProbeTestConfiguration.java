package my.oj.web.contest.submission.queue;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;

@TestConfiguration
class JdbcBatchProbeTestConfiguration {

    @Bean
    JdbcBatchProbe jdbcBatchProbe() {
        return new JdbcBatchProbe();
    }

    @Bean
    static BeanPostProcessor jdbcBatchProbeBeanPostProcessor(JdbcBatchProbe probe) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DataSource dataSource) || !beanName.equals("dataSource")) {
                    return bean;
                }
                return Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[]{DataSource.class},
                        (proxy, method, args) -> {
                            Object result = method.invoke(dataSource, args);
                            if (!method.getName().equals("getConnection")
                                    || !(result instanceof Connection connection)) {
                                return result;
                            }
                            return wrapConnection(connection, probe);
                        }
                );
            }
        };
    }

    static String normalizeSql(String sql) {
        return sql == null ? "" : sql
                .replace('`', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static Connection wrapConnection(Connection connection, JdbcBatchProbe probe) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(connection, args);
                    if (!method.getName().startsWith("prepareStatement")
                            || args == null
                            || args.length == 0
                            || !(args[0] instanceof String sql)) {
                        return result;
                    }
                    String normalizedSql = normalizeSql(sql);
                    if (!(result instanceof PreparedStatement preparedStatement)
                            || !normalizedSql.contains("insert into contest_submission")) {
                        return result;
                    }
                    probe.recordTargetSql(normalizedSql);
                    return wrapPreparedStatement(preparedStatement, normalizedSql, probe);
                }
        );
    }

    private static PreparedStatement wrapPreparedStatement(PreparedStatement preparedStatement,
                                                            String sql,
                                                            JdbcBatchProbe probe) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "addBatch" -> probe.recordAddBatch(sql);
                        case "executeBatch", "executeLargeBatch" -> probe.recordExecuteBatch(sql);
                        case "executeUpdate", "executeLargeUpdate", "execute" -> probe.recordExecuteUpdate(sql);
                        default -> {
                        }
                    }
                    return method.invoke(preparedStatement, args);
                }
        );
    }
}
