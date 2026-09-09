package com.ratelimitly.spring.servlet;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitlyWebMvcConfigurer implements WebMvcConfigurer {
    private final RateLimitlyHandlerInterceptor interceptor;

    public RateLimitlyWebMvcConfigurer(RateLimitlyHandlerInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(Integer.MIN_VALUE + 100);
    }
}
