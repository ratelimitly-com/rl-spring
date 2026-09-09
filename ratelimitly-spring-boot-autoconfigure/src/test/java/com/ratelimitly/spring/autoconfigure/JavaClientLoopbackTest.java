package com.ratelimitly.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.ratelimitly.CanonicalIds;
import com.ratelimitly.DnsResolver;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.ResolvedServer;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.ServiceLatencyReport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JavaClientLoopbackTest {
    @Test
    void springConfiguredRealClientExchangesResourcesGuardsAndIndependentReports() throws Exception {
        // Isolated NONE-auth fixture. No DNS traffic or live RateLimitly service is involved.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket responder = new DatagramSocket(0, loopback)) {
            responder.setSoTimeout(10_000);
            AtomicReference<Throwable> peerFailure = new AtomicReference<>();
            CountDownLatch reportReceived = new CountDownLatch(1);
            Thread peer = Thread.ofPlatform().start(() -> {
                try {
                    while (reportReceived.getCount() != 0) {
                        DatagramPacket packet = new DatagramPacket(new byte[1200], 1200);
                        responder.receive(packet);
                        byte[] bytes = Arrays.copyOf(packet.getData(), packet.getLength());
                        ByteBuffer wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                        assertEquals(0x414e, Short.toUnsignedInt(wire.getShort(40))); // NONE auth
                        int pdu = Short.toUnsignedInt(wire.getShort(44));
                        if (pdu == 0x524c) { // Latency report, no response.
                            assertEquals(88, bytes.length); // 40 + 4 + 8 + 4 + 32
                            assertThat(Arrays.copyOfRange(bytes, 56, 72)).isEqualTo(
                                CanonicalIds.latencyTrackerId("inventory", 10_000, 10, 5));
                            assertEquals(18, wire.getInt(84));
                            reportReceived.countDown();
                            continue;
                        }
                        assertEquals(0x5452, pdu); // Resource request
                        assertEquals(125, wire.getInt(48)); // Policy-derived deduplication TTL
                        int guards = Short.toUnsignedInt(wire.getShort(52));
                        int resources = Short.toUnsignedInt(wire.getShort(54));
                        assertEquals(56 + guards * 36 + resources * 28, bytes.length);
                        if (guards == 1) {
                            assertThat(Arrays.copyOfRange(bytes, 56, 72)).isEqualTo(
                                CanonicalIds.latencyTrackerId("inventory", 10_000, 10, 5));
                            wire.putInt(88, 150); // Measured latency exceeds the 100 ms guard.
                        }
                        if (resources == 1) {
                            int resourceOffset = 56 + guards * 36;
                            assertThat(Arrays.copyOfRange(bytes, resourceOffset, resourceOffset + 16)).isEqualTo(
                                CanonicalIds.bucketId("checkout", 1000, 100));
                            wire.putShort(resourceOffset + 24, (short) 0); // No token deficit.
                        }
                        wire.putLong(4, 1); // Trusted fixture server ID
                        wire.put(36, (byte) 1); // Keep the source port
                        wire.putShort(44, (short) 0x5252); // Resource response
                        wire.putInt(48, 0);
                        responder.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                    }
                } catch (Throwable error) {
                    peerFailure.set(error);
                }
            });
            try {
                new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RateLimitlyAutoConfiguration.class))
                    .withPropertyValues("ratelimitly.enabled=true",
                        "ratelimitly.api-key=rl-none1qyyqwps9qspsyq2sk8e0sfdp3ys",
                        "ratelimitly.client.request-policy.unit=25ms",
                        "ratelimitly.client.request-policy.replay-count=3",
                        "ratelimitly.client.request-policy.final-receive-units=1")
                    .withBean(DnsResolver.class, () -> ignored -> List.of(
                        new ResolvedServer("fixture.invalid", loopback, responder.getLocalPort(), 1)))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        RateLimitlyClient client = context.getBean(RateLimitlyClient.class);
                        var resource = new ResourceRequest("checkout", 1000, 100, 1);
                        var guard = new LatencyGuard("inventory", 100, 10_000, 10, 5);
                        assertTrue(client.checkRateLimit(new RateLimitRequest(List.of(resource), List.of(), null)).success());
                        assertFalse(client.checkRateLimit(new RateLimitRequest(List.of(), List.of(guard), null)).success());
                        assertFalse(client.checkRateLimitAsync(new RateLimitRequest(List.of(resource), List.of(guard), null))
                            .toCompletableFuture().get(2, TimeUnit.SECONDS).success());
                        client.reportLatency(new LatencyReport(List.of(
                            new ServiceLatencyReport("inventory", 18, 10_000, 10, 5))));
                        assertTrue(reportReceived.await(2, TimeUnit.SECONDS), "fixture did not receive the report");
                    });
            } finally {
                responder.close();
                peer.join(3000);
            }
            assertFalse(peer.isAlive(), "fixture thread must stop");
            assertThat(peerFailure.get()).isNull();
        }
    }
}
