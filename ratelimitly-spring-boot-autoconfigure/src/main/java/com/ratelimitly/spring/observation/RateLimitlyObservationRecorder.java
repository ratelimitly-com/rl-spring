package com.ratelimitly.spring.observation;

import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.properties.FailMode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class RateLimitlyObservationRecorder {
    private final MeterRegistry meterRegistry;

    public RateLimitlyObservationRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordAllowed() {
        increment(RateLimitlyMeterNames.REQUESTS_ALLOWED);
    }

    public void recordDenied() {
        increment(RateLimitlyMeterNames.REQUESTS_DENIED);
    }

    public void recordFailOpen() {
        increment(RateLimitlyMeterNames.REQUESTS_FAIL_OPEN);
    }

    public void recordFailClosed() {
        increment(RateLimitlyMeterNames.REQUESTS_FAIL_CLOSED);
    }

    public void recordLatencyReportSent() {
        increment(RateLimitlyMeterNames.LATENCY_REPORTS_SENT);
    }

    public void recordLatencyReportFailed() {
        increment(RateLimitlyMeterNames.LATENCY_REPORTS_FAILED);
    }

    public void recordClientRoundTrip(long nanos) {
        Timer timer = timer(RateLimitlyMeterNames.CLIENT_ROUND_TRIP);
        if (timer != null) {
            timer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordHandlerExecution(long nanos) {
        Timer timer = timer(RateLimitlyMeterNames.HANDLER_EXECUTION);
        if (timer != null) {
            timer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordMethodExecution(long nanos) {
        Timer timer = timer(RateLimitlyMeterNames.METHOD_EXECUTION);
        if (timer != null) {
            timer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordFailure(RateLimitlyException failure) {
        if (failure == null) {
            return;
        }
        switch (failure.kind()) {
            case TIMEOUT -> increment(RateLimitlyMeterNames.REQUESTS_TIMEOUT);
            case DNS_DISCOVERY -> increment(RateLimitlyMeterNames.REQUESTS_DNS_ERROR);
            case PROTOCOL, NO_VALID_RESPONSE -> increment(RateLimitlyMeterNames.REQUESTS_PROTOCOL_ERROR);
            case AUTHENTICATION, CONFIGURATION -> increment(RateLimitlyMeterNames.REQUESTS_AUTH_ERROR);
            case TRANSPORT_IO -> increment(RateLimitlyMeterNames.REQUESTS_TRANSPORT_ERROR);
            default -> increment(RateLimitlyMeterNames.REQUESTS_FAIL_CLOSED);
        }
    }

    public void recordFailureMode(FailMode failMode) {
        if (failMode == FailMode.CLOSED) {
            recordFailClosed();
        } else {
            recordFailOpen();
        }
    }

    private void increment(String meterName) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(meterName).increment();
    }

    private Timer timer(String meterName) {
        return meterRegistry == null ? null : Timer.builder(meterName).register(meterRegistry);
    }
}
