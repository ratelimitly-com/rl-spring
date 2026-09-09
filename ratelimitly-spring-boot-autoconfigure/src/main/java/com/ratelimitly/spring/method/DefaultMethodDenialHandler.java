package com.ratelimitly.spring.method;

import com.ratelimitly.RateLimitDecision;

public class DefaultMethodDenialHandler implements MethodDenialHandler {
    @Override
    public Object handleDenied(MethodInvocationContext context, RateLimitDecision decision) {
        throw new RateLimitDeniedException("Rate limit denied for " + MethodIdentitySupport.stableIdentifier(context));
    }
}
