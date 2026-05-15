# Sentient Assistant — Architecture

A personal-assistant web application served by an embedded Java/Javalin web server. Originally a JavaFX desktop app, now a browser-served UI on port 7070. Designed for a "master device" install — one machine runs the server, every other device (phone, laptop, tablet) opens it in a browser.

This document is the reference for what exists today (the `Web-Server` branch). Continuity issues found during audit are listed at the bottom.

## High-level topology

```
┌─────────────────────────────┐                ┌────────────────────────┐
│  Master device (Pi / Mac)   │                │  Client device         │
│                             │                │  (any browser)         │
│  ┌─────────────────────┐    │   ws://:7070   │                        │
│  │  Java app (Javalin) │◄───┼────────────────┤  index.html + app.js   │
│  │  + Listener (mic)   │    │   http://:7070 │  + Spotify Web SDK     │
│  │  + Piper TTS        │    │                │                        │
│  │  + Groq HTTP client │    │                └────────────────────────┘
│  └──────────┬──────────┘    │
│             │               │   ┌─── Groq API (cloud)
│             ├───────────────┼───┤
│             │               │   ├─── Spotify API
│             │               │   ├─── Google Tasks / Calendar
│             │               │   └─── User-defined webhooks
│  user_profile.json (state)  │
└─────────────────────────────┘
```

Once the OpenClaw integration lands (phase 3 of the current branch), the Groq HTTP client is replaced/augmented by a call to a locally-installed OpenClaw gateway at `http://127.0.0.1:18789/v1/chat/completions`. OpenClaw, in turn, fans out to Anthropic/OpenAI/Google/etc. and exposes Composio tools via MCP.

## Module map

### `com.sentient` (entry)

| File | Role |
|---|---|
| `Launcher.java` | Trivial wrapper — calls `App.main()`. Exists so the maven-shade and javafx-maven plugins both point at one class. |
| `App.java` | Boots `WebServer`, installs a shutdown hook. |
| `WebServer.java` | The whole HTTP + WebSocket surface. ~1100 lines. Mounts static files from `/web` (classpath), handles all REST routes, dispatches WS messages, owns the `Listener` (mic) and orchestrates the TTS streaming. |

### `com.sentient.service`

| File | Role |
|---|---|
| `GroqService.java` | The brain. Three model "routes" — ROUTER (`llama-3.1-8b-instant`) decides CHAT vs THINK; CHAT (`llama-3.3-70b-versatile`) handles conversation + command emission; THINK (`qwen/qwen3-32b`) handles reasoning. Vision via `meta-llama/llama-4-scout-17b-16e-instruct`. Maintains a 20-message rolling history for the CHAT path only. PDF/text attachments are extracted into the prompt; images are sent as base64 to the vision model. |
| `OpenAIService.java` | Legacy fallback. Unused on the chat path today. |
| `GeminiService.java` | Legacy fallback. Unused on the chat path today. |
| `SpotifyService.java` | Wraps the Spotify Web API SDK. Handles OAuth, playback control, AI DJ (calls back into Groq to generate search queries), playlist CRUD. |
| `GoogleTasksService.java` | Google Tasks OAuth + push/pull bridge between local task lists and Google. |
| `GoogleCalendarService.java` | Same shape as GoogleTasksService for Calendar. Shares the OAuth file with Tasks. |
| `AutomationService.java` | Generic webhook dispatcher. User registers `name → URL`; AI can emit `[CMD:AUTOMATE:name]` to trigger. |
| `CameraService.java` | OpenCV camera capture (used by vision flow). |
| `Listener.java` | Vosk-based always-on speech recognition. Emits partial/final transcripts on a callback. Gated by a `ttsSpeaking` flag so the system doesn't hear its own voice. |
| `TextToSpeech.java` | Piper voice synthesis (chorus + ONNX runtime). Returns a WAV file that the WebServer broadcasts as base64 over WS while also playing locally. |

### `com.sentient.util`

| File | Role |
|---|---|
| `ProfileManager.java` | Singleton owning `user_profile.json`. Includes a migration path from the old flat `tasks: [...]` shape to the new `taskLists: [...]` shape. Auto-saves on every mutation. |
| `EnvLoader.java` | Reads `.env` (walks up 3 directories from CWD) into a map, falls back to `System.getenv` for unknown keys. |

### `com.sentient.view`

| File | Role |
|---|---|
| `DashboardView.java` | Dead code — leftover JavaFX view from the pre-web iteration. Not invoked by `App.java`. |

### `src/main/resources/web` (frontend)

| File | Role |
|---|---|
| `index.html` | Static layout with 7 panels (HOME/STUDY/TASKS/CALENDAR/SLEEP/SPOTIFY/SETTINGS). Each panel's nav button toggles `display:none`. |
| `app.js` | ~2400 LOC, no framework. Manages: WS connection (with auto-reconnect + keep-alive pings), the per-panel logic, settings persisted in `localStorage`, the focus timer, the Spotify Web Playback SDK, the calendar grid renderer, and chat rendering with markdown via `marked`. |
| `styles.css` | ~2300 LOC. Theme variables, the panel-flex grid, the sidebar. |

