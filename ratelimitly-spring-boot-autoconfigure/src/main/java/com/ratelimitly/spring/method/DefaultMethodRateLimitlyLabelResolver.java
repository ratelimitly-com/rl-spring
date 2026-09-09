package com.ratelimitly.spring.method;

import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;

public class DefaultMethodRateLimitlyLabelResolver implements RateLimitlyLabelResolver<MethodInvocationContext> {
    private final MethodExpressionEvaluator expressionEvaluator;

    public DefaultMethodRateLimitlyLabelResolver(MethodExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public String resolveLabel(MethodInvocationContext context, RateLimitlyPolicy policy) {
        RateLimited annotation = MethodIdentitySupport.findAnnotation(context);
        if (annotation != null && !annotation.label().isBlank()) {
            String label = expressionEvaluator.evaluate(annotation.label(), context);
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return MethodIdentitySupport.stableIdentifier(context);
    }
}
