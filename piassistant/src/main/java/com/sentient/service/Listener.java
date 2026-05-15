package com.sentient.service;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.vosk.LogLevel;
import org.vosk.Recognizer;
import org.vosk.LibVosk;
import org.vosk.Model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sentient.util.EnvLoader;

/**
 * Two-phase voice listener:
 * Phase 1 – Wake word detection (matches "Jarvis" and near-homophones)
 * Phase 2 – Full transcription until 2 seconds of silence, then auto-send
 */
public class Listener {

    // ── Callback interface ──────────────────────────────
    public interface ListenerCallback {
        /** Called on every partial transcription update during Phase 2. */
        void onPartialResult(String text);

        /**
         * Called when the user pauses for 2+ seconds — contains the final
         * transcription.
         */
        void onFinalResult(String text);

        /** Called when the listening state changes (true = actively transcribing). */
        void onListeningStateChanged(boolean listening);
    }

    // ── Config ──────────────────────────────────────────
    private static final String MODEL_PATH = EnvLoader.get("VOSK_MODEL_PATH", "vosk-model-small-en-us-0.15");
    private static final long SILENCE_TIMEOUT_MS = 2000;

    // ── Audio ───────────────────────────────────────────
    private final AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
    private final DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
    private final TargetDataLine microphone;

    // ── State ───────────────────────────────────────────
    private volatile ListenerCallback callback;
    private volatile boolean running = false;
    private volatile boolean manualTrigger = false;
    private volatile boolean ttsSpeaking = false;
    private volatile boolean cancelRequested = false;
    /**
     * When true the listener loop is dormant — wake-word detection is skipped and any
     * in-flight transcription is cancelled. Used so that no UI client wastes mic time
     * unless a tab actually wants the server-side wake word.
     */
    private volatile boolean paused = true;
    private Thread listenerThread;
    private final Gson gson = new Gson();

