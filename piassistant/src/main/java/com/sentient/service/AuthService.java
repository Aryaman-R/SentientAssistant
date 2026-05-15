package com.sentient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared-password device auth. One password is set by the owner; each device that
 * logs in receives a random opaque token that gets stored in localStorage. The
 * token must accompany every {@code /api/*} request and every WebSocket connect.
 *
 * <p>If no password is set, auth is disabled — the app behaves the same as
 * before this layer was added (LAN-only trust model).
 */
public class AuthService {

    private static final Path CONFIG = Paths.get(System.getProperty("user.home"), ".sentient_auth.json");
    private static final SecureRandom RNG = new SecureRandom();

    private volatile String passwordHash = ""; // sha256-hex
    private volatile String salt = "";          // hex
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    public AuthService() {
        loadConfig();
    }

    public boolean isPasswordSet() { return !passwordHash.isBlank(); }

    public boolean isAuthRequired() { return isPasswordSet(); }

    public boolean checkPassword(String password) {
        if (!isPasswordSet()) return true; // no gate
        if (password == null || password.isEmpty()) return false;
        return constantTimeEquals(hashPassword(password, salt), passwordHash);
    }

    /**
     * Sets or replaces the shared password. Passing null or empty disables auth and
     * revokes all tokens.
     */
    public synchronized void setPassword(String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            passwordHash = "";
            salt = "";
            tokens.clear();
            persist();
            return;
        }
        byte[] s = new byte[16];
        RNG.nextBytes(s);
        salt = HexFormat.of().formatHex(s);
        passwordHash = hashPassword(newPassword, salt);
        // Revoke existing tokens — devices must re-login with the new password.
        tokens.clear();
        persist();
    }

    /** Issue a new device token. Caller guarantees password already matches. */
    public String issueToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        tokens.add(t);
        persist();
        return t;
    }

    public boolean isValidToken(String token) {
        if (!isAuthRequired()) return true; // open mode
        return token != null && !token.isEmpty() && tokens.contains(token);
    }

    public synchronized void revokeToken(String token) {
        if (token == null) return;
        tokens.remove(token);
        persist();
    }

    public synchronized void revokeAll() {
        tokens.clear();
        persist();
    }

    public JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("required", isAuthRequired());
        o.addProperty("activeTokens", tokens.size());
        return o;
    }

    // ── Persistence ─────────────────────────────────────

    private void loadConfig() {
        try {
            if (!Files.exists(CONFIG)) return;
            String raw = Files.readString(CONFIG).trim();
            if (raw.isEmpty()) return;
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            if (o.has("passwordHash")) passwordHash = o.get("passwordHash").getAsString();
            if (o.has("salt")) salt = o.get("salt").getAsString();
            if (o.has("tokens") && o.get("tokens").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("tokens");
                for (JsonElement el : arr) tokens.add(el.getAsString());
            }
        } catch (Exception e) {
            System.err.println("[Auth] Could not load config: " + e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("passwordHash", passwordHash);
            o.addProperty("salt", salt);
            JsonArray arr = new JsonArray();
            for (String t : tokens) arr.add(t);
            o.add("tokens", arr);
            Files.writeString(CONFIG, o.toString());
            try { Files.setPosixFilePermissions(CONFIG, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[Auth] Could not persist config: " + e.getMessage());
        }
    }

    private static String hashPassword(String password, String saltHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(saltHex.getBytes(StandardCharsets.UTF_8));
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
