package com.ratelimitly.spring.policy;

@FunctionalInterface
public interface RateLimitlyPolicyResolver<C> {
    RateLimitlyPolicy resolve(C context);
}
