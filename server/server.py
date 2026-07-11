import asyncio
import json
import math
import struct
import time
import socket
import io
import base64
import hashlib
import threading
import os
import sys
import platform
import subprocess
import signal
import webbrowser

SYSTEM = platform.system()

def check_vigembus_installed() -> bool:
    if SYSTEM != "Windows":
        return True
    try:
        import winreg
        key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"System\CurrentControlSet\Services\ViGEmBus")
        winreg.CloseKey(key)
        return True
    except FileNotFoundError:
        return False

def install_vigembus_driver():
    import urllib.request
    import tempfile
    import ctypes
    
    url = "https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_Setup_1.22.0.exe"
    temp_dir = tempfile.gettempdir()
    installer_path = os.path.join(temp_dir, "ViGEmBus_Setup_1.22.0.exe")
    
    print("[*] ViGEmBus driver not found. Downloading installer...")
    try:
        urllib.request.urlretrieve(url, installer_path)
        print(f"[*] Downloaded installer to {installer_path}")
        print("[*] Launching installer with Admin privileges. Please approve the UAC prompt...")
        
        ret = ctypes.windll.shell32.ShellExecuteW(
            None, "runas", installer_path, "/passive /norestart", None, 1
        )
        if ret <= 32:
            print("[!] UAC elevation failed or was denied by the user. ViGEmBus driver is required on Windows.")
            sys.exit(1)
        else:
            print("[*] ViGEmBus installer launched. Please complete the installation.")
            print("[*] Once installed, restart this server script.")
            sys.exit(0)
    except Exception as e:
        print(f"[!] Error downloading or installing ViGEmBus: {e}")
        sys.exit(1)

def auto_install_dependencies():
    required = ["qrcode"]
    if SYSTEM == "Linux":
        required.append("python-uinput")
    elif SYSTEM == "Windows":
        required.append("vgamepad")
    
    missing = []
    for pkg in required:
        import_name = pkg
        if pkg == "python-uinput":
            import_name = "uinput"
        elif pkg == "vgamepad":
            import_name = "vgamepad"
        
        try:
            globals()[import_name] = __import__(import_name)
        except ImportError:
            missing.append(pkg)
            
    if missing:
        print(f"[*] Missing dependencies for {SYSTEM}: {missing}. Installing...")
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install"] + missing)
            print("[*] Successfully installed dependencies.")
        except Exception as e:
            print(f"[!] Error installing dependencies: {e}. Trying with --user...")
            try:
                subprocess.check_call([sys.executable, "-m", "pip", "install", "--user"] + missing)
                print("[*] Successfully installed dependencies with --user.")
            except Exception as e2:
                print(f"[!] Error installing dependencies with --user: {e2}")
                sys.exit(1)

auto_install_dependencies()

import qrcode

if SYSTEM == "Windows" and not check_vigembus_installed():
    install_vigembus_driver()

if SYSTEM == "Linux":
    import uinput
elif SYSTEM == "Windows":
    import vgamepad
# -------------------------------------------------------------------
# Button bit definitions
# -------------------------------------------------------------------
BIT_MAP = {
    'A':      1 << 0,
    'B':      1 << 1,
    'X':      1 << 2,
    'Y':      1 << 3,
    'LB':     1 << 4,
    'RB':     1 << 5,
    'LT(d)':  1 << 6,
    'RT(d)':  1 << 7,
    'SELECT': 1 << 8,
    'START':  1 << 9,
    'L3':     1 << 10,
    'R3':     1 << 11,
    'DPAD_UP':    1 << 12,
    'DPAD_DOWN':  1 << 13,
    'DPAD_LEFT':  1 << 14,
    'DPAD_RIGHT': 1 << 15,
    'HOME':   1 << 16,
}

