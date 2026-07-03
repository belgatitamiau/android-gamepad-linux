#!/bin/bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
PORTFILE="/tmp/gamepad-bridge-port"
VENV_DIR="$SCRIPT_DIR/.venv"
SERVER_PY="$SCRIPT_DIR/server.py"
SERVER_PID=""
PYTHON=""

cleanup() {
  local ret=$?
  echo ""
  echo "Stopping Gamepad Bridge..."
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$PORTFILE"
  echo "Stopped."
  exit "$ret"
}
trap cleanup SIGINT SIGTERM EXIT

kill_prev() {
  local prev_pid
  prev_pid=$(pgrep -f "python3.*$SERVER_PY" 2>/dev/null || true)
  if [[ -n "$prev_pid" ]]; then
    echo "Stopping existing server (PID $prev_pid)..."
    kill "$prev_pid" 2>/dev/null || true
    sleep 1
  fi
}

# Find a Python that can dlopen libudev (needed by uinput)
find_python() {
  local candidates=(
    "/usr/bin/python3.14" "/usr/bin/python3.13" "/usr/bin/python3.12"
    "/usr/bin/python3.11" "/usr/bin/python3.10" "/usr/bin/python3"
    "/usr/local/bin/python3.14" "/usr/local/bin/python3.13"
    "/usr/local/bin/python3.12" "/usr/local/bin/python3.11"
    "/usr/local/bin/python3.10" "/usr/local/bin/python3"
    "python3.14" "python3.13" "python3.12" "python3.11" "python3.10" "python3"
  )
  for c in "${candidates[@]}"; do
    local path
    if [[ "$c" == */* ]]; then
      path="$c"
      [[ -x "$path" ]] || continue
    else
      path=$(command -v "$c" 2>/dev/null || true)
      [[ -n "$path" ]] || continue
    fi
    if "$path" -c "import ctypes; ctypes.CDLL('libudev.so.1', use_errno=True)" 2>/dev/null; then
      PYTHON="$path"
      return 0
    fi
  done
  echo "ERROR: Could not find Python with libudev support."
  echo "Install dependencies:"
  echo "  Debian/Ubuntu: sudo apt install python3 python3-pip python3-venv"
  echo "  Fedora:        sudo dnf install python3 python3-pip"
  echo "Press Enter to exit."; read -r; exit 1
}

setup_venv() {
  if [[ ! -d "$VENV_DIR" ]]; then
    echo "Creating virtual environment..."
    "$PYTHON" -m venv "$VENV_DIR"
  fi
  local py="$VENV_DIR/bin/python3"

  local missing_pkgs=()
  if ! "$py" -c "import qrcode" 2>/dev/null; then missing_pkgs+=("qrcode[pil]"); fi
  if ! "$py" -c "import uinput" 2>/dev/null; then missing_pkgs+=("python-uinput"); fi

  if [[ ${#missing_pkgs[@]} -gt 0 ]]; then
    echo "Installing dependencies: ${missing_pkgs[*]}..."
    "$VENV_DIR/bin/pip" install --quiet "${missing_pkgs[@]}" || {
      echo "ERROR: Failed to install dependencies." >&2
      echo "Press Enter to exit."; read -r; exit 1
    }
    echo "Dependencies installed."
  fi
}

check_uinput() {
  if [[ ! -w /dev/uinput ]]; then
    echo "WARNING: /dev/uinput is not writable."
    echo "Run this once to fix:"
    echo "  echo 'KERNEL==\"uinput\", MODE=\"0666\"' | sudo tee /etc/udev/rules.d/99-uinput.rules"
    echo "  sudo udevadm control --reload-rules && sudo udevadm trigger"
    echo ""
    echo "Press Enter to continue anyway (may fail)..."; read -r
  fi
}

detect_ip() {
  "$VENV_DIR/bin/python3" -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(0.5)
try:
    s.connect(('10.0.0.1', 9))
    ip = s.getsockname()[0] if s.getsockname()[0] != '0.0.0.0' else '127.0.0.1'
except:
    ip = '127.0.0.1'
finally:
    s.close()
print(ip)
" 2>/dev/null || echo "127.0.0.1"
}

echo ""
echo "========================================"
echo "  Gamepad Bridge"
echo "========================================"
echo ""

kill_prev
find_python
setup_venv
check_uinput

cd "$REPO_DIR"
"$VENV_DIR/bin/python3" -u "$SERVER_PY" &
SERVER_PID=$!

LOCAL_IP=$(detect_ip)
HTTP_PORT=""
for i in $(seq 1 15); do
  if [[ -f "$PORTFILE" ]]; then
    HTTP_PORT=$(cat "$PORTFILE")
    break
  fi
  sleep 1
done

if [[ -z "$HTTP_PORT" ]]; then
  echo "ERROR: Server did not start within 15 seconds."
  echo "Press Enter to exit."; read -r; exit 1
fi

DASHBOARD_URL="http://$LOCAL_IP:$HTTP_PORT"
echo ""
echo "========================================"
echo "  Gamepad Bridge — Running!"
echo "========================================"
echo "  Dashboard: $DASHBOARD_URL"
echo "  TCP port:  60001"
echo "========================================"
echo "  Close this window to stop the server"
echo "========================================"
echo ""

xdg-open "$DASHBOARD_URL" 2>/dev/null || true

wait "$SERVER_PID" 2>/dev/null || true
