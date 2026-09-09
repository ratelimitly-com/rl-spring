package com.ratelimitly.spring.method;

public class RateLimitDeniedException extends RuntimeException {
    public RateLimitDeniedException(String message) {
        super(message);
    }
}
