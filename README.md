# GamepadBridge

Turn your Android phone into a **virtual Xbox 360 controller** for Linux.
Connect over WiFi and use it like a USB gamepad. Up to 4 players.

Tested with gamesir x5 lite

---

## Installation

### 1. Download

Go to https://github.com/belgatitamiau/android-gamepad-linux/releases
Get the **Source code (zip)** of the latest Linux release.
Extract it anywhere (Desktop, Downloads, etc).

### 2. Run

**Double-click** `server/start.sh`.
Your file manager will ask **"Run in terminal"** or **"Execute"** — click yes.
A terminal opens, everything installs automatically (first time only), and the dashboard opens in your browser.

Alternatively, open a terminal in the folder and type:
```bash
bash server/start.sh
```

### 3. One-time — permission for the virtual controller

The server needs to write to `/dev/uinput` to create the virtual controller.
If you see a warning, copy and paste this command into a terminal:

```bash
echo 'KERNEL=="uinput", MODE="0666"' | sudo tee /etc/udev/rules.d/99-uinput.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
```

It will ask for your admin password. You only need to do this **once** per machine.

---

## How to use

1. On your phone: open the **GamepadBridge** app, scan the QR shown on the dashboard
2. The phone connects to the server automatically
3. Connect a Bluetooth/USB gamepad to your phone and use it like it's plugged into your PC
4. Everything is visible in real-time on the web dashboard

### Ports

| Port  | Purpose |
|-------|---------|
| 60001 | Phone-to-server gamepad data |
| 8080* | Web dashboard (opens automatically) |

*If 8080 is busy, a free port is used instead.

---

## FAQ

**Do I need to install Python?**
The script installs it if needed (with admin permissions).
Or get it from python.org or your package manager:
```bash
# Debian/Ubuntu
sudo apt install python3 python3-pip python3-venv

# Fedora
sudo dnf install python3 python3-pip
```

**Does it work with any gamepad?**
Yes — if your phone recognizes it (Bluetooth, USB-OTG, or built-in like Razer Kishi), the server will see it.

**What about Windows?**
This repo is for Linux. For Windows you need ViGEmBus — see `WINDOWS_SUPPORT.md`.

---

## Development (building the app yourself)

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Requirements**: JDK 17, Android SDK 34, Gradle 8.11

MIT License
