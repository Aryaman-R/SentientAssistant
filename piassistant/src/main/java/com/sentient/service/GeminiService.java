package com.sentient.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sentient.util.ProfileManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
//import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import com.sentient.util.EnvLoader;

public class GeminiService {

    private static final String API_KEY = EnvLoader.get("GEMINI_API_KEY", "");
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key="
            + API_KEY;
    private final HttpClient httpClient;
    private final Gson gson;

    public GeminiService() {
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public CompletableFuture<String> sendText(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String taskSummary = ProfileManager.getInstance().getAllTasks().stream()
                        .map(t -> t.title + (t.dueDate.isEmpty() ? "" : " (due: " + t.dueDate + ")"))
                        .reduce((a, b) -> a + ", " + b).orElse("none");

                String systemInstruction = "You are a helpful personal assistant for " +
                        ProfileManager.getInstance().getUserProfile().username + ". " +
                        "Be concise, clear, and friendly. Act like its a conversation but be direct with your responses. Instigate follow up questions if appropiate."
                        +
                        "User habits: " + String.join(", ", ProfileManager.getInstance().getUserProfile().habits) + ". "
                        +
                        "User preferences: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().preferences) + ". " +
                        "\n\nYou can embed command tags in your response when the user's intent matches. " +
                        "Always include a natural language response alongside any commands.\n" +
                        "Available commands:\n" +
                        "- [CMD:SWITCH_STUDY] — Switch to Study screen\n" +
                        "- [CMD:SWITCH_HOME] — Switch to Home screen\n" +
                        "- [CMD:SWITCH_SLEEP] — Switch to Sleep screen\n" +
                        "- [CMD:SWITCH_TASKS] — Switch to Tasks screen\n" +
                        "- [CMD:SET_TIMER:N] — Set focus timer to N minutes\n" +
                        "- [CMD:START_TIMER] / [CMD:PAUSE_TIMER] / [CMD:CANCEL_TIMER] — Timer controls\n" +
                        "- [CMD:SET_USERNAME:name] — Change display name\n" +
                        "- [CMD:ADD_HABIT:habit] / [CMD:REMOVE_HABIT:habit] — Manage habits\n" +
                        "- [CMD:ADD_PREFERENCE:pref] / [CMD:REMOVE_PREFERENCE:pref] — Manage preferences\n" +
                        "- [CMD:SET_RESTRICTION:ON] or [CMD:SET_RESTRICTION:OFF] — Toggle restriction mode\n" +
                        "— Tasks (structured with title, description, date):\n" +
                        "- [CMD:ADD_TASK:title|description|YYYY-MM-DD] — Add a task (e.g. [CMD:ADD_TASK:Finish homework|Complete chapter 5 exercises|2026-02-25])\n"
                        +
                        "- [CMD:REMOVE_TASK:title] — Remove/complete a task (e.g. [CMD:REMOVE_TASK:Finish homework])\n"
                        +
                        "— Commitments:\n" +
                        "- [CMD:ADD_COMMITMENT:description] — Add a commitment (e.g. [CMD:ADD_COMMITMENT:Gym at 6pm])\n"
                        +
                        "- [CMD:REMOVE_COMMITMENT:description] — Remove a commitment\n" +
                        "— Alarms:\n" +
                        "- [CMD:SET_ALARM:HH:mm] — Set an alarm (e.g. [CMD:SET_ALARM:07:30])\n" +
                        "- [CMD:DELETE_ALARM:HH:mm] — Delete an alarm\n" +
                        "— Conversation:\n" +
                        "- [CMD:CONTINUE_CONVERSATION] — Include this when you believe the user wants to keep talking "
                        +
                        "(e.g. you asked a follow-up question, or the conversation naturally continues). " +
                        "This will automatically turn on the microphone to capture the user's next voice input.\n" +
                        "\nCurrent user tasks: " + taskSummary + ".\n" +
                        "Current user commitments: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().commitments) + ".\n" +
                        "Current user alarms: "
                        + String.join(", ", ProfileManager.getInstance().getUserProfile().alarms) + ".\n" +
                        "\nExamples:\n" +
                        "User: 'Add a task to finish homework by Friday' → text + [CMD:ADD_TASK:Finish homework|Complete the assigned work|2026-02-28]\n"
                        +
                        "User: 'What tasks do I have?' → text listing tasks + [CMD:SWITCH_TASKS] [CMD:CONTINUE_CONVERSATION]\n"
                        +
                        "User: 'Start a 45 minute study session' → text + [CMD:SWITCH_STUDY] [CMD:SET_TIMER:45] [CMD:START_TIMER]\n"
                        +
                        "User: 'How are you doing?' → friendly text + [CMD:CONTINUE_CONVERSATION]\n" +
                        "Only use commands when the user's intent clearly matches. For normal conversation, include CONTINUE_CONVERSATION if you think the user wants to keep chatting.";

                JsonObject root = new JsonObject();
                JsonArray contents = new JsonArray();
                JsonObject contentPart = new JsonObject();
                JsonArray parts = new JsonArray();
                JsonObject textPart = new JsonObject();

                // Combine system instruction and prompt
                textPart.addProperty("text", systemInstruction + "\n\nUser: " + prompt);
                parts.add(textPart);
                contentPart.add("parts", parts);
                contents.add(contentPart);
                root.add("contents", contents);

                // Enable Google Search grounding so Gemini can access the web
                JsonArray tools = new JsonArray();
                JsonObject searchTool = new JsonObject();
                searchTool.add("google_search", new JsonObject());
                tools.add(searchTool);
                root.add("tools", tools);

                String requestBody = gson.toJson(root);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GEMINI_API_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String responseText = extractTextFromResponse(response.body());
                return responseText;

            } catch (Exception e) {
                e.printStackTrace();
                return "Error: " + e.getMessage();
            }
        });
    }

    // TODO: Implement sendImage(byte[] imageBytes, String prompt)

    private String extractTextFromResponse(String jsonResponse) {
        try {
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);
            return root.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        } catch (Exception e) {
            return "Error parsing response: " + jsonResponse; // Simple error fallback
        }
    }
}
