# GamepadBridge

Forward physical gamepad input from Android to Linux over your local network.
Turns your phone into a wireless gamepad receiver with a live web dashboard.

## Features

- **4 virtual Xbox 360 gamepads** on the Linux host via `/dev/uinput`
- **Android app** forwards Bluetooth/USB gamepad input over TCP
- **Live dashboard** at `http://PC-IP:8080` with real-time stick visualisation, button states, and QR code for easy connection
- **Audio feedback** — retro connection jingles and player-number callouts
- **Multi-device** — up to 4 phones can connect simultaneously, each gets a unique player slot

## Quick Start

### Server (Linux)

```bash
sudo dnf install -y python3-uinput   # Fedora
pip install qrcode[pil]
sudo python3 server/server.py
```

Or use the included `install.sh` for a one‑shot setup (sudoers entry + launcher).

Open `http://localhost:8080/` in your browser.

### Android App

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open **GamepadBridge** on your phone, enter the PC's IP and port `60001`, tap **Connect**.

## Ports

| Port  | Protocol | Purpose              |
|-------|----------|----------------------|
| 60001 | TCP      | Gamepad data from phone |
| 8080  | HTTP+WS  | Dashboard + live updates |

## Protocol

| Offset | Size | Field              |
|--------|------|--------------------|
| 0      | 1    | Message type (0 = state) |
| 1      | 1    | Gamepad ID (0-3)   |
| 2      | 4    | Button bitmask (LE) |
| 6      | 2    | Left stick X (i16) |
| 8      | 2    | Left stick Y (i16) |
| 10     | 2    | Right stick X (i16) |
| 12     | 2    | Right stick Y (i16) |
| 14     | 1    | Left trigger (0-255) |
| 15     | 1    | Right trigger (0-255) |
| 16     | 4    | Timestamp (LE)     |

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

## Build Requirements

- **Server**: Python 3.10+, `python-uinput`, `qrcode[pil]`
- **Android**: JDK 17, Android SDK 34, Gradle 8.11

## License

MIT
