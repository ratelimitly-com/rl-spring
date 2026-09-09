package com.ratelimitly.spring.servlet;

import java.util.List;

import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

public class DefaultServletRateLimitlyPolicyResolver implements RateLimitlyPolicyResolver<ServletRequestContext> {
    private final RateLimitlyProperties properties;

    public DefaultServletRateLimitlyPolicyResolver(RateLimitlyProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateLimitlyPolicy resolve(ServletRequestContext context) {
        RateLimitlyProperties.Servlet servlet = properties.getServlet();
        String bucketId = servlet.getDefaultBucketPrefix()
            + ":"
            + context.request().getMethod()
            + ":"
            + ServletRequestIdentitySupport.bestPolicyPath(context);
        ResourceRequest resource = new ResourceRequest(
            bucketId,
            servlet.getDefaultWindow().toMillis(),
            servlet.getDefaultRateLimit(),
            servlet.getDefaultTokensRequested()
        );
        return new RateLimitlyPolicy(
            "default-servlet",
            List.of(resource),
            List.of(),
            servlet.isReportLatency(),
            properties.getDefaultFailMode(),
            properties.isEmitDefaultMetricsLabel()
        );
    }
}
