package org.matsim.agentic;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PythonTravelerBridgeTest {
    @Test void pythonReflectionUsesOnlyTodayAndSingleDayInstructions() {
        var today = new MorningMemory.Experience(4,"c0",100,25300.0,0.0);
        var context = new MorningMemoryUpdater.Context(4,"p/0","p","work",32400d,Map.of(),null,
                "Prior memory",3,List.of(today),List.of("c0"));
        var input=PythonTravelerBridge.reflectionInput(context,500);
        assertTrue(input.get("system").toString().contains("last day"));
        assertTrue(input.get("system").toString().contains("1-day log"));
        assertTrue(input.get("prompt").toString().contains("simulation days 4 through 4"));
        var bad = new MorningMemoryUpdater.Context(4,"p/0","p","work",32400d,Map.of(),null,
                "Prior memory",3,List.of(new MorningMemory.Experience(3,"c0",100,25300.0,0.0),today),List.of("c0"));
        assertThrows(IllegalArgumentException.class,() -> PythonTravelerBridge.reflectionInput(bad,500));
    }
    @Test void rejectsStaleAndTamperedActionBeforeApplication() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var mode=new java.util.concurrent.atomic.AtomicReference<>("stale");
        server.createContext("/v1/decisions", exchange -> {
            var req=Json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).path("requests").get(0);
            if(mode.get().equals("backend_error")) {
                byte[] error=Json.write(Map.of("error_code","CONCORDIA_TEXT_EMPTY_OR_TRUNCATED","private_detail","must not appear")).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(502,error.length);
                try(var out=exchange.getResponseBody()) { out.write(error); }
                return;
            }
            var visible=req.path("observations").get(0).path("content");
            assertFalse(visible.path("candidates").get(0).has("chosenCount"));
            assertFalse(visible.path("candidates").get(0).has("experiencedCount"));
            assertFalse(visible.path("personAttributes").has("private_marker"));
            var row=Json.MAPPER.createObjectNode();
            for(String key:List.of("run_id","agent_id","trip_id","simulation_day","phase","request_id","observation_version","action_space_version")) row.set(key,req.get(key));
            if(mode.get().equals("stale")) row.put("action_space_version","stale");
            var action=req.path("actions").get(0).deepCopy();
            if(mode.get().equals("tampered")) ((com.fasterxml.jackson.databind.node.ObjectNode)action).put("route_id","other");
            row.set("action",action);
            byte[] body=Json.write(Map.of("responses",List.of(row))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var option=new MorningRoutePolicy.Option("c0",List.of("road"),900,26100,1000,0,"static_free_flow",3,7);
            var context=new MorningRoutePolicy.Context(0,"p/0","p","home","work",25200,32400d,Map.of("private_marker","not visible"),Map.of(),List.of(option),List.of());
            var bridge=new PythonTravelerBridge("http://127.0.0.1:"+server.getAddress().getPort(),5,120);
            assertThrows(IllegalStateException.class,() -> bridge.decide(List.of(context)));
            mode.set("tampered");
            assertThrows(IllegalStateException.class,() -> bridge.decide(List.of(context)));
            mode.set("backend_error");
            var error=assertThrows(IllegalStateException.class,() -> bridge.decide(List.of(context)));
            assertTrue(error.getMessage().contains("CONCORDIA_TEXT_EMPTY_OR_TRUNCATED"));
            assertFalse(error.getMessage().contains("must not appear"));
            mode.set("valid");
            assertEquals("c0",bridge.decide(List.of(context)).getFirst().routeId());
        } finally { server.stop(0); }
    }
}
