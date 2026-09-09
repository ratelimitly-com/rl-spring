package com.ratelimitly.spring.method;

import java.util.Arrays;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

public class RateLimitlyMethodInterceptor implements MethodInterceptor {
    private final MethodRateLimitEnforcer enforcer;

    public RateLimitlyMethodInterceptor(MethodRateLimitEnforcer enforcer) {
        this.enforcer = enforcer;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        MethodInvocationContext context = new MethodInvocationContext(
            invocation.getThis(),
            invocation.getMethod(),
            Arrays.asList(invocation.getArguments())
        );
        return enforcer.invoke(context, invocation::proceed);
    }
}
