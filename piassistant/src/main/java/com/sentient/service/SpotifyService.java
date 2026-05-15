package com.sentient.service;

import com.google.gson.*;
import com.sentient.util.EnvLoader;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.specification.*;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.requests.data.player.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Spotify integration service — handles OAuth, playlist browsing,
 * search, playback control, and AI DJ recommendations.
 */
public class SpotifyService {

    private static final String CLIENT_ID = EnvLoader.get("SPOTIFY_CLIENT_ID", "");
    private static final String CLIENT_SECRET = EnvLoader.get("SPOTIFY_CLIENT_SECRET", "");
    private static final URI REDIRECT_URI = SpotifyHttpManager.makeUri("http://127.0.0.1:7070/api/spotify/callback");

    // File to persist refresh token across restarts
    private static final Path TOKEN_FILE = Paths.get(System.getProperty("user.home"), ".sentient_spotify_token");

    private static final String SCOPES = String.join(" ",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "streaming",
            "user-library-read");

    private final SpotifyApi spotifyApi;
    private final Gson gson = new Gson();

    private volatile boolean authenticated = false;
    private volatile long tokenExpiresAt = 0;

    public SpotifyService() {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(CLIENT_ID)
                .setClientSecret(CLIENT_SECRET)
                .setRedirectUri(REDIRECT_URI)
                .build();

        // Try to restore session from saved refresh token
        restoreSession();
    }

    // ── OAuth ───────────────────────────────────────────

    /**
     * Generate the Spotify login URL for the user to authorize.
     */
    public String getAuthorizationUrl() {
        return spotifyApi.authorizationCodeUri()
                .scope(SCOPES)
                .show_dialog(true)
                .build()
                .execute()
                .toString();
    }

