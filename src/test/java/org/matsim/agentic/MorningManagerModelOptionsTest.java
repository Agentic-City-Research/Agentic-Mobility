package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MorningManagerModelOptionsTest {
    @Test void absentOverridesPreserveTheExistingHpcServiceAndGenerationSettings() {
        var traveler = qwen();
        var options = new MorningManagerModelOptions(Map.of());
        var manager = options.resolve(traveler);
        assertFalse(options.hasOverrides());
        assertFalse(options.separateEndpoint(traveler));
        assertNotSame(traveler, manager);
        assertEquals(traveler.publicModelSettings(), manager.publicModelSettings());
        assertEquals(traveler.apiKey, manager.apiKey);
        assertEquals(traveler.generationSettings(), manager.withOutputLimit(1024).generationSettings());
        assertEquals(8192, manager.withOutputLimit(8192).maxOutputTokens);
        assertEquals(2048, manager.withOutputLimit(2048).maxOutputTokens);
    }

    @Test void aNewEndpointDoesNotInheritTheQwenKeyOrProviderParameters() {
        var traveler = qwen();
        var options = new MorningManagerModelOptions(Map.of(
                "AGENTIC_MANAGER_BASE_URL", "https://api.deepseek.com",
                "AGENTIC_MANAGER_MODEL", "deepseek-flash"));
        var manager = options.resolve(traveler);
        assertTrue(options.separateEndpoint(traveler));
        assertNull(manager.apiKey, "A Qwen credential must not be sent to an independent endpoint");
        assertNull(manager.temperature); assertNull(manager.topP); assertNull(manager.generationSeed);
        assertEquals("{}", manager.generationParams);
        assertEquals("json_object", manager.responseFormat);
        assertEquals(Map.of("type", "disabled"), manager.generationSettings().get("thinking"));
        assertFalse(manager.generationSettings().containsKey("chat_template_kwargs"));
        assertEquals("max_tokens", manager.tokenLimitField);
        assertEquals("qwen-fixture", traveler.model);
        assertEquals("qwen-key", traveler.apiKey);
    }

    @Test void commandLineOverridesManagerEnvironmentWithoutChangingTraveler() {
        var traveler = qwen();
        var options = new MorningManagerModelOptions(Map.of(
                "AGENTIC_MANAGER_MODEL", "environment-model",
                "AGENTIC_MANAGER_API_KEY", "manager-key",
                "AGENTIC_MANAGER_TIMEOUT_SECONDS", "10"));
        new CommandLine(options).parseArgs("--manager-base-url", "http://127.0.0.1:8009/v1",
                "--manager-model", "cli-model", "--manager-response-format", "json_object",
                "--manager-thinking", "disabled", "--manager-token-limit-field", "max_completion_tokens",
                "--manager-generation-params", "{\"extra\":true}", "--manager-temperature", "0.2",
                "--manager-top-p", "0.8", "--manager-generation-seed", "42", "--manager-timeout-seconds", "8");
        var manager = options.resolve(traveler);
        assertEquals("cli-model", manager.model); assertEquals("manager-key", manager.apiKey);
        assertEquals(8, manager.timeoutSeconds); assertEquals(.2, manager.temperature);
        assertEquals(.8, manager.topP); assertEquals(42, manager.generationSeed);
        assertTrue((Boolean) manager.generationSettings().get("extra"));
        assertEquals(8192, manager.withOutputLimit(8192).generationSettings().get("max_completion_tokens"));
        assertEquals("qwen-fixture", traveler.model); assertEquals(15, traveler.timeoutSeconds);
        String publicSettings = Json.write(manager.publicModelSettings());
        assertFalse(publicSettings.contains("manager-key")); assertFalse(publicSettings.contains("qwen-key"));
    }

    @Test void sameEndpointWithTrailingSlashRetainsTheExistingCredentialsAndOptions() {
        var traveler = qwen();
        var options = new MorningManagerModelOptions(Map.of("AGENTIC_MANAGER_BASE_URL", traveler.baseUrl + "/"));
        var manager = options.resolve(traveler);
        assertFalse(options.separateEndpoint(traveler));
        assertEquals(traveler.apiKey, manager.apiKey);
        assertEquals(traveler.generationSettings(), manager.generationSettings());
    }

    @Test void independentEndpointRequiresAnExplicitModelAndValidManagerOptions() {
        var options = new MorningManagerModelOptions(Map.of("AGENTIC_MANAGER_BASE_URL", "http://127.0.0.1:9000/v1"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> options.resolve(qwen())).getMessage().contains("--manager-model"));
        options.model = "other-model";
        options.timeoutSeconds = 0;
        assertThrows(IllegalArgumentException.class, () -> options.resolve(qwen()));
        options.timeoutSeconds = 5;
        options.generationParams = "[]";
        assertThrows(IllegalArgumentException.class, () -> options.resolve(qwen()));
        options.generationParams = "{}";
        options.baseUrl = "https://secret@example.test/v1";
        assertThrows(IllegalArgumentException.class, () -> options.resolve(qwen()));
    }

    private static AgenticConfig qwen() {
        var config = new AgenticConfig();
        config.baseUrl = "http://127.0.0.1:8002/v1"; config.model = "qwen-fixture";
        config.apiKey = "qwen-key"; config.thinking = "omit"; config.responseFormat = "json_schema";
        config.generationParams = "{\"chat_template_kwargs\":{\"enable_thinking\":false}}";
        config.temperature = .7; config.topP = .9; config.generationSeed = 17;
        config.maxOutputTokens = 1024; config.timeoutSeconds = 15;
        return config;
    }
}
