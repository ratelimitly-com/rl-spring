package com.ratelimitly.spring.autoconfigure;

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
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodLatencyGuard;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.method.RateLimitDeniedException;
import com.ratelimitly.spring.method.RateLimited;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.servlet.RateLimitlyHandlerInterceptor;
import com.ratelimitly.spring.servlet.RateLimitlyServletFilter;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;
import com.ratelimitly.spring.servlet.ServletRequestContext;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActivationSafetyTest {
    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(
        RateLimitlyAutoConfiguration.class, RateLimitlyServletAutoConfiguration.class,
        RateLimitlyMethodAutoConfiguration.class, RateLimitlyObservationAutoConfiguration.class);

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
        .withConfiguration(CONFIGURATIONS);

    @ParameterizedTest
    @ValueSource(strings = {"ratelimitly.enabled=false", "unrelated.enabled=false"})
    void globalOptInIsRequiredEvenWithApplicationClient(String property) {
        RecordingClient client = new RecordingClient();
        webRunner.withPropertyValues(property, "ratelimitly.servlet.filter-enabled=true")
            .withBean(RateLimitlyClient.class, () -> client)
            .withBean(ProtectedService.class, ProtectedService::new)
            .run(context -> {
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(MethodRateLimitEnforcer.class)
                    .doesNotHaveBean(ServletRateLimitEnforcer.class)
                    .doesNotHaveBean(RateLimitlyServletFilter.class)
                    .doesNotHaveBean(RateLimitlyHandlerInterceptor.class)
                    .doesNotHaveBean(RateLimitlyObservationRecorder.class);
                assertThat(context.getBean(ProtectedService.class).serve()).isEqualTo("served");
                assertThat(client.requests).isZero();
            });
    }

    @Test
    void applicationSupportBeansDoNotBypassGlobalDisable() {
        webRunner.withPropertyValues("ratelimitly.enabled=false")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withBean(RateLimitlyProperties.class, RateLimitlyProperties::new)
            .withBean(RateLimitDecisionEvaluator.class, RateLimitDecisionEvaluator::new)
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(MethodRateLimitEnforcer.class)
                .doesNotHaveBean(ServletRateLimitEnforcer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metricsSwitchDoesNotChangeMethodOrServletAdmission(boolean metricsEnabled) {
        RecordingClient client = new RecordingClient();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            webRunner.withPropertyValues("ratelimitly.enabled=true",
                    "ratelimitly.observation.enabled=" + metricsEnabled)
                .withBean(RateLimitlyClient.class, () -> client)
                .withBean(SimpleMeterRegistry.class, () -> registry)
                .withBean(ProtectedService.class, ProtectedService::new)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                        .hasSingleBean(RateLimitlyObservationRecorder.class)
                        .hasSingleBean(MethodRateLimitEnforcer.class)
                        .hasSingleBean(ServletRateLimitEnforcer.class);
                    ProtectedService service = context.getBean(ProtectedService.class);
                    ServletRateLimitEnforcer servlet = context.getBean(ServletRateLimitEnforcer.class);
                    assertThat(service.serve()).isEqualTo("served");
                    assertThat(servlet.enforce(servletContext()).proceed()).isTrue();
                    client.allowed = false;
                    assertThrows(RateLimitDeniedException.class, service::serve);
                    ServletRequestContext denied = servletContext();
                    assertThat(servlet.enforce(denied).proceed()).isFalse();
                    assertThat(denied.response().getStatus()).isEqualTo(429);
                    assertThat(client.requests).isEqualTo(4);
                    if (metricsEnabled) {
                        assertThat(registry.get("ratelimitly.requests.allowed").counter().count()).isEqualTo(2);
                        assertThat(registry.get("ratelimitly.requests.denied").counter().count()).isEqualTo(2);
                    } else {
                        assertThat(registry.getMeters()).isEmpty();
                    }
                });
        } finally {
            registry.close();
        }
    }

    @Test
    void noMeterRegistryStillAllowsEnforcement() {
        RecordingClient client = new RecordingClient();
        webRunner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, () -> client)
            .withBean(ProtectedService.class, ProtectedService::new)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyObservationRecorder.class);
                assertThat(context.getBean(ProtectedService.class).serve()).isEqualTo("served");
                assertThat(client.requests).isEqualTo(1);
            });
    }

    @Test
    void explicitRecorderOverridesDefaultEvenWithAutomaticMetricsDisabled() {
        RateLimitlyObservationRecorder recorder = new RateLimitlyObservationRecorder(null);
        webRunner.withPropertyValues("ratelimitly.enabled=true", "ratelimitly.observation.enabled=false")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withBean(RateLimitlyObservationRecorder.class, () -> recorder)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyObservationRecorder.class);
                assertThat(context.getBean(RateLimitlyObservationRecorder.class)).isSameAs(recorder);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"webflux.enabled=true", "webflux.enabled=false", "webflux.report-latency=true"})
    void unsupportedWebfluxPropertiesFailBinding(String property) {
        webRunner.withPropertyValues("ratelimitly.enabled=true", "ratelimitly." + property)
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("left unbound")
                    .hasStackTraceContaining("ratelimitly." + property.substring(0, property.indexOf('=')));
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void servletAndMethodIntegrationsCanBeSelectedIndependently(boolean servletEnabled) {
        webRunner.withPropertyValues("ratelimitly.enabled=true",
                "ratelimitly.servlet.enabled=" + servletEnabled,
                "ratelimitly.method.enabled=" + !servletEnabled)
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(ServletRateLimitEnforcer.class)).hasSize(servletEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(MethodRateLimitEnforcer.class)).hasSize(servletEnabled ? 0 : 1);
            });
    }

    @Test
    void methodProtectionDoesNotRequireServletApi() {
        new ApplicationContextRunner().withConfiguration(CONFIGURATIONS)
            .withClassLoader(new FilteredClassLoader("jakarta.servlet"))
            .withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withBean(ProtectedService.class, ProtectedService::new)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(MethodRateLimitEnforcer.class)
                    .doesNotHaveBean("servletRateLimitEnforcer");
                assertThat(context.getBean(ProtectedService.class).serve()).isEqualTo("served");
            });
    }

    @Test
    void customMethodComponentsDoNotSuppressServletDefaults() {
        webRunner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withUserConfiguration(MethodOverrides.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(MethodRateLimitEnforcer.class)
                    .hasSingleBean(ServletRateLimitEnforcer.class)
                    .hasBean("servletRateLimitlyPolicyResolver")
                    .hasBean("servletRateLimitlyLabelResolver")
                    .hasBean("servletRateLimitlyFailureHandler")
                    .doesNotHaveBean("methodRateLimitlyPolicyResolver")
                    .doesNotHaveBean("methodRateLimitlyLabelResolver")
                    .doesNotHaveBean("methodRateLimitlyFailureHandler");
            });
    }

    @Test
    void customServletComponentsDoNotSuppressMethodDefaults() {
        webRunner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withUserConfiguration(ServletOverrides.class)
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(MethodRateLimitEnforcer.class).hasSingleBean(ServletRateLimitEnforcer.class)
                .hasBean("methodRateLimitlyPolicyResolver").hasBean("methodRateLimitlyLabelResolver")
                .hasBean("methodRateLimitlyFailureHandler").doesNotHaveBean("servletRateLimitlyPolicyResolver")
                .doesNotHaveBean("servletRateLimitlyLabelResolver").doesNotHaveBean("servletRateLimitlyFailureHandler"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedMethodAdviceDoesNotReplaceOrBreakAdmissionAdvice(boolean primary) {
        RecordingClient client = new RecordingClient();
        client.allowed = false;
        webRunner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, () -> client)
            .withBean(ProtectedService.class, ProtectedService::new)
            .withBean("unrelatedInterceptor", MethodInterceptor.class, () -> invocation -> invocation.proceed(),
                definition -> definition.setPrimary(primary))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThrows(RateLimitDeniedException.class, context.getBean(ProtectedService.class)::serve);
                assertThat(client.requests).isEqualTo(1);
            });
    }

    @Test
    void namedApplicationAdviceCanOverrideDefault() {
        MethodInterceptor custom = invocation -> "custom advice";
        webRunner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .withBean(ProtectedService.class, ProtectedService::new)
            .withBean("rateLimitlyMethodInterceptor", MethodInterceptor.class, () -> custom)
            .withBean("unrelatedInterceptor", MethodInterceptor.class, () -> invocation -> "wrong advice",
                definition -> definition.setPrimary(true))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("rateLimitlyMethodInterceptor")).isSameAs(custom);
                assertThat(context.getBean(ProtectedService.class).serve()).isEqualTo("custom advice");
            });
    }

    @Test
    void disablingLocalMetricsDoesNotDisableLatencyReports() {
        RecordingClient client = new RecordingClient();
        webRunner.withPropertyValues("ratelimitly.enabled=true", "ratelimitly.observation.enabled=false")
            .withBean(RateLimitlyClient.class, () -> client)
            .withBean(GuardedService.class, GuardedService::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(GuardedService.class).serve()).isEqualTo("served");
                assertThat(client.requests).isEqualTo(1);
                assertThat(client.reports).isEqualTo(1);
            });
    }

    @Test
    void filterSelectionDoesNotAlsoRegisterMvcInterceptor() {
        webRunner.withPropertyValues("ratelimitly.enabled=true", "ratelimitly.servlet.filter-enabled=true",
                "ratelimitly.servlet.interceptor-enabled=false", "ratelimitly.method.enabled=false")
            .withBean(RateLimitlyClient.class, RecordingClient::new)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyServletFilter.class)
                .doesNotHaveBean(RateLimitlyHandlerInterceptor.class));
    }

    private static ServletRequestContext servletContext() {
        return new ServletRequestContext(new MockHttpServletRequest("GET", "/test"),
            new MockHttpServletResponse(), null);
    }

    public static class ProtectedService {
        @RateLimited(context = "activation-test", rate = 10, window = "1s")
        public String serve() { return "served"; }
    }

    public static class GuardedService {
        @RateLimited(context = "guarded-activation-test", rate = 10, window = "1s",
            guards = @MethodLatencyGuard(service = "activation-service", thresholdMs = 100,
                ttlMs = 10000, maxSamples = 100, minSampleThreshold = 5))
        public String serve() { return "served"; }
    }

    @Configuration(proxyBeanMethods = false)
    static class MethodOverrides {
        @Bean
        RateLimitlyPolicyResolver<MethodInvocationContext> customMethodPolicy() { return context -> null; }
        @Bean
        RateLimitlyLabelResolver<MethodInvocationContext> customMethodLabel() { return (context, policy) -> null; }
        @Bean
        RateLimitlyFailureHandler<MethodInvocationContext> customMethodFailure() {
            return (context, failure, mode) -> { throw failure; };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ServletOverrides {
        @Bean
        RateLimitlyPolicyResolver<ServletRequestContext> customServletPolicy() { return context -> null; }
        @Bean
        RateLimitlyLabelResolver<ServletRequestContext> customServletLabel() { return (context, policy) -> null; }
        @Bean
        RateLimitlyFailureHandler<ServletRequestContext> customServletFailure() {
            return (context, failure, mode) -> { throw failure; };
        }
    }

    private static final class RecordingClient implements RateLimitlyClient {
        private int requests;
        private int reports;
        private boolean allowed = true;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            requests++;
            return new RateLimitDecision(allowed, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) { reports++; }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
