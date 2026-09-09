package com.ratelimitly.spring.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.HandlerMapping;

class ServletLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asyncContinuationKeepsAdmissionAndReportsOnlyAtCompletion(boolean filter) throws Exception {
        Fixture fixture = new Fixture();
        MockMvc mvc = fixture.mvc(filter);
        MvcResult pending = mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.client.reports).isEmpty();
        fixture.client.allowed = false; // A second admission would wrongly reject already-executed work.
        fixture.controller.result.setResult("served");
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.reportNames()).containsExactly("tracker:/async");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failOpenAsyncContinuationDoesNotAskAgainOrReport(boolean filter) throws Exception {
        Fixture fixture = new Fixture();
        fixture.failMode = FailMode.OPEN;
        fixture.client.failure = new RateLimitlyException(RateLimitlyException.ErrorKind.TIMEOUT, "fixture timeout");
        MockMvc mvc = fixture.mvc(filter);
        MvcResult pending = mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
        fixture.client.failure = null;
        fixture.client.allowed = false;
        fixture.controller.result.setResult("served");
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.client.reports).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bypassedAsyncContinuationKeepsOriginalOutcome(boolean filter) throws Exception {
        Fixture fixture = new Fixture();
        fixture.bypass = true;
        MockMvc mvc = fixture.mvc(filter);
        MvcResult pending = mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
        fixture.bypass = false;
        fixture.client.allowed = false;
        fixture.controller.result.setResult("served");
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        assertThat(fixture.client.requests).isEmpty();
        assertThat(fixture.client.reports).isEmpty();
    }

    @Test
    void mvcAsyncCompletionWithoutRedispatchStillReportsOnce() throws Exception {
        Fixture fixture = new Fixture();
        MvcResult pending = fixture.mvc(false).perform(get("/async"))
            .andExpect(request().asyncStarted()).andReturn();
        assertThat(fixture.client.reports).isEmpty();
        MockAsyncContext async = (MockAsyncContext) pending.getRequest().getAsyncContext();
        async.complete();
        async.complete();
        assertThat(fixture.reportNames()).containsExactly("tracker:/async");
    }

    @Test
    void mvcManualAsyncContinuationAlsoKeepsAdmissionAndMeasurement() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/manual");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object handler = new Object();
        fixture.interceptor.preHandle(request, response, handler);
        MockAsyncContext async = (MockAsyncContext) request.startAsync(request, response);
        // Spring can call afterCompletion when async was started directly, outside WebAsyncManager.
        fixture.interceptor.afterCompletion(request, response, handler, null);
        assertThat(fixture.client.reports).isEmpty();
        fixture.client.allowed = false;
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(fixture.interceptor.preHandle(request, response, handler)).isTrue();
        fixture.interceptor.afterCompletion(request, response, handler, null);
        async.complete();
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.reportNames()).containsExactly("tracker:/manual");
    }

    @Test
    void concurrentCompletionNotificationsEmitAtMostOneReport() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        MockAsyncContext async = (MockAsyncContext) request.getAsyncContext();
        AsyncListener listener = async.getListeners().getFirst();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); listener.onComplete(new AsyncEvent(async)); return null; });
            var second = executor.submit(() -> { start.await(); listener.onComplete(new AsyncEvent(async)); return null; });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(fixture.reportNames()).containsExactly("tracker:/raw");
        MockAsyncContext later = new MockAsyncContext(request, response);
        listener.onStartAsync(new AsyncEvent(later));
        assertThat(later.getListeners()).isEmpty();
    }

    @Test
    void nestedMvcForwardRestoresOuterMeasurementAndClearsCompletedState() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/outer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object outer = new Object();
        Object inner = new Object();
        assertThat(fixture.interceptor.preHandle(request, response, outer)).isTrue();
        request.setDispatcherType(DispatcherType.FORWARD);
        request.setRequestURI("/inner");
        assertThat(fixture.interceptor.preHandle(request, response, inner)).isTrue();
        fixture.interceptor.afterCompletion(request, response, inner, null);
        fixture.interceptor.afterCompletion(request, response, outer, null);
        fixture.interceptor.afterCompletion(request, response, outer, null);
        assertThat(fixture.client.requests).hasSize(2);
        assertThat(fixture.reportNames()).containsExactly("tracker:/inner", "tracker:/outer");
    }

    @Test
    void bypassedNestedDispatchDoesNotReportOuterWorkPrematurely() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/outer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object outer = new Object();
        Object inner = new Object();
        fixture.interceptor.preHandle(request, response, outer);
        request.setDispatcherType(DispatcherType.FORWARD);
        request.setRequestURI("/bypassed");
        fixture.bypass = true;
        fixture.interceptor.preHandle(request, response, inner);
        fixture.interceptor.afterCompletion(request, response, inner, null);
        assertThat(fixture.client.reports).isEmpty();
        fixture.interceptor.afterCompletion(request, response, outer, null);
        assertThat(fixture.reportNames()).containsExactly("tracker:/outer");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorTargetIsProtectedIndependently(boolean filter) throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/original");
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (filter) {
            fixture.filter.doFilter(request, response, (req, res) -> { });
        } else {
            fixture.interceptor.preHandle(request, response, "original");
            fixture.interceptor.afterCompletion(request, response, "original", null);
        }
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        fixture.client.allowed = false;
        if (filter) {
            fixture.filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("denied error target must not execute");
            });
        } else {
            assertThat(fixture.interceptor.preHandle(request, response, "error")).isFalse();
        }
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(fixture.client.requests).hasSize(2);
        assertThat(fixture.reportNames()).containsExactly("tracker:/original");
    }

    @Test
    void filterTracksRestartedAsyncCyclesUntilActualCompletion() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        assertThat(fixture.client.reports).isEmpty();
        MockAsyncContext first = (MockAsyncContext) request.getAsyncContext();
        MockAsyncContext next = new MockAsyncContext(request, response);
        for (var listener : List.copyOf(first.getListeners())) {
            listener.onStartAsync(new AsyncEvent(next));
        }
        assertThat(next.getListeners()).hasSize(1);
        for (var listener : List.copyOf(next.getListeners())) {
            listener.onTimeout(new AsyncEvent(next));
            listener.onError(new AsyncEvent(next, new IllegalStateException("fixture error")));
        }
        assertThat(fixture.client.reports).isEmpty();
        next.complete();
        next.complete();
        assertThat(fixture.reportNames()).containsExactly("tracker:/raw");
    }

    @Test
    void asyncDispatchToAnotherRawTargetGetsItsOwnAdmission() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/first");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        MockAsyncContext async = (MockAsyncContext) request.getAsyncContext();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setRequestURI("/second");
        fixture.filter.doFilter(request, response, (req, res) -> { });
        async.complete();
        assertThat(fixture.client.requests).hasSize(2);
        assertThat(fixture.reportNames()).containsExactly("tracker:/second", "tracker:/first");
    }

    @Test
    void firstObservedAsyncDispatchHasNoImplicitAdmission() throws Exception {
        Fixture fixture = new Fixture();
        fixture.client.allowed = false;
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/first-observed");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> { throw new AssertionError("denied"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.interceptor.preHandle(request, new MockHttpServletResponse(), "first-observed")).isFalse();
        assertThat(fixture.client.requests).hasSize(2);
    }

    @Test
    void unavailableListenerDoesNotInvalidateAdmissionOnContinuation() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> {
            req.startAsync(req, res);
            request.setAsyncContext(new MockAsyncContext(req, res) {
                @Override public void addListener(AsyncListener listener) {
                    throw new IllegalStateException("fixture unavailable cycle");
                }
            });
        });
        assertThat(fixture.client.reports).isEmpty();
        fixture.client.allowed = false;
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        boolean[] continued = {false};
        fixture.filter.doFilter(request, response, (req, res) -> continued[0] = true);
        assertThat(continued[0]).isTrue();
        assertThat(fixture.client.requests).hasSize(1);
        assertThat(fixture.client.reports).isEmpty();
    }

    @Test
    void changedQueryOnRawAsyncDispatchRequiresNewAdmission() throws Exception {
        Fixture fixture = new Fixture();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw");
        request.setQueryString("job=one");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        fixture.filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        MockAsyncContext async = (MockAsyncContext) request.getAsyncContext();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setQueryString("job=two");
        fixture.client.allowed = false;
        fixture.filter.doFilter(request, response, (req, res) -> { throw new AssertionError("denied target"); });
        async.complete();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(fixture.client.requests).hasSize(2);
        assertThat(fixture.reportNames()).containsExactly("tracker:/raw");
    }

    @Test
    void filterIdentityIgnoresStaleMvcAttributesAndUsesIncludeTarget() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/forward-target");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/old/{id}");
        ServletRequestContext context = new ServletRequestContext(request, new MockHttpServletResponse(), null);
        assertThat(ServletRequestIdentitySupport.bestPolicyPath(context)).isEqualTo("/forward-target");
        request.setDispatcherType(DispatcherType.INCLUDE);
        request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/included");
        assertThat(ServletRequestIdentitySupport.bestPolicyPath(context)).isEqualTo("/included");
    }

    @Test
    void checkedReportingFailureDoesNotReplaceSynchronousApplicationFailure() {
        Fixture fixture = new Fixture();
        fixture.client.reportFailure = true;
        ServletException original = new ServletException("application failure");
        ServletException actual = assertThrows(ServletException.class, () -> fixture.filter.doFilter(
            new MockHttpServletRequest("GET", "/failure"), new MockHttpServletResponse(),
            (req, res) -> { throw original; }));
        assertThat(actual).isSameAs(original);
    }

    @RestController
    static class AsyncController {
        final DeferredResult<String> result = new DeferredResult<>();
        @GetMapping("/async") DeferredResult<String> async() { return result; }
    }

    static class Fixture {
        final RecordingClient client = new RecordingClient();
        final AsyncController controller = new AsyncController();
        FailMode failMode = FailMode.CLOSED;
        boolean bypass;
        final ServletRateLimitEnforcer enforcer = new ServletRateLimitEnforcer(client, context -> {
            if (bypass) { return null; }
            String target = context.request().getRequestURI();
            return new RateLimitlyPolicy("policy:" + target,
                List.of(new ResourceRequest("bucket:" + target, 1000, 100, 1)),
                List.of(new LatencyGuard("tracker:" + target, 1000, 10000, 20, 5)),
                true, failMode, false);
        }, (context, policy) -> null, new DefaultHttpDenialHandler(), new DefaultServletFailureHandler(),
            new RateLimitDecisionEvaluator(), new RateLimitlyObservationRecorder(null), false);
        final RateLimitlyHandlerInterceptor interceptor = new RateLimitlyHandlerInterceptor(enforcer);
        final RateLimitlyServletFilter filter = new RateLimitlyServletFilter(enforcer);

        MockMvc mvc(boolean useFilter) {
            var builder = MockMvcBuilders.standaloneSetup(controller);
            if (useFilter) { builder.addFilters(filter); }
            else { builder.addInterceptors(interceptor); }
            return builder.build();
        }

        List<String> reportNames() {
            return client.reports.stream().map(report -> report.reports().getFirst().latencyTrackerName()).toList();
        }
    }

    static class RecordingClient implements RateLimitlyClient {
        boolean allowed = true;
        boolean reportFailure;
        RateLimitlyException failure;
        final List<RateLimitRequest> requests = new ArrayList<>();
        final List<LatencyReport> reports = new ArrayList<>();
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) throws RateLimitlyException {
            requests.add(request);
            if (failure != null) { throw failure; }
            return new RateLimitDecision(allowed, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            throw new AssertionError("unexpected async client admission");
        }
        @Override public void reportLatency(LatencyReport report) throws RateLimitlyException {
            if (reportFailure) {
                throw new RateLimitlyException(RateLimitlyException.ErrorKind.TRANSPORT_IO, "fixture reporting failure");
            }
            reports.add(report);
        }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            return CompletableFuture.failedFuture(new AssertionError("unexpected async report"));
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
