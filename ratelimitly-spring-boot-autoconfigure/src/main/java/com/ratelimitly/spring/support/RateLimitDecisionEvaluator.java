package com.ratelimitly.spring.support;

import com.ratelimitly.RateLimitDecision;

public class RateLimitDecisionEvaluator {
    public boolean isAllowed(RateLimitDecision decision) {
        return decision != null && decision.success();
    }
}
