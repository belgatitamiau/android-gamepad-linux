# Android Gamepad Bridge (Linux)

Forward physical gamepad input from Android to Linux over local network.

## Architecture

```
Android (Kotlin) ── TCP binary protocol ──► Linux PC (Python)
                        20 bytes/packet          │
                     ┌─────────────────┐         │
                     │ gamepad_id (1)  │         │
                     │ buttons (4)     │         ├── HTTP Dashboard :8080
                     │ lx,ly,rx,ry (8) │         ├── WebSocket :8080
                     │ lt,rt (2)       │         └── XUSB receiver (USB)
                     │ timestamp (4)   │
                     └─────────────────┘
```

## Quick Start

### Server (Linux)
```bash
cd server
pip install -r requirements.txt
python3 server.py        # needs root for /dev/uinput
```

### Dashboard
Open http://PC-IP:8080 in browser (shows QR with connection string).

### Android App
Open `android/` in Android Studio or build with:
```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Protocol

| Offset | Size | Field |
|--------|------|-------|
| 0  | 1  | Message type (0 = state) |
| 1  | 1  | Gamepad ID (0-3) |
| 2  | 4  | Button bitmask (uint32 LE) |
| 6  | 2  | Left stick X (int16 LE) |
| 8  | 2  | Left stick Y (int16 LE) |
| 10 | 2  | Right stick X (int16 LE) |
| 12 | 2  | Right stick Y (int16 LE) |
| 14 | 1  | Left trigger (0-255) |
| 15 | 1  | Right trigger (0-255) |
| 16 | 4  | Timestamp (uint32 LE) |

## Button Bits

| Bit | Button |
|-----|--------|
| 0   | A      |
| 1   | B      |
| 2   | X      |
| 3   | Y      |
| 4   | LB     |
| 5   | RB     |
| 6   | LT(d)  |
| 7   | RT(d)  |
| 8   | SELECT |
| 9   | START  |
| 10  | L3     |
| 11  | R3     |
| 12  | DPAD_UP |
| 13  | DPAD_DOWN |
| 14  | DPAD_LEFT |
| 15  | DPAD_RIGHT |
| 16  | HOME   |

## Ports

- `60001` — TCP gamepad data from Android
- `8080`  — HTTP dashboard + WebSocket
