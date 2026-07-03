idk opencode made 95% of it. DON'T HACK MY MOM PLZ i just wanna play w my friends

# GamepadBridge

Convierte tu teléfono Android en un **mando Xbox 360** para tu PC con Linux.
Conectá tu celu por WiFi y jugá como si tuvieras un joystick USB. Hasta 4 jugadores.

No necesitas instalar drivers ni programas raros. Solo descargar, descomprimir y hacer doble click.

---

## Instalación

### 1. Descargar

Andá a https://github.com/belgatitamiau/android-gamepad-linux/releases
Bajá el **Source code (zip)** de la última versión.
Descomprimilo donde quieras (Escritorio, Descargas, etc).

### 2. Ejecutar (elegí una opción)

**Opción A — Doble click (recomendado)**
Hacé doble click en `GamepadBridge.desktop`.
La primera vez el sistema te va a preguntar "¿Confiar y ejecutar?" — decí que sí.
Se abre una terminal, se instala todo solo, y se abre el navegador en el dashboard.

**Opción B — Terminal (si sabés usar una)**
Abrí una terminal en la carpeta y escribí:
```bash
bash server/start.sh
```

Las dos opciones hacen lo mismo: instalan lo que falta (una sola vez), arrancan el servidor y abren el navegador.

### 3. Una sola vez — permiso para el mando virtual

El servidor necesita escribir en `/dev/uinput` para crear el mando virtual.
Si al ejecutar ves un cartel de aviso, copiá y pegá este comando en una terminal:

```bash
echo 'KERNEL=="uinput", MODE="0666"' | sudo tee /etc/udev/rules.d/99-uinput.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
```

Pedirá tu contraseña de administrador. Se hace **una sola vez** en la vida de la PC.

---

## Cómo se usa

1. En el celu: abrí la app **GamepadBridge**, escaneá el QR que aparece en el dashboard
2. El celu se conecta automáticamente al server
3. Conectá un mando Bluetooth/USB al celu y usalo como si estuviera en la PC
4. Todo se ve en vivo en el dashboard del navegador

### Puerto

| Puerto | Para qué |
|--------|----------|
| 60001  | Conexión del teléfono al server |
| 8080*  | Dashboard web (se abre solo en el navegador) |

*Si el 8080 está ocupado, usa otro puerto libre.

---

## Preguntas frecuentes

**¿Necesito instalar Python?**
El script lo instala solo si hace falta (con permisos de administrador).
Si no, puede bajarlo de python.org o con el gestor de paquetes:
```bash
# Debian/Ubuntu
sudo apt install python3 python3-pip python3-venv

# Fedora
sudo dnf install python3 python3-pip
```

**¿Funciona con cualquier mando?**
Sí, si tu celu lo reconoce (Bluetooth, USB-OTG, o mando incorporado como el Razer Kishi), el server lo ve.

**¿Y en Windows?**
Este repo es para Linux. Si querés Windows, necesitás ViGEmBus — hay instrucciones en `WINDOWS_SUPPORT.md`.

---

## Desarrollo (solo si querés compilar la app)

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Requerimientos**: JDK 17, Android SDK 34, Gradle 8.11

Licencia MIT
