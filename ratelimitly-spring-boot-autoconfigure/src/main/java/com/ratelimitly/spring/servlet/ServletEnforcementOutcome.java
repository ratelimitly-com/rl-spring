package com.ratelimitly.spring.servlet;

import com.ratelimitly.spring.policy.RateLimitlyPolicy;

public record ServletEnforcementOutcome(
    boolean proceed,
    RateLimitlyPolicy policy
) {
}
