package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekClientTimeoutTest {
    @Test void headersWithoutCompleteBodyCannotHang() throws Exception {
        assertStalledExchangeTimesOut(true);
    }

    @Test void missingHeadersCannotHang() throws Exception {
        assertStalledExchangeTimesOut(false);
    }

    private void assertStalledExchangeTimesOut(boolean sendHeaders) throws Exception {
        var handlers = Executors.newCachedThreadPool();
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 10000);
                    exchange.getResponseBody().write(" ".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var config = new AgenticConfig();
            config.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            config.apiKey = "local-test-only";
            config.timeoutSeconds = 1;
            var client = new DeepSeekClient(config, new LlmUsageTracker(100));
            // Five 1-second requests plus four exponential backoffs with jitter
            // take up to 27.5 seconds; leave headroom for scheduling and cleanup.
            assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
                var error = assertThrows(LlmTransportException.class,
                        () -> client.complete("traveler", "test", "test"));
                assertTrue(error.getMessage().contains(
                        "after " + DeepSeekClient.MAX_TRANSPORT_ATTEMPTS + " attempts"));
            });
            assertEquals(DeepSeekClient.MAX_TRANSPORT_ATTEMPTS, calls.get());
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
