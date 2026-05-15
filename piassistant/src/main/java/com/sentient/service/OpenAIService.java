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
import java.util.concurrent.CompletableFuture;
import com.sentient.util.EnvLoader;

public class OpenAIService {

    private static final String API_KEY = EnvLoader.get("OPENAI_API_KEY", "");

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    private final HttpClient httpClient;
    private final Gson gson;

    public OpenAIService() {
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

                // Build the OpenAI Chat Completions request body
                JsonObject root = new JsonObject();
                root.addProperty("model", MODEL);

                JsonArray messages = new JsonArray();

                // System message
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", systemInstruction);
                messages.add(systemMsg);

                // User message
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", prompt);
                messages.add(userMsg);

                root.add("messages", messages);

                String requestBody = gson.toJson(root);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OPENAI_API_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return extractTextFromResponse(response.body());

            } catch (Exception e) {
                e.printStackTrace();
                return "Error: " + e.getMessage();
            }
        });
    }

    private String extractTextFromResponse(String jsonResponse) {
        try {
            JsonObject root = gson.fromJson(jsonResponse, JsonObject.class);
            return root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            return "Error parsing response: " + jsonResponse;
        }
    }
}