    /**
     * Exchange authorization code for access + refresh tokens.
     */
    public boolean handleCallback(String code) {
        try {
            AuthorizationCodeCredentials credentials = spotifyApi.authorizationCode(code)
                    .build()
                    .execute();

            spotifyApi.setAccessToken(credentials.getAccessToken());
            spotifyApi.setRefreshToken(credentials.getRefreshToken());
            tokenExpiresAt = System.currentTimeMillis() + (credentials.getExpiresIn() * 1000L);
            authenticated = true;

            // Persist refresh token for next restart
            saveRefreshToken(credentials.getRefreshToken());

            System.out.println(
                    "[Spotify] Authenticated successfully. Token expires in " + credentials.getExpiresIn() + "s");
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] OAuth callback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Save refresh token to disk so the user stays signed in across restarts.
     */
    private void saveRefreshToken(String refreshToken) {
        try {
            Files.writeString(TOKEN_FILE, refreshToken);
            System.out.println("[Spotify] Refresh token saved.");
        } catch (Exception e) {
            System.err.println("[Spotify] Could not save refresh token: " + e.getMessage());
        }
    }

    /**
     * Restore session from saved refresh token on startup.
     */
    private void restoreSession() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                String refreshToken = Files.readString(TOKEN_FILE).trim();
                if (!refreshToken.isEmpty() && !CLIENT_ID.isEmpty()) {
                    spotifyApi.setRefreshToken(refreshToken);
                    AuthorizationCodeCredentials credentials = spotifyApi.authorizationCodeRefresh()
                            .build()
                            .execute();
                    spotifyApi.setAccessToken(credentials.getAccessToken());
                    // Update refresh token if a new one was issued
                    if (credentials.getRefreshToken() != null) {
                        spotifyApi.setRefreshToken(credentials.getRefreshToken());
                        saveRefreshToken(credentials.getRefreshToken());
                    }
                    tokenExpiresAt = System.currentTimeMillis() + (credentials.getExpiresIn() * 1000L);
                    authenticated = true;
                    System.out.println("[Spotify] Session restored from saved token.");
                }
            }
        } catch (Exception e) {
            System.err.println("[Spotify] Could not restore session: " + e.getMessage());
            // Delete invalid token file
            try {
                Files.deleteIfExists(TOKEN_FILE);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Refresh the access token if expired.
     */
    private void refreshTokenIfNeeded() {
        if (!authenticated)
            return;
        if (System.currentTimeMillis() < tokenExpiresAt - 60000)
            return; // still valid

        try {
            AuthorizationCodeCredentials credentials = spotifyApi.authorizationCodeRefresh()
                    .build()
                    .execute();
            spotifyApi.setAccessToken(credentials.getAccessToken());
            if (credentials.getRefreshToken() != null) {
                spotifyApi.setRefreshToken(credentials.getRefreshToken());
                saveRefreshToken(credentials.getRefreshToken());
            }
            tokenExpiresAt = System.currentTimeMillis() + (credentials.getExpiresIn() * 1000L);
            System.out.println("[Spotify] Token refreshed.");
        } catch (Exception e) {
            System.err.println("[Spotify] Token refresh failed: " + e.getMessage());
            authenticated = false;
        }
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getAccessToken() {
        refreshTokenIfNeeded();
        return spotifyApi.getAccessToken();
    }

    /**
     * Probe whether the saved token actually works by calling /v1/me. The local
     * 'authenticated' flag only says "we have a refresh token" — this checks
     * whether the API actually accepts it. Returns:
     *   {authenticated, working, statusCode, userId?, displayName?, error?}
     */
    public JsonObject getAuthHealth() {
        JsonObject result = new JsonObject();
        result.addProperty("authenticated", authenticated);
        if (!authenticated) {
            result.addProperty("working", false);
            result.addProperty("error", "Not connected to Spotify. Click 'Connect Spotify' to authorize.");
            return result;
        }
        refreshTokenIfNeeded();
        try {
            String token = spotifyApi.getAccessToken();
            if (token == null || token.isBlank()) {
                result.addProperty("working", false);
                result.addProperty("error", "No access token available — refresh failed. Reconnect Spotify.");
                return result;
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.spotify.com/v1/me").openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            result.addProperty("statusCode", code);
            if (code == 200) {
                JsonObject me = JsonParser.parseString(
                        new String(conn.getInputStream().readAllBytes())).getAsJsonObject();
                result.addProperty("working", true);
                if (me.has("id")) result.addProperty("userId", me.get("id").getAsString());
                if (me.has("display_name") && !me.get("display_name").isJsonNull())
                    result.addProperty("displayName", me.get("display_name").getAsString());
                return result;
            }
            String errBody = "";
            try { errBody = new String(conn.getErrorStream().readAllBytes()); } catch (Exception ignored) {}
            result.addProperty("working", false);
            if (code == 401) {
                // Mark local flag false too — token is dead
                authenticated = false;
                result.addProperty("authenticated", false);
                result.addProperty("error", "Spotify access token rejected (expired or revoked). Reconnect Spotify.");
            } else if (code == 403) {
                result.addProperty("error", "Spotify forbade the request (HTTP 403). Most endpoints will fail until app is approved for extended quota.");
            } else {
                result.addProperty("error", "Spotify returned HTTP " + code + (errBody.isEmpty() ? "" : " — " + errBody));
            }
            return result;
        } catch (Exception e) {
            result.addProperty("working", false);
            result.addProperty("error", "Could not reach Spotify: " + e.getMessage());
            return result;
        }
    }

    // ── Playlists ───────────────────────────────────────

    /**
     * Get current user's playlists.
     */
    public JsonArray getUserPlaylists() {
        refreshTokenIfNeeded();
        try {
            var request = spotifyApi.getListOfCurrentUsersPlaylists()
                    .limit(30)
                    .build();
            Paging<PlaylistSimplified> playlists = request.execute();
            return playlistsToJson(playlists.getItems());
        } catch (Exception e) {
            System.err.println("[Spotify] Failed to get playlists: " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Get curated/popular playlists by searching Spotify.
     */
    public JsonArray getFeaturedPlaylists() {
        refreshTokenIfNeeded();
        try {
            // Search for popular playlists — works regardless of app mode
            var searchReq = spotifyApi.searchPlaylists("Top Hits")
                    .build();
            Paging<PlaylistSimplified> result = searchReq.execute();
            return playlistsToJson(result.getItems());
        } catch (Exception e) {
            System.err.println("[Spotify] Failed to get featured playlists: " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Get tracks from a playlist.
     * Uses getPlaylist (full object with embedded tracks) instead of
     * getPlaylistsItems which may return Forbidden in dev-mode apps.
     */
    public JsonArray getPlaylistTracks(String playlistId) {
        refreshTokenIfNeeded();
        try {
            // Use the full playlist endpoint — returns tracks embedded
            Playlist playlist = spotifyApi.getPlaylist(playlistId).build().execute();
            if (playlist == null || playlist.getTracks() == null)
                return new JsonArray();

            JsonArray arr = new JsonArray();
            for (PlaylistTrack pt : playlist.getTracks().getItems()) {
                if (pt != null && pt.getTrack() instanceof Track) {
                    arr.add(trackToJson((Track) pt.getTrack()));
                }
            }
            return arr;
        } catch (Exception e) {
            System.err.println("[Spotify] Failed to get playlist tracks: " + e.getMessage());
            // Fallback: try raw HTTP call to Spotify API
            return getPlaylistTracksRaw(playlistId);
        }
    }

    /**
     * Fallback: fetch playlist tracks via raw HTTP when library calls fail.
     */
    private JsonArray getPlaylistTracksRaw(String playlistId) {
        try {
            String token = spotifyApi.getAccessToken();
            java.net.URL url = new java.net.URL(
                    "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=50");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            if (status != 200) {
                // Read error body for debugging
                String errBody = "";
                try {
                    errBody = new String(conn.getErrorStream().readAllBytes());
                } catch (Exception ignored) {
                }
                System.err.println("[Spotify] Raw playlist tracks failed: HTTP " + status + " | " + errBody);
                return new JsonArray();
            }

            String body = new String(conn.getInputStream().readAllBytes());
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = response.getAsJsonArray("items");
            JsonArray arr = new JsonArray();

            if (items != null) {
                for (var item : items) {
                    JsonObject trackObj = item.getAsJsonObject().getAsJsonObject("track");
                    if (trackObj == null || !trackObj.has("id"))
                        continue;
                    JsonObject t = new JsonObject();
                    t.addProperty("id", trackObj.get("id").getAsString());
                    t.addProperty("name", trackObj.get("name").getAsString());
                    t.addProperty("uri", trackObj.get("uri").getAsString());
                    t.addProperty("duration_ms", trackObj.get("duration_ms").getAsInt());

                    // Artists
                    JsonArray artists = trackObj.getAsJsonArray("artists");
                    if (artists != null && artists.size() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < artists.size(); i++) {
                            if (i > 0)
                                sb.append(", ");
                            sb.append(artists.get(i).getAsJsonObject().get("name").getAsString());
                        }
                        t.addProperty("artists", sb.toString());
                    }

                    // Album image
                    JsonObject album = trackObj.getAsJsonObject("album");
                    if (album != null && album.has("images")) {
                        JsonArray images = album.getAsJsonArray("images");
                        if (images != null && images.size() > 0) {
                            t.addProperty("imageUrl",
                                    images.get(images.size() > 1 ? 1 : 0).getAsJsonObject().get("url").getAsString());
                        }
                        t.addProperty("album", album.get("name").getAsString());
                    }

                    arr.add(t);
                }
            }
            return arr;
        } catch (Exception e2) {
            System.err.println("[Spotify] Raw fallback also failed: " + e2.getMessage());
            return new JsonArray();
        }
    }

    // ── Search ──────────────────────────────────────────

    /**
     * Search Spotify for tracks.
     */
    public JsonArray searchTracks(String query) {
        refreshTokenIfNeeded();
        try {
            // Append a limit to the query string to work around library .limit() bug
            var request = spotifyApi.searchTracks(query)
                    .market(com.neovisionaries.i18n.CountryCode.US)
                    .build();
            Paging<Track> tracks = request.execute();
            JsonArray arr = new JsonArray();
            int count = 0;
            for (Track t : tracks.getItems()) {
                arr.add(trackToJson(t));
                if (++count >= 20)
                    break;
            }
            return arr;
        } catch (Exception e) {
            System.err.println("[Spotify] Search failed: " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Get the user's Saved (Liked) tracks via /v1/me/tracks.
     * This endpoint is NOT subject to Spotify's dev-mode restriction.
     */
    public JsonArray getSavedTracks() {
        refreshTokenIfNeeded();
        return libraryTracksRaw("https://api.spotify.com/v1/me/tracks?limit=50", "[SavedTracks]");
    }

    /**
     * Get tracks recently played by the user via /v1/me/player/recently-played.
     * NOT subject to Spotify's dev-mode restriction.
     */
    public JsonArray getRecentlyPlayed() {
        refreshTokenIfNeeded();
        return libraryTracksRaw("https://api.spotify.com/v1/me/player/recently-played?limit=50", "[Recent]");
    }

    /** Shared parser for /me/tracks + /me/player/recently-played — both return {items:[{track:{...}}]}. */
    private JsonArray libraryTracksRaw(String url, String tag) {
        try {
            String token = spotifyApi.getAccessToken();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != 200) {
                String err = "";
                try { err = new String(conn.getErrorStream().readAllBytes()); } catch (Exception ignored) {}
                System.err.println("[Spotify] " + tag + " HTTP " + status + " | " + err);
                return new JsonArray();
            }
            JsonObject response = JsonParser.parseString(
                    new String(conn.getInputStream().readAllBytes())).getAsJsonObject();
            JsonArray out = new JsonArray();
            if (!response.has("items")) return out;
            for (var item : response.getAsJsonArray("items")) {
                JsonObject obj = item.getAsJsonObject();
                if (!obj.has("track") || obj.get("track").isJsonNull()) continue;
                JsonObject trackObj = obj.getAsJsonObject("track");
                if (!trackObj.has("id")) continue;
                JsonObject t = new JsonObject();
                t.addProperty("id", trackObj.get("id").getAsString());
                t.addProperty("name", trackObj.get("name").getAsString());
                t.addProperty("uri", trackObj.get("uri").getAsString());
                t.addProperty("duration_ms", trackObj.get("duration_ms").getAsInt());
                JsonArray artists = trackObj.getAsJsonArray("artists");
                if (artists != null && artists.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < artists.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(artists.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    t.addProperty("artists", sb.toString());
                }
                JsonObject album = trackObj.getAsJsonObject("album");
                if (album != null) {
                    if (album.has("name")) t.addProperty("album", album.get("name").getAsString());
                    if (album.has("images")) {
                        JsonArray images = album.getAsJsonArray("images");
                        if (images != null && images.size() > 0) {
                            int idx = images.size() > 1 ? 1 : 0;
                            t.addProperty("imageUrl", images.get(idx).getAsJsonObject().get("url").getAsString());
                        }
                    }
                }
                if (obj.has("added_at")) t.addProperty("addedAt", obj.get("added_at").getAsString());
                if (obj.has("played_at")) t.addProperty("playedAt", obj.get("played_at").getAsString());
                out.add(t);
            }
            return out;
        } catch (Exception e) {
            System.err.println("[Spotify] " + tag + " failed: " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Probe whether the Spotify app has extended quota mode (i.e. is not dev-locked).
     * Hits /v1/playlists/{any}/tracks?limit=1 — a 403 means dev-mode restriction is in effect.
     * Returns: {"extendedQuota": bool, "probedPlaylist": id|null, "statusCode": int}
     */
    public JsonObject probeExtendedQuota() {
        refreshTokenIfNeeded();
        JsonObject result = new JsonObject();
        try {
            JsonArray playlists = getUserPlaylists();
            if (playlists.size() == 0) {
                result.addProperty("extendedQuota", true); // can't probe; assume OK
                result.addProperty("probedPlaylist", (String) null);
                return result;
            }
            String pid = playlists.get(0).getAsJsonObject().get("id").getAsString();
            String token = spotifyApi.getAccessToken();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.spotify.com/v1/playlists/" + pid + "/tracks?limit=1")
                            .openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            result.addProperty("statusCode", status);
            result.addProperty("probedPlaylist", pid);
            result.addProperty("extendedQuota", status == 200);
            return result;
        } catch (Exception e) {
            System.err.println("[Spotify] probeExtendedQuota failed: " + e.getMessage());
            result.addProperty("extendedQuota", false);
            result.addProperty("error", e.getMessage());
            return result;
        }
    }

    // ── Devices ─────────────────────────────────────────

    /**
     * Get available Spotify devices.
     */
    public JsonArray getAvailableDevices() {
        refreshTokenIfNeeded();
        try {
            Device[] devices = spotifyApi.getUsersAvailableDevices().build().execute();
            JsonArray arr = new JsonArray();
            if (devices != null) {
                for (Device d : devices) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", d.getId());
                    obj.addProperty("name", d.getName());
                    obj.addProperty("type", d.getType());
                    obj.addProperty("active", d.getIs_active());
                    obj.addProperty("volume", d.getVolume_percent());
                    arr.add(obj);
                }
            }
            return arr;
        } catch (Exception e) {
            System.err.println("[Spotify] Get devices failed: " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Transfer playback to a specific device.
     */
    public boolean transferPlayback(String deviceId) {
        refreshTokenIfNeeded();
        try {
            JsonArray deviceIds = new JsonArray();
            deviceIds.add(deviceId);
            spotifyApi.transferUsersPlayback(deviceIds).build().execute();
            System.out.println("[Spotify] Transferred playback to device: " + deviceId);
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Transfer playback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the first available device ID, or null if none.
     */
    private String findActiveDeviceId() {
        try {
            Device[] devices = spotifyApi.getUsersAvailableDevices().build().execute();
            if (devices != null) {
                // Prefer currently active device
                for (Device d : devices) {
                    if (d.getIs_active())
                        return d.getId();
                }
                // Otherwise use first available
                if (devices.length > 0)
                    return devices[0].getId();
            }
        } catch (Exception e) {
            // silent
        }
        return null;
    }

    // ── Playback ────────────────────────────────────────

    /**
     * Play a Spotify URI (track, album, or playlist).
     * Auto-detects device if none specified.
     */
    public boolean play(String uri, String deviceId) {
        refreshTokenIfNeeded();
        try {
            // Auto-find device if none given
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = findActiveDeviceId();
            }

            StartResumeUsersPlaybackRequest.Builder builder = spotifyApi.startResumeUsersPlayback();
            if (deviceId != null && !deviceId.isEmpty()) {
                builder.device_id(deviceId);
            }

            if (uri.contains(":track:")) {
                builder.uris(JsonParser.parseString("[\"" + uri + "\"]").getAsJsonArray());
            } else if (!uri.isEmpty()) {
                builder.context_uri(uri);
            }

            builder.build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Play failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Play a list of track URIs (for AI DJ).
     */
    public boolean playTracks(List<String> trackUris, String deviceId) {
        refreshTokenIfNeeded();
        try {
            // Auto-find device if none given
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = findActiveDeviceId();
            }

            JsonArray uris = new JsonArray();
            trackUris.forEach(uris::add);

            StartResumeUsersPlaybackRequest.Builder builder = spotifyApi.startResumeUsersPlayback();
            if (deviceId != null && !deviceId.isEmpty()) {
                builder.device_id(deviceId);
            }
            builder.uris(uris);
            builder.build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Play tracks failed: " + e.getMessage());
            return false;
        }
    }

    public boolean pause() {
        refreshTokenIfNeeded();
        try {
            spotifyApi.pauseUsersPlayback().build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Pause failed: " + e.getMessage());
            return false;
        }
    }

    public boolean resume() {
        refreshTokenIfNeeded();
        try {
            spotifyApi.startResumeUsersPlayback().build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Resume failed: " + e.getMessage());
            return false;
        }
    }

    public boolean skipNext() {
        refreshTokenIfNeeded();
        try {
            spotifyApi.skipUsersPlaybackToNextTrack().build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Skip failed: " + e.getMessage());
            return false;
        }
    }

    public boolean skipPrevious() {
        refreshTokenIfNeeded();
        try {
            spotifyApi.skipUsersPlaybackToPreviousTrack().build().execute();
            return true;
        } catch (Exception e) {
            System.err.println("[Spotify] Previous failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get current playback state.
     */
    public JsonObject getPlaybackState() {
        if (!authenticated) {
            JsonObject empty = new JsonObject();
            empty.addProperty("playing", false);
            return empty;
        }
        refreshTokenIfNeeded();
        try {
            CurrentlyPlaying cp = spotifyApi.getUsersCurrentlyPlayingTrack().build().execute();
            if (cp == null || cp.getItem() == null) {
                JsonObject empty = new JsonObject();
                empty.addProperty("playing", false);
                return empty;
            }

            JsonObject state = new JsonObject();
            state.addProperty("playing", cp.getIs_playing());
            state.addProperty("progress_ms", cp.getProgress_ms());

            if (cp.getItem() instanceof Track) {
                Track track = (Track) cp.getItem();
                state.add("track", trackToJson(track));
            }

            return state;
        } catch (Exception e) {
            // Suppress repeated "Invalid access token" spam
            if (!e.getMessage().contains("Invalid access token")) {
                System.err.println("[Spotify] Playback state failed: " + e.getMessage());
            }
            JsonObject empty = new JsonObject();
            empty.addProperty("playing", false);
            return empty;
        }
    }

    // ── AI DJ ───────────────────────────────────────────

    /**
     * Use AI to generate search queries from a mood prompt,
     * then search Spotify and return matching tracks.
     */
    public CompletableFuture<JsonObject> aiDjPick(String moodPrompt, GroqService groqService) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Ask AI to generate search queries
                String aiPrompt = "You are a music DJ AI. The user wants music for: \"" + moodPrompt + "\". " +
                        "Generate exactly 5 Spotify search queries that would find great songs matching this mood/vibe. "
                        +
                        "Each query should be a specific song title, artist name, or descriptive search term. " +
                        "Output ONLY the queries, one per line, with no numbering, no explanations, no extra text. " +
                        "Example output:\n" +
                        "lofi hip hop chill beats\n" +
                        "tame impala let it happen\n" +
                        "mac demarco my old man\n" +
                        "khruangbin chill vibes\n" +
                        "tycho awake";

                // Call Groq directly for the search queries
                String aiResponse = groqService.callGroqForDJ(aiPrompt);
                String[] queries = aiResponse.trim().split("\\n");

                // Search Spotify for each query and collect tracks
                JsonArray allTracks = new JsonArray();
                List<String> trackUris = new ArrayList<>();

                for (String query : queries) {
                    query = query.trim();
                    if (query.isEmpty())
                        continue;
                    // Remove any numbering like "1. " or "- "
                    query = query.replaceAll("^[\\d.\\-\\s]+", "").trim();
                    if (query.isEmpty())
                        continue;

                    JsonArray results = searchTracks(query);
                    if (results.size() > 0) {
                        // Take top 2 results per query
                        for (int i = 0; i < Math.min(2, results.size()); i++) {
                            JsonObject track = results.get(i).getAsJsonObject();
                            String uri = track.get("uri").getAsString();
                            if (!trackUris.contains(uri)) {
                                trackUris.add(uri);
                                allTracks.add(track);
                            }
                        }
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("mood", moodPrompt);
                result.add("tracks", allTracks);
                result.add("uris", gson.toJsonTree(trackUris));
                result.addProperty("message", "🎧 AI DJ picked " + allTracks.size() + " tracks for: " + moodPrompt);

                return result;
            } catch (Exception e) {
                System.err.println("[Spotify] AI DJ failed: " + e.getMessage());
                JsonObject error = new JsonObject();
                error.addProperty("error", "AI DJ failed: " + e.getMessage());
                return error;
            }
        });
    }

    // ── Playlist Creation ───────────────────────────────

    /**
     * Get the current user's Spotify user ID.
     */
    private String getCurrentUserId() {
        try {
            String token = spotifyApi.getAccessToken();
            java.net.URL url = new java.net.URL("https://api.spotify.com/v1/me");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                String body = new String(conn.getInputStream().readAllBytes());
                JsonObject user = JsonParser.parseString(body).getAsJsonObject();
                return user.get("id").getAsString();
            }
        } catch (Exception e) {
            System.err.println("[Spotify] Failed to get user ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * Create a new playlist for the current user.
     * Returns the playlist JSON (with id, name, uri, error) or null on hard failure.
     *
     * <p>Common 403 causes in Spotify Developer apps:
     * <ul>
     *   <li>The authenticated user is not registered as a test user in the Spotify Developer Dashboard.</li>
     *   <li>The OAuth token was obtained without the {@code playlist-modify-public} or
     *       {@code playlist-modify-private} scopes.</li>
     *   <li>Token has expired and refresh failed.</li>
     * </ul>
     * Re-authenticate via {@code /api/spotify/auth} to obtain a fresh token with all required scopes.
     */
    public JsonObject createPlaylist(String name, String description, boolean isPublic) {
        refreshTokenIfNeeded();
        try {
            String userId = getCurrentUserId();
            if (userId == null) {
                System.err.println("[Spotify] Cannot create playlist: user ID unknown");
                JsonObject err = new JsonObject();
                err.addProperty("error", "Could not determine your Spotify user ID. Please re-authenticate.");
                return err;
            }

            String token = spotifyApi.getAccessToken();
            JsonObject payloadObj = new JsonObject();
            payloadObj.addProperty("name", name);
            payloadObj.addProperty("description", description != null ? description : "");
            payloadObj.addProperty("public", isPublic);
            byte[] bodyBytes = gson.toJson(payloadObj).getBytes(StandardCharsets.UTF_8);

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/users/" + userId + "/playlists"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();

            if (status == 200 || status == 201) {
                JsonObject playlist = JsonParser.parseString(body).getAsJsonObject();
                JsonObject result = new JsonObject();
                result.addProperty("id", playlist.get("id").getAsString());
                result.addProperty("name", playlist.get("name").getAsString());
                result.addProperty("uri", playlist.get("uri").getAsString());
                System.out.println("[Spotify] Created playlist: " + name);
                return result;
            } else if (status == 403) {
                String msg = "Forbidden (403). Your Spotify account may not have permission to create playlists. " +
                        "If your app is in development mode, add your Spotify account as a test user at " +
                        "developer.spotify.com → Dashboard → your app → User Management. " +
                        "Then re-authenticate via /api/spotify/auth.";
                System.err.println("[Spotify] " + msg + " | Response: " + body);
                JsonObject err = new JsonObject();
                err.addProperty("error", msg);
                err.addProperty("statusCode", 403);
                return err;
            } else if (status == 401) {
                System.err.println("[Spotify] Unauthorized (401). Token may be expired. Response: " + body);
                authenticated = false;
                JsonObject err = new JsonObject();
                err.addProperty("error", "Spotify token expired or invalid. Please re-authenticate via /api/spotify/auth.");
                err.addProperty("statusCode", 401);
                return err;
            } else {
                System.err.println("[Spotify] Create playlist failed: HTTP " + status + " | " + body);
                JsonObject err = new JsonObject();
                err.addProperty("error", "Spotify API error " + status + ": " + body);
                err.addProperty("statusCode", status);
                return err;
            }
        } catch (Exception e) {
            System.err.println("[Spotify] Create playlist error: " + e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("error", "Unexpected error: " + e.getMessage());
            return err;
        }
    }

    /**
     * Add tracks to a playlist.
     */
    public boolean addTracksToPlaylist(String playlistId, List<String> trackUris) {
        refreshTokenIfNeeded();
        try {
            String token = spotifyApi.getAccessToken();
            JsonObject payload = new JsonObject();
            JsonArray uris = new JsonArray();
            trackUris.forEach(uris::add);
            payload.add("uris", uris);

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/playlists/" + playlistId + "/tracks"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200 || status == 201) {
                System.out.println("[Spotify] Added " + trackUris.size() + " tracks to playlist " + playlistId);
                return true;
            } else if (status == 403) {
                System.err.println("[Spotify] Add tracks forbidden (403). Ensure the user is a test user or " +
                        "re-authenticate with playlist-modify scopes. Response: " + response.body());
            } else {
                System.err.println("[Spotify] Add tracks failed: HTTP " + status + " | " + response.body());
            }
        } catch (Exception e) {
            System.err.println("[Spotify] Add tracks error: " + e.getMessage());
        }
        return false;
    }

    // ── JSON Helpers ────────────────────────────────────

    private JsonArray playlistsToJson(PlaylistSimplified[] playlists) {
        JsonArray arr = new JsonArray();
        if (playlists == null)
            return arr;
        for (PlaylistSimplified p : playlists) {
            if (p == null || p.getId() == null)
                continue; // skip null entries
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.getId());
            obj.addProperty("name", p.getName() != null ? p.getName() : "Untitled");
            obj.addProperty("uri", p.getUri() != null ? p.getUri() : "");

            // Null-safe track count
            if (p.getTracks() != null) {
                obj.addProperty("trackCount", p.getTracks().getTotal());
            } else {
                obj.addProperty("trackCount", 0);
            }

            // Get cover image
            Image[] images = p.getImages();
            if (images != null && images.length > 0) {
                obj.addProperty("imageUrl", images[0].getUrl());
            } else {
                obj.addProperty("imageUrl", "");
            }

            // Owner
            if (p.getOwner() != null) {
                obj.addProperty("owner", p.getOwner().getDisplayName());
            }

            arr.add(obj);
        }
        return arr;
    }

    private JsonObject trackToJson(Track track) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", track.getId());
        obj.addProperty("name", track.getName());
        obj.addProperty("uri", track.getUri());
        obj.addProperty("duration_ms", track.getDurationMs());

        // Artists
        String artists = Arrays.stream(track.getArtists())
                .map(ArtistSimplified::getName)
                .collect(Collectors.joining(", "));
        obj.addProperty("artists", artists);

        // Album
        if (track.getAlbum() != null) {
            obj.addProperty("album", track.getAlbum().getName());
            Image[] images = track.getAlbum().getImages();
            if (images != null && images.length > 0) {
                obj.addProperty("imageUrl", images[images.length > 1 ? 1 : 0].getUrl());
            } else {
                obj.addProperty("imageUrl", "");
            }
        }

        return obj;
    }
}
