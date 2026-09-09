package com.ratelimitly.spring.policy;

@FunctionalInterface
public interface RateLimitlyLabelResolver<C> {
    String resolveLabel(C context, RateLimitlyPolicy policy);
}
