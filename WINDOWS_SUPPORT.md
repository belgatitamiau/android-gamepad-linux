# Windows Support (ViGEmBus)

> Instrucciones para que otra ia entienda el proyecto e implemente
> soporte nativo para Windows usando vgamepad (ViGEmBus).

## Visión general del proyecto

**GamepadBridge** — servidor TCP que recibe estado de gamepad desde una app
Android y lo reenvía a gamepads virtuales en la PC.

```
App Android → TCP :60001 → Server (Python) → uinput (Linux) / vgamepad (Windows)
                              ↕
                        Web Dashboard (HTTP)
```

- 4 slots de gamepad, uno por cliente conectado
- El dashboard HTTP muestra el estado en vivo de los 4 gamepads
- El servidor es la única pieza que necesita ser multiplataforma

## Cómo está estructurado server.py

El archivo `server/server.py` contiene todo en un solo archivo (~790 líneas):

### Clase `GamepadBridgeServer`

```python
class GamepadBridgeServer:
    def __init__(self):
        self.gamepads = [VirtualGamepad(i) for i in range(4)]
        self.clients = {}  # {writer: slot_index}
        self._lock = threading.Lock()
```

- **`handle_gamepad_client(reader, writer)`** — corutina asyncio, maneja un
  cliente TCP. Lee 1 byte de player number, luego entra en un loop recibiendo
  `GamepadState` (struct binario de 40 bytes) y llama a `self._update_gamepad()`.
- **`_update_gamepad(slot, state)`** — llama a `self.gamepads[slot].apply(state)`.
- **`handle_http(reader, writer)`** — sirve el HTML/JS del dashboard y el
  endpoint `/api/state`.
- **`_broadcast_loop()`** — cada 16ms envía el estado actual de los 4 gamepads
  a todos los clientes HTTP conectados via SSE (Server-Sent Events).
- **`_on_connect(slot)`** / **`_on_disconnect(slot)`** — hooks para logging.

### Clase `VirtualGamepad`

```python
class VirtualGamepad:
    def __init__(self, slot):
        # Linux: crea dispositivo uinput con botones y ejes de Xbox 360
        self.device = uinput.UInput(...)
    
    def apply(self, state: GamepadState):
        # Traduce GamepadState a uinput events
        ...
    
    def close(self):
        self.device.destroy()
```

Esta es la clase que **debe ser reemplazada en Windows** para usar `vgamepad`
en lugar de `uinput`.

### `GamepadState` (namedtuple)

```python
GamepadState = namedtuple('GamepadState', [
    'buttons', 'lt', 'rt', 'lx', 'ly', 'rx', 'ry',
    'left_mag', 'left_angle', 'right_mag', 'right_angle', 'dpad'
])
```

- `buttons`: entero de 32 bits, cada bit es un botón (A, B, X, Y, LB, RB,
  SELECT, START, HOME, etc.)
- `lt`, `rt`: gatillos analógicos (0-255)
- `lx`, `ly`, `rx`, `ry`: ejes analógicos (-32768 a 32767)
- `left_mag`, `right_mag`: magnitud del stick (0.0-1.0)
- `left_angle`, `right_angle`: ángulo del stick en grados (0-360)
- `dpad`: string: `'up'`, `'down'`, `'left'`, `'right'`, `'neutral'`

### Protocolo TCP (cómo se comunican app y server)

1. **Handshake**: cliente envía 1 byte = player number (0-3) o 0xFF para
   auto-asignar.
2. **Loop**: cliente envía `GamepadState` serializado en 40 bytes (struct
   binario: 1B buttons, luego 9 floats little-endian, 1B pad).
3. **Heartbeat**: si no hay gamepad físico conectado, la app envía un estado
   vacío cada 1 segundo.

### Protocolo HTTP

- `GET /` → HTML del dashboard (template con QR embebido)
- `GET /api/state` → JSON con estado de los 4 gamepads
- `GET /api/state?stream=1` → SSE (Server-Sent Events)

## Lo que cambia en Windows

### 1. Dependencias

