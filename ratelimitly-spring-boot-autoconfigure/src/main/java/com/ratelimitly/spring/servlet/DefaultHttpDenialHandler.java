package com.ratelimitly.spring.servlet;

import java.io.IOException;

import com.ratelimitly.RateLimitDecision;

import jakarta.servlet.http.HttpServletResponse;

public class DefaultHttpDenialHandler implements HttpDenialHandler {
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    @Override
    public void handleDenied(ServletRequestContext context, RateLimitDecision decision) throws IOException {
        HttpServletResponse response = context.response();
        if (!response.isCommitted()) {
            response.sendError(HTTP_TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
    }
}