## Runtime data flow — a single chat turn

```
browser                       WebServer.java                    GroqService           TTS / WS
   │                                │                                │                   │
   │  WS {type:"chat",text:...}     │                                │                   │
   ├───────────────────────────────►│                                │                   │
   │                                │  processCommand(prompt,...)    │                   │
   │                                ├───────────────────────────────►│                   │
   │                                │                                │  Groq API (HTTPS) │
   │                                │                                ├──────────────────►│
   │                                │                                │◄─────── response  │
   │                                │◄───────── completion           │                   │
   │                                │  extractCommands(...)          │                   │
   │                                │  ── server-side commands:      │                   │
   │                                │     CREATE_PLAYLIST,           │                   │
   │                                │     ADD_EVENT, ADD_TASK,       │                   │
   │                                │     REMOVE_TASK, AUTOMATE,     │                   │
   │                                │     ADD/REMOVE_COMMITMENT      │                   │
   │                                │  ── client-side commands       │                   │
   │                                │     broadcast as type:"command"│                   │
   │  WS {type:"command",action:...}│                                │                   │
   │◄───────────────────────────────┤                                │                   │
   │                                │       TextToSpeech.generate()  │                   │
   │                                ├──────────────────────────────────────────────────►│
   │                                │       split words, broadcast chat_word per delay  │
   │  WS {type:"chat_word",word:..} │                                                   │
   │◄───────────────────────────────┤                                                    │
   │  WS {type:"tts_audio",         │                                                    │
   │       audioData: base64WAV}    │                                                    │
   │◄───────────────────────────────┤                                                    │
   │  WS {type:"chat_done"}         │                                                    │
   │◄───────────────────────────────┤                                                    │
```

If the AI included `[CMD:CONTINUE_CONVERSATION]`, after the audio finishes the server either:
- Restarts the local `Listener` (when `playOnServer=true`), or
- Broadcasts `{type:"web_record"}` so the browser starts its own `webkitSpeechRecognition`.

## WebSocket message contract

### Client → Server

| `type` | Payload | Meaning |
|---|---|---|
| `init` | `sessionId` | New browser tab connecting. Triggers session-profile extraction + history clear when the ID changes. |
| `chat` | `text`, `source?` (`"web"` skips server-side audio playback), `modelOverride?` (`AUTO`/`CHAT`/`THINK`), `image?` (base64 data URL), `fileName?`, `fileType?` | A user message. |
| `record` | — | Start the server-side mic listener. |
| `stop` | — | Cancel an in-flight response (stops streaming text + audio). |
| `ping` | — | Keep-alive. |

### Server → Client (all WS clients via broadcast)

| `type` | Payload | Meaning |
|---|---|---|
| `system` | `text` | A system message (e.g. welcome banner). |
| `voice_partial` | `text` | Live mic transcript while listening. |
| `voice_final` | `text` | Final transcript — auto-sent as chat. |
| `voice_state` | `listening: bool` | Mic state changed. |
| `chat_word` | `word` | One word of streamed AI output. |
| `chat_done` | — | End of message. |
| `tts_audio` | `audioData` (base64 WAV) | Audio for the just-streamed message. |
| `command` | `action`, `param?`, plus action-specific extras | Client-side command (panel switch, timer, etc.). |
| `web_record` | — | Tells the browser to start its own SpeechRecognition. |

## REST endpoints (selected)

All mounted in `WebServer.setupRestEndpoints`.

- Profile: `GET/PUT /api/profile`
- Tasks: `/api/tasklists`, `/api/tasklists/{name}`, `/api/tasklists/{name}/tasks`, plus legacy flat `/api/tasks`
- Commitments: `/api/commitments`, `/api/commitments/{name}`
- Spotify: `auth`, `callback`, `status`, `token`, `playlists`, `featured`, `playlist/{id}/tracks`, `search`, `play`, `pause`, `resume`, `skip`, `previous`, `playback`, `ai-dj`, `devices`, `transfer`, `playlist/create`, `playlist/{id}/tracks` (POST)
- Automation: `GET /api/automation`, `POST /api/automation/register`, `POST /api/automation/trigger`
- Google Tasks: `status`, `auth`, `callback`, `push`, `pull`
- Google Calendar: `refresh-auth`, `status`, `events` (GET/POST), `events/{id}` (DELETE/PUT)

## Persistent state

| File | Owner | Purpose |
|---|---|---|
| `piassistant/user_profile.json` | `ProfileManager` | All user state: profile fields, taskLists, commitments, alarms, events. Auto-saved. |
| `piassistant/.env` | `EnvLoader` | API keys (`GROQ_API_KEY`, `SPOTIFY_CLIENT_ID/SECRET`, `GOOGLE_CLIENT_ID/SECRET`, etc.). |
| `piassistant/google_auth.json` (created at runtime) | Google services | OAuth tokens for Tasks + Calendar. |
| `piassistant/spotify_token.json` (created at runtime) | Spotify service | OAuth tokens. |

## Build & run

