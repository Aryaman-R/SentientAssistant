package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Reads and writes OpenClaw's config file at ~/.config/openclaw/openclaw.json5.
 *
 * OpenClaw uses JSON5 (with comments and trailing commas). We accept either flavour
 * on read by stripping line/block comments and trailing commas before parsing as JSON.
 * On write, we emit strict JSON — still a valid JSON5 file. We keep a .bak of the
 * previous contents before every write so the user can recover if needed.
 *
 * Also handles invoking the `openclaw` CLI: checking installation, restarting the
 * gateway, etc. All shell commands are passed as argv arrays — no shell interpolation
 * of user-supplied strings.
 */
public class OpenClawConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR  = Paths.get(System.getProperty("user.home"), ".config", "openclaw");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("openclaw.json5");
    private static final Path CONFIG_PATH_FALLBACK = Paths.get(System.getProperty("user.home"), ".openclaw", "config.json5");

    // ── File location ────────────────────────────────────

    public Path configPath() {
        if (Files.exists(CONFIG_PATH)) return CONFIG_PATH;
        if (Files.exists(CONFIG_PATH_FALLBACK)) return CONFIG_PATH_FALLBACK;
        return CONFIG_PATH; // default for write
    }

    // ── Read / write ────────────────────────────────────

    public JsonObject loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return new JsonObject();
        try {
            String raw = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            String stripped = stripJson5ToJson(raw);
            JsonElement el = JsonParser.parseString(stripped);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            System.err.println("[OpenClawConfig] Failed to read " + p + ": " + e.getMessage());
            return new JsonObject();
        }
    }

    public void saveConfig(JsonObject cfg) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        Path p = configPath();
        if (Files.exists(p)) {
            Files.copy(p, p.resolveSibling(p.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(p, GSON.toJson(cfg) + "\n");
    }

    // ── Provider config writes ──────────────────────────

    /**
     * Set the active provider and model. For built-in providers we just stamp the env
     * var (apiKey -> env mapping) — OpenClaw discovers built-ins automatically. For
     * 'custom' we write a full models.providers.<id> entry.
     */
    public JsonObject applyProvider(String provider, String apiKey, String model,
                                    String customBaseUrl, String customApiType,
                                    String gatewayToken) throws IOException {
        JsonObject cfg = loadConfig();

        // Persist the API key. The cleanest approach is via a `secrets` block that OpenClaw
        // surfaces as env vars. If the user prefers env vars in their shell, they can clear
        // this — but for "configure from UI", we put it in the config.
        JsonObject secrets = getOrCreateObject(cfg, "secrets");
        String envVar = providerEnvVar(provider);
        if (envVar != null && apiKey != null && !apiKey.isBlank()) {
            secrets.addProperty(envVar, apiKey);
        }

        // Set the default model in gateway section
        JsonObject gateway = getOrCreateObject(cfg, "gateway");
        if (model != null && !model.isBlank()) {
            String fq = qualifyModel(provider, model);
            gateway.addProperty("defaultModel", fq);
        }
        if (gatewayToken != null && !gatewayToken.isBlank()) {
            JsonObject auth = getOrCreateObject(gateway, "auth");
            auth.addProperty("token", gatewayToken);
        }

        if ("custom".equalsIgnoreCase(provider)) {
            // Write a full provider block under models.providers.custom-proxy
            JsonObject models = getOrCreateObject(cfg, "models");
            JsonObject providers = getOrCreateObject(models, "providers");
            JsonObject custom = getOrCreateObject(providers, "custom");
            if (customBaseUrl != null && !customBaseUrl.isBlank()) custom.addProperty("baseUrl", customBaseUrl);
            if (apiKey != null && !apiKey.isBlank()) custom.addProperty("apiKey", apiKey);
            if (customApiType != null && !customApiType.isBlank()) custom.addProperty("api", customApiType);
            // private network access for non-localhost endpoints
            if (customBaseUrl != null && !customBaseUrl.contains("localhost") && !customBaseUrl.contains("127.0.0.1")) {
                JsonObject request = getOrCreateObject(custom, "request");
                request.addProperty("allowPrivateNetwork", true);
            }
            if (model != null && !model.isBlank()) {
                JsonArray modelList = new JsonArray();
                JsonObject m = new JsonObject();
                m.addProperty("id", model);
                m.addProperty("name", model);
                modelList.add(m);
                custom.add("models", modelList);
            }
        }

        saveConfig(cfg);
        return cfg;
    }

    /**
     * Apply or remove a Composio MCP server entry plus an explicit list of enabled toolkits.
     * If consumerKey is empty, removes the composio block entirely.
     */
    public JsonObject applyComposio(String consumerKey, List<String> enabledToolkits) throws IOException {
        JsonObject cfg = loadConfig();
        JsonObject mcp = getOrCreateObject(cfg, "mcp");
        JsonObject servers = getOrCreateObject(mcp, "servers");

        if (consumerKey == null || consumerKey.isBlank()) {
            if (servers.has("composio")) servers.remove("composio");
        } else {
            JsonObject composio = new JsonObject();
            composio.addProperty("transport", "http");
            composio.addProperty("baseUrl", "https://connect.composio.dev/mcp");
            JsonObject headers = new JsonObject();
            headers.addProperty("x-consumer-api-key", consumerKey);
            composio.add("headers", headers);
            if (enabledToolkits != null && !enabledToolkits.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (String t : enabledToolkits) arr.add(t);
                composio.add("toolkits", arr);
            }
            servers.add("composio", composio);

            // Also persist the consumer key as a secret for the plugin-style integration
            JsonObject secrets = getOrCreateObject(cfg, "secrets");
            secrets.addProperty("COMPOSIO_API_KEY", consumerKey);
        }
        saveConfig(cfg);
        return cfg;
    }

    // ── Installation / process management ──────────────

    /** Is the `openclaw` binary on PATH? */
    public boolean isInstalled() {
        return findOpenClawBinary() != null;
    }

    /** Best-effort: returns the path to the `openclaw` binary, or null. */
    public String findOpenClawBinary() {
        String home = System.getProperty("user.home");
        String[] candidates = new String[] {
            "/usr/local/bin/openclaw",
            "/opt/homebrew/bin/openclaw",
            home + "/.openclaw/bin/openclaw",
            home + "/.local/bin/openclaw",
            "/usr/bin/openclaw",
        };
        for (String c : candidates) if (Files.isExecutable(Paths.get(c))) return c;
        try {
            Process p = new ProcessBuilder("which", "openclaw").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !out.isEmpty() && Files.isExecutable(Paths.get(out))) return out;
        } catch (Exception ignored) {}
        return null;
    }

    /** Run `openclaw gateway restart`. Returns stdout/stderr or an error message. */
    public String restartGateway() {
        String bin = findOpenClawBinary();
        if (bin == null) return "openclaw binary not found on PATH";
        try {
            Process p = new ProcessBuilder(bin, "gateway", "restart").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int code = p.waitFor();
            return (code == 0 ? "OK: " : "ERR(" + code + "): ") + out.trim();
        } catch (Exception e) {
            return "Failed to invoke openclaw: " + e.getMessage();
        }
    }

    // ── Helpers ─────────────────────────────────────────

    private static JsonObject getOrCreateObject(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonObject()) return parent.getAsJsonObject(key);
        JsonObject n = new JsonObject();
        parent.add(key, n);
        return n;
    }

    /** Map an OpenClaw provider id to the env var name OpenClaw expects for its API key. */
    private static String providerEnvVar(String provider) {
        if (provider == null) return null;
        switch (provider.toLowerCase()) {
            case "anthropic":   return "ANTHROPIC_API_KEY";
            case "openai":      return "OPENAI_API_KEY";
            case "google":      return "GEMINI_API_KEY";
            case "groq":        return "GROQ_API_KEY";
            case "openrouter":  return "OPENROUTER_API_KEY";
            case "xai":         return "XAI_API_KEY";
            case "deepseek":    return "DEEPSEEK_API_KEY";
            case "moonshot":    return "MOONSHOT_API_KEY";
            case "custom":      return "CUSTOM_API_KEY";
            default: return null;
        }
    }

    /**
     * If model already has a "provider/" prefix, leave it. Otherwise prepend the chosen provider.
     * OpenClaw expects fully-qualified model ids like "anthropic/claude-sonnet-4-5".
     */
    private static String qualifyModel(String provider, String model) {
        if (model.contains("/")) return model;
        if (provider == null || provider.isBlank()) return model;
        return provider + "/" + model;
    }

    /**
     * Strip JSON5 features (line comments, block comments, trailing commas) so the result
     * is parseable by Gson. Naive but good enough for OpenClaw's hand-edited configs.
     * Does NOT support unquoted keys — if the user has those, we recommend they re-save
     * from this UI to normalize.
     */
    static String stripJson5ToJson(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        boolean inString = false;
        char stringQuote = '"';
        while (i < n) {
            char c = src.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == stringQuote) inString = false;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                out.append(c == '\'' ? '"' : c); // normalize single-quoted strings
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char nc = src.charAt(i + 1);
                if (nc == '/') {
                    while (i < n && src.charAt(i) != '\n') i++;
                    continue;
                }
                if (nc == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        // Strip trailing commas: ",}" or ",]"
        String s = out.toString().replaceAll(",\\s*([}\\]])", "$1");
        return s;
    }
}
