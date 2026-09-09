package com.ratelimitly.spring.autoconfigure;

import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.properties.RateLimitlyProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;

@AutoConfiguration(after = RateLimitlyAutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnProperty(prefix = "ratelimitly", name = "enabled", havingValue = "true")
public class RateLimitlyObservationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RateLimitlyObservationRecorder rateLimitlyObservationRecorder(
        RateLimitlyProperties properties,
        ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        // Observation is optional; admission still needs a recorder when it is disabled.
        MeterRegistry registry = properties.getObservation().isEnabled() ? meterRegistryProvider.getIfAvailable() : null;
        return new RateLimitlyObservationRecorder(registry);
    }
}
