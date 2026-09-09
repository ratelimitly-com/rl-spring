package com.ratelimitly.spring.autoconfigure;

import java.util.EnumSet;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.policy.RateLimitlyFailureHandler;
import com.ratelimitly.spring.policy.RateLimitlyLabelResolver;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.servlet.DefaultHttpDenialHandler;
import com.ratelimitly.spring.servlet.DefaultServletFailureHandler;
import com.ratelimitly.spring.servlet.DefaultServletRateLimitlyLabelResolver;
import com.ratelimitly.spring.servlet.DefaultServletRateLimitlyPolicyResolver;
import com.ratelimitly.spring.servlet.HttpDenialHandler;
import com.ratelimitly.spring.servlet.RateLimitlyHandlerInterceptor;
import com.ratelimitly.spring.servlet.RateLimitlyServletFilter;
import com.ratelimitly.spring.servlet.RateLimitlyWebMvcConfigurer;
import com.ratelimitly.spring.servlet.ServletRequestContext;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;
import com.ratelimitly.spring.support.RateLimitDecisionEvaluator;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.DispatcherType;

@AutoConfiguration(after = RateLimitlyAutoConfiguration.class)
@ConditionalOnClass(HttpServletRequest.class)
@ConditionalOnBean(RateLimitlyClient.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ratelimitly", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "ratelimitly.servlet", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitlyServletAutoConfiguration {
    @Bean
    public InitializingBean rateLimitlyServletAdapterSelection(RateLimitlyProperties properties) {
        return () -> {
            if (properties.getServlet().isFilterEnabled() && properties.getServlet().isInterceptorEnabled()) {
                throw new IllegalStateException("Choose one HTTP adapter: ratelimitly.servlet.filter-enabled and "
                    + "ratelimitly.servlet.interceptor-enabled must not both be true");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyPolicyResolver<ServletRequestContext> servletRateLimitlyPolicyResolver(
        RateLimitlyProperties properties
    ) {
        return new DefaultServletRateLimitlyPolicyResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyLabelResolver<ServletRequestContext> servletRateLimitlyLabelResolver() {
        return new DefaultServletRateLimitlyLabelResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpDenialHandler httpDenialHandler() {
        return new DefaultHttpDenialHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyFailureHandler<ServletRequestContext> servletRateLimitlyFailureHandler() {
        return new DefaultServletFailureHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ServletRateLimitEnforcer servletRateLimitEnforcer(
        RateLimitlyClient client,
        RateLimitlyPolicyResolver<ServletRequestContext> policyResolver,
        RateLimitlyLabelResolver<ServletRequestContext> labelResolver,
        HttpDenialHandler denialHandler,
        RateLimitlyFailureHandler<ServletRequestContext> failureHandler,
        RateLimitDecisionEvaluator decisionEvaluator,
        RateLimitlyObservationRecorder observationRecorder,
        RateLimitlyProperties properties
    ) {
        return new ServletRateLimitEnforcer(
            client,
            policyResolver,
            labelResolver,
            denialHandler,
            failureHandler,
            decisionEvaluator,
            observationRecorder,
            properties.getClient().isDebug()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimitly.servlet", name = "filter-enabled", havingValue = "true")
    public RateLimitlyServletFilter rateLimitlyServletFilter(ServletRateLimitEnforcer enforcer) {
        return new RateLimitlyServletFilter(enforcer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimitly.servlet", name = "filter-enabled", havingValue = "true")
    public FilterRegistrationBean<RateLimitlyServletFilter> rateLimitlyServletFilterRegistration(
        RateLimitlyServletFilter filter
    ) {
        FilterRegistrationBean<RateLimitlyServletFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("rateLimitlyServletFilter");
        registration.setOrder(Integer.MIN_VALUE + 100);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        return registration;
    }

    @Bean
    @ConditionalOnClass(HandlerInterceptor.class)
    @ConditionalOnProperty(prefix = "ratelimitly.servlet", name = "interceptor-enabled", havingValue = "true", matchIfMissing = true)
    public RateLimitlyHandlerInterceptor rateLimitlyHandlerInterceptor(ServletRateLimitEnforcer enforcer) {
        return new RateLimitlyHandlerInterceptor(enforcer);
    }

    @Bean
    @ConditionalOnClass(WebMvcConfigurer.class)
    @ConditionalOnProperty(prefix = "ratelimitly.servlet", name = "interceptor-enabled", havingValue = "true", matchIfMissing = true)
    public WebMvcConfigurer rateLimitlyWebMvcConfigurer(RateLimitlyHandlerInterceptor interceptor) {
        return new RateLimitlyWebMvcConfigurer(interceptor);
    }
}
