package com.ratelimitly.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
import com.ratelimitly.spring.servlet.ServletRequestContext;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DiagnosticBoundaryTest {
    private static final String PRIVATE = "private-data-sentinel";
    enum Outcome { ALLOW, DENY, FAILURE }

    @Test
    void unavailableHttpResponseDoesNotExposeClientFailureText() throws Exception {
        var response = new MockHttpServletResponse();
        new DefaultServletFailureHandler().onFailure(new ServletRequestContext(new MockHttpServletRequest(), response, null),
            failure(), FailMode.CLOSED);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getErrorMessage()).isEqualTo("RateLimitly unavailable");
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE);
    }

    @Test
    void committedResponseKeepsItsContentAndRetainsServerSideCause() throws Exception {
        var response = new MockHttpServletResponse();
        response.getWriter().write("existing");
        response.flushBuffer();
        var failure = failure();
        var thrown = assertThrows(ServletException.class, () -> new DefaultServletFailureHandler().onFailure(
            new ServletRequestContext(new MockHttpServletRequest(), response, null), failure, FailMode.CLOSED));
        assertThat(thrown.getCause()).isSameAs(failure);
        assertThat(response.getContentAsString()).isEqualTo("existing");
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void adapterDebugLogsExcludeApplicationDataAndFailureMessages(Outcome outcome) throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger("com.ratelimitly.spring");
        Level original = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
        try {
            var client = new Client(outcome);
            var policy = new RateLimitlyPolicy(PRIVATE + "-policy",
                List.of(new ResourceRequest(PRIVATE + "-bucket", 1000, 100, 1)),
                List.of(new LatencyGuard(PRIVATE + "-tracker", 100, 10000, 20, 5)), true, FailMode.CLOSED, true);
            var recorder = new RateLimitlyObservationRecorder(null);
            var method = new MethodRateLimitEnforcer(client, ignored -> policy, (context, p) -> PRIVATE + "-label",
                new DefaultMethodDenialHandler(), new DefaultMethodFailureHandler(), new RateLimitDecisionEvaluator(), recorder, true);
            var context = new MethodInvocationContext(new Object(), Object.class.getMethod("toString"), List.of());
            if (outcome == Outcome.ALLOW) { assertThat(method.invoke(context, () -> "served")).isEqualTo("served"); }
            else { Class<? extends RuntimeException> expected = outcome == Outcome.DENY
                    ? RateLimitDeniedException.class : RateLimitUnavailableException.class;
                assertThrows(expected,
                () -> method.invoke(context, () -> { throw new AssertionError("not admitted"); })); }
            var servlet = new ServletRateLimitEnforcer(client, ignored -> policy, (ctx, p) -> PRIVATE + "-label",
                new DefaultHttpDenialHandler(), new DefaultServletFailureHandler(), new RateLimitDecisionEvaluator(), recorder, true);
            var result = servlet.enforce(new ServletRequestContext(new MockHttpServletRequest("GET", "/" + PRIVATE),
                new MockHttpServletResponse(), null));
            if (result.proceed()) { servlet.afterCompletion(result.policy(), 1_000_000); }
            assertThat(logs.list).isNotEmpty();
            for (ILoggingEvent log : logs.list) {
                assertThat(log.getFormattedMessage()).doesNotContain(PRIVATE);
                assertThat(log.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(original);
            logs.stop();
        }
    }

    static RateLimitlyException failure() {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.TRANSPORT_IO, PRIVATE + "-exception");
    }
    static class Client implements RateLimitlyClient {
        private final Outcome outcome;
        Client(Outcome outcome) { this.outcome = outcome; }
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException {
            if (outcome == Outcome.FAILURE) { throw failure(); }
            return new RateLimitDecision(outcome == Outcome.ALLOW, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected async admission"));
        }
        @Override public void reportLatency(LatencyReport report) throws RateLimitlyException { throw failure(); }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected async report"));
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
