package com.ratelimitly.spring.servlet;

import org.springframework.web.servlet.AsyncHandlerInterceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitlyHandlerInterceptor implements AsyncHandlerInterceptor {
    private static final String LIFECYCLE_ATTRIBUTE = RateLimitlyHandlerInterceptor.class.getName() + ".lifecycle";

    private final ServletRateLimitEnforcer enforcer;

    public RateLimitlyHandlerInterceptor(ServletRateLimitEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ServletDispatchLifecycle lifecycle = ServletDispatchLifecycle.get(request, LIFECYCLE_ATTRIBUTE);
        ServletDispatchLifecycle.Admission admission = null;
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            admission = lifecycle.resume(request, handler);
        }
        if (admission == null) {
            ServletEnforcementOutcome outcome = enforcer.enforce(new ServletRequestContext(request, response, handler));
            if (!outcome.proceed()) { return false; }
            admission = lifecycle.admitted(request, handler, outcome.policy(), enforcer);
        }
        lifecycle.enter(admission);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ServletDispatchLifecycle.Admission admission =
            ServletDispatchLifecycle.get(request, LIFECYCLE_ATTRIBUTE).exit(handler);
        if (admission != null) {
            if (request.isAsyncStarted()) { admission.awaitCompletion(request); }
            else { admission.complete(); }
        }
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ServletDispatchLifecycle.Admission admission =
            ServletDispatchLifecycle.get(request, LIFECYCLE_ATTRIBUTE).exit(handler);
        if (admission != null) { admission.awaitCompletion(request); }
    }
}
