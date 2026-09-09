package com.ratelimitly.spring.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.ratelimitly.ClientDiagnostics;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.spring.autoconfigure.RateLimitlyAutoConfiguration;
import com.ratelimitly.spring.autoconfigure.RateLimitlyMethodAutoConfiguration;
import com.ratelimitly.spring.autoconfigure.RateLimitlyObservationAutoConfiguration;
import com.ratelimitly.spring.autoconfigure.RateLimitlyServletAutoConfiguration;
import com.ratelimitly.spring.method.DefaultMethodRateLimitlyPolicyResolver;
import com.ratelimitly.spring.method.MethodExpressionEvaluator;
import com.ratelimitly.spring.method.MethodInvocationContext;
import com.ratelimitly.spring.method.MethodRateLimitEnforcer;
import com.ratelimitly.spring.policy.RateLimitlyPolicy;
import com.ratelimitly.spring.properties.FailMode;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.servlet.ServletRateLimitEnforcer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;

class PublicDocumentationTest {
    // Isolated format-v1 NONE fixture, never a production credential.
    private static final String API_KEY = "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";
    private static final Path ROOT = repositoryRoot();
    @TempDir Path temporary;

    @Test
    void readmeJavaExamplesCompileAndDescribeMatchingResourcesAndTrackers() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("documentation examples require a JDK").isNotNull();
        List<String> examples = fences("README.md", "java");
        assertEquals(3, examples.size(), "README must exercise all three introductory operations");
        List<Path> sources = new ArrayList<>();
        for (String example : examples) {
            var name = Pattern.compile("public class (\\w+)").matcher(example);
            assertTrue(name.find(), "each README Java example must be a complete class");
            Path source = temporary.resolve(name.group(1) + ".java");
            Files.writeString(source, example);
            sources.add(source);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(null, files, diagnostics, List.of(
                "--release", "21", "-d", temporary.toString(), "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
            ), null, files.getJavaFileObjectsFromPaths(sources)).call(), () -> diagnostics.getDiagnostics().toString());
        }

        try (var classes = new URLClassLoader(new java.net.URL[] {temporary.toUri().toURL()}, getClass().getClassLoader())) {
            RateLimitlyPolicy checkout = policy(classes.loadClass("CheckoutService"));
            assertEquals("method:checkout", checkout.resources().getFirst().bucketName());
            assertEquals(100, checkout.resources().getFirst().rateLimit());
            assertEquals(1000, checkout.resources().getFirst().windowSizeMs());
            assertEquals(1, checkout.resources().getFirst().tokensRequested());
            assertTrue(checkout.guards().isEmpty());

            RecordingClient client = new RecordingClient();
            Class<?> measurements = classes.loadClass("InventoryMeasurements");
            Object bean = measurements.getConstructor(RateLimitlyClient.class).newInstance(client);
            measurements.getMethod("record", long.class).invoke(bean, 18L);
            assertEquals(0, client.requests, "reporting does not perform admission");
            assertEquals(1, client.report.reports().size());
            var report = client.report.reports().getFirst();
            assertEquals(18, report.observedLatencyMs());

            RateLimitlyPolicy guarded = policy(classes.loadClass("GuardedCheckoutService"));
            assertEquals(checkout.resources(), guarded.resources());
            assertFalse(guarded.reportLatency(), "whole-checkout latency must not stand in for inventory latency");
            assertEquals(1, guarded.guards().size());
            var guard = guarded.guards().getFirst();
            assertEquals("inventory", guard.latencyTrackerName());
            assertEquals(100, guard.thresholdMs());
            assertEquals(report.latencyTrackerName(), guard.latencyTrackerName());
            assertEquals(report.ttlMs(), guard.ttlMs());
            assertEquals(report.maxSamples(), guard.maxSamples());
            assertEquals(report.minSampleThreshold(), guard.minSampleThreshold());
        }
    }

    @Test
    void readmeYamlSelectsFailClosedMethodOnlyEnforcement() throws Exception {
        configured(fences("README.md", "yaml").getFirst()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyClient.class)
                .hasSingleBean(MethodRateLimitEnforcer.class).doesNotHaveBean(ServletRateLimitEnforcer.class);
            assertEquals(FailMode.CLOSED, context.getBean(RateLimitlyProperties.class).getDefaultFailMode());
        });
    }

    @Test
    void configurationGuideSeparatesMvcAndClientOnlyModes() throws Exception {
        List<String> examples = fences("docs/configuration.md", "yaml");
        configured(examples.get(0)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ServletRateLimitEnforcer.class)
                .doesNotHaveBean(MethodRateLimitEnforcer.class);
        });
        configured(examples.get(1)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RateLimitlyClient.class)
                .doesNotHaveBean(ServletRateLimitEnforcer.class).doesNotHaveBean(MethodRateLimitEnforcer.class);
        });
    }

    @Test
    void publicGuidesHaveResolvableLocalLinksAndAnchors() throws Exception {
        List<Path> documents = new ArrayList<>(List.of(ROOT.resolve("README.md"), ROOT.resolve("CONTRIBUTING.md"),
            ROOT.resolve("SECURITY.md"), ROOT.resolve("SPRING_BOOT_REQUIREMENTS.md"), ROOT.resolve("MVP_PACKAGE_LAYOUT.md")));
        try (var paths = Files.walk(ROOT.resolve("docs"))) {
            documents.addAll(paths.filter(path -> path.toString().endsWith(".md")).toList());
        }
        Pattern link = Pattern.compile("\\[[^\\]\\n]*]\\(([^)\\s]+)\\)");
        for (Path document : documents) {
            var links = link.matcher(Files.readString(document));
            while (links.find()) {
                String target = links.group(1);
                if (target.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*")) { continue; }
                String[] parts = target.split("#", 2);
                Path destination = parts[0].isEmpty() ? document : document.getParent().resolve(parts[0]).normalize();
                assertTrue(destination.startsWith(ROOT) && Files.exists(destination),
                    () -> document + " has invalid local link " + target);
                if (parts.length == 2 && !parts[1].isEmpty()) {
                    var headings = Pattern.compile("(?m)^#{1,6} (.+)$").matcher(Files.readString(destination));
                    List<String> anchors = new ArrayList<>();
                    while (headings.find()) {
                        anchors.add(headings.group(1).toLowerCase(Locale.ROOT)
                            .replaceAll("[^\\p{L}\\p{N}_ -]", "").replace(' ', '-'));
                    }
                    assertTrue(anchors.contains(parts[1]), () -> document + " has unknown anchor " + target);
                }
            }
        }
    }

    private static RateLimitlyPolicy policy(Class<?> type) throws Exception {
        return new DefaultMethodRateLimitlyPolicyResolver(new RateLimitlyProperties(), new MethodExpressionEvaluator(""))
            .resolve(new MethodInvocationContext(type.getConstructor().newInstance(), type.getMethod("checkout"), List.of()));
    }

    private static WebApplicationContextRunner configured(String yaml) {
        YamlPropertiesFactoryBean parser = new YamlPropertiesFactoryBean();
        parser.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        Properties properties = parser.getObject();
        assertThat(properties).isNotNull();
        assertEquals("${RATELIMITLY_API_KEY}", properties.getProperty("ratelimitly.api-key"));
        properties.setProperty("ratelimitly.api-key", API_KEY);
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class,
                RateLimitlyMethodAutoConfiguration.class, RateLimitlyServletAutoConfiguration.class,
                RateLimitlyObservationAutoConfiguration.class))
            .withPropertyValues(properties.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private static List<String> fences(String file, String language) throws Exception {
        var matches = Pattern.compile("(?ms)^```" + language + "\\R(.*?)^```$").matcher(Files.readString(ROOT.resolve(file)));
        List<String> result = new ArrayList<>();
        while (matches.find()) { result.add(matches.group(1)); }
        return result;
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("ratelimitly-spring-boot-autoconfigure"))) {
            root = root.getParent();
        }
        if (root == null) { throw new IllegalStateException("cannot locate documentation root"); }
        return root;
    }

    private static final class RecordingClient implements RateLimitlyClient {
        private LatencyReport report;
        private int requests;
        @Override public RateLimitDecision checkRateLimit(RateLimitRequest request) {
            requests++;
            return new RateLimitDecision(true, List.of(), List.of(), 0, false);
        }
        @Override public CompletionStage<RateLimitDecision> checkRateLimitAsync(RateLimitRequest request) {
            return CompletableFuture.completedFuture(checkRateLimit(request));
        }
        @Override public void reportLatency(LatencyReport report) { this.report = report; }
        @Override public CompletionStage<Void> reportLatencyAsync(LatencyReport report) {
            reportLatency(report);
            return CompletableFuture.completedFuture(null);
        }
        @Override public ClientDiagnostics diagnostics() { return ClientDiagnostics.empty(); }
        @Override public void close() { }
    }
}