- `requirements-linux.txt`: `qrcode[pil]`, `python-uinput`
- `requirements-windows.txt`: `qrcode[pil]`, `vgamepad`

### 2. Detección automática de SO

```python
import platform
SYSTEM = platform.system()
```

### 3. VirtualGamepad para Windows (vgamepad)

En lugar de `uinput.UInput()`, usar `vgamepad.VX360Gamepad()`:

```python
class VirtualGamepad:
    def __init__(self, slot):
        if SYSTEM == 'Windows':
            import vgamepad as vg
            self.device = vg.VX360Gamepad()
            self._slot = slot
            self.device.reset()
        else:
            self.device = uinput.UInput(...)

    def apply(self, state):
        if SYSTEM == 'Windows':
            # Botones
            for btn_name, btn_bit in BIT_MAP.items():
                if hasattr(vg.XUSB_BUTTON, btn_name):
                    self.device.press_button(getattr(vg.XUSB_BUTTON, btn_name)) \
                        if state.buttons & btn_bit else \
                        self.device.release_button(getattr(vg.XUSB_BUTTON, btn_name))
            # Gatillos
            self.device.left_trigger(state.lt)
            self.device.right_trigger(state.rt)
            # Sticks (Y invertido en Windows)
            self.device.left_joystick(state.lx, -state.ly)
            self.device.right_joystick(state.rx, -state.ry)
            # D-Pad
            dpad_map = {'up': vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP, ...}
            self.device.update()
        else:
            # Linux uinput logic
            ...

    def close(self):
        if SYSTEM == 'Windows':
            self.device.reset()
            self.device = None
        else:
            self.device.destroy()
```

### 4. Instalación de ViGEmBus (driver kernel)

En Windows, ViGEmBus necesita un driver kernel. El server debe detectar si está
instalado (vía Registry: `HKLM\SYSTEM\CurrentControlSet\Services\viogem`) y si
no, descargar el instalador desde `https://github.com/nefarius/ViGEmBus/releases`
y ejecutarlo.

```python
def _ensure_vigem():
    if SYSTEM != 'Windows':
        return
    import winreg
    try:
        winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
            r'SYSTEM\CurrentControlSet\Services\ViGEmBus')
        return  # ya instalado
    except FileNotFoundError:
        pass
    print('[setup] ViGEmBus no encontrado. Instalando...')
    import urllib.request, subprocess, tempfile, shutil
    url = 'https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_ewdk_fre_22100.msi'
    with tempfile.NamedTemporaryFile(suffix='.msi', delete=False) as f:
        urllib.request.urlretrieve(url, f.name)
        subprocess.run(['msiexec', '/passive', '/i', f.name], check=True)
```

### 5. Configuración `reuse_port`

Windows no soporta `SO_REUSEPORT`. Usar `reuse_port=platform.system() == 'Linux'`.

```python
reuse_port_opt = (SYSTEM == 'Linux')
tcp_server = await asyncio.start_server(..., reuse_port=reuse_port_opt)
http_server = await asyncio.start_server(..., reuse_port=reuse_port_opt)
```

### 6. Ruta del archivo de puerto

`/tmp/gamepad-bridge-port` es Linux-only. En Windows usar
`%TEMP%\gamepad-bridge-port.txt`.

## Archivos del proyecto

| Archivo | Propósito |
|---|---|
| `server/server.py` | Servidor principal (multiplataforma) |
| `requirements-linux.txt` | Deps para Linux |
| `requirements-windows.txt` | Deps para Windows |
| `android/` | App Android (Kotlin) |
| `gamepad-bridge.service` | Systemd unit (Linux) |

## Resumen de cambios necesarios para Windows

1. Importar `platform` y `sys` al inicio de `server.py`
2. Agregar `SYSTEM = platform.system()`
3. Crear `VirtualGamepad` con rama `if SYSTEM == 'Windows'` usando `vgamepad`
4. Implementar `_ensure_vigem()` para instalar ViGEmBus automáticamente
5. Condicionar `reuse_port` a Linux
6. Crear `requirements-windows.txt` con `qrcode[pil]` y `vgamepad`
7. Ajustar ruta del archivo de puerto en Windows
