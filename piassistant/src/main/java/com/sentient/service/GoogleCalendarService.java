package com.sentient.service;

import com.google.gson.*;
import com.sentient.util.EnvLoader;

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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Google Calendar API integration — syncs events with Google Calendar.
 *
 * <p>
 * Shares OAuth credentials and tokens with {@link GoogleTasksService}.
 * The OAuth scope in GoogleTasksService has been expanded to include Calendar
 * access,
 * so a single authentication flow grants both Tasks + Calendar permissions.
 *
 * <h2>Setup</h2>
 * <ol>
 * <li>Enable the <b>Google Calendar API</b> in your Google Cloud Console
 * (same project as Google Tasks).</li>
 * <li>Use the same {@code GOOGLE_CLIENT_ID} and {@code GOOGLE_CLIENT_SECRET}
 * in your {@code .env} file.</li>
 * <li>Re-authenticate at {@code http://localhost:7070/api/tasks/google/auth}
 * to grant the expanded scope.</li>
 * </ol>
 */
public class GoogleCalendarService {

    private static final String CLIENT_ID = EnvLoader.get("GOOGLE_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("GOOGLE_CLIENT_SECRET", "");
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3";

    // Share token file with GoogleTasksService
    private static final Path TOKEN_FILE = Paths.get(
            System.getProperty("user.home"), ".sentient_google_tasks_token");

    private final HttpClient client;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAt = 0;
    private volatile boolean authenticated = false;

    public GoogleCalendarService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        restoreSession();
    }

    // ── Auth helpers ──────────────────────────────────────────────────────

    public boolean isAuthenticated() {
        return authenticated && !CLIENT_ID.isBlank();
    }

    public boolean isConfigured() {
        return !CLIENT_ID.isBlank() && !CLIENT_SECRET.isBlank();
    }

    /** Re-read tokens from disk (e.g. after GoogleTasksService completes OAuth). */
    public void refreshFromDisk() {
        restoreSession();
    }

    /**
     * Probe whether Calendar can actually reach Google. Triggers a refresh if needed.
     * Returns {authenticated, working, statusCode, error?}.
     */
    public JsonObject getAuthHealth() {
        JsonObject out = new JsonObject();
        out.addProperty("configured", isConfigured());
        if (!isAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Not authenticated. Connect Google to sync Calendar.");
            return out;
        }
        if (!ensureAuthenticated()) {
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Calendar token refresh failed. Please reconnect Google.");
            return out;
        }
        try {
            HttpResponse<String> resp = apiGet(CALENDAR_API + "/users/me/calendarList?maxResults=1");
            out.addProperty("statusCode", resp.statusCode());
            if (resp.statusCode() == 200) {
                out.addProperty("authenticated", true);
                out.addProperty("working", true);
                return out;
            }
            out.addProperty("authenticated", false);
            out.addProperty("working", false);
            out.addProperty("error", "Google Calendar returned HTTP " + resp.statusCode() + ".");
            if (resp.statusCode() == 401) authenticated = false;
            return out;
        } catch (Exception e) {
            out.addProperty("authenticated", true);
            out.addProperty("working", false);
            out.addProperty("error", "Could not reach Google Calendar: " + e.getMessage());
            return out;
        }
    }

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
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                accessToken = json.get("access_token").getAsString();
                tokenExpiresAt = System.currentTimeMillis()
                        + (json.has("expires_in") ? json.get("expires_in").getAsLong() * 1000 : 3600_000);
                if (json.has("refresh_token")) {
                    refreshToken = json.get("refresh_token").getAsString();
                }
                authenticated = true;
                return true;
            }
            System.err.println("[GoogleCalendar] Token refresh failed: " + response.body());
            authenticated = false;
            return false;
        } catch (Exception e) {
            System.err.println("[GoogleCalendar] Token refresh error: " + e.getMessage());
            authenticated = false;
            return false;
        }
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
                        System.out.println("[GoogleCalendar] Session restored from shared token.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GoogleCalendar] Could not restore session: " + e.getMessage());
        }
    }

    // ── Calendar API operations ───────────────────────────────────────────

    /**
     * List events from the user's primary calendar.
     *
     * @param days how many days into the future to fetch (default: 7)
     * @return JSON with "events" array
     */
    public CompletableFuture<JsonObject> listEvents(int days) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", "Google Calendar not authenticated. Please authenticate first.");
                return result;
            }

            try {
                String timeMin = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                String timeMax = ZonedDateTime.now().plusDays(days)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

                String url = CALENDAR_API + "/calendars/primary/events"
                        + "?timeMin=" + encode(timeMin)
                        + "&timeMax=" + encode(timeMax)
                        + "&singleEvents=true"
                        + "&orderBy=startTime"
                        + "&maxResults=50";

                HttpResponse<String> resp = apiGet(url);
                if (resp.statusCode() != 200) {
                    result.addProperty("error", "Failed to fetch events: HTTP " + resp.statusCode());
                    System.err.println("[GoogleCalendar] List events failed: " + resp.body());
                    return result;
                }

                JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray items = body.has("items") ? body.getAsJsonArray("items") : new JsonArray();

                // Simplify events for frontend
                JsonArray events = new JsonArray();
                for (JsonElement elem : items) {
                    JsonObject event = elem.getAsJsonObject();
                    JsonObject simple = new JsonObject();
                    simple.addProperty("id", event.has("id") ? event.get("id").getAsString() : "");
                    simple.addProperty("title",
                            event.has("summary") ? event.get("summary").getAsString() : "(No title)");
                    simple.addProperty("description",
                            event.has("description") ? event.get("description").getAsString() : "");

                    // Start time
                    if (event.has("start")) {
                        JsonObject start = event.getAsJsonObject("start");
                        simple.addProperty("start",
                                start.has("dateTime") ? start.get("dateTime").getAsString()
                                        : start.has("date") ? start.get("date").getAsString() : "");
                        simple.addProperty("allDay", !start.has("dateTime"));
                    }
                    // End time
                    if (event.has("end")) {
                        JsonObject end = event.getAsJsonObject("end");
                        simple.addProperty("end",
                                end.has("dateTime") ? end.get("dateTime").getAsString()
                                        : end.has("date") ? end.get("date").getAsString() : "");
                    }

                    events.add(simple);
                }

                result.add("events", events);
                result.addProperty("count", events.size());
                System.out.println("[GoogleCalendar] Fetched " + events.size() + " events.");
            } catch (Exception e) {
                result.addProperty("error", "Failed to list events: " + e.getMessage());
                System.err.println("[GoogleCalendar] listEvents error: " + e.getMessage());
            }
            return result;
        });
    }

    /**
     * Create a new event on the user's primary calendar.
     *
     * @param title       event title
     * @param description event description
     * @param startTime   ISO 8601 datetime (e.g. 2026-03-25T10:00:00-07:00)
     * @param endTime     ISO 8601 datetime
     * @return JSON with created event info
     */
    public CompletableFuture<JsonObject> createEvent(String title, String description,
            String startTime, String endTime) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", "Google Calendar not authenticated.");
                return result;
            }

            try {
                JsonObject event = new JsonObject();
                event.addProperty("summary", title);
                if (description != null && !description.isEmpty()) {
                    event.addProperty("description", description);
                }

                JsonObject start = new JsonObject();
                JsonObject end = new JsonObject();

                // Check if it's a date-only (all-day) or dateTime
                if (startTime.length() <= 10) {
                    start.addProperty("date", startTime);
                    end.addProperty("date", endTime);
                } else {
                    start.addProperty("dateTime", startTime);
                    start.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
                    end.addProperty("dateTime", endTime);
                    end.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
                }

                event.add("start", start);
                event.add("end", end);

                HttpResponse<String> resp = apiPost(
                        CALENDAR_API + "/calendars/primary/events", event.toString());

                if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                    JsonObject created = JsonParser.parseString(resp.body()).getAsJsonObject();
                    result.addProperty("success", true);
                    result.addProperty("id", created.get("id").getAsString());
                    result.addProperty("message", "Event created: " + title);
                    System.out.println("[GoogleCalendar] Created event: " + title);
                } else {
                    result.addProperty("error", "Failed to create event: HTTP " + resp.statusCode());
                    System.err.println("[GoogleCalendar] Create event failed: " + resp.body());
                }
            } catch (Exception e) {
                result.addProperty("error", "Failed to create event: " + e.getMessage());
            }
            return result;
        });
    }

    /**
     * Delete an event by ID.
     */
    public CompletableFuture<JsonObject> deleteEvent(String eventId) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", "Google Calendar not authenticated.");
                return result;
            }

            try {
                HttpResponse<String> resp = apiDelete(
                        CALENDAR_API + "/calendars/primary/events/" + eventId);

                if (resp.statusCode() == 204 || resp.statusCode() == 200) {
                    result.addProperty("success", true);
                    result.addProperty("message", "Event deleted.");
                    System.out.println("[GoogleCalendar] Deleted event: " + eventId);
                } else {
                    result.addProperty("error", "Failed to delete event: HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                result.addProperty("error", "Failed to delete event: " + e.getMessage());
            }
            return result;
        });
    }

    /**
     * Update an existing event.
     */
    public CompletableFuture<JsonObject> updateEvent(String eventId, String title,
            String description, String startTime, String endTime) {
        return CompletableFuture.supplyAsync(() -> {
            JsonObject result = new JsonObject();
            if (!ensureAuthenticated()) {
                result.addProperty("error", "Google Calendar not authenticated.");
                return result;
            }

            try {
                JsonObject event = new JsonObject();
                if (title != null && !title.isEmpty())
                    event.addProperty("summary", title);
                if (description != null)
                    event.addProperty("description", description);

                if (startTime != null && !startTime.isEmpty()) {
                    JsonObject start = new JsonObject();
                    if (startTime.length() <= 10) {
                        start.addProperty("date", startTime);
                    } else {
                        start.addProperty("dateTime", startTime);
                        start.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
                    }
                    event.add("start", start);
                }

                if (endTime != null && !endTime.isEmpty()) {
                    JsonObject end = new JsonObject();
                    if (endTime.length() <= 10) {
                        end.addProperty("date", endTime);
                    } else {
                        end.addProperty("dateTime", endTime);
                        end.addProperty("timeZone", ZonedDateTime.now().getZone().getId());
                    }
                    event.add("end", end);
                }

                HttpResponse<String> resp = apiPatch(
                        CALENDAR_API + "/calendars/primary/events/" + eventId, event.toString());

                if (resp.statusCode() == 200) {
                    result.addProperty("success", true);
                    result.addProperty("message", "Event updated.");
                    System.out.println("[GoogleCalendar] Updated event: " + eventId);
                } else {
                    result.addProperty("error", "Failed to update event: HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                result.addProperty("error", "Failed to update event: " + e.getMessage());
            }
            return result;
        });
    }

    /**
     * Get a summary of upcoming events for AI context.
     * Returns a human-readable string of events in the next N days.
     */
    public String getEventsSummary(int days) {
        try {
            JsonObject result = listEvents(days).get();
            if (result.has("error"))
                return "Calendar not available.";
            JsonArray events = result.getAsJsonArray("events");
            if (events.size() == 0)
                return "No upcoming events in the next " + days + " days.";

            StringBuilder sb = new StringBuilder();
            for (JsonElement elem : events) {
                JsonObject e = elem.getAsJsonObject();
                sb.append("- ").append(e.get("title").getAsString());
                if (e.has("start") && !e.get("start").getAsString().isEmpty()) {
                    sb.append(" (").append(e.get("start").getAsString()).append(")");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Calendar not available.";
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

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

    private HttpResponse<String> apiPatch(String url, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiDelete(String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
