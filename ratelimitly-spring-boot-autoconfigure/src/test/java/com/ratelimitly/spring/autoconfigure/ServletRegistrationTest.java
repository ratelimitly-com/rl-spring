package com.ratelimitly.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.spring.servlet.RateLimitlyHandlerInterceptor;
import com.ratelimitly.spring.servlet.RateLimitlyServletFilter;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class ServletRegistrationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class,
            RateLimitlyServletAutoConfiguration.class, RateLimitlyObservationAutoConfiguration.class))
        .withPropertyValues("ratelimitly.enabled=true")
        .withBean(RateLimitlyClient.class, UnusedClient::new);

    @ParameterizedTest
    @ValueSource(strings = {"ratelimitly.servlet.interceptor-enabled=true", "unrelated.setting=true"})
    void overlappingAlternativeAdaptersFailStartup(String property) {
        runner.withPropertyValues("ratelimitly.servlet.filter-enabled=true", property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("filter-enabled")
                .hasStackTraceContaining("interceptor-enabled");
        });
    }

    @Test
    void disabledServletIntegrationIgnoresInactiveAdapterChoices() {
        runner.withPropertyValues("ratelimitly.servlet.enabled=false", "ratelimitly.servlet.filter-enabled=true")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RateLimitlyServletFilter.class)
                .doesNotHaveBean(RateLimitlyHandlerInterceptor.class));
    }

    @Test
    void rawFilterCoversInternalDispatchesAndAsync() {
        runner.withPropertyValues("ratelimitly.servlet.filter-enabled=true",
                "ratelimitly.servlet.interceptor-enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                assertThat(registration.isAsyncSupported()).isTrue();
                assertThat(registration.determineDispatcherTypes()).isEqualTo(EnumSet.allOf(DispatcherType.class));
            });
    }

    private static class UnusedClient implements RateLimitlyClient {
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            throw new AssertionError("startup must not perform admission");
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("startup must not perform admission"));
        }
        @Override public void reportLatency(LatencyReport report) { throw new AssertionError("unexpected report"); }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected report"));
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
