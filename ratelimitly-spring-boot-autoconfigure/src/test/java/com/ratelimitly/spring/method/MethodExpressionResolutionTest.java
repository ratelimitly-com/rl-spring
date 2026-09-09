package com.ratelimitly.spring.method;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.ratelimitly.LatencyGuard;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class MethodExpressionResolutionTest {
    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolvesContextPolicyAndLabelFromArgsApiKeyIdAndRequest() throws Exception {
        RateLimitlyProperties properties = new RateLimitlyProperties();
        properties.getMethod().setDefaultBucketPrefix("method");

        MethodExpressionEvaluator evaluator = new MethodExpressionEvaluator("72623859790382856");
        DefaultMethodRateLimitlyPolicyResolver policyResolver = new DefaultMethodRateLimitlyPolicyResolver(properties, evaluator);
        DefaultMethodRateLimitlyLabelResolver labelResolver = new DefaultMethodRateLimitlyLabelResolver(evaluator);

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(buildRequest()));

        SampleTarget target = new SampleTarget();
        Method method = SampleTarget.class.getMethod("loadCustomer", String.class, String.class, String.class);
        MethodInvocationContext context = new MethodInvocationContext(target, method, List.of("cust-123", "us-east", "active"));

        RateLimitlyPolicy policy = policyResolver.resolve(context);
        ResourceRequest resource = policy.resources().getFirst();

        assertEquals("customer-read:72623859790382856", policy.policyName());
        assertEquals("method:customer:cust-123:us-east", resource.bucketName());
        assertEquals(1_000L, resource.windowSizeMs());
        assertEquals(5L, resource.rateLimit());
        assertEquals(1, resource.tokensRequested());

        List<LatencyGuard> guards = policy.guards();
        assertEquals(2, guards.size());
        assertEquals("customer-db:cust-123", guards.getFirst().latencyTrackerName());
        assertEquals("customer-cache:us-east", guards.get(1).latencyTrackerName());
        assertEquals("demo.customer.lookup.cust-123.active", labelResolver.resolveLabel(context, policy));
    }

    @Test
    void apiKeyIdentityCannotBeReplacedByLazyMethodArgumentLoading() throws Exception {
        MethodExpressionEvaluator evaluator = new MethodExpressionEvaluator("72623859790382856");
        Method method = SampleTarget.class.getMethod("collidingArguments", String.class, String.class);
        MethodInvocationContext context = new MethodInvocationContext(new SampleTarget(), method,
            List.of("cust-123", "untrusted-argument"));

        assertEquals("cust-123:72623859790382856", evaluator.evaluate("{#a0}:{#apiKeyId}", context));
        assertEquals("72623859790382856:cust-123:72623859790382856",
            evaluator.evaluate("{#apiKeyId}:{#p0}:{#apiKeyId}", context));
        assertEquals("untrusted-argument", evaluator.evaluate("{#p1}", context));
    }

    @Test
    void apiKeyIdentityCannotBeReplacedByPathOrQueryParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("apiKeyId", "untrusted-query");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("apiKeyId", "untrusted-path"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MethodInvocationContext context = new MethodInvocationContext(new Object(),
            Object.class.getMethod("toString"), List.of());

        assertEquals("72623859790382856:untrusted-path:untrusted-query",
            new MethodExpressionEvaluator("72623859790382856").evaluate(
                "{#apiKeyId}:{#pathVariables['apiKeyId']}:{#queryParams['apiKeyId']}", context));
    }

    private HttpServletRequest buildRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo/customers/cust-123");
        request.setParameter("region", "us-east");
        request.setParameter("status", "active");
        request.setParameter("apiKeyId", "must-not-override-configured-identity");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("customerId", "cust-123"));
        return request;
    }

    static final class SampleTarget {
        public void collidingArguments(String customerId, String apiKeyId) {
        }

        @RateLimited(
            context = "customer:{#customerId}:{#queryParams['region']}",
            policy = "customer-read:{#apiKeyId}",
            label = "demo.customer.lookup.{#pathVariables['customerId']}.{#status}",
            guards = {
                @MethodLatencyGuard(service = "customer-db:{#customerId}", thresholdMs = 250, ttlMs = 300000),
                @MethodLatencyGuard(service = "customer-cache:{#queryParams['region']}", thresholdMs = 80, ttlMs = 120000)
            },
            rate = 5,
            window = "1s"
        )
        public void loadCustomer(String customerId, String region, String status) {
        }
    }
}