- Source: Java 17, Maven
- Main entry: `com.sentient.Launcher`
- Build a fat jar: `mvn package` → `piassistant/target/sentient-assistant-1.0-SNAPSHOT.jar`
- Run: `java -jar piassistant/target/sentient-assistant-1.0-SNAPSHOT.jar` (or `mvn javafx:run` — the `javafx-maven-plugin` is configured but the JavaFX deps are largely dead weight; see issues below)
- Then open `http://localhost:7070`

## Continuity / logic issues found during the audit

Read-only audit findings. Not fixed in this branch unless explicitly noted. Severity is my judgment.

### Logic bugs

1. **Wrong endpoint in Settings → Connect Google Calendar.** `app.js:2388` opens `/api/calendar/google/auth`, but `WebServer.java` only registers `/api/tasks/google/auth` (Google Tasks/Calendar share OAuth). Click does nothing useful. *Severity: medium — easy fix.*

2. **`models.json` is orphaned.** Committed at `piassistant/models.json` (Groq's model list snapshot from March) but never read by any code. It looks like a stub for a future "model picker" feature. *Severity: low.*

3. **`DashboardView.java` is dead code** — JavaFX leftover not referenced from `App.java`. *Severity: low.*

4. **JavaFX dependencies in `pom.xml` are dead weight.** `javafx-controls`, `javafx-media`, `javafx-swing` (with OpenJFX 21) are still declared but only `DashboardView.java` uses JavaFX, and it's unreachable. Removing them would shrink the fat jar by tens of MB and remove platform-specific natives. *Severity: low (cleanliness), only safe to remove after confirming no transitive usage.*

5. **Duplicate `user_profile.json`.** One at repo root (orphaned), one at `piassistant/user_profile.json` (loaded — `ProfileManager` uses a relative path from CWD which is `piassistant/` under `mvn javafx:run`). Easy footgun: running from the repo root would silently load the empty one. *Severity: medium.*

6. **Auto-browser-open is platform-incomplete.** `WebServer.start()` only handles mac/linux. Windows hosts won't auto-open the browser. *Severity: low.*

7. **The "Master Server" model assumes you're on the same host.** `app.js:233` builds the WS URL from `location.host`. If you open the app from a remote browser, you have to hit `http://<master-ip>:7070` directly — there's no in-UI control for pointing at a remote master. (Fixing this is part of the current task plan.) *Severity: medium for the use case the user described.*

8. **`processCommand` writes `groq.clearHistory()` on every `init`.** That's correct for "new session," but also means every fresh browser tab wipes the conversation history of whichever tab connected before. Worth being aware of if the project ever supports persistent cross-tab sessions. *Severity: low — intentional today.*

### Repo hygiene

9. **JVM crash dumps committed.** Seven `hs_err_pid*.log` files in `piassistant/` totalling ~2MB. Should be deleted and `.gitignore`d. *Severity: low.*

10. **316MB shaded jar committed.** `piassistant/RaspberryPi-Home-Assistant.jar` — should never be in git. (Even worse for cloning.) *Severity: medium-high for repo health.*

11. **`.env` committed.** `piassistant/.env` is in the tree, possibly containing real API keys. `.env` should be in `.gitignore`, `.env.example` is fine to commit. **Check that keys haven't been pushed to a public remote.** *Severity: high if any of those keys are real.*

12. **`.DS_Store` files committed.** Multiple `.DS_Store` files across the tree — macOS-specific Finder metadata, harmless but noise. Should be `.gitignore`d. *Severity: low.*

13. **`target/` directory committed.** Build output should be excluded. *Severity: low.*

### Naming / structure

14. **`Listener.java` lives in `service/`** but functionally it's input infrastructure. Fine. Also there's a `RaspberryPi-Home-Assistant.code-workspace` file inside the `service` package directory — an IDE artifact accidentally dropped into source. *Severity: low.*

15. **GroqService is monolithic and provider-coupled.** Hardcoded model IDs, API URL, the routing prompt, and the entire system prompt for CHAT mode all live in one file. The "Model Override" dropdown in the UI only switches the routing decision, not the underlying provider/model. To swap to OpenClaw cleanly, we want an interface (`ChatService`) with implementations for Groq and OpenClaw. *Severity: medium — directly relevant to the OpenClaw migration.*

## What's next (current branch tasks)

The OpenClaw integration adds:

1. A **provider settings UI** — pick provider (Anthropic/OpenAI/Google/Groq/OpenRouter/xAI), paste API key, pick model. Writes to `~/.config/openclaw/openclaw.json5` and triggers `openclaw gateway restart`.
2. A **Skills / Composio menu** — paste Composio consumer key, toggle which Composio toolkits to enable. Writes the corresponding `mcp` entries into `openclaw.json5`.
3. A **Master Server URL field** — `localStorage` setting on the web client so a remote browser can target the home machine's `<ip>:7070`.
4. A new **`OpenClawService`** that calls the local `http://127.0.0.1:18789/v1/chat/completions` OpenAI-compatible gateway. Routed via an engine toggle in `WebServer.handleChat`; the existing Groq path is preserved as fallback.
