package com.ratelimitly.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.DnsResolver;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.method.MethodExpressionEvaluator;
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JavaClientV3AutoConfigurationTest {
    // Synthetic NONE key with a 300 ms deduplication quota, never a live credential.
    private static final String API_KEY = "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";
    private static final RateLimitRequest EMPTY = new RateLimitRequest(List.of(), List.of(), null);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class));

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("ratelimitly.enabled=true", "ratelimitly.api-key=" + API_KEY,
            "ratelimitly.client.request-policy.unit=25ms", "ratelimitly.client.request-policy.replay-count=3");
    }

    @Test
    void disabledIntegrationNeedsNoApiKeyOrClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RateLimitlyClient.class);
        });
    }

    @Test
    void apiKeyAloneCreatesClientAndEmptyRequestsStayLocal() {
        AtomicReference<RateLimitlyClient> created = new AtomicReference<>();
        enabled().withBean(DnsResolver.class, () -> name -> { throw new AssertionError("unexpected DNS"); })
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyClient.class);
                RateLimitlyClient client = context.getBean(RateLimitlyClient.class);
                created.set(client);
                assertTrue(client.checkRateLimit(EMPTY).success());
                assertEquals(0, client.checkRateLimit(EMPTY).serverId());
                assertTrue(client.checkRateLimitAsync(EMPTY).toCompletableFuture().get(2, TimeUnit.SECONDS).success());
                assertEquals(ClientDiagnostics.empty(), client.diagnostics());
            });
        assertThat(assertThrows(RateLimitlyException.class, () -> created.get().checkRateLimit(EMPTY)))
            .hasMessageContaining("closed");
        created.get().close(); // Idempotent after Spring has already closed it.
    }

    @Test
    void customResolverReceivesApiKeyDerivedDnsName() {
        assertDiscoveryName(null, "c-72623859790382856.p0.ratelimitly.com");
    }

    @Test
    void explicitDnsOverrideIsOptionalAndHonored() {
        assertDiscoveryName("ratelimitly.dns-name=fixture.invalid", "fixture.invalid");
    }

    private void assertDiscoveryName(String override, String expected) {
        AtomicReference<String> queried = new AtomicReference<>();
        ApplicationContextRunner configured = enabled().withBean(DnsResolver.class, () -> name -> {
            queried.set(name);
            return List.of();
        });
        if (override != null) { configured = configured.withPropertyValues(override); }
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitlyException failure = assertThrows(RateLimitlyException.class,
                () -> context.getBean(RateLimitlyClient.class).checkRateLimit(
                    new RateLimitRequest(List.of(new ResourceRequest("test", 1000, 10, 1)), List.of(), null)));
            assertEquals(RateLimitlyException.ErrorKind.DNS_DISCOVERY, failure.kind());
            assertEquals(expected, queried.get());
        });
    }

    @Test
    void rejectsPolicyBeyondApiKeyQuotaAtStartup() {
        enabled().withPropertyValues("ratelimitly.client.request-policy.unit=100ms")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("dedup_ttl_ms_max");
            });
    }

    @Test
    void malformedApiKeyFailsStartupWithoutEchoingIt() {
        String invalid = "not-a-real-api-key-sensitive-sentinel";
        enabled().withPropertyValues("ratelimitly.api-key=" + invalid).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(RateLimitlyException.class);
            assertThat(context.getStartupFailure().toString()).doesNotContain(invalid);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"credential=removed", "tenant-dns-name=removed.invalid", "client.timeout=1s",
        "client.server-stability-threshold=30s", "client.request-policy.replay-cout=3",
        "client.steering-feedback=true", "client.ignore-steering-feedback=true"})
    void removedAndMisspelledPropertiesFailBinding(String property) {
        enabled().withPropertyValues("ratelimitly." + property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("left unbound");
        });
    }

    @Test
    void userClientOverridesFactoryWithoutRequiringApiKey() {
        TrackingClient supplied = new TrackingClient();
        runner.withPropertyValues("ratelimitly.enabled=true")
            .withBean(RateLimitlyClient.class, () -> supplied).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyClient.class);
                assertThat(context.getBean(RateLimitlyClient.class)).isSameAs(supplied);
            });
        assertEquals(1, supplied.closeCount);
    }

    @Test
    void methodIntegrationBootstrapsWithoutServletAndUsesNonSecretIdentity() {
        enabled().withConfiguration(AutoConfigurations.of(RateLimitlyMethodAutoConfiguration.class,
            RateLimitlyObservationAutoConfiguration.class)).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(MethodRateLimitEnforcer.class);
                var invocation = new MethodInvocationContext(new Object(), Object.class.getMethod("toString"), List.of());
                assertEquals("72623859790382856", context.getBean(MethodExpressionEvaluator.class)
                    .evaluate("{#apiKeyId}", invocation));
            });
    }

    private static final class TrackingClient implements RateLimitlyClient {
        private int closeCount;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            return new RateLimitDecision(true, List.of(), List.of(), 0, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) { }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { closeCount++; }
    }
}
