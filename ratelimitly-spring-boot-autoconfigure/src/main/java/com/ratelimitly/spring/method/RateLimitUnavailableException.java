package com.ratelimitly.spring.method;

public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
