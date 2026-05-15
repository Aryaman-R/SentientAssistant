package com.sentient.service;

import com.google.gson.*;
import com.sentient.util.EnvLoader;
import com.sentient.util.ProfileManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Google Tasks API integration — syncs the in-app task list with Google Tasks.
 *
 * <h2>Setup</h2>
 * <ol>
 * <li>Go to <a href="https://console.cloud.google.com/">Google Cloud
 * Console</a> and
 * create a project.</li>
 * <li>Enable the <b>Google Tasks API</b>.</li>
 * <li>Create OAuth 2.0 credentials (type: Web application).</li>
 * <li>Add {@code http://127.0.0.1:7070/api/tasks/google/callback} as a redirect
 * URI.</li>
 * <li>Copy the Client ID and Secret into your {@code .env} file:
 * 
 * <pre>
 *       GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
 *       GOOGLE_CLIENT_SECRET=your-client-secret
 * </pre>
 * 
 * </li>
 * <li>Start the assistant and open
 * {@code http://localhost:7070/api/tasks/google/auth} to authenticate.</li>
 * </ol>
 *
 * <h2>Sync behaviour</h2>
 * Sync is mirrored across <b>every</b> Google task list, matched to local task
 * lists by name (case-insensitive). On push, missing Google lists are created.
 * On pull, missing local lists are created. Tasks are de-duped by title within
 * the list — re-running sync is safe and idempotent.
 */
public class GoogleTasksService {

    // ── OAuth 2.0 config ───────────────────────────────────────────────────
    private static final String CLIENT_ID = EnvLoader.get("GOOGLE_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("GOOGLE_CLIENT_SECRET", "");
    private static final String REDIRECT_URI = "http://localhost:7070/api/tasks/google/callback";
    private static final String SCOPE = "https://www.googleapis.com/auth/tasks https://www.googleapis.com/auth/calendar";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String TASKS_API = "https://tasks.googleapis.com/tasks/v1";

    // Persist tokens between restarts
    private static final Path TOKEN_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_google_tasks_token");

    private final HttpClient client;
    private final Gson gson;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAt = 0;
    private volatile boolean authenticated = false;

    public GoogleTasksService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
        restoreSession();
    }

    // ── OAuth flow ─────────────────────────────────────────────────────────

    /** Returns true if the service has valid OAuth credentials. */
    public boolean isAuthenticated() {
        return authenticated && !CLIENT_ID.isBlank();
    }

    /**
     * Probe whether Tasks can actually reach Google. Triggers a refresh if needed.
     * Returns {authenticated, working, statusCode, error?}.
     */
    public JsonObject getAuthHealth() {
        JsonObject out = new JsonObject();
        out.addProperty("configured", isConfigured());
        if (!isAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Not authenticated. Connect Google to sync Tasks.");
            return out;
        }
        if (!ensureAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Tasks token refresh failed. Please reconnect Google.");
            return out;
        }
        try {
            HttpResponse<String> resp = apiGet(TASKS_API + "/users/@me/lists?maxResults=1");
            out.addProperty("statusCode", resp.statusCode());
            if (resp.statusCode() == 200) {
                out.addProperty("authenticated", true);
                out.addProperty("working", true);
                return out;
            }
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Google Tasks returned HTTP " + resp.statusCode() + ".");
            if (resp.statusCode() == 401) authenticated = false;
            return out;
        } catch (Exception e) {
            out.addProperty("authenticated", true);
            out.addProperty("working", false);
            out.addProperty("error", "Could not reach Google Tasks: " + e.getMessage());
            return out;
        }
    }

    /**
     * Returns true if Google credentials are configured (not necessarily
     * authenticated).
     */
    public boolean isConfigured() {
        return !CLIENT_ID.isBlank() && !CLIENT_SECRET.isBlank();
    }

    /**
     * Build the Google OAuth authorization URL.
     * Direct the user to open this URL in a browser.
     */
    public String getAuthorizationUrl() {
        return AUTH_URL
                + "?client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&response_type=code"
                + "&scope=" + encode(SCOPE)
                + "&access_type=offline"
                + "&prompt=consent";
    }

    /**
     * Exchange the OAuth authorization code for access + refresh tokens.
     * Called automatically when the user is redirected back to the callback URL.
     *
     * @return true on success
     */
    public boolean handleCallback(String code) {
        try {
            String body = "code=" + encode(code)
                    + "&client_id=" + encode(CLIENT_ID)
                    + "&client_secret=" + encode(CLIENT_SECRET)
                    + "&redirect_uri=" + encode(REDIRECT_URI)
                    + "&grant_type=authorization_code";

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[GoogleTasks] Token exchange failed: " + response.body());
                return false;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            applyTokenResponse(json);
            saveTokenFile();
            System.out.println("[GoogleTasks] Authenticated successfully.");
            return true;
        } catch (Exception e) {
            System.err.println("[GoogleTasks] handleCallback error: " + e.getMessage());
            return false;
        }
    }

    // ── Sync operations ────────────────────────────────────────────────────

    /**
     * Push every local TaskList to Google Tasks. Each local list maps to a
     * Google list of the same name (created if missing). Tasks already present
     * in a Google list (matched by title, case-insensitive) are skipped.
     */
    public CompletableFuture<JsonObject> pushTasks() {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", notConfiguredMessage());
                return result;
            }
            try {
                int pushed = 0;
                int listsCreated = 0;
                JsonArray perList = new JsonArray();

                for (ProfileManager.TaskList localList : ProfileManager.getInstance().getTaskLists()) {
                    if (localList.name == null || localList.name.isBlank()) continue;
                    Boolean[] wasCreated = { false };
                    String listId = findOrCreateList(localList.name, wasCreated);
                    if (listId == null) {
                        JsonObject row = new JsonObject();
                        row.addProperty("list", localList.name);
                        row.addProperty("error", "Could not find or create matching Google list");
                        perList.add(row);
                        continue;
                    }
                    if (wasCreated[0]) listsCreated++;

                    // De-dupe by title within this Google list
                    java.util.Set<String> existing = new java.util.HashSet<>();
                    for (JsonElement el : listTasksInList(listId)) {
                        JsonObject t = el.getAsJsonObject();
                        if (t.has("title")) existing.add(t.get("title").getAsString().trim().toLowerCase());
                    }

                    int listPushed = 0;
                    for (ProfileManager.TaskItem task : localList.items) {
                        if (task.title == null || task.title.isBlank()) continue;
                        if (existing.contains(task.title.trim().toLowerCase())) continue;
                        if (createGoogleTask(listId, task)) {
                            listPushed++;
                            pushed++;
                        }
                    }

                    JsonObject row = new JsonObject();
                    row.addProperty("list", localList.name);
                    row.addProperty("pushed", listPushed);
                    row.addProperty("created", wasCreated[0]);
                    perList.add(row);
                }

                result.addProperty("pushed", pushed);
                result.addProperty("listsCreated", listsCreated);
                result.add("byList", perList);
                result.addProperty("message", "Pushed " + pushed + " new tasks across "
                        + perList.size() + " list(s). Created " + listsCreated + " Google list(s).");
                System.out.println("[GoogleTasks] Pushed " + pushed + " across " + perList.size() + " lists.");
            } catch (Exception e) {
                result.addProperty("error", "Push failed: " + e.getMessage());
                System.err.println("[GoogleTasks] Push error: " + e.getMessage());
            }
            return result;
        });
    }

    /**
     * Pull every Google task list into local TaskLists. Each Google list
     * becomes a local list of the same name (created if missing). Tasks already
     * present locally (by title, case-insensitive in that same list) are skipped.
     */
    public CompletableFuture<JsonObject> pullTasks() {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", notConfiguredMessage());
                return result;
            }
            try {
                JsonArray googleLists = listAllGoogleLists();
                int pulled = 0;
                int listsAdded = 0;
                JsonArray perList = new JsonArray();

                for (JsonElement listEl : googleLists) {
                    JsonObject googleList = listEl.getAsJsonObject();
                    if (!googleList.has("id") || !googleList.has("title")) continue;
                    String listId = googleList.get("id").getAsString();
                    String listName = googleList.get("title").getAsString().trim();
                    if (listName.isEmpty()) continue;

                    // Ensure local TaskList exists with this name
                    boolean localExisted = ProfileManager.getInstance().getTaskLists().stream()
                            .anyMatch(l -> l.name.equalsIgnoreCase(listName));
                    if (!localExisted) {
                        ProfileManager.getInstance().addTaskList(listName);
                        listsAdded++;
                    }

                    // Collect existing local titles in this list for de-dup
                    java.util.Set<String> existingLocal = new java.util.HashSet<>();
                    for (ProfileManager.TaskList l : ProfileManager.getInstance().getTaskLists()) {
                        if (l.name.equalsIgnoreCase(listName)) {
                            for (ProfileManager.TaskItem t : l.items) {
                                if (t.title != null) existingLocal.add(t.title.trim().toLowerCase());
                            }
                        }
                    }

                    int listPulled = 0;
                    for (JsonElement elem : listTasksInList(listId)) {
                        JsonObject t = elem.getAsJsonObject();
                        String title = t.has("title") ? t.get("title").getAsString().trim() : "";
                        if (title.isEmpty()) continue;
                        if (existingLocal.contains(title.toLowerCase())) continue;
                        String notes = t.has("notes") ? t.get("notes").getAsString() : "";
                        String rawDue = t.has("due") ? t.get("due").getAsString() : "";
                        String due = rawDue.length() >= 10 ? rawDue.substring(0, 10) : "";
                        String gId = t.has("id") ? t.get("id").getAsString() : "";
                        boolean done = t.has("status") && "completed".equals(t.get("status").getAsString());
                        // Add the task, then stamp the Google IDs so future
                        // updates/deletes target Google by ID rather than title.
                        ProfileManager.getInstance().addTaskToList(listName, title, notes, due);
                        for (ProfileManager.TaskList l : ProfileManager.getInstance().getTaskLists()) {
                            if (l.name.equalsIgnoreCase(listName)) {
                                for (ProfileManager.TaskItem ti : l.items) {
                                    if (ti.title.equalsIgnoreCase(title)) {
                                        ti.googleId = gId;
                                        ti.googleListId = listId;
                                        ti.completed = done;
                                    }
                                }
                            }
                        }
                        listPulled++;
                        pulled++;
                    }
                    ProfileManager.getInstance().saveProfile();

                    JsonObject row = new JsonObject();
                    row.addProperty("list", listName);
                    row.addProperty("pulled", listPulled);
                    row.addProperty("createdLocally", !localExisted);
                    perList.add(row);
                }

                result.addProperty("pulled", pulled);
                result.addProperty("listsAdded", listsAdded);
                result.add("byList", perList);
                result.addProperty("message", "Pulled " + pulled + " new tasks from "
                        + googleLists.size() + " Google list(s). Added " + listsAdded + " local list(s).");
                System.out.println("[GoogleTasks] Pulled " + pulled + " across " + googleLists.size() + " lists.");
            } catch (Exception e) {
                result.addProperty("error", "Pull failed: " + e.getMessage());
                System.err.println("[GoogleTasks] Pull error: " + e.getMessage());
            }
            return result;
        });
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /** Refresh token if within 60 seconds of expiry. */
    private boolean ensureAuthenticated() {
        if (!authenticated)
            return false;
        if (System.currentTimeMillis() < tokenExpiresAt - 60_000)
            return true;
        return refreshAccessToken();
    }

    private boolean refreshAccessToken() {
        try {
            String body = "refresh_token=" + encode(refreshToken)
                    + "&client_id=" + encode(CLIENT_ID)
                    + "&client_secret=" + encode(CLIENT_SECRET)
                    + "&grant_type=refresh_token";

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                applyTokenResponse(JsonParser.parseString(response.body()).getAsJsonObject());
                saveTokenFile();
                return true;
            }
            System.err.println("[GoogleTasks] Token refresh failed: " + response.body());
            authenticated = false;
            return false;
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Token refresh error: " + e.getMessage());
            authenticated = false;
            return false;
        }
    }

    private void applyTokenResponse(JsonObject json) {
        accessToken = json.get("access_token").getAsString();
        tokenExpiresAt = System.currentTimeMillis()
                + (json.has("expires_in") ? json.get("expires_in").getAsLong() * 1000 : 3600_000);
        if (json.has("refresh_token")) {
            refreshToken = json.get("refresh_token").getAsString();
        }
        authenticated = true;
    }

    /** Return every Google task list (raw JSON entries from /users/@me/lists). */
    private JsonArray listAllGoogleLists() throws Exception {
        HttpResponse<String> resp = apiGet(TASKS_API + "/users/@me/lists");
        if (resp.statusCode() != 200) {
            System.err.println("[GoogleTasks] listAllGoogleLists HTTP " + resp.statusCode() + ": " + resp.body());
            return new JsonArray();
        }
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        return body.has("items") ? body.getAsJsonArray("items") : new JsonArray();
    }

    /**
     * Find a Google task list by name (case-insensitive); create it if missing.
     * The {@code outCreated} array (length 1) receives whether a new list was created.
     * Returns the list ID, or null on failure.
     */
    private String findOrCreateList(String name, Boolean[] outCreated) throws Exception {
        for (JsonElement el : listAllGoogleLists()) {
            JsonObject list = el.getAsJsonObject();
            if (list.has("title") && list.get("title").getAsString().equalsIgnoreCase(name)) {
                if (outCreated != null && outCreated.length > 0) outCreated[0] = false;
                return list.get("id").getAsString();
            }
        }
        // Not found — create
        JsonObject payload = new JsonObject();
        payload.addProperty("title", name);
        HttpResponse<String> create = apiPost(TASKS_API + "/users/@me/lists", payload.toString());
        if (create.statusCode() == 200 || create.statusCode() == 201) {
            if (outCreated != null && outCreated.length > 0) outCreated[0] = true;
            return JsonParser.parseString(create.body()).getAsJsonObject().get("id").getAsString();
        }
        System.err.println("[GoogleTasks] Create list '" + name + "' failed HTTP " + create.statusCode() + ": " + create.body());
        return null;
    }

    private boolean createGoogleTask(String listId, ProfileManager.TaskItem task) {
        JsonObject created = createGoogleTaskAndReturnId(listId, task);
        if (created == null) return false;
        // Tag the local TaskItem so future updates/deletes can target Google by ID.
        task.googleId = created.has("id") ? created.get("id").getAsString() : "";
        task.googleListId = listId;
        return true;
    }

    /** Same as createGoogleTask but returns the parsed response so callers can grab the ID. */
    private JsonObject createGoogleTaskAndReturnId(String listId, ProfileManager.TaskItem task) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("title", task.title);
            if (task.description != null && !task.description.isEmpty())
                payload.addProperty("notes", task.description);
            if (task.dueDate != null && !task.dueDate.isEmpty()) {
                payload.addProperty("due", task.dueDate + "T00:00:00.000Z");
            }
            HttpResponse<String> resp = apiPost(
                    TASKS_API + "/lists/" + listId + "/tasks", payload.toString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            }
            System.err.println("[GoogleTasks] createGoogleTask HTTP " + resp.statusCode() + ": " + resp.body());
            return null;
        } catch (Exception e) {
            System.err.println("[GoogleTasks] createGoogleTask error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Public single-task push. Finds-or-creates a Google list with the given name,
     * creates the task, then mutates the passed-in TaskItem to record its Google
     * IDs. Async — fire-and-forget from local CRUD handlers.
     */
    public CompletableFuture<Boolean> pushSingleTask(String listName, ProfileManager.TaskItem task) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ensureAuthenticated()) return false;
            try {
                Boolean[] created = { false };
                String listId = findOrCreateList(listName, created);
                if (listId == null) return false;
                JsonObject resp = createGoogleTaskAndReturnId(listId, task);
                if (resp == null) return false;
                if (resp.has("id")) {
                    task.googleId = resp.get("id").getAsString();
                    task.googleListId = listId;
                    ProfileManager.getInstance().saveProfile();
                }
                return true;
            } catch (Exception e) {
                System.err.println("[GoogleTasks] pushSingleTask error: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Public single-task delete. If the TaskItem has a Google ID, delete by ID
     * (precise). Otherwise look it up by title within the named list.
     */
    public CompletableFuture<Boolean> deleteSingleTask(String listName, ProfileManager.TaskItem task) {
        return CompletableFuture.supplyAsync(() -> {
            if (!ensureAuthenticated()) return false;
            try {
                String listId = task.googleListId != null && !task.googleListId.isEmpty()
                        ? task.googleListId
                        : findOrCreateList(listName, new Boolean[] { false });
                if (listId == null) return false;

                String taskId = task.googleId;
                if (taskId == null || taskId.isEmpty()) {
                    // Fall back to title match
                    for (JsonElement el : listTasksInList(listId)) {
                        JsonObject t = el.getAsJsonObject();
                        if (t.has("title") && t.get("title").getAsString().equalsIgnoreCase(task.title)) {
                            taskId = t.get("id").getAsString();
                            break;
                        }
                    }
                }
                if (taskId == null || taskId.isEmpty()) return false;

                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(TASKS_API + "/lists/" + listId + "/tasks/" + taskId))
                                .header("Authorization", "Bearer " + accessToken)
                                .DELETE()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200 || resp.statusCode() == 204;
            } catch (Exception e) {
                System.err.println("[GoogleTasks] deleteSingleTask error: " + e.getMessage());
                return false;
            }
        });
    }

    private JsonArray listTasksInList(String listId) throws Exception {
        // Include completed tasks so users see their actual Google data. We expose
        // each task's status downstream via TaskItem.completed; UI can choose to hide.
        HttpResponse<String> resp = apiGet(
                TASKS_API + "/lists/" + listId + "/tasks?showCompleted=true&showHidden=true&maxResults=100");
        if (resp.statusCode() != 200) {
            System.err.println("[GoogleTasks] listTasksInList HTTP " + resp.statusCode() + ": " + resp.body());
            return new JsonArray();
        }
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        return body.has("items") ? body.getAsJsonArray("items") : new JsonArray();
    }

    private HttpResponse<String> apiGet(String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiPost(String url, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void restoreSession() {
        if (CLIENT_ID.isBlank())
            return;
        try {
            if (Files.exists(TOKEN_FILE)) {
                String json = Files.readString(TOKEN_FILE).trim();
                if (!json.isEmpty()) {
                    JsonObject saved = JsonParser.parseString(json).getAsJsonObject();
                    refreshToken = saved.has("refresh_token") ? saved.get("refresh_token").getAsString() : null;
                    accessToken = saved.has("access_token") ? saved.get("access_token").getAsString() : null;
                    tokenExpiresAt = saved.has("expires_at") ? saved.get("expires_at").getAsLong() : 0;
                    authenticated = refreshToken != null && !refreshToken.isBlank();
                    if (authenticated) {
                        System.out.println("[GoogleTasks] Session restored from saved token.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Could not restore session: " + e.getMessage());
        }
    }

    private void saveTokenFile() {
        try {
            JsonObject saved = new JsonObject();
            if (accessToken != null)
                saved.addProperty("access_token", accessToken);
            if (refreshToken != null)
                saved.addProperty("refresh_token", refreshToken);
            saved.addProperty("expires_at", tokenExpiresAt);
            Files.writeString(TOKEN_FILE, gson.toJson(saved));
        } catch (Exception e) {
            System.err.println("[GoogleTasks] Could not save token: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String notConfiguredMessage() {
        return "Google Tasks is not configured. Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET " +
                "to your .env file, then authenticate at /api/tasks/google/auth.";
    }
}
