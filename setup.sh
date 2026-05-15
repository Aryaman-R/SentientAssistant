#!/usr/bin/env bash
# =============================================================================
# Sentient Home Assistant — Developer Setup Script
# Supports Linux and macOS. Run from the repository root:
#   chmod +x setup.sh && ./setup.sh
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$REPO_ROOT/.env"
ENV_EXAMPLE="$REPO_ROOT/.env.example"

# ── Colours ────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Colour

info()    { echo -e "${CYAN}[INFO]${NC} $*"; }
success() { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Banner ─────────────────────────────────────────────────────────────────
echo -e "${CYAN}"
echo "  ____  ______ _   _ _______ _____ ______ _   _ _______"
echo " / ___||  ____| \ | |__   __|_   _|  ____| \ | |__   __|"
echo " \___ \| |__  |  \| |  | |   | | | |__  |  \| |  | |"
echo "  ___) |  __| | . \` |  | |   | | |  __| | . \` |  | |"
echo " |____/ |_____| |\\_|  |_|  _| |_| |_____| |\\__|  |_|"
echo "       Home Assistant Setup"
echo -e "${NC}"

# ── 1. Check required tools ────────────────────────────────────────────────
info "Checking prerequisites..."

check_cmd() {
    if command -v "$1" &>/dev/null; then
        success "$1 found: $(command -v "$1")"
    else
        error "$1 is not installed. Please install it and re-run this script."
        exit 1
    fi
}

check_cmd java
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    error "Java 17+ required. Found: Java $JAVA_VER"
    exit 1
fi
success "Java $JAVA_VER detected"

check_cmd mvn

# ── 2. Create .env from template ──────────────────────────────────────────
info "Setting up environment file..."

if [ -f "$ENV_FILE" ]; then
    warn ".env already exists. Skipping creation (edit it manually to update keys)."
else
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    success "Created .env from .env.example"
fi

# ── 3. Prompt for API keys ────────────────────────────────────────────────
prompt_key() {
    local KEY="$1"
    local DESC="$2"
    local URL="$3"
    local CURRENT
    CURRENT=$(grep "^${KEY}=" "$ENV_FILE" | cut -d'=' -f2- || true)

    if [ -n "$CURRENT" ] && [ "$CURRENT" != "your_${KEY,,}_here" ] && \
       [[ "$CURRENT" != *"your_"* ]]; then
        success "$KEY already set."
        return
    fi

    echo ""
    echo -e "${YELLOW}→ $DESC${NC}"
    echo -e "  Get it from: ${CYAN}$URL${NC}"
    read -rp "  Enter $KEY (press Enter to skip): " VALUE
    if [ -n "$VALUE" ]; then
        # Replace or append the key in .env
        if grep -q "^${KEY}=" "$ENV_FILE"; then
            sed -i.bak "s|^${KEY}=.*|${KEY}=${VALUE}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        else
            echo "${KEY}=${VALUE}" >> "$ENV_FILE"
        fi
        success "$KEY saved."
    else
        warn "$KEY skipped. Some features may not work."
    fi
}

echo ""
info "API Key Configuration"
echo "  (Press Enter to skip any key you don't have yet)"

prompt_key "GROQ_API_KEY" \
    "Groq API Key — powers the AI assistant (Llama/Qwen models)" \
    "https://console.groq.com/keys"

prompt_key "SPOTIFY_CLIENT_ID" \
    "Spotify Client ID — enables music integration" \
    "https://developer.spotify.com/dashboard"

prompt_key "SPOTIFY_CLIENT_SECRET" \
    "Spotify Client Secret — enables music integration" \
    "https://developer.spotify.com/dashboard"

prompt_key "GEMINI_API_KEY" \
    "Google Gemini API Key — used for vision/camera features" \
    "https://aistudio.google.com/app/apikey"

prompt_key "OPENAI_API_KEY" \
    "OpenAI API Key — optional alternative AI backend" \
    "https://platform.openai.com/api-keys"

# Optional: Vosk model path
echo ""
info "Vosk Speech-to-Text Model (optional — needed for wake word / voice input)"
echo "  Download from: ${CYAN}https://alphacephei.com/vosk/models${NC}"
echo "  Recommended model: vosk-model-small-en-us-0.15"
CURRENT_VOSK=$(grep "^VOSK_MODEL_PATH=" "$ENV_FILE" | cut -d'=' -f2- || true)
if [ -z "$CURRENT_VOSK" ] || [[ "$CURRENT_VOSK" == *"/path/to/"* ]]; then
    read -rp "  Enter path to Vosk model directory (press Enter to skip): " VOSK_PATH
    if [ -n "$VOSK_PATH" ]; then
        if grep -q "^VOSK_MODEL_PATH=" "$ENV_FILE"; then
            sed -i.bak "s|^VOSK_MODEL_PATH=.*|VOSK_MODEL_PATH=${VOSK_PATH}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        else
            echo "VOSK_MODEL_PATH=${VOSK_PATH}" >> "$ENV_FILE"
        fi
        success "VOSK_MODEL_PATH saved."
    else
        warn "Vosk path skipped. Voice input will not be available."
    fi
else
    success "VOSK_MODEL_PATH already set."
fi

# ── 4. Spotify Redirect URI reminder ────────────────────────────────────────
echo ""
info "Spotify OAuth Setup"
echo "  In your Spotify Developer Dashboard, add the following Redirect URI:"
echo -e "    ${CYAN}http://127.0.0.1:7070/api/spotify/callback${NC}"
echo "  Steps:"
echo "    1. Go to https://developer.spotify.com/dashboard"
echo "    2. Select your app → Edit Settings"
echo "    3. Add http://127.0.0.1:7070/api/spotify/callback to Redirect URIs"
echo "    4. If your app is in Development Mode, add your Spotify username as a"
echo "       test user under Dashboard → your app → User Management"
echo "    5. Save settings."

# ── 5. Maven build ─────────────────────────────────────────────────────────
echo ""
info "Building the project with Maven..."
cd "$REPO_ROOT/piassistant"
if mvn package -DskipTests -q; then
    success "Build successful!"
else
    error "Build failed. Check the output above for errors."
    exit 1
fi

# ── 6. Tailscale (optional) ────────────────────────────────────────────────
echo ""
info "Tailscale Self-Hosting (optional)"
echo "  Tailscale lets you access the assistant from anywhere on your private network."
echo "  1. Install Tailscale: https://tailscale.com/download"
echo "  2. Authenticate:  tailscale up"
echo "  3. Enable subnet routing (if you want to share with others on your LAN):"
echo "     tailscale up --advertise-routes=<your-LAN-subnet>"
echo "  4. Once connected, replace 'localhost' with your Tailscale IP (100.x.x.x)"
echo "     to access the assistant from other devices."
echo "  5. Share with teammates: tailscale share <node-name>"

# ── 7. Launch instructions ─────────────────────────────────────────────────
echo ""
success "Setup complete!"
echo ""
echo -e "${CYAN}To start the assistant:${NC}"
echo "  cd piassistant"
echo "  mvn javafx:run"
echo ""
echo -e "${CYAN}Web interface (after starting):${NC}"
echo "  http://localhost:7070"
echo ""
echo -e "${CYAN}Spotify authentication:${NC}"
echo "  Open http://localhost:7070/api/spotify/auth in your browser after starting."
echo ""
echo -e "${CYAN}Check .env to review or update your API keys at any time.${NC}"
