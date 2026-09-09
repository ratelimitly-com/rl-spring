package com.ratelimitly.spring.support;

import java.util.Objects;

import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.observation.RateLimitlyObservationRecorder;
import com.ratelimitly.spring.properties.FailMode;

/** Internal adapter boundary: optional observation must not control admission or application outcomes. */
public final class BestEffortObservation {
    private BestEffortObservation() { }

    public static RateLimitlyObservationRecorder wrap(RateLimitlyObservationRecorder recorder) {
        return new SafeRecorder(Objects.requireNonNull(recorder, "recorder"));
    }

    private static void observe(Runnable action) {
        try { action.run(); }
        catch (RuntimeException ignored) {
            // Do not disclose application values or recursively use the failing recorder.
            // Fatal JVM Errors are intentionally not classified as observation failures.
        }
    }

    private static final class SafeRecorder extends RateLimitlyObservationRecorder {
        private final RateLimitlyObservationRecorder delegate;
        SafeRecorder(RateLimitlyObservationRecorder delegate) {
            super(null);
            this.delegate = delegate;
        }
        @Override public void recordAllowed() { observe(delegate::recordAllowed); }
        @Override public void recordDenied() { observe(delegate::recordDenied); }
        @Override public void recordFailOpen() { observe(delegate::recordFailOpen); }
        @Override public void recordFailClosed() { observe(delegate::recordFailClosed); }
        @Override public void recordLatencyReportSent() { observe(delegate::recordLatencyReportSent); }
        @Override public void recordLatencyReportFailed() { observe(delegate::recordLatencyReportFailed); }
        @Override public void recordClientRoundTrip(long nanos) { observe(() -> delegate.recordClientRoundTrip(nanos)); }
        @Override public void recordHandlerExecution(long nanos) { observe(() -> delegate.recordHandlerExecution(nanos)); }
        @Override public void recordMethodExecution(long nanos) { observe(() -> delegate.recordMethodExecution(nanos)); }
        @Override public void recordFailure(RateLimitlyException failure) { observe(() -> delegate.recordFailure(failure)); }
        @Override public void recordFailureMode(FailMode mode) { observe(() -> delegate.recordFailureMode(mode)); }
    }
}
