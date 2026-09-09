package com.ratelimitly.spring.method;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;

import com.ratelimitly.LatencyGuard;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

import org.junit.jupiter.api.Test;

class MethodGuardResolutionTest {
    @Test
    void resolvesGuardsFromAnnotationWithExpressions() throws Exception {
        RateLimitlyProperties properties = new RateLimitlyProperties();
        MethodExpressionEvaluator evaluator = new MethodExpressionEvaluator("72623859790382856");
        DefaultMethodRateLimitlyPolicyResolver resolver = new DefaultMethodRateLimitlyPolicyResolver(properties, evaluator);

        Method method = GuardedService.class.getMethod("load", String.class, String.class);
        MethodInvocationContext context = new MethodInvocationContext(new GuardedService(), method, List.of("cust-7", "eu-west"));

        RateLimitlyPolicy policy = resolver.resolve(context);
        List<LatencyGuard> guards = policy.guards();

        assertEquals(2, guards.size());

        LatencyGuard dbGuard = guards.getFirst();
        assertEquals("db:cust-7", dbGuard.latencyTrackerName());
        assertEquals(200, dbGuard.thresholdMs());
        assertEquals(120000, dbGuard.ttlMs());
        assertEquals(20, dbGuard.maxSamples());
        assertEquals(1, dbGuard.minSampleThreshold());

        LatencyGuard cacheGuard = guards.get(1);
        assertEquals("cache:eu-west", cacheGuard.latencyTrackerName());
        assertEquals(50, cacheGuard.thresholdMs());
        assertEquals(60000, cacheGuard.ttlMs());
        assertEquals(40, cacheGuard.maxSamples());
        assertEquals(3, cacheGuard.minSampleThreshold());
    }

    @Test
    void hasNoGuardsWhenAnnotationDoesNotDefineAny() throws Exception {
        RateLimitlyProperties properties = new RateLimitlyProperties();
        MethodExpressionEvaluator evaluator = new MethodExpressionEvaluator("");
        DefaultMethodRateLimitlyPolicyResolver resolver = new DefaultMethodRateLimitlyPolicyResolver(properties, evaluator);

        Method method = UnguardedService.class.getMethod("ping");
        MethodInvocationContext context = new MethodInvocationContext(new UnguardedService(), method, List.of());

        RateLimitlyPolicy policy = resolver.resolve(context);
        assertEquals(0, policy.guards().size());
    }

    static final class GuardedService {
        @RateLimited(
            guards = {
                @MethodLatencyGuard(
                    service = "db:{#customerId}",
                    thresholdMs = 200,
                    ttlMs = 120000,
                    maxSamples = 20,
                    minSampleThreshold = 1
                ),
                @MethodLatencyGuard(
                    service = "cache:{#region}",
                    thresholdMs = 50,
                    ttlMs = 60000,
                    maxSamples = 40,
                    minSampleThreshold = 3
                )
            }
        )
        public void load(String customerId, String region) {
        }
    }

    static final class UnguardedService {
        @RateLimited
        public void ping() {
        }
    }
}
