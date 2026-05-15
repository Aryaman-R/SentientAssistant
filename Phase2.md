Here is your official, updated Phase 2: Senses & Survival blueprint. I have attached tailored engineering notes to each task summarizing exactly what we discussed, the hardware constraints of your Pi 3B+, and the specific code architectures you need to use.

Phase 2: Senses & Survival
1. The Migration & Portability
The Goal: Run the Java app on the Raspberry Pi and make the entire unit portable like a Cyberdeck tablet.

Your Engineering Notes:

The Lag: Because the Pi 3B+ only has 1GB of RAM and weak graphics acceleration, full-screen JavaFX will be laggy. Strip out complex CSS (drop-shadows, animations, gradients) and stick to flat, simple UI elements.

The Display: Use your Amazon Fire Tablet as the screen. Buy a cheap $15 USB Capture Card and an OTG adapter. Plug the Pi's HDMI into the capture card, plug the card into the tablet, and use the "USB Camera" Android app. This gives you a lag-free, hardwired monitor.

The Battery: You must power the Pi using a power bank capable of delivering a strict 5 Volts and 2.5 Amps. Anything less, and the Pi will crash when the SSD spins up.

2. The Mouth (Piper TTS)
The Goal: Give the AI a hyper-fast, offline voice that sounds great on low-power hardware.

Your Engineering Notes:

No Files: Do not save .wav files to your SSD. Constantly writing and deleting files creates latency and wears out the drive.

The Pipeline: Use Linux "Piping". Your Java code will execute a background terminal command that pipes the text to Piper, and pipes the raw audio bytes directly to the speaker: echo "Hello" | piper --model voice.onnx --output-raw | aplay. This plays instantly.

3. The Ears (Wake-word + Cloud STT)
The Goal: Talk to the AI naturally from across the room, and have it stop listening automatically when you finish speaking.

Your Engineering Notes:

Multithreading (Crucial): You cannot put the Picovoice listening loop in your main Java code, or the whole UI will freeze. You must wrap the while(true) microphone loop inside a background Thread.

The JavaFX Golden Rule: The background thread is not allowed to update your UI. If you want the screen to say "Listening...", the background thread must use Platform.runLater(() -> { label.setText("Listening..."); });.

The Silence Stopwatch: To know when to stop recording, your Java code must constantly check the microphone's Loudness Score (RMS). If the score drops below a certain threshold for 3 solid seconds, Java breaks the recording loop, saves the 5-second command over a single command.wav file (overwriting it each time), and fires it to the Groq API.

4. The Eyes (On-Demand Vision)
The Goal: Give the AI contextual awareness using the Gemini 1.5 Flash Vision API without draining battery or limits.

Your Engineering Notes:

The Pivot: Because you are building a portable tablet, the camera will be moving in your hands. We are completely scrapping the "background motion watchdog" idea, as it would trigger a false alarm every time you shifted in your chair.

On-Demand Only: Instead, link the camera strictly to a UI button or a specific voice phrase ("Gemini, look at this"). When triggered, Java wakes the webcam, snaps one single photo, sends it to Google, and immediately turns the webcam off.

5. The Survival Instinct (Offline Fallback)
The Goal: Keep basic commands and core personality alive if you take your tablet away from your Wi-Fi router.

Your Engineering Notes:

Fail Fast: Update your Java HTTP client to have a strict 2-second or 3-second timeout. If Google doesn't answer immediately, Java assumes the internet is dead.

The Instant Router: If the timeout triggers, intercept the text command and use hardcoded if statements (e.g., if (text.contains("timer"))) to instantly execute local hardware tasks without needing an AI brain.

The Tiny Brain: If it's a conversational question, route it to your local Ollama instance running the ultra-lightweight Qwen 2.5 (0.5B) model.