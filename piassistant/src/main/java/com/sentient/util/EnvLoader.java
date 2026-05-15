package com.sentient.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads variables from a .env file and makes them available via
 * {@link #get(String)}.
 * Falls back to real system environment variables if the .env file is missing
 * or a key isn't defined in it.
 */
public class EnvLoader {

    private static final java.util.Map<String, String> envVars = new java.util.LinkedHashMap<>();
    private static boolean loaded = false;

    /** Load the .env file once (idempotent). */
    public static synchronized void load() {
        if (loaded)
            return;
        loaded = true;

        // Walk up from CWD to find the .env file (handles running from piassistant/)
        Path dir = Paths.get(System.getProperty("user.dir"));
        for (int i = 0; i < 3; i++) {
            Path envFile = dir.resolve(".env");
            if (envFile.toFile().exists()) {
                parseFile(envFile.toFile().getAbsolutePath());
                return;
            }
            dir = dir.getParent();
            if (dir == null)
                break;
        }
        System.out.println("[EnvLoader] No .env file found — using system environment variables only.");
    }

    private static void parseFile(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    envVars.put(key, value);
                }
            }
            System.out.println("[EnvLoader] Loaded " + envVars.size() + " vars from " + path);
        } catch (IOException e) {
            System.err.println("[EnvLoader] Failed to read .env: " + e.getMessage());
        }
    }

    /**
     * Get an environment variable — checks .env first, falls back to system env.
     * Returns null if not found in either.
     */
    public static String get(String key) {
        load(); // ensure loaded
        String value = envVars.get(key);
        if (value != null)
            return value;
        return System.getenv(key);
    }

    /**
     * Get an environment variable with a default fallback.
     */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Return all keys that were loaded from the .env file.
     * System environment variables are not included in this set.
     */
    public static java.util.Set<String> allKeys() {
        load();
        return java.util.Collections.unmodifiableSet(envVars.keySet());
    }
}
