# reference.md — Claude's working notes

For Claude. Not for users. Short, no-nonsense, the things that would have saved me time on this codebase.

> See also: `ARCHITECTURE.md` (deeper module map + WS contract) and the user-facing `README.md`.

## Hard rules (from user)

- **Don't start the server.** User runs `java -jar` themselves. Stop at `mvn package`. (Saved as feedback memory: `feedback_no_auto_launch.md`.)
- **Don't auto-launch via `mvn javafx:run` either.** Same reason.
- **Commits only on explicit request.** Default to leaving the working tree dirty for the user to review.
- **Push back if you're unsure.** User prefers a clarifying question to a wrong implementation.

## Where things live

| What | Path |
|---|---|
| Entry point | `piassistant/src/main/java/com/sentient/App.java` → `WebServer.java` |
| All WS + REST routes | `piassistant/src/main/java/com/sentient/WebServer.java` |
| LLM service (Groq path) | `piassistant/src/main/java/com/sentient/service/GroqService.java` |
| LLM service (OpenClaw path) | `piassistant/src/main/java/com/sentient/service/OpenClawService.java` |
| OpenClaw config writer (`~/.config/openclaw/openclaw.json5`) | `piassistant/src/main/java/com/sentient/service/OpenClawConfigManager.java` |
| Vosk wake-word + transcription | `piassistant/src/main/java/com/sentient/service/Listener.java` |
| Piper / Chorus TTS | `piassistant/src/main/java/com/sentient/service/TextToSpeech.java` |
| User state persistence | `piassistant/src/main/java/com/sentient/util/ProfileManager.java` |
| Frontend HTML | `piassistant/src/main/resources/web/index.html` |
| Frontend JS (~3000 LOC, no framework) | `piassistant/src/main/resources/web/app.js` |
| Frontend CSS (~2600 LOC, dark monospace) | `piassistant/src/main/resources/web/styles.css` |
| `.env` (repo-root, walks up 3 dirs from CWD) | `/Users/aryamanr/Documents/RaspberryPi-Home-Assistant/.env` |
| Google Tasks/Calendar token (shared) | `~/.sentient_google_tasks_token` |
| Spotify token | written by SpotifyService; check `~/.sentient_spotify_token` or repo root |
| OpenClaw config | `~/.config/openclaw/openclaw.json5` (alternate: `~/.openclaw/config.json5`) |

## Build + verify cycle

```bash
# Quick syntax checks (no jar)
mvn -DskipTests compile
node --check src/main/resources/web/app.js

# Full fat jar (~327 MB, 30-60s)
mvn -q -DskipTests package

# User restarts on their end — do NOT do it for them.
```

If the user already has the server running and asks you to verify a change, you can probe `http://localhost:7070/...` but **never** kill + restart.

## WebSocket message contract (cheat sheet)

Client → server:
- `init` `{sessionId}` — new tab. Clears chat history + welcomes.
- `chat` `{text, source?, modelOverride?, engine?, image?, fileName?, fileType?}` — user message.
- `record` — start server-side mic (manual trigger; bypasses wake word).
- `stop` — cancel in-flight response (stream + TTS + listener).
- `set_listener` `{paused: bool}` — VOICE tab controls server Vosk listener.
- `ping` — keep-alive.

Server → all clients (broadcast):
- `system` `{text}`, `voice_partial`/`voice_final`/`voice_state`, `chat_word`, `chat_done`, `tts_audio` (base64 WAV), `command` `{action, param?}`, `web_record` (prompt browser to start SR).

## Frontend patterns

- **`api(path)`** helper in `app.js` resolves a URL through the master-server override. Use `fetch(api('/api/...'))` for new calls so remote clients work. The legacy code uses bare `fetch('/api/...')` — it's fine on the master device, breaks on remote clients pointed at another master.
- **Panels** are stored in `activePanels` set and rendered in a grid. Multiple panels can be open at once.
- **Settings** persist in `localStorage`. Pattern: load on init, save on change. Keys are `sentient_<thing>`.
- **`escapeHtml(text)`** is the safe text setter — use it for any user-controlled string in `innerHTML`.

## LLM command DSL (`[CMD:...]` tags)

The system prompt teaches the model to embed command tags in its reply. The server extracts them in `WebServer.extractCommands` (regex `\[CMD:(\w+)(?::([^\]]+))?\]`). Some are handled server-side, the rest are broadcast as `{type: "command", action, param}` for the frontend to act on.

Server-side handlers in `WebServer.handleChat`:
- `ADD_TASK:title|description|YYYY-MM-DD` — adds to ProfileManager + auto-pushes to Google Tasks if authenticated
- `REMOVE_TASK:title` — removes locally + auto-deletes on Google
- `ADD_COMMITMENT:text` / `REMOVE_COMMITMENT:text`
- `ADD_EVENT:title|description|start|end` — routes to Google Calendar if authenticated, else local
- `CREATE_PLAYLIST:name` — Spotify
- `AUTOMATE:name` — fires registered webhook
- `CONTINUE_CONVERSATION` — auto-mic for next turn (only honored in VOICE tab with wake mode on)

Client-side commands (frontend executes them): `SWITCH_*` (panel navigation), `SET_TIMER`, `START_TIMER`, `PAUSE_TIMER`, `CANCEL_TIMER`, `SET_USERNAME`, `SET_RESTRICTION`, `SWITCH_SPOTIFY`, `SET_ALARM`, `DELETE_ALARM`.

When adding a new command, update *both* GroqService AND OpenClawService system prompts (currently duplicated — a TODO is to extract them).

## Gotchas / things that bit me