if SYSTEM == "Linux":
    XBOX_EVENTS = [
        uinput.BTN_A, uinput.BTN_B, uinput.BTN_X, uinput.BTN_Y,
        uinput.BTN_TL, uinput.BTN_TR,
        uinput.BTN_SELECT, uinput.BTN_START, uinput.BTN_MODE,
        uinput.BTN_THUMBL, uinput.BTN_THUMBR,
        uinput.ABS_X + (-32768, 32767, 0, 0),
        uinput.ABS_Y + (-32768, 32767, 0, 0),
        uinput.ABS_RX + (-32768, 32767, 0, 0),
        uinput.ABS_RY + (-32768, 32767, 0, 0),
        uinput.ABS_Z + (0, 255, 0, 0),
        uinput.ABS_RZ + (0, 255, 0, 0),
        uinput.ABS_HAT0X + (-1, 1, 0, 0),
        uinput.ABS_HAT0Y + (-1, 1, 0, 0),
    ]
else:
    XBOX_EVENTS = []


class GamepadState:
    def __init__(self):
        self.buttons = 0
        self.lx = 0
        self.ly = 0
        self.rx = 0
        self.ry = 0
        self.lt = 0
        self.rt = 0
        self.gamepad_id = 0
        self.last_seen = 0.0

    def apply_android_report(self, data: bytes):
        if len(data) < 16:
            return
        self.gamepad_id = data[1]
        self.buttons = struct.unpack_from('<I', data, 2)[0]
        self.lx = struct.unpack_from('<h', data, 6)[0]
        self.ly = struct.unpack_from('<h', data, 8)[0]
        self.rx = struct.unpack_from('<h', data, 10)[0]
        self.ry = struct.unpack_from('<h', data, 12)[0]
        self.lt = data[14]
        self.rt = data[15]
        self.last_seen = time.time()

    def as_dict(self):
        pressed = [k for k, v in BIT_MAP.items() if self.buttons & v]
        connected = (time.time() - self.last_seen) < 2.0
        dpad = self._dpad_str()
        mag_left = min(math.hypot(self.lx / 32767, self.ly / 32767), 1.0)
        mag_right = min(math.hypot(self.rx / 32767, self.ry / 32767), 1.0)
        angle_left = (math.degrees(math.atan2(-self.ly, self.lx)) % 360) if mag_left > 0.001 else 0.0
        angle_right = (math.degrees(math.atan2(-self.ry, self.rx)) % 360) if mag_right > 0.001 else 0.0
        return {
            'lx': self.lx, 'ly': self.ly,
            'rx': self.rx, 'ry': self.ry,
            'left_angle': round(angle_left, 1),
            'left_mag': round(mag_left, 3),
            'right_angle': round(angle_right, 1),
            'right_mag': round(mag_right, 3),
            'lt': self.lt, 'rt': self.rt,
            'dpad': dpad,
            'buttons': pressed,
            'gamepad_id': self.gamepad_id,
            'connected': connected,
        }

    def _dpad_str(self):
        if self.buttons & BIT_MAP['DPAD_UP']:
            if self.buttons & BIT_MAP['DPAD_RIGHT']: return 'up-right'
            if self.buttons & BIT_MAP['DPAD_LEFT']: return 'up-left'
            return 'up'
        if self.buttons & BIT_MAP['DPAD_DOWN']:
            if self.buttons & BIT_MAP['DPAD_RIGHT']: return 'down-right'
            if self.buttons & BIT_MAP['DPAD_LEFT']: return 'down-left'
            return 'down'
        if self.buttons & BIT_MAP['DPAD_LEFT']: return 'left'
        if self.buttons & BIT_MAP['DPAD_RIGHT']: return 'right'
        return 'neutral'


# -------------------------------------------------------------------
# Virtual Gamepad (runs in its own thread)
# -------------------------------------------------------------------

