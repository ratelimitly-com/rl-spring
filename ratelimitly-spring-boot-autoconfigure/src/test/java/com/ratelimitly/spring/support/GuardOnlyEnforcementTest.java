package com.ratelimitly.spring.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.method.DefaultMethodDenialHandler;
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.method.RateLimitDeniedException;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.servlet.DefaultHttpDenialHandler;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;
import com.ratelimitly.spring.servlet.ServletRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GuardOnlyEnforcementTest {
    @Test
    void deniedGuardOnlyMethodNeverExecutesApplicationWork() throws Throwable {
        StubClient client = new StubClient(false);
        AtomicBoolean invoked = new AtomicBoolean();
        MethodRateLimitEnforcer enforcer = methodEnforcer(client, FailMode.CLOSED);
        assertThrows(RateLimitDeniedException.class, () -> enforcer.invoke(methodContext(), () -> {
            invoked.set(true);
            return "unexpected";
        }));
        assertFalse(invoked.get());
        assertEquals(1, client.requests);
        assertTrue(client.lastRequest.resources().isEmpty());
        assertEquals(1, client.lastRequest.guards().size());
    }

    @Test
    void allowedGuardOnlyMethodExecutesApplicationWork() throws Throwable {
        StubClient client = new StubClient(true);
        assertEquals("ok", methodEnforcer(client, FailMode.CLOSED).invoke(methodContext(), () -> "ok"));
        assertEquals(1, client.requests);
    }

    @Test
    void deniedGuardOnlyServletReturns429() throws Exception {
        StubClient client = new StubClient(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        var outcome = servletEnforcer(client, FailMode.CLOSED).enforce(
            new ServletRequestContext(new MockHttpServletRequest("GET", "/test"), response, null)
        );
        assertFalse(outcome.proceed());
        assertEquals(429, response.getStatus());
        assertEquals(1, client.requests);
        assertTrue(client.lastRequest.resources().isEmpty());
        assertEquals(1, client.lastRequest.guards().size());
    }

    @Test
    void failureRemainsDistinctFromDenialForBothEnforcers() throws Throwable {
        StubClient client = new StubClient(true);
        client.failure = new RateLimitlyException(RateLimitlyException.ErrorKind.TIMEOUT, "synthetic timeout");
        assertThrows(RateLimitlyException.class,
            () -> methodEnforcer(client, FailMode.CLOSED).invoke(methodContext(), () -> "unexpected"));
        assertEquals("fail-open", methodEnforcer(client, FailMode.OPEN).invoke(methodContext(), () -> "fail-open"));
        var context = new ServletRequestContext(new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        assertThrows(RateLimitlyException.class, () -> servletEnforcer(client, FailMode.CLOSED).enforce(context));
        assertTrue(servletEnforcer(client, FailMode.OPEN).enforce(context).proceed());
    }

    private static RateLimitlyPolicy policy(FailMode mode) {
        return new RateLimitlyPolicy("guard-only", List.of(),
            List.of(new LatencyGuard("inventory", 100, 10_000, 10, 5)), false, mode, false);
    }

    private static MethodRateLimitEnforcer methodEnforcer(StubClient client, FailMode mode) {
        return new MethodRateLimitEnforcer(client, ignored -> policy(mode), (ignored, policy) -> null,
            new DefaultMethodDenialHandler(), (context, failure, failMode) -> { throw failure; },
            new RateLimitDecisionEvaluator(), new RateLimitlyObservationRecorder(null), false);
    }

    private static ServletRateLimitEnforcer servletEnforcer(StubClient client, FailMode mode) {
        return new ServletRateLimitEnforcer(client, ignored -> policy(mode), (ignored, policy) -> null,
            new DefaultHttpDenialHandler(), (context, failure, failMode) -> { throw failure; },
            new RateLimitDecisionEvaluator(), new RateLimitlyObservationRecorder(null), false);
    }

    private static MethodInvocationContext methodContext() throws Exception {
        return new MethodInvocationContext(new Object(), Object.class.getMethod("toString"), List.of());
    }

    private static final class StubClient implements RateLimitlyClient {
        private final boolean allowed;
        private int requests;
        private RateLimitRequest lastRequest;
        private RateLimitlyException failure;

        private StubClient(boolean allowed) { this.allowed = allowed; }

        @Override
        public RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException {
            requests++;
            lastRequest = request;
            if (failure != null) { throw failure; }
            return new RateLimitDecision(allowed, List.of(), List.of(), 1, false);
        }

        @Override
        public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            try { return CompletableFuture.completedFuture(checkRateLimit(request)); }
            catch (RateLimitlyException error) { return CompletableFuture.failedFuture(error); }
        }

        @Override public void reportLatency(LatencyReport report) { }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
