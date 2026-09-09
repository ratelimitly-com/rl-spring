package com.ratelimitly.spring.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.GuardDecision;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.ResourceDecision;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.ServiceLatencyReport;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import org.junit.jupiter.api.Test;

class MethodRateLimitEnforcerLatencyReportTest {
    @Test
    void sendsLatencyReportForAllowedSynchronousMethod() throws Throwable {
        RecordingClient client = new RecordingClient();
        MethodRateLimitEnforcer enforcer = newEnforcer(client);
        MethodInvocationContext context = methodContext("syncCall");

        Object result = enforcer.invoke(context, () -> {
            Thread.sleep(5L);
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, client.latencyReportCount);
        assertNotNull(client.lastLatencyReport);
        assertEquals(2, client.lastLatencyReport.reports().size());
        ServiceLatencyReport first = client.lastLatencyReport.reports().getFirst();
        assertEquals("customer-db", first.latencyTrackerName());
    }

    @Test
    void sendsLatencyReportForAllowedAsyncMethodOnCompletion() throws Throwable {
        RecordingClient client = new RecordingClient();
        MethodRateLimitEnforcer enforcer = newEnforcer(client);
        MethodInvocationContext context = methodContext("asyncCall");

        @SuppressWarnings("unchecked")
        CompletionStage<String> stage = (CompletionStage<String>) enforcer.invoke(
            context,
            () -> CompletableFuture.completedFuture("ok")
        );

        assertEquals("ok", stage.toCompletableFuture().join());
        assertEquals(1, client.latencyReportCount);
        assertNotNull(client.lastLatencyReport);
    }

    private MethodRateLimitEnforcer newEnforcer(RecordingClient client) {
        RateLimitlyPolicy policy = new RateLimitlyPolicy(
            "test-policy",
            List.of(new ResourceRequest("test:bucket", 1000, 10, 1)),
            List.of(
                new LatencyGuard("customer-db", 250, 300000, 120, 1),
                new LatencyGuard("customer-cache", 80, 120000, 120, 1)
            ),
            true,
            FailMode.CLOSED,
            true
        );
        RateLimitlyPolicyResolver<MethodInvocationContext> policyResolver = ignored -> policy;
        RateLimitlyLabelResolver<MethodInvocationContext> labelResolver = (ignored, ignoredPolicy) -> "test.label";

        return new MethodRateLimitEnforcer(
            client,
            policyResolver,
            labelResolver,
            (ignored, ignoredDecision) -> null,
            (ignored, failure, failMode) -> {
                throw failure;
            },
            new RateLimitDecisionEvaluator(),
            new RateLimitlyObservationRecorder(null),
            true
        );
    }

    private MethodInvocationContext methodContext(String methodName) throws NoSuchMethodException {
        Method method = SampleTarget.class.getMethod(methodName);
        return new MethodInvocationContext(new SampleTarget(), method, List.of());
    }

    static final class SampleTarget {
        public String syncCall() {
            return "ok";
        }

        public CompletionStage<String> asyncCall() {
            return CompletableFuture.completedFuture("ok");
        }
    }

    static final class RecordingClient implements RateLimitlyClient {
        private int latencyReportCount;
        private LatencyReport lastLatencyReport;

        @Override
        public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            return new RateLimitDecision(true, List.<GuardDecision>of(), List.<ResourceDecision>of(), 1L, false);
        }

        @Override
        public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }

        @Override
        public void reportLatency(LatencyReport report) {
            latencyReportCount++;
            lastLatencyReport = report;
        }

        @Override
        public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            reportLatency(report);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ClientDiagnostics diagnostics() {
            return new ClientDiagnostics(List.of(), null, java.util.Map.of(), 0L, 0L);
        }

        @Override
        public void close() {
        }
    }
}
