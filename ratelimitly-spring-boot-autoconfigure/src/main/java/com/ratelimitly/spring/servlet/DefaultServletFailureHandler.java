package com.ratelimitly.spring.servlet;

import java.io.IOException;

import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.properties.FailMode;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

public class DefaultServletFailureHandler implements RateLimitlyFailureHandler<ServletRequestContext> {
    @Override
    public void onFailure(ServletRequestContext context, Exception failure, FailMode failMode) throws Exception {
        if (failMode != FailMode.CLOSED) {
            return;
        }

        if (context.response().isCommitted()) {
            throw new ServletException("RateLimitly request failed after response commit", failure);
        }

        sendUnavailable(context.response());
    }

    private void sendUnavailable(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "RateLimitly unavailable");
    }
}
