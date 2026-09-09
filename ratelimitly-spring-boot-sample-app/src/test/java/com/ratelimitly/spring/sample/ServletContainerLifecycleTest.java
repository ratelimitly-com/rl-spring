package com.ratelimitly.spring.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.policy.RateLimitlyPolicyResolver;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.servlet.ServletRequestContext;
import com.ratelimitly.spring.servlet.ServletRequestIdentitySupport;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = ServletContainerLifecycleTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ratelimitly.enabled=true", "ratelimitly.method.enabled=false",
        "ratelimitly.servlet.filter-enabled=true", "ratelimitly.servlet.interceptor-enabled=false"})
class ServletContainerLifecycleTest {
    @LocalServerPort int port;
    @Autowired RecordingClient client;

    @BeforeEach
    void reset() {
        client.requests.clear();
        client.reports.clear();
        client.completed = new CountDownLatch(1);
    }

    @Test
    void realMvcAsyncResultDoesNotConsumeAgain() throws Exception {
        assertThat(get("/async").statusCode()).isEqualTo(200);
        assertThat(client.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.requests).containsExactly("/async");
        assertThat(client.reports).containsExactly("/async");
    }

    @Test
    void realServletAsyncRestartReportsOnlyOnce() throws Exception {
        HttpResponse<String> response = get("/cycle");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("completed");
        assertThat(client.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.requests).containsExactly("/cycle");
        assertThat(client.reports).containsExactly("/cycle");
    }

    @Test
    void realForwardChecksAndReportsBothTargets() throws Exception {
        client.completed = new CountDownLatch(2);
        HttpResponse<String> response = get("/forward");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("target");
        assertThat(client.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.requests).containsExactly("/forward", "/target");
        assertThat(client.reports).containsExactly("/target", "/forward");
    }

    @Test
    void realErrorPageHasItsOwnAdmissionAndCanBeRejected() throws Exception {
        HttpResponse<String> response = get("/error-source");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(client.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.requests).containsExactly("/error-source", "/error");
        assertThat(client.reports).containsExactly("/error-source");
    }

    @Test
    void realIncludeUsesItsTargetRatherThanTheCallingMvcRoute() throws Exception {
        client.completed = new CountDownLatch(2);
        HttpResponse<String> response = get("/include");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("target");
        assertThat(client.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(client.requests).containsExactly("/include", "/target");
        assertThat(client.reports).containsExactly("/target", "/include");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean RecordingClient fixtureClient() { return new RecordingClient(); }
        @Bean Controller fixtureController() { return new Controller(); }
        @Bean RateLimitlyPolicyResolver<ServletRequestContext> fixturePolicy() {
            return context -> {
                String target = ServletRequestIdentitySupport.bestPolicyPath(context);
                return new RateLimitlyPolicy(target,
                    List.of(new ResourceRequest(target, 1000, 100, 1)),
                    List.of(new LatencyGuard(target, 1000, 10000, 20, 5)), true, FailMode.CLOSED, false);
            };
        }
    }

    @RestController
    static class Controller {
        @GetMapping("/async") CompletionStage<String> async() {
            return CompletableFuture.completedFuture("completed");
        }
        @GetMapping("/cycle") void cycle(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                request.startAsync().dispatch();
            } else {
                var next = request.startAsync();
                response.getWriter().write("completed");
                next.complete();
            }
        }
        @GetMapping("/forward") void forward(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/target").forward(request, response);
        }
        @GetMapping("/target") String target() { return "target"; }
        @GetMapping("/include") void include(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/target").include(request, response);
        }
        @GetMapping("/error-source") void error(HttpServletResponse response) throws IOException {
            response.sendError(500);
        }
    }

    static class RecordingClient implements RateLimitlyClient {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> reports = new CopyOnWriteArrayList<>();
        volatile CountDownLatch completed = new CountDownLatch(1);
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            String target = request.guards().getFirst().latencyTrackerName();
            requests.add(target);
            return new RateLimitDecision(!target.equals("/error"), List.of(), List.of(), 1, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) {
            reports.add(report.reports().getFirst().latencyTrackerName());
            completed.countDown();
        }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            reportLatency(report);
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
