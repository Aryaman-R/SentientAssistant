package com.sentient.service;

import org.pitest.voices.Chorus;
import org.pitest.voices.ChorusConfig;
import org.pitest.voices.Voice;
import org.pitest.voices.download.Models;
import org.pitest.voices.audio.Audio;
import org.pitest.voices.uk.EnUkDictionary;
import static org.pitest.voices.ChorusConfig.chorusConfig;

import java.io.File;
import javax.sound.sampled.*;

/**
 * Text-to-Speech using Chorus/Piper ONNX voices.
 * Audio playback uses javax.sound.sampled (no JavaFX dependency).
 */
public class TextToSpeech {

    private static final ChorusConfig CONFIG = chorusConfig(EnUkDictionary.en_uk());

    // Cached Chorus + Voice — model loading is ~2-3s, only done once
    private static Chorus chorus;
    private static Voice voice;

    // Active playback controls for stop
    private static volatile Clip activeClip;
    private static volatile SourceDataLine activeLine;
    private static volatile boolean stopRequested = false;

    private static synchronized void ensureInitialized() {
        if (chorus == null) {
            System.out.println("[TTS] Initializing Chorus (first time)...");
            chorus = new Chorus(CONFIG);
            voice = chorus.voice(Models.aruMedium(2)).withSpeed(1.0f);
            System.out.println("[TTS] Chorus ready.");
        }
    }

    /**
     * Result of generating audio — contains the file and its duration in ms.
     */
    public static class AudioResult {
        public final File file;
        public final long durationMs;

        AudioResult(File file, long durationMs) {
            this.file = file;
            this.durationMs = durationMs;
        }
    }

    /**
     * Generate audio file from text. Returns the file and its duration in ms.
     * Call this on a background thread.
     */
    public AudioResult generateAudio(String text) {
        String cleanText = stripMarkdown(text);
        if (cleanText.isEmpty())
            return null;

        ensureInitialized();
        Audio audio = voice.say(cleanText);

        try {
            File tempFile = File.createTempFile("tts_audio_", ".wav");
            tempFile.deleteOnExit();
            audio.save(tempFile.toPath());

            long durationMs = getWavDurationMs(tempFile);
            System.out.println("[TTS] Audio: " + durationMs + "ms, file: " + tempFile.getName());
            return new AudioResult(tempFile, durationMs);
        } catch (Exception e) {
            System.err.println("[TTS] Error saving audio: " + e.getMessage());
            return null;
        }
    }

    /**
     * Play a WAV file using javax.sound.sampled.Clip.
     * Blocks until playback finishes or stopPlayback() is called.
     */
    public void playFile(File wavFile) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile);
            AudioFormat format = ais.getFormat();
            System.out.println("[TTS] Playing: format=" + format.getEncoding()
                    + " rate=" + format.getSampleRate()
                    + " bits=" + format.getSampleSizeInBits()
                    + " ch=" + format.getChannels());

            // Try SourceDataLine (more reliable than Clip on some systems)
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
            if (AudioSystem.isLineSupported(lineInfo)) {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(lineInfo);
                activeLine = line;
                activeClip = null;
                stopRequested = false;
                line.open(format);
                line.start();
                System.out.println("[TTS] SourceDataLine playback started");

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = ais.read(buffer)) != -1) {
                    if (stopRequested)
                        break;
                    line.write(buffer, 0, bytesRead);
                }
                if (!stopRequested)
                    line.drain();
                line.stop();
                line.close();
                ais.close();
                activeLine = null;
                System.out.println("[TTS] SourceDataLine playback " + (stopRequested ? "stopped" : "finished"));
                return;
            }

            // Fallback to Clip
            System.out.println("[TTS] SourceDataLine not supported, trying Clip...");
            Clip clip = AudioSystem.getClip();
            activeClip = clip;

            clip.open(ais);

            final Object lock = new Object();
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            });

            clip.start();
            System.out.println("[TTS] Clip playback started, length=" + clip.getMicrosecondLength() / 1000 + "ms");

            synchronized (lock) {
                while (clip.isRunning()) {
                    lock.wait();
                }
            }

            clip.close();
            ais.close();
            activeClip = null;
            System.out.println("[TTS] Clip playback finished");
        } catch (Exception e) {
            System.err.println("[TTS] Playback error: " + e.getMessage());
            e.printStackTrace();
            activeClip = null;
        }
    }

    /**
     * Stop any active TTS playback immediately.
     * Safe to call from any thread.
     */
    public static void stopPlayback() {
        stopRequested = true;

        // Stop SourceDataLine if active
        SourceDataLine line = activeLine;
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
            }
            activeLine = null;
            System.out.println("[TTS] SourceDataLine playback stopped.");
        }

        // Stop Clip if active
        Clip clip = activeClip;
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception ignored) {
            }
            activeClip = null;
            System.out.println("[TTS] Clip playback stopped.");
        }
    }

    /** Get WAV file duration in milliseconds by reading its header. */
    private long getWavDurationMs(File wavFile) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile)) {
            AudioFormat format = ais.getFormat();
            long frames = ais.getFrameLength();
            return (long) ((frames / format.getFrameRate()) * 1000);
        } catch (Exception e) {
            return 3000; // fallback ~3s
        }
    }

    static String stripMarkdown(String text) {
        return text
                .replaceAll("\\*+", "")
                .replaceAll("#+\\s*", "")
                .replaceAll("`+", "")
                .replaceAll("\\[|\\]|\\(|\\)", "")
                .replaceAll("(?m)^[-*>]\\s+", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /** Call on shutdown to free ONNX resources. */
    public static synchronized void shutdown() {
        stopPlayback();
        if (chorus != null) {
            chorus.close();
            chorus = null;
            voice = null;
        }
    }
}
