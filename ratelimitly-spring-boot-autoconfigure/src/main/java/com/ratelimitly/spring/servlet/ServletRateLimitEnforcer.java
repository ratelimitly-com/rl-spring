package com.ratelimitly.spring.servlet;

import java.util.List;

import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ServiceLatencyReport;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.support.BestEffortObservation;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletRateLimitEnforcer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServletRateLimitEnforcer.class);

    private final RateLimitlyClient client;
    private final RateLimitlyPolicyResolver<ServletRequestContext> policyResolver;
    private final RateLimitlyLabelResolver<ServletRequestContext> labelResolver;
    private final HttpDenialHandler denialHandler;
    private final RateLimitlyFailureHandler<ServletRequestContext> failureHandler;
    private final RateLimitDecisionEvaluator decisionEvaluator;
    private final RateLimitlyObservationRecorder observationRecorder;
    private final boolean debugEnabled;

    public ServletRateLimitEnforcer(
        RateLimitlyClient client,
        RateLimitlyPolicyResolver<ServletRequestContext> policyResolver,
        RateLimitlyLabelResolver<ServletRequestContext> labelResolver,
        HttpDenialHandler denialHandler,
        RateLimitlyFailureHandler<ServletRequestContext> failureHandler,
        RateLimitDecisionEvaluator decisionEvaluator,
        RateLimitlyObservationRecorder observationRecorder,
        boolean debugEnabled
    ) {
        this.client = client;
        this.policyResolver = policyResolver;
        this.labelResolver = labelResolver;
        this.denialHandler = denialHandler;
        this.failureHandler = failureHandler;
        this.decisionEvaluator = decisionEvaluator;
        this.observationRecorder = BestEffortObservation.wrap(observationRecorder);
        this.debugEnabled = debugEnabled;
    }

    public ServletEnforcementOutcome enforce(ServletRequestContext context) throws Exception {
        RateLimitlyPolicy policy = policyResolver.resolve(context);
        if (policy == null) {
            return new ServletEnforcementOutcome(true, null);
        }

        String label = policy.emitMetricsLabel() ? labelResolver.resolveLabel(context, policy) : null;
        if (debugEnabled && LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Servlet rate limit resolved resourceCount={} guardCount={}",
                policy.resources().size(), policy.guards().size()
            );
        }

        long requestStart = System.nanoTime();
        RateLimitDecision decision;
        try {
            decision = client.checkRateLimit(new RateLimitRequest(
                policy.resources(),
                policy.guards(),
                label
            ));
            observationRecorder.recordClientRoundTrip(System.nanoTime() - requestStart);
        } catch (RateLimitlyException failure) {
            if (debugEnabled && LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Servlet rate limit failure kind={}", failure.kind()
                );
            }
            observationRecorder.recordFailure(failure);
            observationRecorder.recordFailureMode(policy.failMode());
            if (policy.failMode() == FailMode.OPEN) {
                return new ServletEnforcementOutcome(true, null);
            }
            failureHandler.onFailure(context, failure, policy.failMode());
            return new ServletEnforcementOutcome(false, policy);
        }

        if (!decisionEvaluator.isAllowed(decision)) {
            if (debugEnabled && LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Servlet rate limit denied serverId={}", decision.serverId()
                );
            }
            observationRecorder.recordDenied();
            denialHandler.handleDenied(context, decision);
            return new ServletEnforcementOutcome(false, policy);
        }

        if (debugEnabled && LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Servlet rate limit allowed serverId={}", decision.serverId()
            );
        }

        observationRecorder.recordAllowed();
        return new ServletEnforcementOutcome(true, policy);
    }

    public void afterCompletion(RateLimitlyPolicy policy, long handlerDurationNanos) {
        if (policy == null) {
            return;
        }

        observationRecorder.recordHandlerExecution(handlerDurationNanos);
        if (!policy.reportLatency() || policy.guards().isEmpty()) {
            return;
        }

        long observedLatencyMs = Math.max(1L, handlerDurationNanos / 1_000_000L);
        try {
            List<ServiceLatencyReport> services = policy.guards().stream()
                .map(guard -> new ServiceLatencyReport(
                    guard.latencyTrackerName(),
                    observedLatencyMs,
                    guard.ttlMs(),
                    guard.maxSamples(),
                    guard.minSampleThreshold()
                ))
                .toList();
            client.reportLatency(new LatencyReport(services));
            observationRecorder.recordLatencyReportSent();
        } catch (RateLimitlyException | RuntimeException ignored) {
            observationRecorder.recordLatencyReportFailed();
        }
    }
}
