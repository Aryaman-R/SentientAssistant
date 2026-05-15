package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentient.util.ProfileManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to a locally-installed OpenClaw gateway via its OpenAI-compatible HTTP API.
 *
 * The gateway lives at http://127.0.0.1:18789 by default. We POST to /v1/chat/completions
 * with the same payload shape as Groq/OpenAI, including the command-emitting system prompt
 * the rest of the app already understands.
 *
 * Keeps its own conversation history, separate from GroqService — that way the user can
 * toggle engines without one stomping the other.
 */
public class OpenClawService {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:18789";
    private static final String DEFAULT_MODEL = "openclaw/default";
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int TIMEOUT_SECONDS = 60;

    private final HttpClient client;
    private final Gson gson = new Gson();
    private final List<JsonObject> conversationHistory = new ArrayList<>();

    private volatile String baseUrl = DEFAULT_BASE_URL;
    private volatile String defaultModel = DEFAULT_MODEL;
    private volatile String authToken = ""; // empty = no Authorization header sent

    public OpenClawService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Configuration mutators (called by WebServer when settings change) ──

    public void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) this.baseUrl = url.replaceAll("/+$", "");
    }

    public void setDefaultModel(String model) {
        if (model != null && !model.isBlank()) this.defaultModel = model;
    }

    public void setAuthToken(String token) {
        this.authToken = token == null ? "" : token.trim();
    }

    public String getBaseUrl() { return baseUrl; }
    public String getDefaultModel() { return defaultModel; }

    public void clearHistory() { conversationHistory.clear(); }

    // ── Health check ────────────────────────────────────

    /** Quick check — does the gateway respond at all? */
    public boolean isGatewayUp() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> r = client.send(req, HttpResponse.BodyHandlers.discarding());
            int code = r.statusCode();
            // Any 2xx/4xx response from the right port = it's up. Only network errors mean down.
            return code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Chat ─────────────────────────────────────────────

    public CompletableFuture<String> processCommand(String userPrompt, String modelOverride,
                                                    String imageBase64, String fileName, String fileType) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = inlineAttachmentsIfTextual(userPrompt, imageBase64, fileName, fileType);

            String systemPrompt = buildChatSystemPrompt();
            String model = (modelOverride != null && !modelOverride.isBlank() && !"AUTO".equalsIgnoreCase(modelOverride))
                    ? modelOverride
                    : defaultModel;

            String response = callGateway(model, prompt, imageBase64, fileType, systemPrompt);

            // Record turn in history only if it wasn't an error reply
            if (!response.startsWith("Error:") && !response.startsWith("Sorry,")) {
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", prompt);
                conversationHistory.add(userMsg);

                JsonObject assistantMsg = new JsonObject();
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", response);
                conversationHistory.add(assistantMsg);

                while (conversationHistory.size() > MAX_HISTORY_TURNS * 2) {
                    conversationHistory.remove(0);
                    conversationHistory.remove(0);
                }
            }

            return response;
        });
    }

    /** Simple test ping — used by the Settings "TEST CHAT" button. */
    public String testChat() {
        String systemPrompt = "You are a connectivity test. Reply with exactly one short sentence confirming you are alive.";
        return callGateway(defaultModel, "Say hello.", null, null, systemPrompt);
    }

    // ── Implementation ──────────────────────────────────

    private String inlineAttachmentsIfTextual(String prompt, String imageBase64, String fileName, String fileType) {
        if (imageBase64 == null || fileType == null) return prompt;
        try {
            String cleanBase64 = imageBase64.contains(",") ? imageBase64.split(",")[1] : imageBase64;
            if (fileType.contains("pdf")) {
                byte[] pdfBytes = java.util.Base64.getDecoder().decode(cleanBase64);
                try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                    return prompt + "\n\n[Attached PDF: " + fileName + "]\n" + text;
                }
            }
            if (fileType.contains("text")) {
                byte[] bytes = java.util.Base64.getDecoder().decode(cleanBase64);
                return prompt + "\n\n[Attached text: " + fileName + "]\n"
                        + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[OpenClaw] Attachment parse failed: " + e.getMessage());
        }
        return prompt;
    }

    private String callGateway(String model, String prompt, String imageBase64, String fileType, String systemPrompt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("temperature", 0.5);
        payload.addProperty("max_tokens", 2048);

        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        for (JsonObject m : conversationHistory) messages.add(m);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        boolean isImage = imageBase64 != null && fileType != null && fileType.startsWith("image/");
        if (isImage) {
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", prompt);
            content.add(text);

            JsonObject img = new JsonObject();
            img.addProperty("type", "image_url");
            JsonObject url = new JsonObject();
            url.addProperty("url", imageBase64);
            img.add("image_url", url);
            content.add(img);

            user.add("content", content);
        } else {
            user.addProperty("content", prompt);
        }
        messages.add(user);

        payload.add("messages", messages);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)));

        if (!authToken.isEmpty()) {
            rb.header("Authorization", "Bearer " + authToken);
        }

        try {
            HttpResponse<String> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            String body = r.body();
            System.out.println("[OpenClaw] HTTP " + code + " | bytes=" + (body == null ? 0 : body.length()));

            if (code == 401 || code == 403) {
                return "Sorry, OpenClaw gateway rejected the request. Check the gateway auth token in Settings.";
            }
            if (code >= 400) {
                System.err.println("[OpenClaw] Error body: " + body.substring(0, Math.min(500, body.length())));
                return "Sorry, OpenClaw returned an error (HTTP " + code + "). Check your provider config.";
            }

            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (java.net.ConnectException ce) {
            return "Error: OpenClaw gateway unreachable at " + baseUrl
                    + ". Is it running? Try 'openclaw gateway start'.";
        } catch (Exception e) {
            System.err.println("[OpenClaw] Call failed: " + e.getMessage());
            return "Error: OpenClaw call failed (" + e.getClass().getSimpleName() + ").";
        }
    }

    /**
     * Same command-emitting system prompt the Groq CHAT path uses, so client-side and
     * server-side command handling work identically across engines.
     */
    private String buildChatSystemPrompt() {
        ProfileManager.UserProfile p = ProfileManager.getInstance().getUserProfile();
        String taskSummary = ProfileManager.getInstance().getAllTasks().stream()
                .map(t -> t.title + (t.dueDate.isEmpty() ? "" : " (due: " + t.dueDate + ")"))
                .reduce((a, b) -> a + ", " + b).orElse("none");

        return "You are a warm, friendly personal assistant for " + p.username + ". " +
                "You genuinely care about the user and enjoy chatting with them. " +
                "Give thoughtful, fleshed-out responses — not just one-liners. " +
                "Use a natural speaking tone since your responses will be read aloud by TTS." +
                "\n\nUser habits: " + String.join(", ", p.habits) + ". " +
                "User preferences/likes: " + String.join(", ", p.preferences) + ". " +
                "User dislikes: " + String.join(", ", p.dislikes) + ". " +
                "User goals: " + String.join(", ", p.goals) + ". " +
                "\n\nYou can embed command tags in your response when the user's intent matches. " +
                "Always include a natural language response alongside any commands.\n" +
                "Available commands:\n" +
                "- [CMD:SWITCH_STUDY] / [CMD:SWITCH_HOME] / [CMD:SWITCH_SLEEP] / [CMD:SWITCH_TASKS] / [CMD:SWITCH_CALENDAR] / [CMD:SWITCH_SPOTIFY] — switch screens\n" +
                "- [CMD:SET_TIMER:N] / [CMD:START_TIMER] / [CMD:PAUSE_TIMER] / [CMD:CANCEL_TIMER] — timer controls\n" +
                "- [CMD:ADD_TASK:title|description|YYYY-MM-DD] / [CMD:REMOVE_TASK:title] — tasks\n" +
                "- [CMD:ADD_COMMITMENT:text] / [CMD:REMOVE_COMMITMENT:text] — commitments\n" +
                "- [CMD:ADD_EVENT:title|description|start|end] — calendar events\n" +
                "- [CMD:CREATE_PLAYLIST:name] — Spotify playlist\n" +
                "- [CMD:AUTOMATE:name] — fire a configured webhook\n" +
                "- [CMD:SET_ALARM:HH:mm] / [CMD:DELETE_ALARM:HH:mm] — alarms\n" +
                "- [CMD:CONTINUE_CONVERSATION] — include when the user likely wants to keep talking\n" +
                "\nCurrent tasks: " + taskSummary + ".\n" +
                "Current commitments: " + String.join(", ", p.commitments) + ".\n" +
                "Only use commands when the user's intent clearly matches.";
    }
}
