idk opencode made 95% of it. DON'T HACK MY MOM PLZ i just wanna play w my friends

# GamepadBridge

Convierte tu teléfono Android en un mando **Xbox 360 virtual** para Linux.
Conéctalo por WiFi y úsalo como si fuera un gamepad conectado por USB.
Hasta **4 jugadores** a la vez con dashboard web en vivo.

## Quick Start

### Server (Linux)

1. **Doble click** en `GamepadBridge.desktop` (o en `server/start.sh`)
2. Se abre una terminal, instala lo necesario (solo la primera vez)
3. Se abre el navegador con el dashboard
4. **Cerrar la terminal → el server se detiene**

O manualmente:

```bash
python3 server/server.py
```

Luego abre `http://localhost:8080/` (el dashboard muestra la IP real y un QR).

### Android App

Opción A — **Escanea el QR** en el dashboard desde la app.
Opción B — Ingresa manualmente la IP del PC y el puerto `60001`.

## Cómo funciona

El teléfono envía el estado del mando (sticks, botones, gatillos) por TCP al servidor Linux.
El servidor crea un `/dev/uinput` virtual que el sistema reconoce como un Xbox 360 gamepad.
El dashboard web muestra todo en tiempo real y genera un QR para conectar el teléfono.

## Puertos

| Puerto | Protocolo | Propósito |
|--------|-----------|-----------|
| 60001  | TCP       | Datos del mando desde el teléfono |
| 8080*  | HTTP+WS   | Dashboard web + actualizaciones en vivo |

*Si el 8080 está ocupado, usa otro puerto libre.

## Build (solo para desarrollo)

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Server**: Python 3.11+, `python-uinput`, `qrcode[pil]`
**Android**: JDK 17, Android SDK 34, Gradle 8.11

## Licencia

MIT
