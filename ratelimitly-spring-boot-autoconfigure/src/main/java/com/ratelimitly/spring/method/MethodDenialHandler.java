package com.ratelimitly.spring.method;

import com.ratelimitly.RateLimitDecision;

@FunctionalInterface
public interface MethodDenialHandler {
    Object handleDenied(MethodInvocationContext context, RateLimitDecision decision);
}
