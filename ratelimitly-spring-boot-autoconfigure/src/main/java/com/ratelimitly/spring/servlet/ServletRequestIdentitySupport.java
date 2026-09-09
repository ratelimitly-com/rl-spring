package com.ratelimitly.spring.servlet;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

public final class ServletRequestIdentitySupport {
    private ServletRequestIdentitySupport() {
    }

    public static String bestPolicyPath(ServletRequestContext context) {
        Object bestMatchingPattern = context.request().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (context.handler() != null && bestMatchingPattern instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }

        if (context.handler() instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        }

        String requestUri = dispatchUri(context.request());
        return requestUri == null || requestUri.isBlank() ? "/" : requestUri;
    }

    static String dispatchUri(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.INCLUDE
                && request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI) instanceof String included) {
            return included;
        }
        return request.getRequestURI();
    }
}
