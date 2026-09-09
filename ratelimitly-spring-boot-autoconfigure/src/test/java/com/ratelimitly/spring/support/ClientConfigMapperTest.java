package com.ratelimitly.spring.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Locale;

import com.ratelimitly.RateLimitlyClientConfig;
import com.ratelimitly.RequestPolicy;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

import org.junit.jupiter.api.Test;

class ClientConfigMapperTest {
    // Synthetic format-v1 NONE fixture: key ID 0x0102030405060708, dedup limit 300 ms.
    static final String API_KEY = "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";

    @Test
    void mapsTheJavaDefaultsAndOptionalDnsOverride() {
        RateLimitlyProperties properties = properties();
        RateLimitlyClientConfig config = new ClientConfigMapper().map(properties, null);
        assertTrue(config.dnsNameOverride().isEmpty());
        assertEquals(RequestPolicy.defaultPolicy(), config.requestPolicy());
        assertEquals(1000, config.dnsTimeoutMs());
        assertEquals(300, config.dnsRefreshIntervalSeconds());
        properties.setDnsName("test.invalid");
        assertEquals("test.invalid", new ClientConfigMapper().map(properties, null).dnsNameOverride().orElseThrow());
        assertTrue(!config.toString().contains(API_KEY));
    }

    @Test
    void mapsConservativePolicyAndOptionalFinalReceive() {
        RateLimitlyProperties properties = properties();
        properties.getClient().getRequestPolicy().setUnit(Duration.ofMillis(25));
        properties.getClient().getRequestPolicy().setReplayCount(3);
        RateLimitlyClientConfig config = new ClientConfigMapper().map(properties, null);
        assertEquals(125, config.requestPolicy().horizonMs(300));
        properties.getClient().getRequestPolicy().setFinalReceiveUnits(0);
        properties.getClient().getRequestPolicy().setCompletionDelivery(false);
        config = new ClientConfigMapper().map(properties, null);
        assertEquals(100, config.requestPolicy().horizonMs(300));
        assertTrue(!config.requestPolicy().completionDelivery());
    }

    @Test
    void mapsLinearGrowthBeforeCap() {
        RateLimitlyProperties properties = properties();
        var schedule = properties.getClient().getRequestPolicy().getSchedule();
        schedule.setKind("linear");
        schedule.setInitialUnits(1);
        schedule.setGrowth(2);
        schedule.setMaxUnits(4);
        RequestPolicy.Schedule result = new ClientConfigMapper().map(properties, null).requestPolicy().replayGap();
        assertEquals(RequestPolicy.Schedule.linear(1, 2, 4), result);
        assertEquals(3, result.units(1));
        assertEquals(4, result.units(2));
    }

    @Test
    void mapsExponentialFactorBeforeCap() {
        RateLimitlyProperties properties = properties();
        var schedule = properties.getClient().getRequestPolicy().getSchedule();
        schedule.setKind("exponential");
        schedule.setGrowth(2);
        schedule.setMaxUnits(8);
        RequestPolicy.Schedule result = new ClientConfigMapper().map(properties, null).requestPolicy().replayGap();
        assertEquals(RequestPolicy.Schedule.exponential(1, 2, 8), result);
        assertEquals(4, result.units(2));
        assertEquals(8, result.units(3));
    }

    @Test
    void scheduleParsingDoesNotDependOnTheHostLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            RateLimitlyProperties properties = properties();
            properties.getClient().getRequestPolicy().getSchedule().setKind("fixed");
            assertEquals(RequestPolicy.defaultPolicy(), new ClientConfigMapper().map(properties, null).requestPolicy());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsDurationsThatWouldBeSilentlyRounded() {
        RateLimitlyProperties properties = properties();
        properties.getClient().getRequestPolicy().setUnit(Duration.ofNanos(1_500_000));
        assertThrows(IllegalArgumentException.class, () -> new ClientConfigMapper().map(properties, null));
        properties.getClient().getRequestPolicy().setUnit(Duration.ofMillis(20));
        properties.getClient().setDnsTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> new ClientConfigMapper().map(properties, null));
        properties.getClient().setDnsTimeout(Duration.ofMillis(1000));
        properties.getClient().setDnsRefreshInterval(Duration.ofMillis(1500));
        assertThrows(IllegalArgumentException.class, () -> new ClientConfigMapper().map(properties, null));
    }

    private static RateLimitlyProperties properties() {
        RateLimitlyProperties properties = new RateLimitlyProperties();
        properties.setApiKey(API_KEY);
        return properties;
    }
}
