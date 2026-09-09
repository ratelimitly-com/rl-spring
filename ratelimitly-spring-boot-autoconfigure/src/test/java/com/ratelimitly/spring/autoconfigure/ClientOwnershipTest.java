package com.ratelimitly.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.ratelimitly.DnsResolver;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyClientConfig;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.spring.properties.RateLimitlyProperties;
import com.ratelimitly.spring.support.ClientConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ClientOwnershipTest {
    // Public synthetic NONE fixture, not a deployed credential.
    private static final String API_KEY = "rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys";
    private static final RateLimitRequest EMPTY = new RateLimitRequest(List.of(), List.of(), null);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class))
        .withPropertyValues("ratelimitly.enabled=true", "ratelimitly.api-key=" + API_KEY);

    @Test
    void laterStartupFailureClosesAlreadyCreatedClient() {
        AtomicReference<RateLimitlyClient> created = new AtomicReference<>();
        runner.withBean("brokenAfterClient", Object.class, () -> {
            throw new IllegalStateException("deliberate startup failure");
        }, definition -> definition.setDependsOn(RateLimitlyBeanNames.CLIENT))
        .withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(
            new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof RateLimitlyClient client) { created.set(client); }
                    return bean;
                }
            })).run(context -> assertThat(context).hasFailed());
        assertThat(created.get()).isNotNull();
        assertThat(assertThrows(RateLimitlyException.class, () -> created.get().checkRateLimit(EMPTY)))
            .hasMessageContaining("closed");
    }

    @Test
    void suppliedExecutorSurvivesClientShutdownAndQueuedOperationFailsCleanly() throws Exception {
        var executor = new QueuedExecutor();
        // Supply a deterministic queueing executor: no DNS, scheduling race, or sleep.
        AtomicReference<CompletionStage<RateLimitDecision>> pending = new AtomicReference<>();
        try {
            runner.withBean(ClientConfigMapper.class, () -> new ClientConfigMapper() {
                @Override public RateLimitlyClientConfig map(RateLimitlyProperties properties, DnsResolver resolver) {
                    return RateLimitlyClientConfig.builder(properties.getApiKey())
                        .asyncExecutor(executor)
                        .dnsResolver(name -> { throw new AssertionError("unexpected DNS"); }).build();
                }
            }).run(context -> {
                assertThat(context).hasNotFailed();
                pending.set(context.getBean(RateLimitlyClient.class).checkRateLimitAsync(EMPTY));
                assertThat(pending.get().toCompletableFuture()).isNotDone();
            });
            assertThat(executor.isShutdown()).isFalse();
            executor.runNext();
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.get().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertThat(error.getCause()).isInstanceOf(RateLimitlyException.class).hasMessageContaining("closed");
            var next = executor.submit(() -> "still usable");
            executor.runNext();
            assertThat(next.get(2, TimeUnit.SECONDS)).isEqualTo("still usable");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void explicitDestroyOptOutKeepsExternallyOwnedClientOpen() throws Exception {
        AtomicReference<RateLimitlyClient> supplied = new AtomicReference<>();
        runner.withUserConfiguration(ExternalOwner.class).run(context -> {
            assertThat(context).hasNotFailed();
            supplied.set(context.getBean(RateLimitlyClient.class));
        });
        try {
            assertThat(supplied.get().checkRateLimit(EMPTY).success()).isTrue();
        } finally {
            supplied.get().close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExternalOwner {
        @Bean(destroyMethod = "") RateLimitlyClient externallyOwnedClient() throws RateLimitlyException {
            return com.ratelimitly.RateLimitlyClients.create(RateLimitlyClientConfig.builder(API_KEY).build());
        }
    }

    private static class QueuedExecutor extends ThreadPoolExecutor {
        private final LinkedBlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        QueuedExecutor() { super(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) { pending.add(command); }
        void runNext() { super.execute(pending.remove()); }
    }
}
