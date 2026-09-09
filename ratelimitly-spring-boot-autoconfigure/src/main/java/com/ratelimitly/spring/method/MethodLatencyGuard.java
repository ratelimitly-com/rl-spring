package com.ratelimitly.spring.method;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface MethodLatencyGuard {
    String service();

    long thresholdMs() default 250;

    long ttlMs() default 300_000;

    long maxSamples() default 120;

    long minSampleThreshold() default 1;
}
