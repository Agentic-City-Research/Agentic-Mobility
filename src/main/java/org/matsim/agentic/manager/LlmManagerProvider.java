package org.matsim.agentic.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** One manager, no traveler opinions; observational history is not causal evidence. */
public final class LlmManagerProvider implements ManagerDecisionProvider {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String key,base,model;
    public LlmManagerProvider() {
        this(env("MANAGER_API_KEY",env("AGENTIC_API_KEY","")),
                env("MANAGER_BASE_URL",env("AGENTIC_BASE_URL","https://api.deepseek.com")),
                env("MANAGER_MODEL",env("AGENTIC_MODEL","deepseek-chat")));
    }
    LlmManagerProvider(String key,String base,String model) {
        this.key=key; this.base=base; this.model=model;
        if(key.isBlank()) throw new IllegalArgumentException("LLM manager requires MANAGER_API_KEY or AGENTIC_API_KEY");
    }
    public String model() { return model; }
    private static String env(String name,String fallback) {
        String value=System.getenv(name); return value==null || value.isBlank()?fallback:value.trim();
    }
    public static String resource(String name) throws java.io.IOException {
        try(var in=LlmManagerProvider.class.getResourceAsStream(name)) {
            if(in==null) throw new java.io.IOException("Missing resource "+name);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Override public Decision decide(ManagerSettings s,TollPolicy current,Observation observation,
                                    String scene,List<Object> memory) throws Exception {
        var input=new LinkedHashMap<String,Object>();
        input.put("permissions",s); input.put("current_policy",current);
        input.put("observation",observation); input.put("scene",json.readTree(scene)); input.put("history",memory);
        var body=json.createObjectNode(); body.put("model",model); body.put("temperature",0);
        body.put("max_tokens",4096); body.putObject("response_format").put("type","json_object");
        var messages=body.putArray("messages");
        messages.addObject().put("role","system").put("content",resource("/prompts/manager-pricing-v1.txt")
                +"\n"+resource("/knowledge/congestion-pricing-v1.md"));
        messages.addObject().put("role","user").put("content",json.writeValueAsString(input));
        var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/+$","")+"/chat/completions"))
                .timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        // Do not copy arbitrary remote error bodies to logs (they can contain credentials).
        if(response.statusCode()!=200) throw new java.io.IOException("Manager HTTP "+response.statusCode());
        return parse(json.readTree(response.body()).at("/choices/0/message/content").asText());
    }
    public static Decision parse(String content) throws java.io.IOException {
        var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var root=mapper.readTree(content);
        if(root==null || !root.path("levels").isObject() || !root.path("reason").isTextual())
            throw new IllegalArgumentException("Expected levels object and reason string");
        var levels=new LinkedHashMap<String,List<Integer>>();
        var fields=root.path("levels").fields();
        while(fields.hasNext()) {
            var entry=fields.next();
            if(!entry.getValue().isArray()) throw new IllegalArgumentException("Expected bin array");
            var values=new ArrayList<Integer>();
            for(var node:entry.getValue()) {
                if(!node.isIntegralNumber() || !node.canConvertToInt())
                    throw new IllegalArgumentException("Levels must be integers");
                values.add(node.intValue());
            }
            levels.put(entry.getKey(),values);
        }
        String reason=root.path("reason").asText();
        if(reason.length()>4000) throw new IllegalArgumentException("Manager reason too long");
        return new Decision(levels,reason);
    }
}
