package com.ratelimitly.spring.policy;

import com.ratelimitly.spring.properties.FailMode;

@FunctionalInterface
public interface RateLimitlyFailureHandler<C> {
    void onFailure(C context, Exception failure, FailMode failMode) throws Exception;
}