    public Listener() throws LineUnavailableException {
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format);
        microphone.start();
    }

    public void setCallback(ListenerCallback callback) {
        this.callback = callback;
    }

    // ── Public API ──────────────────────────────────────

    /** Start the background listener loop (wake word → transcribe → repeat). */
    public void start() {
        if (running)
            return;
        running = true;
        LibVosk.setLogLevel(LogLevel.WARNINGS);

        listenerThread = new Thread(this::listenerLoop, "VoiceListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /** Skip wake word — jump straight to transcription (for the record button). */
    public void startListening() {
        manualTrigger = true;
    }

    /** Cancel any active or pending transcription and return to wake word mode. */
    public void cancelTranscription() {
        cancelRequested = true;
        manualTrigger = false;
        System.out.println("[Listener] Transcription cancelled.");
    }

    /** Signal that TTS is playing — listener will ignore audio. */
    public void setTtsSpeaking(boolean speaking) {
        this.ttsSpeaking = speaking;
        if (!speaking) {
            microphone.flush(); // discard residual TTS echo in the buffer
        }
        System.out.println("[Listener] TTS speaking = " + speaking);
    }

    /** Pause or resume the wake-word / transcription loop without tearing down the audio line. */
    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        if (paused) {
            cancelRequested = true; // bail out of any active transcription
            manualTrigger = false;
            microphone.flush();
            System.out.println("[Listener] Paused.");
        } else {
            microphone.flush();
            System.out.println("[Listener] Resumed.");
        }
    }

    public boolean isPaused() { return paused; }

    /** Shut down the listener. */
    public void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        microphone.close();
    }

    // ── Core loop ───────────────────────────────────────

    private void listenerLoop() {
        try (Model model = new Model(MODEL_PATH)) {
            while (running) {
                if (paused && !manualTrigger) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    continue;
                }
                if (manualTrigger) {
                    manualTrigger = false;
                    System.out.println("[Listener] Manual trigger — skipping wake word, going to transcribe.");
                    transcribe(model);
                } else {
                    System.out.println("[Listener] Entering wake word detection mode...");
                    waitForWakeWord(model);
                    if (!paused) transcribe(model);
                }
            }
        } catch (Exception e) {
            System.err.println("[Listener] Listener loop crashed!");
            e.printStackTrace();
        }
    }

    // ── Phase 1: Wake word ──────────────────────────────

    private void waitForWakeWord(Model model) {
        cancelRequested = false;
        // Flush any stale audio from the mic buffer before starting detection
        microphone.flush();

        try (Recognizer recognizer = new Recognizer(model, 16000.0f)) {
            byte[] buffer = new byte[4096];

            while (running && !manualTrigger && !cancelRequested && !paused) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (ttsSpeaking)
                    continue; // ignore audio while TTS is playing

                if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                    String text = extractText(recognizer.getResult());
                    if (!text.isEmpty()) {
                        System.out.println("[Listener] Wake word hearing: '" + text + "'");
                    }
                    if (isWakeWord(text)) {
                        System.out.println("[Listener] Wake word detected: " + text);
                        return;
                    }
                } else {
                    String partial = extractPartial(recognizer.getPartialResult());
                    if (!partial.isEmpty()) {
                        System.out.println("[Listener] Wake word partial: '" + partial + "'");
                    }
                    if (isWakeWord(partial)) {
                        System.out.println("[Listener] Wake word detected (partial): " + partial);
                        return;
                    }
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("[Listener] Failed to create wake word recognizer: " + e.getMessage());
        }
    }

    /**
     * Check if the transcribed text contains something that sounds like "jarvis".
     * Vosk's small EN model commonly mishears the J/V — accept a few near-spellings.
     */
    public static boolean isWakeWord(String text) {
        if (text == null || text.isEmpty())
            return false;
        String lower = text.toLowerCase();
        return lower.contains("jarvis") || lower.contains("jervis")
                || lower.contains("jarvi") || lower.contains("harvis")
                || lower.contains("charvis");
    }

    // ── Phase 2: Full transcription ─────────────────────

    private void transcribe(Model model) {
        cancelRequested = false;
        fireStateChanged(true);

        try (Recognizer recognizer = new Recognizer(model, 16000.0f)) {
            byte[] buffer = new byte[4096];
            StringBuilder accumulated = new StringBuilder();
            long lastSpeechTime = System.currentTimeMillis();

            while (running && !cancelRequested && !paused) {
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (ttsSpeaking)
                    continue; // TTS should be stopped before transcription

                if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                    // End of utterance — append finalized text
                    String text = extractText(recognizer.getResult());
                    if (!text.isEmpty()) {
                        if (accumulated.length() > 0)
                            accumulated.append(" ");
                        accumulated.append(text);
                        lastSpeechTime = System.currentTimeMillis();
                        firePartial(accumulated.toString());
                    }
                } else {
                    // Partial result — show live preview
                    String partial = extractPartial(recognizer.getPartialResult());
                    if (!partial.isEmpty()) {
                        lastSpeechTime = System.currentTimeMillis();
                        String preview = accumulated.length() > 0
                                ? accumulated + " " + partial
                                : partial;
                        firePartial(preview);
                    }
                }

                // Check for 3-second silence with accumulated text
                if (accumulated.length() > 0
                        && System.currentTimeMillis() - lastSpeechTime >= SILENCE_TIMEOUT_MS) {

                    // Grab any remaining final result
                    try {
                        String finalBit = extractText(recognizer.getFinalResult());
                        if (!finalBit.isEmpty()) {
                            if (accumulated.length() > 0)
                                accumulated.append(" ");
                            accumulated.append(finalBit);
                        }
                    } catch (Exception ignored) {
                        // getFinalResult may throw — safe to ignore
                    }

                    System.out.println("[Listener] Silence timeout — sending: " + accumulated);
                    fireFinal(accumulated.toString().trim());
                    fireStateChanged(false);
                    return; // back to Phase 1
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("[Listener] Failed to create recognizer: " + e.getMessage());
        }

        fireStateChanged(false);
    }

    // ── Helpers ─────────────────────────────────────────

    private String extractText(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            return obj.has("text") ? obj.get("text").getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String extractPartial(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            return obj.has("partial") ? obj.get("partial").getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void firePartial(String text) {
        ListenerCallback cb = callback;
        if (cb != null)
            cb.onPartialResult(text);
    }

    private void fireFinal(String text) {
        ListenerCallback cb = callback;
        if (cb != null)
            cb.onFinalResult(text);
    }

    private void fireStateChanged(boolean listening) {
        ListenerCallback cb = callback;
        if (cb != null)
            cb.onListeningStateChanged(listening);
    }
}