- **The Vosk microphone is opened once in the Listener constructor and can't be re-opened after `stop()`.** Use `setPaused(true/false)` to pause/resume the loop instead of stop/start.
- **`Listener` defaults to `paused = true` on boot.** Only the VOICE tab's wake-mode toggle resumes it.
- **`/v1/playlists/{id}/tracks` returns 403 for apps in Spotify dev mode.** Not a code bug. Workaround: LIBRARY tab uses `/v1/me/tracks` and `/v1/me/player/recently-played` which still work.
- **Spotify SDK quirk**: `PlaylistSimplified.getTracks()` often returns 0 total even when the playlist isn't empty. We display `trackCount` from the SDK but it's frequently wrong; don't trust it.
- **Static files are bundled into the shaded jar.** Edits to `app.js` / `index.html` / `styles.css` don't take effect until `mvn package`. (Unless running `mvn javafx:run`.)
- **`.env` is at the repo root**, not under `piassistant/`. `EnvLoader.load()` walks up 3 directories from CWD.
- **Shaded jar is ~327 MB** because JavaFX + Vosk + ONNX + chorus models. The JavaFX deps are dead weight from a previous iteration — removable but not yet removed.
- **The static-file mount is `classpath:/web`**, so the file must be inside the jar. `Files: jar:file:.../sentient-assistant.jar!/web` in startup logs confirms it loaded.
- **JSON5 stripper** in `OpenClawConfigManager.stripJson5ToJson` is naive — handles `//`, `/* */`, single-quoted strings, and trailing commas. Doesn't handle unquoted keys. If the user hand-edited their `openclaw.json5` with unquoted keys, parsing will fail.
- **`authenticated` flags lie.** Spotify, Tasks, and Calendar all set `authenticated = true` based on "we have a refresh token saved." Use the new `getAuthHealth()` methods (which actually probe the API) when you need ground truth.
- **Google Tasks "@sentient" list is legacy.** The current code mirrors *every* Google list, not just one. The constant has been removed.
- **`showCompleted=true&showHidden=true`** is intentional in `listTasksInList` so the user actually sees their existing completed Google tasks. Each pulled task carries `completed: bool` on the local TaskItem.

## OpenClaw integration shape

- `OpenClawService.callGateway` hits `http://127.0.0.1:18789/v1/chat/completions` (OpenAI-compatible). Auth via `Authorization: Bearer <gatewayToken>` if set.
- `OpenClawConfigManager.applyProvider(provider, key, model, ...)` writes:
  - `secrets.<PROVIDER>_API_KEY = key`
  - `gateway.defaultModel = "<provider>/<model>"`
  - (custom only) `models.providers.custom = {baseUrl, apiKey, api, models:[{id}]}`
  Then shells out to `openclaw gateway restart`.
- `OpenClawConfigManager.applyComposio(consumerKey, enabledList)` writes:
  - `mcp.servers.composio = {transport:"http", baseUrl:"https://connect.composio.dev/mcp", headers:{x-consumer-api-key}, toolkits:[...]}`
  - `secrets.COMPOSIO_API_KEY = consumerKey`

## When the user asks for a new feature

Order of operations:
1. Read existing code FIRST. Don't assume from a function name; read it.
2. If unsure about scope, ask one clarifying question via `AskUserQuestion`. Don't ask three.
3. Identify which layer(s) change: backend (Java) and/or frontend (HTML/JS/CSS).
4. Make the change. Compile or syntax-check.
5. Tell the user "rebuilt, restart on your end" — never restart for them.
6. Tell the user exactly what to test, including failure modes to watch for.

## Voice + audio specifics

- The **wake word** is `"jarvis"` (server-side `Listener.isWakeWord` and browser-side `WAKE_PATTERNS` regex must match). Five spellings accepted: `jarvis`, `jervis`, `jarvi`, `harvis`, `charvis`.
- **TTS audio** is generated server-side (Piper), sent to the browser as base64-encoded WAV via `tts_audio` WS message. Played in browser via `new Audio("data:audio/wav;base64,...")`. The `setSinkId` call routes it to the user's chosen output device.
- **Barge-in** uses a browser-side `AnalyserNode` running on `getUserMedia` audio. If RMS exceeds `max(0.05, baseline * 6)` for >220 ms during `voiceState === "speaking"`, we fire `sendWS({type:'stop'})` and start a new transcription turn.
- **HOME push-to-talk** uses `mousedown`/`mouseup` + `touchstart`/`touchend` on `recordBtn`. Browser SR is the transcription path; `recognition.onresult` sends the chat.

## Memory

Persistent feedback notes live at:
`/Users/aryamanr/.claude/projects/-Users-aryamanr-Documents-RaspberryPi-Home-Assistant/memory/`

`MEMORY.md` indexes them. Check it before assuming anything about the user's preferences.

## Open known issues (from ARCHITECTURE.md "Continuity / logic issues")

Severity-ordered things still worth fixing:

1. **`.env` committed.** High risk if real keys are in there and the repo is public. User aware; not yet rotated.
2. **316MB shaded jar committed in `piassistant/`.** Should be removed from git history.
3. **Duplicate `user_profile.json`** at repo root and `piassistant/`. Only the inner one is loaded.
4. **JavaFX deps in `pom.xml`** are dead weight — removable for ~50 MB jar shave, but the migration to web UI left them in place.
5. **Auto-browser-open misses Windows** (`WebServer.start()` only handles mac/linux).
6. **GroqService.java and OpenClawService.java duplicate the CHAT system prompt + command list.** Extract to a `Prompts` util when next touching either.
7. **`models.json`** at repo root is orphaned — committed but unused.
8. **`DashboardView.java`** is dead JavaFX code.
9. **JVM crash logs** (`hs_err_pid*.log`) committed in `piassistant/`. Should be `.gitignore`d.
