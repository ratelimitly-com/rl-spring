package com.ratelimitly.spring.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = {SampleApplication.class, SampleIntegrationTest.ClientConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"ratelimitly.enabled=true", "ratelimitly.api-key=rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys"})
class SampleIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private RecordingClient client;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        client.allowed = true;
        client.requests.clear();
        client.reports.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void starterLoadsServletAndMethodIntegrationAgainstV3Api() throws Exception {
        assertEquals(1, context.getBeansOfType(AbstractAdvisorAutoProxyCreator.class).size(),
            () -> "Competing proxy creators: " + context.getBeansOfType(AbstractAdvisorAutoProxyCreator.class));
        mvc.perform(get("/api/demo/customers/cust-123").param("region", "us-east").param("status", "active"))
            .andExpect(status().isOk());
        assertEquals(2, client.requests.size(), "servlet and method each have an explicitly configured policy");
        RateLimitRequest method = client.requests.get(1);
        assertEquals(2, method.guards().size());
        assertEquals("customer-db", method.guards().getFirst().latencyTrackerName());
        assertEquals("customer-cache:us-east", method.guards().get(1).latencyTrackerName());
        assertEquals(1, client.reports.size());
        assertEquals(2, client.reports.getFirst().reports().size());
        assertEquals("customer-db", client.reports.getFirst().reports().getFirst().latencyTrackerName());
    }

    @Test
    void servletDenialStopsBeforeMethodEnforcementOrReporting() throws Exception {
        client.allowed = false;
        mvc.perform(get("/api/demo/customers/cust-123")).andExpect(status().isTooManyRequests());
        assertEquals(1, client.requests.size());
        assertTrue(client.reports.isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClientConfiguration {
        @Bean RecordingClient fixtureClient() { return new RecordingClient(); }
    }

    static class RecordingClient implements RateLimitlyClient {
        private boolean allowed = true;
        private final List<RateLimitRequest> requests = new ArrayList<>();
        private final List<LatencyReport> reports = new ArrayList<>();

        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            requests.add(request);
            return new RateLimitDecision(allowed, List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) { reports.add(report); }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            reportLatency(report);
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
