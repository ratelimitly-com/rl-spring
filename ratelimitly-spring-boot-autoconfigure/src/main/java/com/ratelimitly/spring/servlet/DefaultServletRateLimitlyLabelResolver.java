package com.ratelimitly.spring.servlet;

import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;

public class DefaultServletRateLimitlyLabelResolver implements RateLimitlyLabelResolver<ServletRequestContext> {
    @Override
    public String resolveLabel(ServletRequestContext context, RateLimitlyPolicy policy) {
        return context.request().getMethod() + ":" + ServletRequestIdentitySupport.bestPolicyPath(context);
    }
}
