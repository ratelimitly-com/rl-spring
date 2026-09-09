package com.ratelimitly.spring.sample;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ratelimitly.spring.method.MethodLatencyGuard;
import com.ratelimitly.spring.method.RateLimited;

import org.springframework.stereotype.Service;

@Service
public class DemoService {
    @RateLimited(
        context = "sample-user-lookup",
        policy = "sample-user-lookup",
        label = "demo.user.lookup",
        rate = 10,
        window = "1s"
    )
    public Map<String, Object> loadUserGreeting(String userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("message", "hello from rl-spring");
        payload.put("generatedAt", Instant.now().toString());
        return payload;
    }

    @RateLimited(
        context = "customer:{#customerId}:{#queryParams['region']}",
        policy = "customer-read:{#apiKeyId}",
        label = "demo.customer.lookup.{#pathVariables['customerId']}.{#status}",
        guards = {
            @MethodLatencyGuard(
                service = "customer-db",
                thresholdMs = 250,
                ttlMs = 300000,
                maxSamples = 64
            ),
            @MethodLatencyGuard(
                service = "customer-cache:{#region}",
                thresholdMs = 80,
                ttlMs = 120000,
                maxSamples = 64
            )
        },
        rate = 5,
        window = "1s"
    )
    public Map<String, Object> loadCustomerGreeting(String customerId, String region, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("region", region);
        payload.put("status", status);
        payload.put("message", "customer-specific method limit with request-aware SpEL");
        payload.put("generatedAt", Instant.now().toString());
        return payload;
    }

    @RateLimited(
        context = "slow-customer:{#customerId}:{#region}",
        policy = "slow-customer:{#apiKeyId}",
        label = "demo.customer.slow.{#customerId}",
        guards = {
            @MethodLatencyGuard(
                service = "slow-db",
                thresholdMs = 250,
                ttlMs = 300000,
                maxSamples = 64
            )
        },
        rate = 5,
        window = "1s"
    )
    public Map<String, Object> loadSlowCustomerOperation(String customerId, String region) {
        long startNanos = System.nanoTime();
        try {
            Thread.sleep(300);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating slow customer operation", interruptedException);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerId", customerId);
        payload.put("region", region);
        payload.put("simulatedLatencyMs", elapsedMs);
        payload.put("guardThresholdMs", 250);
        payload.put("message", "slow method for latency guard demo");
        payload.put("generatedAt", Instant.now().toString());
        return payload;
    }
}
