# Windows Support (ViGEmBus)

This project provides a virtual Xbox 360 controller across the network.
The server creates 4 virtual gamepads using **uinput** on Linux and **vgamepad** (ViGEmBus) on Windows.

## How it works

- **Android app** connects to the server via TCP port 60001
- **Server** reads gamepad state from the phone and forwards it to virtual gamepads
- **Web dashboard** (HTTP) shows live state of all 4 gamepads

## Windows requirements

- Python 3.14+
- **ViGEmBus driver** installed (auto-detected and prompted on first run)
- Dependencies from `requirements-windows.txt`:
  - `qrcode[pil]` — QR code generation in the dashboard
  - `vgamepad` — virtual Xbox 360 controller emulation

## Auto-setup

On first run the server detects the OS and:
1. Installs the correct pip dependencies (`requirements-windows.txt` or `requirements-linux.txt`)
2. On Windows, checks if ViGEmBus is installed (Registry lookup) and offers to download/install it

## Key files

- `server/server.py` — main server (cross-platform, detects `platform.system()`)
- `requirements-linux.txt` — Linux pip dependencies
- `requirements-windows.txt` — Windows pip dependencies

## Notes

- `reuse_port=True` is only set on Linux (Windows doesn't support SO_REUSEPORT)
- HTTP dashboard tries port 8080 first, falls back to random free port
- On Windows, analog Y-axes are negated to match XInput standard
