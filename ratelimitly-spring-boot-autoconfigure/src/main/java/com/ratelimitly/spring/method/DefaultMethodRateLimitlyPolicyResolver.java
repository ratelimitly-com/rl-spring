package com.ratelimitly.spring.method;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.ratelimitly.LatencyGuard;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

import org.springframework.boot.convert.DurationStyle;

public class DefaultMethodRateLimitlyPolicyResolver implements RateLimitlyPolicyResolver<MethodInvocationContext> {
    private final RateLimitlyProperties properties;
    private final MethodExpressionEvaluator expressionEvaluator;

    public DefaultMethodRateLimitlyPolicyResolver(
        RateLimitlyProperties properties,
        MethodExpressionEvaluator expressionEvaluator
    ) {
        this.properties = properties;
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public RateLimitlyPolicy resolve(MethodInvocationContext context) {
        RateLimited annotation = MethodIdentitySupport.findAnnotation(context);
        if (annotation == null) {
            return null;
        }

        RateLimitlyProperties.Method methodProperties = properties.getMethod();
        String stableIdentifier = MethodIdentitySupport.stableIdentifier(context);
        String configuredContext = annotation.context().isBlank() ? stableIdentifier : annotation.context();
        String rateLimitContext = expressionEvaluator.evaluate(configuredContext, context);
        if (rateLimitContext == null || rateLimitContext.isBlank()) {
            rateLimitContext = stableIdentifier;
        }
        String configuredPolicyName = annotation.policy().isBlank() ? stableIdentifier : annotation.policy();
        String policyName = expressionEvaluator.evaluate(configuredPolicyName, context);
        if (policyName == null || policyName.isBlank()) {
            policyName = stableIdentifier;
        }
        long windowMs = resolveWindowMs(annotation, methodProperties);
        long rateLimit = annotation.rate() > 0 ? annotation.rate() : methodProperties.getDefaultRateLimit();
        int tokensRequested = annotation.tokensRequested() > 0
            ? annotation.tokensRequested()
            : methodProperties.getDefaultTokensRequested();
        ResourceRequest resource = new ResourceRequest(
            methodProperties.getDefaultBucketPrefix() + ":" + rateLimitContext,
            windowMs,
            rateLimit,
            tokensRequested
        );
        List<LatencyGuard> guards = resolveGuards(annotation, context);
        return new RateLimitlyPolicy(
            policyName,
            List.of(resource),
            guards,
            annotation.reportLatency() && methodProperties.isReportLatency(),
            properties.getDefaultFailMode(),
            properties.isEmitDefaultMetricsLabel()
        );
    }

    private long resolveWindowMs(RateLimited annotation, RateLimitlyProperties.Method methodProperties) {
        if (annotation.window().isBlank()) {
            return methodProperties.getDefaultWindow().toMillis();
        }
        Duration duration = DurationStyle.detectAndParse(annotation.window());
        return duration.toMillis();
    }

    private List<LatencyGuard> resolveGuards(RateLimited annotation, MethodInvocationContext context) {
        if (annotation.guards().length == 0) {
            return List.of();
        }
        List<LatencyGuard> guards = new ArrayList<>(annotation.guards().length);
        for (MethodLatencyGuard guard : annotation.guards()) {
            String serviceId = expressionEvaluator.evaluate(guard.service(), context);
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("Latency guard service must resolve to a non-blank name");
            }
            guards.add(new LatencyGuard(
                serviceId,
                guard.thresholdMs(),
                guard.ttlMs(),
                guard.maxSamples(),
                guard.minSampleThreshold()
            ));
        }
        return List.copyOf(guards);
    }
}
