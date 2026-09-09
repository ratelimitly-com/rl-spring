package com.ratelimitly.spring.servlet;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitlyServletFilter implements Filter {
    private static final String LIFECYCLE_ATTRIBUTE = RateLimitlyServletFilter.class.getName() + ".lifecycle";
    private final ServletRateLimitEnforcer enforcer;

    public RateLimitlyServletFilter(ServletRateLimitEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        ServletDispatchLifecycle lifecycle = ServletDispatchLifecycle.get(httpRequest, LIFECYCLE_ATTRIBUTE);
        ServletDispatchLifecycle.Admission admission = httpRequest.getDispatcherType() == DispatcherType.ASYNC
            ? lifecycle.resume(httpRequest, null) : null;
        if (admission == null) {
            ServletEnforcementOutcome outcome;
            try {
                outcome = enforcer.enforce(new ServletRequestContext(httpRequest, httpResponse, null));
            } catch (ServletException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException("RateLimitly servlet enforcement failed", e);
            }
            if (!outcome.proceed()) { return; }
            admission = lifecycle.admitted(httpRequest, null, outcome.policy(), enforcer);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (httpRequest.isAsyncStarted()) { admission.awaitCompletion(httpRequest); }
            else { admission.complete(); }
        }
    }
}
