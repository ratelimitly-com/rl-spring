package com.ratelimitly.spring.policy;

import java.util.List;

import com.ratelimitly.LatencyGuard;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.properties.FailMode;

public record RateLimitlyPolicy(
    String policyName,
    List<ResourceRequest> resources,
    List<LatencyGuard> guards,
    boolean reportLatency,
    FailMode failMode,
    boolean emitMetricsLabel
) {
    public RateLimitlyPolicy {
        resources = resources == null ? List.of() : List.copyOf(resources);
        guards = guards == null ? List.of() : List.copyOf(guards);
        failMode = failMode == null ? FailMode.OPEN : failMode;
    }
}
