package consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.method.RateLimitDeniedException;
import com.ratelimitly.spring.method.RateLimited;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class InstalledStarterTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .withPropertyValues("ratelimitly.enabled=true", "ratelimitly.servlet.enabled=false");

    @Test
    void installedStarterDiscoversAutoConfigurationAndProtectsNonWebMethod() {
        var client = new Client();
        runner.withBean(Client.class, () -> client).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MethodRateLimitEnforcer.class);
            // The integration must really come from an installed jar, not reactor classes.
            assertThat(MethodRateLimitEnforcer.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .endsWith(".jar");
            var service = context.getBean(Service.class);
            assertThat(service.serve(null)).isEqualTo("served:null");
            client.allow = false;
            assertThrows(RateLimitDeniedException.class, () -> service.serve("not served"));
            assertThat(client.checks).isEqualTo(2);
            assertThat(context).doesNotHaveBean(io.micrometer.core.instrument.MeterRegistry.class);
        });
        assertThat(client.closes).isEqualTo(1);
    }

    @Test
    void clientOnlyModeDoesNotInstallMethodAdvice() {
        var client = new Client();
        runner.withPropertyValues("ratelimitly.method.enabled=false")
            .withBean(Client.class, () -> client).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(MethodRateLimitEnforcer.class);
                assertThat(context.getBean(Service.class).serve("local")).isEqualTo("served:local");
                assertThat(client.checks).isZero();
            });
        assertThat(client.closes).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean Service service() { return new Service(); }
    }

    public static class Service {
        @RateLimited(context = "consumer", reportLatency = false)
        public String serve(String value) { return "served:" + value; }
    }

    static class Client implements RateLimitlyClient {
        boolean allow = true;
        int checks;
        int closes;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            checks++;
            return new RateLimitDecision(allow, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) { throw new AssertionError("unexpected report"); }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected report"));
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { closes++; }
    }
}