class VirtualGamepad:
    def __init__(self, gamepad_id: int):
        self.gamepad_id = gamepad_id
        self.device = None
        self._lock = threading.Lock()
        self._create()

    def _create(self):
        try:
            if SYSTEM == "Linux":
                self.device = uinput.Device(
                    XBOX_EVENTS,
                    name=f'GamepadBridge Gamepad {self.gamepad_id}',
                    vendor=0x045e, product=0x028e, version=0x110,
                )
                print(f'[uinput] Created virtual gamepad #{self.gamepad_id}')
            elif SYSTEM == "Windows":
                self.device = vgamepad.VX360Gamepad()
                print(f'[vgamepad] Created virtual gamepad #{self.gamepad_id}')
        except Exception as e:
            tag = "uinput" if SYSTEM == "Linux" else "vgamepad"
            print(f'[{tag}] Failed to create gamepad #{self.gamepad_id}: {e}')
            self.device = None

    def update(self, state: GamepadState):
        if self.device is None:
            return
        with self._lock:
            try:
                if SYSTEM == "Linux":
                    d = self.device
                    d.emit(uinput.BTN_A, 1 if state.buttons & BIT_MAP['A'] else 0)
                    d.emit(uinput.BTN_B, 1 if state.buttons & BIT_MAP['B'] else 0)
                    d.emit(uinput.BTN_X, 1 if state.buttons & BIT_MAP['X'] else 0)
                    d.emit(uinput.BTN_Y, 1 if state.buttons & BIT_MAP['Y'] else 0)
                    d.emit(uinput.BTN_TL, 1 if state.buttons & BIT_MAP['LB'] else 0)
                    d.emit(uinput.BTN_TR, 1 if state.buttons & BIT_MAP['RB'] else 0)
                    d.emit(uinput.BTN_SELECT, 1 if state.buttons & BIT_MAP['SELECT'] else 0)
                    d.emit(uinput.BTN_START, 1 if state.buttons & BIT_MAP['START'] else 0)
                    d.emit(uinput.BTN_MODE, 1 if state.buttons & BIT_MAP['HOME'] else 0)
                    d.emit(uinput.BTN_THUMBL, 1 if state.buttons & BIT_MAP['L3'] else 0)
                    d.emit(uinput.BTN_THUMBR, 1 if state.buttons & BIT_MAP['R3'] else 0)
                    d.emit(uinput.ABS_X, state.lx)
                    d.emit(uinput.ABS_Y, state.ly)
                    d.emit(uinput.ABS_RX, state.rx)
                    d.emit(uinput.ABS_RY, state.ry)
                    d.emit(uinput.ABS_Z, state.lt)
                    d.emit(uinput.ABS_RZ, state.rt)
                    hx, hy = 0, 0
                    if state.buttons & BIT_MAP['DPAD_LEFT']: hx = -1
                    elif state.buttons & BIT_MAP['DPAD_RIGHT']: hx = 1
                    if state.buttons & BIT_MAP['DPAD_UP']: hy = -1
                    elif state.buttons & BIT_MAP['DPAD_DOWN']: hy = 1
                    d.emit(uinput.ABS_HAT0X, hx)
                    d.emit(uinput.ABS_HAT0Y, hy)
                    d.syn()
                elif SYSTEM == "Windows":
                    d = self.device
                    
                    mapping = {
                        'A': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_A,
                        'B': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_B,
                        'X': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_X,
                        'Y': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_Y,
                        'LB': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
                        'RB': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
                        'SELECT': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
                        'START': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_START,
                        'HOME': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_GUIDE,
                        'L3': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
                        'R3': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
                        'DPAD_UP': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
                        'DPAD_DOWN': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
                        'DPAD_LEFT': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
                        'DPAD_RIGHT': vgamepad.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
                    }
                    
                    for name, button_val in mapping.items():
                        if state.buttons & BIT_MAP[name]:
                            d.press_button(button=button_val)
                        else:
                            d.release_button(button=button_val)
                    
                    d.left_joystick(x_value=state.lx, y_value=-state.ly)
                    d.right_joystick(x_value=state.rx, y_value=-state.ry)
                    
                    d.left_trigger(value=state.lt)
                    d.right_trigger(value=state.rt)
                    
                    d.update()
            except Exception:
                pass



# -------------------------------------------------------------------
# Server
# -------------------------------------------------------------------

