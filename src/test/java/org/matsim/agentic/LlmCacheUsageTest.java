package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LlmCacheUsageTest {
    @Test void providerOmissionReportedZeroAndReportedHitsAreDistinct() throws Exception {
        var returned=new AtomicReference<Map<String,Object>>(Map.of("prompt_tokens",10,"completion_tokens",2));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body=Json.write(Map.of("choices",List.of(Map.of("message",Map.of("content","ok"))),"usage",returned.get())).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var config=new AgenticConfig(); config.apiKey="loopback-only"; config.baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            var client=new DeepSeekClient(config,new LlmUsageTracker(10));
            var missing=client.complete("traveler","sys","usr");
            assertTrue(missing.usageReported()); assertFalse(missing.promptCacheUsageReported());
            assertNull(MorningOutputContract.usage(missing).get("promptCacheHitTokens"));
            assertNull(MorningOutputContract.usage(missing).get("promptCacheMissTokens"));
            returned.set(Map.of("prompt_tokens",10,"completion_tokens",2,"prompt_tokens_details",Map.of("cached_tokens",0)));
            var zero=client.complete("traveler_memory","sys","usr");
            assertTrue(zero.promptCacheUsageReported()); assertEquals(0,zero.promptCacheHitTokens()); assertEquals(10,zero.promptCacheMissTokens());
            returned.set(Map.of("prompt_tokens",10,"completion_tokens",2,"prompt_tokens_details",Map.of("cached_tokens",7)));
            var hit=client.complete("manager","sys","usr");
            assertTrue(hit.promptCacheUsageReported()); assertEquals(7,hit.promptCacheHitTokens()); assertEquals(3,hit.promptCacheMissTokens());
            returned.set(Map.of("prompt_tokens",10,"completion_tokens",2,"prompt_cache_hit_tokens",4,"prompt_cache_miss_tokens",6));
            var vendor=client.complete("manager","sys","usr");
            assertEquals(4,vendor.promptCacheHitTokens()); assertEquals(6,vendor.promptCacheMissTokens());
            var metadata=new LinkedHashMap<String,Object>(); MorningOutputContract.responseMetadata(metadata,hit);
            assertEquals(true,metadata.get("promptCacheUsageReported"));
        } finally { server.stop(0); }
    }

    @Test void missingAllUsageRemainsAbsentRatherThanFabricated() {
        var missing=new LlmResponse("ok",0,0,0,0,false);
        assertFalse(missing.promptCacheUsageReported()); assertNull(MorningOutputContract.usage(missing));
    }
}
