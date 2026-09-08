package org.matsim.agentic.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LlmManagerProviderTest {
    @Test void localHttpContractAndSafeError() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<String>(); var auth=new AtomicReference<String>();
        var mapper=new ObjectMapper();
        server.createContext("/chat/completions",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String decision="{\"levels\":{\"tolled\":[0,1]},\"reason\":\"bounded proposal\"}";
            var response=mapper.createObjectNode(); response.putArray("choices").addObject()
                    .putObject("message").put("content",decision);
            byte[] bytes=mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider=new LlmManagerProvider("dummy-local-test-key","http://127.0.0.1:"+server.getAddress().getPort(),"test-model");
            var s=ManagerTest.settings("shadow","llm");
            var observation=new ManagerDecisionProvider.Observation(0,0,1,0,20,1,10,0,List.of());
            assertEquals(List.of(0,1),provider.decide(s,TollPolicy.zero(s),observation,"{\"network_links\":3}",List.of())
                    .levels().get("tolled"));
            assertEquals("Bearer dummy-local-test-key",auth.get());
            var request=mapper.readTree(body.get());
            assertEquals("test-model",request.path("model").asText());
            assertTrue(request.at("/messages/0/content").asText().contains("K1."));
            var input=mapper.readTree(request.at("/messages/1/content").asText());
            assertEquals(3,input.at("/scene/network_links").asInt());
            assertEquals("shadow",input.at("/permissions/mode").asText());
            assertFalse(body.get().contains("dummy-local-test-key"));
            server.removeContext("/chat/completions");
            server.createContext("/chat/completions",exchange->{
                byte[] bytes="secret server response".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            var error=assertThrows(java.io.IOException.class,()->provider.decide(s,TollPolicy.zero(s),observation,"{}",List.of()));
            assertFalse(error.getMessage().contains("secret"));
        } finally {server.stop(0);}
    }
}