class GamepadBridgeServer:
    MAX_GAMEPADS = 4

    def __init__(self):
        self.states = [GamepadState() for _ in range(self.MAX_GAMEPADS)]
        self.virtual = [VirtualGamepad(i) for i in range(self.MAX_GAMEPADS)]
        self.ws_clients: set[asyncio.StreamWriter] = set()
        self._ws_lock = threading.Lock()
        self._tcp_slots: dict[str, int] = {}  # addr -> server slot
        self._tcp_writers: dict[int, asyncio.StreamWriter] = {}  # slot -> writer
        self._tcp_lock = threading.Lock()
        self._loop: asyncio.AbstractEventLoop | None = None

    def _allocate_tcp_slot(self, addr: str) -> int | None:
        with self._tcp_lock:
            used = set(self._tcp_slots.values())
            for slot in range(self.MAX_GAMEPADS):
                if slot not in used:
                    self._tcp_slots[addr] = slot
                    self.states[slot].gamepad_id = slot
                    return slot
            return None

    def _free_tcp_slot(self, addr: str):
        with self._tcp_lock:
            slot = self._tcp_slots.pop(addr, None)
            if slot is not None:
                self.states[slot] = GamepadState()
                self.states[slot].gamepad_id = slot
                self._tcp_writers.pop(slot, None)

    # -- rumble (force feedback) ------------------------------------

    def _register_rumble_callbacks(self):
        if SYSTEM != "Windows":
            return
        for slot, vgp in enumerate(self.virtual):
            if vgp.device:
                def _cb(client, target, large_motor, small_motor, led_number, user_data, _s=slot):
                    self._on_rumble(_s, large_motor, small_motor)
                vgp.device.register_notification(_cb)
                print(f'[vgamepad] Registered rumble callback for gamepad #{slot}')

    def _on_rumble(self, slot: int, large_motor: int, small_motor: int):
        if self._loop is None or not self._loop.is_running():
            return
        async def send():
            await self._send_rumble(slot, large_motor, small_motor)
        asyncio.run_coroutine_threadsafe(send(), self._loop)

    async def _send_rumble(self, slot: int, large: int, small: int):
        writer = self._tcp_writers.get(slot)
        if writer is None:
            return
        try:
            writer.write(bytes([0x01, slot, large, small]))
            await writer.drain()
        except Exception:
            pass

    # -- uinput thread worker ---------------------------------------

    def _uinput_worker(self, gid: int):
        vgp = self.virtual[gid]
        vgp.update(self.states[gid])

    # -- TCP gamepad data handler (port 60001) -----------------------

    async def handle_gamepad_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        addr = writer.get_extra_info('peername')
        addr_key = f'{addr[0]}:{addr[1]}'
        print(f'[tcp] Gamepad client connected: {addr_key}')
        slot = self._allocate_tcp_slot(addr_key)
        if slot is None:
            print(f'[tcp] No free slots for {addr_key}, rejecting')
            writer.close()
            return
        print(f'[tcp] Assigned slot {slot} to {addr_key}')
        player_num = slot + 1
        self._tcp_writers[slot] = writer
        writer.write(bytes([player_num]))
        await writer.drain()
        loop = asyncio.get_running_loop()
        try:
            while True:
                data = await asyncio.wait_for(reader.readexactly(20), timeout=600)
                if len(data) < 20:
                    break
                self.states[slot].apply_android_report(data)
                loop.run_in_executor(None, self._uinput_worker, slot)
        except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionResetError, EOFError):
            pass
        finally:
            print(f'[tcp] Gamepad client disconnected: {addr_key} (slot {slot})')
            self._free_tcp_slot(addr_key)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    # -- HTTP / WebSocket handler (port 8080) ------------------------

    async def handle_http(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            request_line = await asyncio.wait_for(reader.readline(), timeout=10)
            if not request_line:
                return
            method, path, _ = request_line.decode('ascii', errors='replace').strip().split(' ', 2)
            headers = {}
            while True:
                line = (await reader.readline()).decode('ascii', errors='replace').strip()
                if not line:
                    break
                if ':' in line:
                    k, v = line.split(':', 1)
                    headers[k.strip().lower()] = v.strip()

            if path in ('/dashboard', '/'):
                await self._serve_dashboard(writer)
            elif path == '/ws':
                await self._handle_ws(reader, writer, headers)
            elif path == '/api/state':
                await self._serve_api_state(writer)
            else:
                writer.write(b'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n')
                await writer.drain()
        except Exception as e:
            print(f'[http] Error: {e}')
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def _serve_dashboard(self, writer):
        local_ip = _detect_local_ip()
        qr_text = f'{local_ip}:60001'
        qr = qrcode.QRCode(border=1, box_size=10)
        qr.add_data(qr_text)
        qr.make(fit=True)
        qr_img = qr.make_image()
        buf = io.BytesIO()
        qr_img.save(buf, format='PNG')
        qr_b64 = base64.b64encode(buf.getvalue()).decode()
        qr_data_uri = f'data:image/png;base64,{qr_b64}'

        html = _dashboard_html().replace('{qr_data_uri}', qr_data_uri).replace('{qr_text}', qr_text)
        body = html.encode('utf-8')
        resp = (
            'HTTP/1.1 200 OK\r\n'
            'Content-Type: text/html; charset=utf-8\r\n'
            f'Content-Length: {len(body)}\r\n'
            'Cache-Control: no-cache, no-store, must-revalidate\r\n'
            '\r\n'
        ).encode() + body
        writer.write(resp)
        await writer.drain()

    async def _serve_api_state(self, writer):
        states_list = [s.as_dict() for s in self.states]
        data = json.dumps({'gamepads': states_list}).encode('utf-8')
        resp = (
            'HTTP/1.1 200 OK\r\n'
            'Content-Type: application/json\r\n'
            f'Content-Length: {len(data)}\r\n'
            '\r\n'
        ).encode() + data
        writer.write(resp)
        await writer.drain()

    async def _handle_ws(self, reader, writer, headers):
        key = headers.get('sec-websocket-key', '')
        accept = self._ws_accept(key)
        resp = (
            'HTTP/1.1 101 Switching Protocols\r\n'
            'Upgrade: websocket\r\n'
            'Connection: Upgrade\r\n'
            f'Sec-WebSocket-Accept: {accept}\r\n'
            '\r\n'
        ).encode()
        try:
            writer.write(resp)
            await writer.drain()
        except Exception:
            return
        self.ws_clients.add(writer)
        print(f'[ws] Dashboard client connected ({len(self.ws_clients)} total)')
        try:
            while True:
                frame = await asyncio.wait_for(self._read_ws_frame(reader), timeout=120)
                if frame is None:
                    break
                opcode = frame['opcode']
                payload = frame['payload']
                if opcode == 0x8:
                    break
                if payload == 'ping':
                    await self._send_ws(writer, 'pong')
        except (asyncio.TimeoutError, ConnectionResetError, EOFError, asyncio.IncompleteReadError):
            pass
        finally:
            self.ws_clients.discard(writer)
            print(f'[ws] Dashboard client disconnected ({len(self.ws_clients)} total)')

    # -- Broadcast timer (runs every 50ms in event loop) ------------

    async def _broadcast_loop(self):
        while True:
            await asyncio.sleep(0.05)
            if not self.ws_clients:
                continue
            states_list = [s.as_dict() for s in self.states]
            payload = json.dumps({'gamepads': states_list})
            tasks = [asyncio.create_task(self._send_ws(ws, payload)) for ws in list(self.ws_clients)]
            if tasks:
                await asyncio.wait(tasks, return_when=asyncio.ALL_COMPLETED)

    @staticmethod
    def _ws_accept(key: str) -> str:
        GUID = '258EAFA5-E914-47DA-95CA-5AB5DC11B735'
        sha1 = hashlib.sha1(key.encode() + GUID.encode()).digest()
        return base64.b64encode(sha1).decode()

    @staticmethod
    async def _read_ws_frame(reader):
        try:
            b0, b1 = await reader.readexactly(2)
        except asyncio.IncompleteReadError:
            return None
        opcode = b0 & 0x0F
        masked = b1 & 0x80
        length = b1 & 0x7F
        if length == 126:
            length = struct.unpack('>H', await reader.readexactly(2))[0]
        elif length == 127:
            length = struct.unpack('>Q', await reader.readexactly(8))[0]
        mask = await reader.readexactly(4) if masked else b'\x00\x00\x00\x00'
        payload = await reader.readexactly(length)
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return {'opcode': opcode, 'payload': payload.decode('utf-8', errors='replace')}

    @staticmethod
    async def _send_ws(writer, text):
        try:
            data = text.encode('utf-8')
            length = len(data)
            if length < 126:
                header = bytes([0x81, length])
            elif length < 65536:
                header = bytes([0x81, 126]) + struct.pack('>H', length)
            else:
                header = bytes([0x81, 127]) + struct.pack('>Q', length)
            writer.write(header + data)
            await writer.drain()
        except Exception:
            pass


# -------------------------------------------------------------------
# Dashboard HTML loader
# -------------------------------------------------------------------

_dash_mtime = 0
_dash_cache = ''

def _dashboard_html():
    global _dash_mtime, _dash_cache
    path = os.path.join(os.path.dirname(__file__), 'dashboard.html')
    try:
        st = os.stat(path)
        if st.st_mtime != _dash_mtime:
            with open(path, 'r', encoding='utf-8') as f:
                _dash_cache = f.read()
            _dash_mtime = st.st_mtime
            print(f'[server] Reloaded dashboard.html from disk')
        return _dash_cache
    except (FileNotFoundError, OSError) as e:
        print(f'[server] ERROR: dashboard.html not found at {path}: {e}')
        return '<html><body><h1>Error: dashboard.html not found</h1></body></html>'

def _detect_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0.5)
    try:
        s.connect(('10.0.0.1', 9))
        ip = s.getsockname()[0] if s.getsockname()[0] != '0.0.0.0' else '127.0.0.1'
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

