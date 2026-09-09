package com.ratelimitly.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.spring.method.MethodLatencyGuard;
import com.ratelimitly.spring.method.RateLimitDeniedException;
import com.ratelimitly.spring.method.RateLimited;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

class MethodProxyBoundaryTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class,
            RateLimitlyMethodAutoConfiguration.class, RateLimitlyObservationAutoConfiguration.class))
        .withPropertyValues("ratelimitly.enabled=true", "ratelimitly.default-fail-mode=closed")
        .withUserConfiguration(Services.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedProxyCallsAreIndependentAndReportOnlyAdmittedWork(boolean denyInner) {
        Client client = new Client();
        client.denyInner = denyInner;
        runner.withBean(RateLimitlyClient.class, () -> client).run(context -> {
            assertThat(context).hasNotFailed();
            Outer outer = context.getBean(Outer.class);
            if (denyInner) { assertThrows(RateLimitDeniedException.class, outer::call); }
            else { assertThat(outer.call()).isEqualTo("served"); }
            assertThat(client.requests).containsExactly("method:outer", "method:inner");
            assertThat(client.reports).containsExactlyElementsOf(denyInner ? List.of("outer") : List.of("inner", "outer"));
        });
    }

    @Test
    void classAndMethodAnnotationSelectOneMethodPolicy() {
        Client client = new Client();
        runner.withBean(RateLimitlyClient.class, () -> client).run(context -> {
            assertThat(context.getBean(Annotated.class).call()).isEqualTo("served");
            assertThat(client.requests).containsExactly("method:method");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedAsyncProxyCallsKeepIndependentMeasurements(boolean exceptional) {
        Client client = new Client();
        runner.withBean(RateLimitlyClient.class, () -> client).run(context -> {
            CompletionStage<String> returned = context.getBean(AsyncOuter.class).call();
            assertThat(client.requests).containsExactly("method:async-outer", "method:async-inner");
            assertThat(client.reports).isEmpty();
            IllegalStateException problem = new IllegalStateException("application failure");
            context.getBean(AsyncInner.class).complete(exceptional ? problem : null);
            if (exceptional) {
                assertThat(assertThrows(java.util.concurrent.CompletionException.class,
                    () -> returned.toCompletableFuture().join()).getCause()).isSameAs(problem);
            } else { assertThat(returned.toCompletableFuture().join()).isEqualTo("served"); }
            assertThat(client.reports).containsExactlyInAnyOrder("async-inner", "async-outer");
        });
    }

    @Test
    void selfInvocationDoesNotPretendToBeASecondProxyBoundary() {
        Client client = new Client();
        runner.withBean(RateLimitlyClient.class, () -> client).run(context -> {
            assertThat(context.getBean(SelfCalling.class).outer()).isEqualTo("served");
            assertThat(client.requests).containsExactly("method:self-outer");
        });
    }

    @Test
    void explicitlyEarlierRejectingAdviceRunsBeforeAdmission() {
        Client client = new Client();
        var before = new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(RateLimited.class),
            (MethodInterceptor) invocation -> { throw new IllegalArgumentException("application guard rejected"); });
        before.setOrder(Ordered.HIGHEST_PRECEDENCE);
        runner.withBean(RateLimitlyClient.class, () -> client).withBean("applicationGuard", DefaultPointcutAdvisor.class, () -> before)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThrows(IllegalArgumentException.class, context.getBean(Outer.class)::call);
                assertThat(client.requests).isEmpty();
                assertThat(client.reports).isEmpty();
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingAdvisorAutoProxyCreatorIsReusedWithoutDuplicateOrMissingAdmission(boolean infrastructure) {
        Client client = new Client();
        Class<? extends AbstractAdvisorAutoProxyCreator> type = infrastructure
            ? InfrastructureAdvisorAutoProxyCreator.class : ApplicationAutoProxyCreator.class;
        runner.withBean(RateLimitlyClient.class, () -> client)
            .withBean("org.springframework.aop.config.internalAutoProxyCreator", type)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Annotated.class).call()).isEqualTo("served");
                assertThat(client.requests).containsExactly("method:method");
                assertThat(context.getBeansOfType(AbstractAdvisorAutoProxyCreator.class)).hasSize(1);
            });
    }

    @Test
    void intentionalNamedAdvisorOverrideStillWorksWithInfrastructureCreator() {
        Client client = new Client();
        var override = new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(RateLimited.class),
            (MethodInterceptor) invocation -> "custom outcome");
        runner.withBean(RateLimitlyClient.class, () -> client)
            .withBean("org.springframework.aop.config.internalAutoProxyCreator", InfrastructureAdvisorAutoProxyCreator.class)
            .withBean("rateLimitlyMethodAdvisor", DefaultPointcutAdvisor.class, () -> override)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Annotated.class).call()).isEqualTo("custom outcome");
                assertThat(client.requests).isEmpty();
            });
    }

    public static class ApplicationAutoProxyCreator extends AbstractAdvisorAutoProxyCreator { }

    @Configuration(proxyBeanMethods = false)
    static class Services {
        @Bean Inner inner() { return new Inner(); }
        @Bean Outer outer(Inner inner) { return new Outer(inner); }
        @Bean Annotated annotated() { return new Annotated(); }
        @Bean SelfCalling selfCalling() { return new SelfCalling(); }
        @Bean AsyncInner asyncInner() { return new AsyncInner(); }
        @Bean AsyncOuter asyncOuter(AsyncInner inner) { return new AsyncOuter(inner); }
    }

    public static class Outer {
        private final Inner inner;
        Outer(Inner inner) { this.inner = inner; }
        @RateLimited(context = "outer", guards = @MethodLatencyGuard(service = "outer"))
        public String call() { return inner.call(); }
    }
    public static class Inner {
        @RateLimited(context = "inner", guards = @MethodLatencyGuard(service = "inner"))
        public String call() { return "served"; }
    }
    public static class AsyncOuter {
        private final AsyncInner inner;
        AsyncOuter(AsyncInner inner) { this.inner = inner; }
        @RateLimited(context = "async-outer", guards = @MethodLatencyGuard(service = "async-outer"))
        public CompletionStage<String> call() { return inner.call(); }
    }
    public static class AsyncInner {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        @RateLimited(context = "async-inner", guards = @MethodLatencyGuard(service = "async-inner"))
        public CompletionStage<String> call() { return result; }
        public void complete(Throwable failure) {
            if (failure == null) { result.complete("served"); }
            else { result.completeExceptionally(failure); }
        }
    }
    @RateLimited(context = "class")
    public static class Annotated {
        @RateLimited(context = "method") public String call() { return "served"; }
    }
    public static class SelfCalling {
        @RateLimited(context = "self-outer") public String outer() { return inner(); }
        @RateLimited(context = "self-inner") public String inner() { return "served"; }
    }

    static class Client implements RateLimitlyClient {
        final List<String> requests = new ArrayList<>();
        final List<String> reports = new ArrayList<>();
        boolean denyInner;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            String name = request.resources().getFirst().bucketName();
            requests.add(name);
            return new RateLimitDecision(!(denyInner && name.equals("method:inner")), List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) {
            reports.add(report.reports().getFirst().latencyTrackerName());
        }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            reportLatency(report);
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
