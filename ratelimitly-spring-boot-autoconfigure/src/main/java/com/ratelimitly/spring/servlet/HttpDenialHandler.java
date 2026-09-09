package com.ratelimitly.spring.servlet;

import java.io.IOException;

import com.ratelimitly.RateLimitDecision;

@FunctionalInterface
public interface HttpDenialHandler {
    void handleDenied(ServletRequestContext context, RateLimitDecision decision) throws IOException;
}
