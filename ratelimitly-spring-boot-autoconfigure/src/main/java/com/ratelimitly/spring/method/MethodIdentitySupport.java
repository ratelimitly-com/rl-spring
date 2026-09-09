package com.ratelimitly.spring.method;

import java.lang.reflect.Method;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

public final class MethodIdentitySupport {
    private MethodIdentitySupport() {
    }

    public static Method resolveMethod(MethodInvocationContext context) {
        if (context.target() == null) {
            return context.method();
        }
        return AopUtils.getMostSpecificMethod(context.method(), context.target().getClass());
    }

    public static String stableIdentifier(MethodInvocationContext context) {
        Method method = resolveMethod(context);
        Class<?> owner = context.target() == null ? method.getDeclaringClass() : context.target().getClass();
        return owner.getName() + "#" + method.getName();
    }

    public static RateLimited findAnnotation(MethodInvocationContext context) {
        Method method = resolveMethod(context);
        RateLimited methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, RateLimited.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        Class<?> owner = context.target() == null ? method.getDeclaringClass() : context.target().getClass();
        return AnnotatedElementUtils.findMergedAnnotation(owner, RateLimited.class);
    }
}
