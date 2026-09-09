package com.ratelimitly.spring.support;

import java.time.Duration;
import java.util.Locale;

import com.ratelimitly.DnsResolver;
import com.ratelimitly.RateLimitlyClientConfig;
import com.ratelimitly.RequestPolicy;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

public class ClientConfigMapper {
    public RateLimitlyClientConfig map(RateLimitlyProperties properties, DnsResolver dnsResolver) {
        RateLimitlyClientConfig.Builder builder = RateLimitlyClientConfig
            .builder(properties.getApiKey())
            .dnsTimeoutMs(toMillis(properties.getClient().getDnsTimeout(), 1000))
            .dnsRefreshIntervalSeconds(toSeconds(properties.getClient().getDnsRefreshInterval(), 300))
            .requestPolicy(mapRequestPolicy(properties.getClient().getRequestPolicy()));

        if (properties.getDnsName() != null) {
            builder.dnsName(properties.getDnsName());
        }
        if (dnsResolver != null) {
            builder.dnsResolver(dnsResolver);
        }

        return builder.build();
    }

    private RequestPolicy mapRequestPolicy(RateLimitlyProperties.RequestPolicy properties) {
        if (properties == null) {
            return RequestPolicy.defaultPolicy();
        }
        long unitMs = toMillis(properties.getUnit(), 20);
        int replayCount = properties.getReplayCount();
        int finalReceiveUnits = properties.getFinalReceiveUnits();
        boolean completionDelivery = properties.isCompletionDelivery();
        RequestPolicy.Schedule schedule = mapSchedule(properties.getSchedule());

        return new RequestPolicy(unitMs, replayCount, schedule, finalReceiveUnits, completionDelivery);
    }

    private RequestPolicy.Schedule mapSchedule(RateLimitlyProperties.RequestPolicy.Schedule properties) {
        if (properties == null) {
            return RequestPolicy.Schedule.fixed(1);
        }
        RequestPolicy.Schedule.Kind kind = RequestPolicy.Schedule.Kind.valueOf(properties.getKind().toUpperCase(Locale.ROOT));
        return switch (kind) {
            case FIXED -> RequestPolicy.Schedule.fixed(properties.getInitialUnits());
            case LINEAR -> RequestPolicy.Schedule.linear(properties.getInitialUnits(), properties.getGrowth(), properties.getMaxUnits());
            case EXPONENTIAL -> RequestPolicy.Schedule.exponential(properties.getInitialUnits(), properties.getGrowth(), properties.getMaxUnits());
        };
    }

    private long toMillis(Duration duration, long defaultValue) {
        if (duration == null) {
            return defaultValue;
        }
        long millis = duration.toMillis();
        if (millis <= 0 || !duration.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException("duration must be a positive whole number of milliseconds");
        }
        return millis;
    }

    private long toSeconds(Duration duration, long defaultValue) {
        if (duration == null) {
            return defaultValue;
        }
        long seconds = duration.toSeconds();
        if (seconds <= 0 || !duration.equals(Duration.ofSeconds(seconds))) {
            throw new IllegalArgumentException("DNS refresh interval must be a positive whole number of seconds");
        }
        return seconds;
    }
}
