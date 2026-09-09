package com.ratelimitly.spring.method;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RateLimited {
    String context() default "";

    String policy() default "";

    long rate() default -1;

    String window() default "";

    int tokensRequested() default -1;

    String label() default "";

    MethodLatencyGuard[] guards() default {};

    boolean reportLatency() default true;
}
