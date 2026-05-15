package com.sentient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Wraps the {@code tailscale} CLI to expose the master device via
 * <a href="https://tailscale.com/kb/1223/funnel">Tailscale Funnel</a>.
 *
 * <p>Detection is best-effort: if the binary is missing we surface that to the
 * UI and the user falls back to the setup docs. We never assume a particular
 * Tailscale auth state; we surface whatever {@code tailscale status} returns.
 *
 * <p>Funnel exposes the master to the public internet. The shared-password auth
 * layer ({@link AuthService}) MUST be enabled before turning Funnel on, or the
 * whole app is reachable by anyone who finds the URL.
 */
public class TailscaleService {

    /** Reasonable default port — matches WebServer's default. */
    public static final int DEFAULT_PORT = 7070;

    /** Locate the tailscale binary. */
    public String findBinary() {
        String home = System.getProperty("user.home");
        List<String> candidates = Arrays.asList(
                "/usr/bin/tailscale",
                "/usr/local/bin/tailscale",
                "/opt/homebrew/bin/tailscale",
                "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
                home + "/.local/bin/tailscale");
        for (String c : candidates) {
            if (Files.isExecutable(Paths.get(c))) return c;
        }
        try {
            Process p = new ProcessBuilder("which", "tailscale").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !out.isEmpty() && Files.isExecutable(Paths.get(out))) return out;
        } catch (Exception ignored) {}
        return null;
    }

    public boolean isInstalled() { return findBinary() != null; }

    /** Returns {installed, loggedIn, dnsName?, funnelEnabled, funnelUrl?, error?}. */
    public JsonObject status(int port) {
        JsonObject out = new JsonObject();
        String bin = findBinary();
        out.addProperty("installed", bin != null);
        if (bin == null) {
            out.addProperty("error", "Tailscale not installed. See SETUP_OPENCLAW.md or https://tailscale.com/download.");
            return out;
        }
        // `tailscale status --json` tells us if we're logged in and our DNS name.
        ProcessResult login = run(bin, "status", "--json");
        String dnsName = null;
        if (login.exitCode == 0) {
            try {
                JsonObject st = JsonParser.parseString(login.stdout).getAsJsonObject();
                out.addProperty("loggedIn", true);
                if (st.has("Self") && st.get("Self").isJsonObject()) {
                    JsonObject self = st.getAsJsonObject("Self");
                    if (self.has("DNSName")) {
                        dnsName = self.get("DNSName").getAsString();
                        if (dnsName.endsWith(".")) dnsName = dnsName.substring(0, dnsName.length() - 1);
                        out.addProperty("dnsName", dnsName);
                    }
                }
            } catch (Exception e) {
                out.addProperty("loggedIn", false);
                out.addProperty("error", "Could not parse tailscale status: " + e.getMessage());
            }
        } else {
            out.addProperty("loggedIn", false);
            out.addProperty("error", "Tailscale not logged in. Run `tailscale up` first.");
        }
        // Funnel status. Newer tailscale supports `funnel status --json`.
        ProcessResult fn = run(bin, "funnel", "status", "--json");
        boolean funnelEnabled = false;
        if (fn.exitCode == 0 && !fn.stdout.isBlank()) {
            try {
                JsonElement el = JsonParser.parseString(fn.stdout);
                if (el.isJsonObject()) {
                    JsonObject fnObj = el.getAsJsonObject();
                    if (fnObj.has("AllowFunnel") && fnObj.get("AllowFunnel").isJsonObject()) {
                        JsonObject allow = fnObj.getAsJsonObject("AllowFunnel");
                        for (String k : allow.keySet()) {
                            if (allow.get(k).getAsBoolean()) { funnelEnabled = true; break; }
                        }
                    }
                }
            } catch (Exception ignored) { /* older tailscale prints non-JSON */ }
        }
        out.addProperty("funnelEnabled", funnelEnabled);
        if (funnelEnabled && dnsName != null) {
            out.addProperty("funnelUrl", "https://" + dnsName + (port == 443 ? "" : ""));
        } else if (dnsName != null) {
            out.addProperty("hintUrl", "https://" + dnsName + "/");
        }
        out.addProperty("port", port);
        return out;
    }

    /**
     * Bring up Funnel for the given local port. Returns the URL or null on failure.
     */
    public JsonObject enableFunnel(int port) {
        JsonObject out = new JsonObject();
        String bin = findBinary();
        if (bin == null) {
            out.addProperty("error", "Tailscale binary not found.");
            return out;
        }
        // Two-step: 1) serve the local port over HTTPS, 2) open funnel on it.
        // Newer tailscale lets you do this with one command:
        //   tailscale funnel --bg --https=443 7070
        ProcessResult start = run(bin, "funnel", "--bg", "--https=443", String.valueOf(port));
        if (start.exitCode != 0) {
            // Fall back to legacy two-command flow.
            ProcessResult serve = run(bin, "serve", "--bg", "--https=443", "http://localhost:" + port);
            if (serve.exitCode != 0) {
                out.addProperty("error", "tailscale funnel failed: " + start.stderr + " | "
                        + serve.stderr);
                return out;
            }
            ProcessResult fn = run(bin, "funnel", "443", "on");
            if (fn.exitCode != 0) {
                out.addProperty("error", "tailscale funnel failed: " + fn.stderr);
                return out;
            }
        }
        JsonObject st = status(port);
        st.addProperty("success", true);
        return st;
    }

    public JsonObject disableFunnel() {
        JsonObject out = new JsonObject();
        String bin = findBinary();
        if (bin == null) {
            out.addProperty("error", "Tailscale binary not found.");
            return out;
        }
        // Newest CLI: `tailscale funnel reset`. Older: `tailscale funnel 443 off`.
        ProcessResult reset = run(bin, "funnel", "reset");
        if (reset.exitCode != 0) {
            ProcessResult off = run(bin, "funnel", "443", "off");
            if (off.exitCode != 0) {
                out.addProperty("error", "tailscale funnel disable failed: " + reset.stderr + " | " + off.stderr);
                return out;
            }
        }
        run(bin, "serve", "reset");
        out.addProperty("success", true);
        return out;
    }

    private ProcessResult run(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new ProcessResult(code, stdout.trim(), stderr.trim());
        } catch (Exception e) {
            return new ProcessResult(-1, "", e.getMessage() == null ? "exception" : e.getMessage());
        }
    }

    private static class ProcessResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        ProcessResult(int c, String o, String e) { exitCode = c; stdout = o; stderr = e; }
    }
}