# -------------------------------------------------------------------
# MAIN
# -------------------------------------------------------------------

def _print_connection_qr(ip: str, port: int):
    addr = f'{ip}:{port}'
    print()
    print('=' * 50)
    print('  Connect the Android app to:')
    print(f'  {addr}')
    print('=' * 50)
    qr = qrcode.QRCode(border=1, box_size=2)
    qr.add_data(addr)
    qr.make(fit=True)
    qr.print_ascii(invert=True)
    print()

async def _auto_open_browser(url: str):
    await asyncio.sleep(0.5)
    try:
        webbrowser.open(url)
        print(f'[server] Opened browser to {url}')
    except Exception as e:
        print(f'[server] Could not open browser: {e}')

async def main():
    # try 8080, fall back to random free port
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as stmp:
        try:
            stmp.bind(('0.0.0.0', 8080))
            http_port = 8080
        except OSError:
            stmp.bind(('0.0.0.0', 0))
            http_port = stmp.getsockname()[1]

    # ensure port 60001 is free before binding (blocking, no asyncio)
    for _ in range(30):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(('0.0.0.0', 60001))
            break
        except OSError:
            if _ == 0:
                print('[server] Port 60001 busy, waiting for it to free up...')
            time.sleep(1)
    else:
        print('[server] ERROR: could not bind port 60001 after 30s')
        return

    srv = GamepadBridgeServer()
    srv._loop = asyncio.get_running_loop()
    srv._register_rumble_callbacks()
    reuse_port_opt = (SYSTEM == 'Linux')
    tcp_server = await asyncio.start_server(srv.handle_gamepad_client, '0.0.0.0', 60001, reuse_port=reuse_port_opt)
    http_server = await asyncio.start_server(srv.handle_http, '0.0.0.0', http_port, reuse_port=reuse_port_opt)
    local_ip = _detect_local_ip()
    tcp_port = 60001
    print(f'[server] TCP gamepad listener on :{tcp_port}')
    print(f'[server] HTTP dashboard on :{http_port}')
    print(f'[server] Open http://{local_ip}:{http_port}/ in a browser')
    _print_connection_qr(local_ip, tcp_port)
    asyncio.create_task(_auto_open_browser(f'http://{local_ip}:{http_port}/'))
    try:
        with open('/tmp/gamepad-bridge-port', 'w') as f:
            f.write(str(http_port))
    except OSError:
        pass

    shutdown_event = asyncio.Event()

    def _signal_handler():
        print(f'[server] Shutting down...')
        shutdown_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _signal_handler)
        except NotImplementedError:
            pass

    async def _waiter():
        await shutdown_event.wait()
        tcp_server.close()
        http_server.close()
        await tcp_server.wait_closed()
        await http_server.wait_closed()

    try:
        async with asyncio.TaskGroup() as tg:
            tg.create_task(tcp_server.serve_forever())
            tg.create_task(http_server.serve_forever())
            tg.create_task(srv._broadcast_loop())
            tg.create_task(_waiter())
    except BaseException:
        pass
    print(f'[server] Goodbye')


if __name__ == '__main__':
    asyncio.run(main())
