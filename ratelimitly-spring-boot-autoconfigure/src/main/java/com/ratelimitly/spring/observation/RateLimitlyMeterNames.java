package com.ratelimitly.spring.observation;

public final class RateLimitlyMeterNames {
    public static final String REQUESTS_ALLOWED = "ratelimitly.requests.allowed";
    public static final String REQUESTS_DENIED = "ratelimitly.requests.denied";
    public static final String REQUESTS_FAIL_OPEN = "ratelimitly.requests.fail_open";
    public static final String REQUESTS_FAIL_CLOSED = "ratelimitly.requests.fail_closed";
    public static final String REQUESTS_TIMEOUT = "ratelimitly.requests.timeout";
    public static final String REQUESTS_TRANSPORT_ERROR = "ratelimitly.requests.transport_error";
    public static final String REQUESTS_DNS_ERROR = "ratelimitly.requests.dns_error";
    public static final String REQUESTS_PROTOCOL_ERROR = "ratelimitly.requests.protocol_error";
    public static final String REQUESTS_AUTH_ERROR = "ratelimitly.requests.auth_error";
    public static final String LATENCY_REPORTS_SENT = "ratelimitly.latency_reports.sent";
    public static final String LATENCY_REPORTS_FAILED = "ratelimitly.latency_reports.failed";
    public static final String CLIENT_ROUND_TRIP = "ratelimitly.client.round_trip";
    public static final String HANDLER_EXECUTION = "ratelimitly.handler.execution";
    public static final String METHOD_EXECUTION = "ratelimitly.method.execution";

    private RateLimitlyMeterNames() {
    }
}
