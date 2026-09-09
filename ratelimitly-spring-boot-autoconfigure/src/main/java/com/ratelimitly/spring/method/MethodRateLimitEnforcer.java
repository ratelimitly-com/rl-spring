package com.ratelimitly.spring.method;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

public class MethodRateLimitEnforcer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodRateLimitEnforcer.class);

    private final RateLimitlyClient client;
    private final RateLimitlyPolicyResolver<MethodInvocationContext> policyResolver;
    private final RateLimitlyLabelResolver<MethodInvocationContext> labelResolver;
    private final MethodDenialHandler denialHandler;
    private final RateLimitlyFailureHandler<MethodInvocationContext> failureHandler;
    private final RateLimitDecisionEvaluator decisionEvaluator;
    private final RateLimitlyObservationRecorder observationRecorder;
    private final boolean debugEnabled;

    public MethodRateLimitEnforcer(
        RateLimitlyClient client,
        RateLimitlyPolicyResolver<MethodInvocationContext> policyResolver,
        RateLimitlyLabelResolver<MethodInvocationContext> labelResolver,
        MethodDenialHandler denialHandler,
        RateLimitlyFailureHandler<MethodInvocationContext> failureHandler,
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

    public Object invoke(MethodInvocationContext context, InvocationAction action) throws Throwable {
        RateLimitlyPolicy policy = policyResolver.resolve(context);
        if (policy == null) {
            return action.proceed();
        }

        String label = policy.emitMetricsLabel() ? labelResolver.resolveLabel(context, policy) : null;
        if (debugEnabled && LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Method rate limit resolved resourceCount={} guardCount={}",
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
                    "Method rate limit failure kind={}", failure.kind()
                );
            }
            observationRecorder.recordFailure(failure);
            observationRecorder.recordFailureMode(policy.failMode());
            if (policy.failMode() == FailMode.OPEN) {
                return action.proceed();
            }
            try {
                failureHandler.onFailure(context, failure, policy.failMode());
                return null;
            } catch (RuntimeException runtime) {
                return adaptExceptionalResult(context, runtime);
            }
        }

        if (!decisionEvaluator.isAllowed(decision)) {
            if (debugEnabled && LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Method rate limit denied serverId={}", decision.serverId()
                );
            }
            observationRecorder.recordDenied();
            try {
                return denialHandler.handleDenied(context, decision);
            } catch (RuntimeException runtime) {
                return adaptExceptionalResult(context, runtime);
            }
        }

        if (debugEnabled && LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Method rate limit allowed serverId={}", decision.serverId()
            );
        }

        observationRecorder.recordAllowed();
        long executionStart = System.nanoTime();
        Object result;
        try {
            result = action.proceed();
        } catch (Throwable throwable) {
            completeInvocation(policy, executionStart);
            throw throwable;
        }
        if (result instanceof CompletionStage<?> stage) {
            try {
                stage.whenComplete((ignored, throwable) -> completeInvocation(policy, executionStart));
            } catch (RuntimeException unavailableObserver) {
                // A custom stage can refuse observation. Preserve the application result;
                // do not invent an early measurement or replace its future/cancellation.
            }
        } else {
            completeInvocation(policy, executionStart);
        }
        return result;
    }

    private void completeInvocation(RateLimitlyPolicy policy, long executionStart) {
        long executionNanos = System.nanoTime() - executionStart;
        observationRecorder.recordMethodExecution(executionNanos);
        try {
            reportMethodLatency(policy, executionNanos);
        } catch (RuntimeException reportingFailure) {
            observationRecorder.recordLatencyReportFailed();
        }
    }

    private void reportMethodLatency(
        RateLimitlyPolicy policy,
        long executionNanos
    ) {
        if (!policy.reportLatency() || policy.guards().isEmpty()) {
            return;
        }

        long observedLatencyMs = Math.max(1L, executionNanos / 1_000_000L);
        var services = policy.guards().stream()
            .map(guard -> new ServiceLatencyReport(
                guard.latencyTrackerName(),
                observedLatencyMs,
                guard.ttlMs(),
                guard.maxSamples(),
                guard.minSampleThreshold()
            ))
            .toList();
        try {
            client.reportLatency(new LatencyReport(services));
            observationRecorder.recordLatencyReportSent();
            if (debugEnabled && LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Method latency report sent observedLatencyMs={} serviceCount={}",
                    observedLatencyMs, services.size()
                );
            }
        } catch (RateLimitlyException failure) {
            observationRecorder.recordLatencyReportFailed();
            if (debugEnabled && LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "Method latency report failed kind={}", failure.kind()
                );
            }
        }
    }

    private Object adaptExceptionalResult(MethodInvocationContext context, RuntimeException exception) {
        Class<?> returnType = MethodIdentitySupport.resolveMethod(context).getReturnType();
        if (CompletionStage.class.isAssignableFrom(returnType) && returnType.isAssignableFrom(CompletableFuture.class)) {
            return CompletableFuture.failedFuture(exception);
        }
        throw exception;
    }

    @FunctionalInterface
    public interface InvocationAction {
        Object proceed() throws Throwable;
    }
}
