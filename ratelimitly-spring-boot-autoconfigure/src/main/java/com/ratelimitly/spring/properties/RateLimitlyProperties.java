package com.ratelimitly.spring.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ratelimitly", ignoreUnknownFields = false)
public class RateLimitlyProperties {
    private boolean enabled;
    private String dnsName;
    private String apiKey;
    private FailMode defaultFailMode = FailMode.OPEN;
    private boolean emitDefaultMetricsLabel;
    private final Client client = new Client();
    private final Servlet servlet = new Servlet();
    private final Method method = new Method();
    private final Observation observation = new Observation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDnsName() {
        return dnsName;
    }

    public void setDnsName(String dnsName) {
        this.dnsName = dnsName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public FailMode getDefaultFailMode() {
        return defaultFailMode;
    }

    public void setDefaultFailMode(FailMode defaultFailMode) {
        this.defaultFailMode = defaultFailMode;
    }

    public boolean isEmitDefaultMetricsLabel() {
        return emitDefaultMetricsLabel;
    }

    public void setEmitDefaultMetricsLabel(boolean emitDefaultMetricsLabel) {
        this.emitDefaultMetricsLabel = emitDefaultMetricsLabel;
    }

    public Client getClient() {
        return client;
    }

    public Servlet getServlet() {
        return servlet;
    }

    public Method getMethod() {
        return method;
    }

    public Observation getObservation() {
        return observation;
    }

    public static final class Client {
        private Duration dnsTimeout = Duration.ofSeconds(1);
        private Duration dnsRefreshInterval = Duration.ofMinutes(5);
        private boolean debug;
        private final RequestPolicy requestPolicy = new RequestPolicy();

        public Duration getDnsTimeout() {
            return dnsTimeout;
        }

        public void setDnsTimeout(Duration dnsTimeout) {
            this.dnsTimeout = dnsTimeout;
        }

        public Duration getDnsRefreshInterval() {
            return dnsRefreshInterval;
        }

        public void setDnsRefreshInterval(Duration dnsRefreshInterval) {
            this.dnsRefreshInterval = dnsRefreshInterval;
        }

        public boolean isDebug() {
            return debug;
        }

        public void setDebug(boolean debug) {
            this.debug = debug;
        }

        public RequestPolicy getRequestPolicy() {
            return requestPolicy;
        }
    }

    public static final class RequestPolicy {
        private Duration unit = Duration.ofMillis(20);
        private int replayCount = 1;
        private int finalReceiveUnits = 1;
        private boolean completionDelivery = true;
        private final Schedule schedule = new Schedule();

        public Duration getUnit() {
            return unit;
        }

        public void setUnit(Duration unit) {
            this.unit = unit;
        }

        public int getReplayCount() {
            return replayCount;
        }

        public void setReplayCount(int replayCount) {
            this.replayCount = replayCount;
        }

        public int getFinalReceiveUnits() {
            return finalReceiveUnits;
        }

        public void setFinalReceiveUnits(int finalReceiveUnits) {
            this.finalReceiveUnits = finalReceiveUnits;
        }

        public boolean isCompletionDelivery() {
            return completionDelivery;
        }

        public void setCompletionDelivery(boolean completionDelivery) {
            this.completionDelivery = completionDelivery;
        }

        public Schedule getSchedule() {
            return schedule;
        }

        public static final class Schedule {
            private String kind = "FIXED";
            private long initialUnits = 1;
            private long maxUnits = 1;
            private long growth = 0;

            public String getKind() {
                return kind;
            }

            public void setKind(String kind) {
                this.kind = kind;
            }

            public long getInitialUnits() {
                return initialUnits;
            }

            public void setInitialUnits(long initialUnits) {
                this.initialUnits = initialUnits;
            }

            public long getMaxUnits() {
                return maxUnits;
            }

            public void setMaxUnits(long maxUnits) {
                this.maxUnits = maxUnits;
            }

            public long getGrowth() {
                return growth;
            }

            public void setGrowth(long growth) {
                this.growth = growth;
            }
        }
    }

    public static final class Servlet {
        private boolean enabled = true;
        private boolean filterEnabled;
        private boolean interceptorEnabled = true;
        private boolean reportLatency = true;
        private String defaultBucketPrefix = "http";
        private Duration defaultWindow = Duration.ofSeconds(1);
        private long defaultRateLimit = 1000;
        private int defaultTokensRequested = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFilterEnabled() {
            return filterEnabled;
        }

        public void setFilterEnabled(boolean filterEnabled) {
            this.filterEnabled = filterEnabled;
        }

        public boolean isInterceptorEnabled() {
            return interceptorEnabled;
        }

        public void setInterceptorEnabled(boolean interceptorEnabled) {
            this.interceptorEnabled = interceptorEnabled;
        }

        public boolean isReportLatency() {
            return reportLatency;
        }

        public void setReportLatency(boolean reportLatency) {
            this.reportLatency = reportLatency;
        }

        public String getDefaultBucketPrefix() {
            return defaultBucketPrefix;
        }

        public void setDefaultBucketPrefix(String defaultBucketPrefix) {
            this.defaultBucketPrefix = defaultBucketPrefix;
        }

        public Duration getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(Duration defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public long getDefaultRateLimit() {
            return defaultRateLimit;
        }

        public void setDefaultRateLimit(long defaultRateLimit) {
            this.defaultRateLimit = defaultRateLimit;
        }

        public int getDefaultTokensRequested() {
            return defaultTokensRequested;
        }

        public void setDefaultTokensRequested(int defaultTokensRequested) {
            this.defaultTokensRequested = defaultTokensRequested;
        }
    }

    public static final class Method {
        private boolean enabled = true;
        private boolean reportLatency = true;
        private String defaultBucketPrefix = "method";
        private Duration defaultWindow = Duration.ofSeconds(1);
        private long defaultRateLimit = 1000;
        private int defaultTokensRequested = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isReportLatency() {
            return reportLatency;
        }

        public void setReportLatency(boolean reportLatency) {
            this.reportLatency = reportLatency;
        }

        public String getDefaultBucketPrefix() {
            return defaultBucketPrefix;
        }

        public void setDefaultBucketPrefix(String defaultBucketPrefix) {
            this.defaultBucketPrefix = defaultBucketPrefix;
        }

        public Duration getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(Duration defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public long getDefaultRateLimit() {
            return defaultRateLimit;
        }

        public void setDefaultRateLimit(long defaultRateLimit) {
            this.defaultRateLimit = defaultRateLimit;
        }

        public int getDefaultTokensRequested() {
            return defaultTokensRequested;
        }

        public void setDefaultTokensRequested(int defaultTokensRequested) {
            this.defaultTokensRequested = defaultTokensRequested;
        }
    }

    public static final class Observation {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
