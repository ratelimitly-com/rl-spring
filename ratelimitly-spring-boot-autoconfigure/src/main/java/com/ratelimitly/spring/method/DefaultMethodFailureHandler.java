package com.ratelimitly.spring.method;

import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.properties.FailMode;

public class DefaultMethodFailureHandler implements RateLimitlyFailureHandler<MethodInvocationContext> {
    @Override
    public void onFailure(MethodInvocationContext context, Exception failure, FailMode failMode) {
        if (failMode == FailMode.CLOSED) {
            throw new RateLimitUnavailableException(
                "RateLimitly unavailable for " + MethodIdentitySupport.stableIdentifier(context),
                failure
            );
        }
    }
}
