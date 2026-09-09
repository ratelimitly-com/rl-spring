package com.ratelimitly.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.method.DefaultMethodDenialHandler;
import com.ratelimitly.spring.method.DefaultMethodFailureHandler;
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.method.RateLimitDeniedException;
import com.ratelimitly.spring.method.RateLimitUnavailableException;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.servlet.DefaultHttpDenialHandler;
import com.ratelimitly.spring.servlet.DefaultServletFailureHandler;
import com.ratelimitly.spring.servlet.RateLimitlyServletFilter;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdmissionOutcomeTest {
    @ParameterizedTest
    @CsvSource({"sync,OPEN", "sync,CLOSED", "stage,OPEN", "stage,CLOSED", "future,OPEN", "future,CLOSED"})
    void denialNeverExecutesOrReports(String method, FailMode mode) throws Throwable {
        Fixture f = new Fixture();
        f.mode = mode;
        f.client.allowed = false;
        assertMethodFailure(f, method, RateLimitDeniedException.class);
        assertThat(f.executions).isZero();
        assertThat(f.client.reports).isZero();
        assertThat(f.client.requests).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "stage", "future"})
    void closedFailureDoesNotExecuteAndPreservesCause(String method) throws Throwable {
        Fixture f = new Fixture();
        f.client.failure = timeout();
        Throwable failure = assertMethodFailure(f, method, RateLimitUnavailableException.class);
        assertThat(failure.getCause()).isSameAs(f.client.failure);
        assertThat(f.executions).isZero();
        assertThat(f.client.reports).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failOpenOrBypassPreservesOriginalAsyncStageWithoutReporting(boolean bypass) throws Throwable {
        Fixture f = new Fixture();
        f.mode = FailMode.OPEN;
        f.bypass = bypass;
        f.client.failure = timeout();
        CompletableFuture<String> original = new CompletableFuture<>();
        Object returned = f.method().invoke(context("stage"), () -> { f.executions++; return original; });
        assertThat(returned).isSameAs(original);
        original.complete("served");
        assertThat(f.executions).isEqualTo(1);
        assertThat(f.client.reports).isZero();
        assertThat(f.client.requests).isEqualTo(bypass ? 0 : 1);
    }

    @ParameterizedTest
    @EnumSource(ReportFailure.class)
    void synchronousReportingFailureCannotReplaceValueOrRetry(ReportFailure failure) throws Throwable {
        Fixture f = new Fixture();
        f.client.reportFailure = failure;
        assertThat(f.method().invoke(context("sync"), () -> "served")).isEqualTo("served");
        assertThat(f.client.reports).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ReportFailure.class)
    void synchronousReportingFailureCannotMaskApplicationException(ReportFailure failure) {
        Fixture f = new Fixture();
        f.client.reportFailure = failure;
        IllegalStateException original = new IllegalStateException("application failure");
        Throwable actual = assertThrows(IllegalStateException.class,
            () -> f.method().invoke(context("sync"), () -> { throw original; }));
        assertThat(actual).isSameAs(original);
        assertThat(f.client.reports).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false,NONE", "true,NONE", "false,CHECKED", "true,CHECKED", "false,RUNTIME", "true,RUNTIME"})
    void asynchronousCompletionPreservesValueOrException(boolean exceptional, ReportFailure failure) throws Throwable {
        Fixture f = new Fixture();
        f.client.reportFailure = failure;
        CompletableFuture<String> original = new CompletableFuture<>();
        CompletionStage<?> returned = (CompletionStage<?>) f.method().invoke(context("stage"), () -> original);
        assertThat(f.client.reports).isZero();
        IllegalStateException problem = new IllegalStateException("application failure");
        if (exceptional) {
            original.completeExceptionally(problem);
            assertThat(assertThrows(CompletionException.class, () -> returned.toCompletableFuture().join()).getCause())
                .isSameAs(problem);
        } else {
            original.complete("served");
            assertThat(returned.toCompletableFuture().join()).isEqualTo("served");
        }
        assertThat(f.client.reports).isEqualTo(1);
        assertThat(returned).isSameAs(original);
    }

    @Test
    void cancellationReachesTheApplicationFutureAndReportsOnce() throws Throwable {
        Fixture f = new Fixture();
        CompletableFuture<String> original = new CompletableFuture<>();
        CompletableFuture<?> returned = (CompletableFuture<?>) f.method().invoke(context("future"), () -> original);
        assertThat(returned.cancel(false)).isTrue();
        assertThat(original.isCancelled()).isTrue();
        assertThrows(CancellationException.class, original::join);
        original.cancel(false);
        assertThat(f.client.reports).isEqualTo(1);
    }

    @Test
    void grantedCustomFutureRetainsItsTypeAndIdentity() throws Throwable {
        Fixture f = new Fixture();
        CustomFuture original = new CustomFuture();
        assertThat(f.method().invoke(context("custom"), () -> original)).isSameAs(original);
        original.complete("served");
        assertThat(f.client.reports).isEqualTo(1);
    }

    @Test
    void refusedCompletionObservationDoesNotInvalidateTheApplicationStage() throws Throwable {
        Fixture f = new Fixture();
        CompletableFuture<String> original = new CompletableFuture<>() {
            @Override public CompletableFuture<String> whenComplete(BiConsumer<? super String, ? super Throwable> action) {
                throw new IllegalStateException("fixture refuses observer");
            }
        };
        assertThat(f.method().invoke(context("stage"), () -> original)).isSameAs(original);
        original.complete("served");
        assertThat(original.join()).isEqualTo("served");
        assertThat(f.client.reports).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledReportingOrAbsentGuardsDoNotSendReports(boolean guardsAbsent) throws Throwable {
        Fixture f = new Fixture();
        f.guards = !guardsAbsent;
        f.reporting = guardsAbsent;
        assertThat(f.method().invoke(context("sync"), () -> "served")).isEqualTo("served");
        f.filter().doFilter(new MockHttpServletRequest("GET", "/work"), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(f.client.requests).isEqualTo(2);
        assertThat(f.client.reports).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompatibleCustomFutureAdmissionFailureIsThrownWithoutReturningWrongType(boolean timeout) {
        Fixture f = new Fixture();
        f.client.allowed = false;
        if (timeout) { f.client.failure = timeout(); }
        Class<? extends RuntimeException> type = timeout ? RateLimitUnavailableException.class : RateLimitDeniedException.class;
        assertThrows(type, () -> f.method().invoke(context("custom"), () -> { throw new AssertionError("not admitted"); }));
    }

    @ParameterizedTest
    @CsvSource({"true,OPEN,200,1", "true,CLOSED,503,0", "false,OPEN,429,0", "false,CLOSED,429,0"})
    void servletDenialAndFailureRemainDistinct(boolean timeout, FailMode mode, int status, int executions) throws Exception {
        Fixture f = new Fixture();
        f.mode = mode;
        f.client.allowed = false;
        if (timeout) { f.client.failure = timeout(); }
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.filter().doFilter(new MockHttpServletRequest("GET", "/work"), response, (req, res) -> f.executions++);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(f.executions).isEqualTo(executions);
        assertThat(f.client.reports).isZero();
    }

    @ParameterizedTest
    @EnumSource(ReportFailure.class)
    void servletReportingFailureDoesNotMaskApplicationException(ReportFailure failure) {
        Fixture f = new Fixture();
        f.client.reportFailure = failure;
        ServletException original = new ServletException("application failure");
        Throwable actual = assertThrows(ServletException.class, () -> f.filter().doFilter(
            new MockHttpServletRequest("GET", "/work"), new MockHttpServletResponse(), (req, res) -> { throw original; }));
        assertThat(actual).isSameAs(original);
        assertThat(f.client.reports).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokenObservationCannotPreventGrantedWorkOrReporting(boolean servlet) throws Throwable {
        Fixture f = new Fixture();
        f.observations = new BrokenRecorder();
        if (servlet) {
            f.filter().doFilter(new MockHttpServletRequest("GET", "/work"), new MockHttpServletResponse(),
                (req, res) -> f.executions++);
        } else {
            assertThat(f.method().invoke(context("sync"), () -> { f.executions++; return "served"; })).isEqualTo("served");
        }
        assertThat(f.executions).isEqualTo(1);
        assertThat(f.client.reports).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokenObservationCannotTurnFailOpenIntoFailure(boolean servlet) throws Throwable {
        Fixture f = new Fixture();
        f.observations = new BrokenRecorder();
        f.mode = FailMode.OPEN;
        f.client.failure = timeout();
        if (servlet) {
            f.filter().doFilter(new MockHttpServletRequest("GET", "/work"), new MockHttpServletResponse(),
                (req, res) -> f.executions++);
        } else {
            f.method().invoke(context("sync"), () -> { f.executions++; return "served"; });
        }
        assertThat(f.executions).isEqualTo(1);
        assertThat(f.client.reports).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokenObservationCannotReplaceDenialOrClosedFailure(boolean timeout) throws Throwable {
        Fixture f = new Fixture();
        f.observations = new BrokenRecorder();
        f.client.allowed = false;
        if (timeout) { f.client.failure = timeout(); }
        Class<? extends RuntimeException> type = timeout ? RateLimitUnavailableException.class : RateLimitDeniedException.class;
        assertMethodFailure(f, "stage", type);
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.filter().doFilter(new MockHttpServletRequest("GET", "/work"), response, (req, res) -> f.executions++);
        assertThat(response.getStatus()).isEqualTo(timeout ? 503 : 429);
        assertThat(f.executions).isZero();
        assertThat(f.client.reports).isZero();
    }

    private static Throwable assertMethodFailure(Fixture f, String method, Class<? extends Throwable> type) throws Throwable {
        MethodRateLimitEnforcer.InvocationAction action = () -> { f.executions++; return "must not execute"; };
        if (method.equals("sync")) { return assertThrows(type, () -> f.method().invoke(context(method), action)); }
        CompletionStage<?> result = (CompletionStage<?>) f.method().invoke(context(method), action);
        Throwable cause = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join()).getCause();
        assertThat(cause).isInstanceOf(type);
        return cause;
    }

    private static MethodInvocationContext context(String name) throws NoSuchMethodException {
        return new MethodInvocationContext(new Target(), Target.class.getMethod(name), List.of());
    }

    static class Target {
        public String sync() { return "served"; }
        public CompletionStage<String> stage() { return null; }
        public CompletableFuture<String> future() { return null; }
        public CustomFuture custom() { return null; }
    }
    static class CustomFuture extends CompletableFuture<String> { }
    enum ReportFailure { NONE, CHECKED, RUNTIME }

    static RateLimitlyException timeout() {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.TIMEOUT, "fixture timeout");
    }

    static class Fixture {
        final RecordingClient client = new RecordingClient();
        RateLimitlyObservationRecorder observations = new RateLimitlyObservationRecorder(null);
        FailMode mode = FailMode.CLOSED;
        boolean bypass;
        boolean reporting = true;
        boolean guards = true;
        int executions;
        RateLimitlyPolicy policy() {
            return bypass ? null : new RateLimitlyPolicy("work", List.of(new ResourceRequest("work", 1000, 100, 1)),
                guards ? List.of(new LatencyGuard("work", 1000, 10000, 20, 5)) : List.of(), reporting, mode, false);
        }
        MethodRateLimitEnforcer method() {
            return new MethodRateLimitEnforcer(client, ignored -> policy(), (context, policy) -> null,
                new DefaultMethodDenialHandler(), new DefaultMethodFailureHandler(), new RateLimitDecisionEvaluator(),
                observations, false);
        }
        RateLimitlyServletFilter filter() {
            return new RateLimitlyServletFilter(new ServletRateLimitEnforcer(client, ignored -> policy(),
                (context, policy) -> null, new DefaultHttpDenialHandler(), new DefaultServletFailureHandler(),
                new RateLimitDecisionEvaluator(), observations, false));
        }
    }

    static class RecordingClient implements RateLimitlyClient {
        int requests;
        int reports;
        boolean allowed = true;
        RateLimitlyException failure;
        ReportFailure reportFailure = ReportFailure.NONE;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException {
            requests++;
            if (failure != null) { throw failure; }
            return new RateLimitDecision(allowed, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            throw new AssertionError("admission is synchronous");
        }
        @Override public void reportLatency(LatencyReport report) throws RateLimitlyException {
            reports++;
            if (reportFailure == ReportFailure.CHECKED) { throw timeout(); }
            if (reportFailure == ReportFailure.RUNTIME) { throw new IllegalStateException("fixture reporting failure"); }
        }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            throw new AssertionError("reporting is synchronous");
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }

    static class BrokenRecorder extends RateLimitlyObservationRecorder {
        BrokenRecorder() { super(null); }
        private void fail() { throw new IllegalStateException("fixture observation failure"); }
        @Override public void recordAllowed() { fail(); }
        @Override public void recordDenied() { fail(); }
        @Override public void recordClientRoundTrip(long nanos) { fail(); }
        @Override public void recordMethodExecution(long nanos) { fail(); }
        @Override public void recordHandlerExecution(long nanos) { fail(); }
        @Override public void recordFailure(RateLimitlyException failure) { fail(); }
        @Override public void recordFailureMode(FailMode mode) { fail(); }
        @Override public void recordLatencyReportSent() { fail(); }
        @Override public void recordLatencyReportFailed() { fail(); }
    }
}
