package my.oj.web.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batch and judge roles serve no business HTTP but must still be scrapeable, which
 * {@code server.port=-1} plus a management port on its own delivers. Nothing else covers
 * that pairing: {@code RoleBasedSchedulerActivationTests} and
 * {@code ContestJudgeRoleProfileActivationTests} force {@code WebApplicationType.NONE} in
 * code, so they keep passing with the port settings deleted outright.
 *
 * <p>The web application type is deliberately left to deduction here. A
 * {@code spring.main.web-application-type=none} creeping back into either role profile has
 * to fail this test rather than be overridden by it.
 */
class WorkerRoleManagementPortContractTests {

    @Test
    void multiBatchOpensTheManagementPortAndNoBusinessConnector() throws Exception {
        assertManagementPortOnly("multi-batch");
    }

    @Test
    void multiJudgeOpensTheManagementPortAndNoBusinessConnector() throws Exception {
        assertManagementPortOnly("multi-judge");
    }

    @Test
    void batchRoleKeepsItsManagementPortWithoutTheMultiServerProfile() {
        assertManagementPortIsPairedWith("batch-role");
        assertManagementPortIsPairedWith("multi-batch");
    }

    @Test
    void judgeRoleKeepsItsManagementPortWithoutTheMultiServerProfile() {
        assertManagementPortIsPairedWith("judge-role");
        assertManagementPortIsPairedWith("multi-judge");
    }

    /**
     * compose.yaml leaves the profile list open (BATCH1_PROFILES and friends), so the role
     * profiles can be activated without multi-server. A role that reaches that combination
     * without a management port of its own gets SAME out of
     * {@link ManagementPortType#get(org.springframework.core.env.Environment)}, that SAME
     * port is {@code server.port=-1}, and the process comes up serving no HTTP at all: no
     * exception, healthcheck still green, Prometheus target quietly down. So the pairing is
     * asserted against the role profile alone, not only against the group.
     */
    private static void assertManagementPortIsPairedWith(String profiles) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        // What the repository ships has to be what gets asserted, not what this machine
        // exports: MANAGEMENT_PORT would otherwise feed the ${MANAGEMENT_PORT:9000}
        // placeholder and move the expected value. System properties go with it because
        // Gradle passes spring.profiles.active=test that way, so the profile under test is
        // decided here rather than by property source ordering.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("contract-test", Map.<String, Object>of(
                "spring.config.location", "file:./src/main/resources/",
                "spring.profiles.active", profiles)));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("server.port", Integer.class))
                .as("%s serves no business HTTP", profiles)
                .isEqualTo(-1);
        assertThat(environment.getProperty("management.server.port", Integer.class))
                .as("%s has no management port to fall back to, or the shipped default has "
                        + "drifted from the :9000 targets in observability/prometheus/prometheus.yml",
                        profiles)
                .isEqualTo(9000);
        assertThat(ManagementPortType.get(environment))
                .as("%s would start without binding anything", profiles)
                .isEqualTo(ManagementPortType.DIFFERENT);
    }

    private static void assertManagementPortOnly(String profile) throws IOException, InterruptedException {
        ManagementPortListener managementPort = new ManagementPortListener();
        SpringApplication application = new SpringApplication(ManagementSurfaceApplication.class);
        application.setRegisterShutdownHook(false);
        application.addListeners(managementPort);

        try (ConfigurableApplicationContext context = application.run(
                "--spring.profiles.active=" + profile,
                "--spring.config.location=file:./src/main/resources/",
                // Port 0 rather than the configured 9000: a locally running compose stack
                // already holds 9000 and would turn this into a port-collision failure.
                "--management.server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false")) {

            assertThat(context)
                    .as("the management child context needs a servlet parent to attach to")
                    .isInstanceOf(ServletWebServerApplicationContext.class);
            assertThat(((ServletWebServerApplicationContext) context).getWebServer().getPort())
                    .as("server.port=-1 must leave the business connector unbound")
                    .isNegative();
            assertThat(managementPort.port())
                    .as("no management web server started, so nothing scrapes this role")
                    .isPositive();
            assertThat(scrapeStatus(managementPort.port())).isEqualTo(200);
        }
    }

    private static int scrapeStatus(int port) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isNotBlank();
        return response.statusCode();
    }

    /**
     * The management context publishes its own initialization event, which propagates up to
     * the parent. Declared as a class rather than a lambda so the event type survives
     * erasure and the listener is not handed every event on the context.
     */
    private static final class ManagementPortListener implements ApplicationListener<WebServerInitializedEvent> {

        private volatile int port = -1;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            if ("management".equals(event.getApplicationContext().getServerNamespace())) {
                this.port = event.getWebServer().getPort();
            }
        }

        private int port() {
            return this.port;
        }
    }

    /**
     * Only the servlet and Actuator halves of the application. The role profiles point at
     * MySQL, Redis and RabbitMQ, none of which this contract depends on, so their
     * autoconfiguration is dropped rather than stubbed.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            RabbitAutoConfiguration.class,
            SessionAutoConfiguration.class
    })
    static class ManagementSurfaceApplication {
    }
}
