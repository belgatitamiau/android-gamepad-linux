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
from collections import defaultdict

import uinput

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
            self.device = uinput.Device(
                XBOX_EVENTS,
                name=f'GamepadBridge Gamepad {self.gamepad_id}',
                vendor=0x045e, product=0x028e, version=0x110,
            )
            print(f'[uinput] Created virtual gamepad #{self.gamepad_id}')
        except Exception as e:
            print(f'[uinput] Failed to create gamepad #{self.gamepad_id}: {e}')
            self.device = None

    def update(self, state: GamepadState):
        if self.device is None:
            return
        with self._lock:
            d = self.device
            try:
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

    # -- uinput thread worker ---------------------------------------

    def _uinput_worker(self, gid: int):
        vgp = self.virtual[gid]
        vgp.update(self.states[gid])

    # -- TCP gamepad data handler (port 60001) -----------------------

    async def handle_gamepad_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        addr = writer.get_extra_info('peername')
        print(f'[tcp] Gamepad client connected: {addr}')
        loop = asyncio.get_running_loop()
        try:
            while True:
                data = await asyncio.wait_for(reader.readexactly(20), timeout=600)
                if len(data) < 20:
                    break
                gid = data[1]
                if gid < 0 or gid >= self.MAX_GAMEPADS:
                    continue
                self.states[gid].apply_android_report(data)
                loop.run_in_executor(None, self._uinput_worker, gid)
        except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionResetError, EOFError):
            pass
        finally:
            print(f'[tcp] Gamepad client disconnected: {addr}')
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
            elif path == '/controller.svg':
                with open(os.path.join(os.path.dirname(__file__), 'controller.svg'), 'rb') as f:
                    svg_data = f.read()
                resp = (
                    'HTTP/1.1 200 OK\r\n'
                    'Content-Type: image/svg+xml\r\n'
                    f'Content-Length: {len(svg_data)}\r\n'
                    '\r\n'
                ).encode() + svg_data
                writer.write(resp)
                await writer.drain()
                return
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
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
        except:
            ip = 'localhost'
        s.close()
        qr_text = f'{ip}:60001'

        import qrcode
        qr_img = qrcode.make(qr_text)
        buf = io.BytesIO()
        qr_img.save(buf, format='PNG')
        qr_b64 = base64.b64encode(buf.getvalue()).decode()
        qr_data_uri = f'data:image/png;base64,{qr_b64}'

        body = DASHBOARD_HTML.replace('{qr_data_uri}', qr_data_uri).replace('{qr_text}', qr_text).encode('utf-8')
        resp = (
            'HTTP/1.1 200 OK\r\n'
            'Content-Type: text/html; charset=utf-8\r\n'
            f'Content-Length: {len(body)}\r\n'
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
# DASHBOARD HTML
# -------------------------------------------------------------------

DASHBOARD_HTML = r'''<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gamepad Bridge</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#12070e;color:#e8c8d8;font-family:'Segoe UI',sans-serif;padding:10px;min-height:100vh}
h1{font-size:1.1rem;color:#ff69b4;text-align:center;margin-bottom:8px;letter-spacing:2px;text-shadow:0 0 12px #ff69b455}
.gw{position:relative;margin-bottom:8px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#qrOver{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:10;background:#1a0a12ee;border-radius:12px;padding:10px;border:1px solid #ff69b466;text-align:center;backdrop-filter:blur(4px);transition:opacity .3s}
#qrOver img{width:300px;height:300px;background:#fff;border-radius:6px;display:block}
#qrOver .l{font-size:.5rem;color:#b06080;margin-top:2px}
#qrTog{position:absolute;top:4px;right:4px;background:none;border:none;color:#ff69b4;font-size:.9rem;cursor:pointer;z-index:12;line-height:1;padding:2px 6px;border-radius:4px}
#qrTog:hover{background:#ff69b433}
#qrBtn{display:none;position:fixed;bottom:12px;right:12px;z-index:20;background:#ff69b4;color:#1a0a12;border:none;border-radius:8px;padding:8px 12px;font-size:.7rem;font-weight:600;cursor:pointer}
#qrBtn:hover{background:#ff1493}
.sg{display:grid;grid-template-columns:1fr 1fr;gap:2px}
.sl{font-size:.5rem;color:#b06080}
.sv{font-size:.6rem;font-weight:600;color:#f0d0d8}
#lat{color:#ff69b4;font-family:monospace;font-size:.7rem}

.gc{background:#1e0a14;border-radius:12px;padding:6px;border:1px solid #ff69b422;display:flex;flex-direction:column;position:relative;min-height:400px}
.gc .nc{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:.85rem;color:#604050;letter-spacing:1px;z-index:1}
.xb{display:flex;flex-direction:column;gap:2px;position:relative;z-index:2;flex:1}
.xb-h{font-size:.55rem;color:#ff69b488;text-align:center;letter-spacing:2px;padding:2px 0;text-transform:uppercase}
.xb-wrap{position:relative;flex:1;overflow:hidden;border-radius:12px}
.xb-wrap img{width:100%;height:100%;object-fit:contain;display:block;filter:brightness(0.7) contrast(1.1)}
.xb-ov{position:absolute;inset:0;pointer-events:none}

.xb-stick{position:absolute;width:9%;height:9%}
.xb-stick canvas{width:100%;height:100%;display:block}
#lc0,#lc1,#lc2,#lc3{left:19.5%;top:38%}
#rc0,#rc1,#rc2,#rc3{left:71%;top:52%}

.xb-abxy{position:absolute;left:63.5%;top:21%;display:grid;grid-template-columns:14px 14px;gap:2px}
.xb-abxy>div{width:14px;height:14px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:.35rem;font-weight:700;border:1px solid #5558;transition:all .08s;color:#ccc}
.xb-abxy>div.on{color:#fff;border-color:#fff;box-shadow:0 0 8px #ff69b488}
.xb-abxy .ya{background:#c8960e}
.xb-abxy .ya.on{background:#ffe066}
.xb-abxy .xa{background:#1a6ab0}
.xb-abxy .xa.on{background:#3a9aff}
.xb-abxy .ba{background:#b02020}
.xb-abxy .ba.on{background:#ff4040}
.xb-abxy .aa{background:#1a8a30}
.xb-abxy .aa.on{background:#30e060}

.xb-dpad{position:absolute;left:35%;top:55%;display:grid;grid-template-columns:12px 12px 12px;grid-template-rows:12px 12px 12px;gap:1px}
.xb-dpad>div{width:12px;height:12px;border-radius:1px;background:#3338;display:flex;align-items:center;justify-content:center;font-size:.28rem;color:#666;border:1px solid #5554;transition:all .08s}
.xb-dpad>div.on{background:#ff69b466;color:#ff69b4;border-color:#ff69b488}

.xb-mid{position:absolute;left:46.5%;top:44%;display:flex;gap:5px;align-items:center}
.xb-mid-btn{width:5%;padding-bottom:5%;border-radius:3px;background:#3338;border:1px solid #5554;display:flex;align-items:center;justify-content:center;font-size:.25rem;color:#666;transition:all .08s;position:relative}
.xb-mid-btn.on{background:#ff69b444;color:#ff69b4;border-color:#ff69b466}
.xb-mid-btn span{position:absolute;inset:0;display:flex;align-items:center;justify-content:center}

.xb-home{position:absolute;left:48%;top:32%;width:4%;padding-bottom:4%;border-radius:50%;background:#3338;border:1px solid #5556;display:flex;align-items:center;justify-content:center;font-size:.25rem;color:#888;transition:all .08s}
.xb-home.on{background:#39ff7f44;border-color:#39ff7f88;color:#39ff7f;box-shadow:0 0 6px #39ff7f66}

.xb-lr{position:absolute;display:flex;gap:4px}
.xb-lr>div{font-size:.4rem;color:#888;text-align:center;padding:1px 5px;border-radius:2px;background:#3338;min-width:20px;border:1px solid #5554;transition:all .08s}
.xb-lr>div.on{background:#ff69b444;color:#ff69b4;border-color:#ff69b466}
#lw0,#lw1,#lw2,#lw3{left:15%;top:16%}
#rw0,#rw1,#rw2,#rw3{left:75%;top:16%}

.xb-trig{position:absolute;height:4px;display:flex;gap:4px}
.xb-trig>div{flex:1;background:#222;border-radius:2px;overflow:hidden;width:50px}
.xb-trig>div>div{height:100%;border-radius:2px;transition:width .05s}
.xb-trig .ltf{background:linear-gradient(90deg,#ff69b4,#ff1493)}
.xb-trig .rtf{background:linear-gradient(90deg,#ff69b4,#ff1493)}
#trig0,#trig1,#trig2,#trig3{left:12%;right:12%;top:12%}

.xb-sel{position:absolute;left:44%;top:50%;display:flex;gap:6px}
</style>
</head>
<body>
<h1>🌸 Gamepad Bridge</h1>
<div class="gw">
  <div class="g2" id="gpGrid"></div>
  <div id="qrOver">
    <button id="qrTog">✕</button>
    <img src="{qr_data_uri}" alt="QR">
    <div class="l" id="ql">{qr_text}</div>
    <div class="sg" style="margin-top:4px"><div><div class="sl">Lat</div><div class="sv" id="lat">--</div></div><div><div class="sl">Act</div><div class="sv" id="ac">0/4</div></div></div>
  </div>
</div>
<button id="qrBtn">📱 QR</button>
<script>
const SVG_PATH='/controller.svg';
const gpGrid=document.getElementById('gpGrid');
for(let i=0;i<4;i++){
  const c=document.createElement('div');c.className='gc';c.id='gc'+i;
  c.innerHTML='<div class="nc" id="nc'+i+'">not connected</div><div class="xb" id="gp'+i+'" style="display:none"><div class="xb-h">GAMEPAD '+(i+1)+'</div><div class="xb-wrap"><img src="'+SVG_PATH+'" alt="controller"><div class="xb-ov"><div class="xb-trig" id="trig'+i+'"><div><div class="ltf" id="ltf'+i+'" style="width:0%"></div></div><div><div class="rtf" id="rtf'+i+'" style="width:0%"></div></div></div><div class="xb-lr" id="lw'+i+'"><div id="lb'+i+'">LB</div></div><div class="xb-lr" id="rw'+i+'"><div id="rb'+i+'">RB</div></div><div class="xb-abxy"><div id="y'+i+'" class="ya">Y</div><div id="x'+i+'" class="xa">X</div><div id="b'+i+'" class="ba">B</div><div id="a'+i+'" class="aa">A</div></div><div class="xb-dpad"><div></div><div id="dup'+i+'">&#9650;</div><div></div><div id="dl'+i+'">&#9664;</div><div id="dn'+i+'">&#183;</div><div id="dr'+i+'">&#9654;</div><div></div><div id="dd'+i+'">&#9660;</div><div></div></div><div class="xb-stick"><canvas id="lc'+i+'" width="80" height="80"></canvas></div><div class="xb-stick"><canvas id="rc'+i+'" width="80" height="80"></canvas></div><div class="xb-home" id="hm'+i+'">&#9679;</div><div class="xb-mid"><div class="xb-mid-btn" id="sel'+i+'"><span>&#9776;</span></div><div class="xb-mid-btn" id="sta'+i+'"><span>&#10095;</span></div></div></div></div></div>';
  gpGrid.appendChild(c);
  ['l','r'].forEach(sd=>{const cx=document.getElementById(sd+'c'+i).getContext('2d');cx.fillStyle='#222';cx.fillRect(0,0,80,80)});
}
function ds(cx,x,y,m,a){
  const ox=40,oy=40,r=34;
  cx.clearRect(0,0,80,80);
  cx.beginPath();cx.arc(ox,oy,r,0,Math.PI*2);cx.fillStyle='#222';cx.fill();cx.strokeStyle='#444';cx.lineWidth=1.5;cx.stroke();
  cx.beginPath();cx.moveTo(ox-20,oy);cx.lineTo(ox+20,oy);cx.moveTo(ox,oy-20);cx.lineTo(ox,oy+20);cx.strokeStyle='#444';cx.lineWidth=1;cx.stroke();
  if(m>0.01){const rad=a*Math.PI/180,dx=Math.cos(rad)*m*r,dy=-Math.sin(rad)*m*r;
    cx.beginPath();cx.arc(ox+dx,oy+dy,6,0,Math.PI*2);cx.fillStyle='#ff69b4';cx.fill()}
  cx.beginPath();cx.arc(ox,oy,3,0,Math.PI*2);cx.fillStyle='#ff1493';cx.fill()}
function ui(d){
  const g=d.gamepads||[d];
  for(let i=0;i<4;i++){
    const s=g[i]||{};
    const conn=s.connected;
    document.getElementById('nc'+i).style.display=conn?'none':'';
    document.getElementById('gp'+i).style.display=conn?'flex':'none';
    if(conn){
      const bt=new Set(s.buttons||[]);
      const bp=n=>bt.has(n);
      ['A','B','X','Y'].forEach(n=>document.getElementById(n.toLowerCase()+i).classList.toggle('on',bp(n)));
      ['LB','RB'].forEach(n=>document.getElementById(n.toLowerCase()+i).classList.toggle('on',bp(n)));
      document.getElementById('sel'+i).classList.toggle('on',bp('SELECT'));
      document.getElementById('sta'+i).classList.toggle('on',bp('START'));
      document.getElementById('hm'+i).classList.toggle('on',bp('HOME'));
      const lt=Math.min(100,(s.lt||0)/2.55);
      const rt=Math.min(100,(s.rt||0)/2.55);
      document.getElementById('ltf'+i).style.width=lt+'%';
      document.getElementById('rtf'+i).style.width=rt+'%';
      const lcx=document.getElementById('lc'+i).getContext('2d');
      const rcx=document.getElementById('rc'+i).getContext('2d');
      ds(lcx,s.lx||0,s.ly||0,s.left_mag||0,s.left_angle||0);
      ds(rcx,s.rx||0,s.ry||0,s.right_mag||0,s.right_angle||0);
      const dpa=s.dpad||'neutral';
      const dmap={up:'dup'+i,down:'dd'+i,left:'dl'+i,right:'dr'+i,neutral:'dn'+i};
      Object.keys(dmap).forEach(d=>document.getElementById(dmap[d]).classList.toggle('on',d===dpa));
    }
  }
  document.getElementById('ac').textContent=g.filter(x=>x.connected).length+'/4'}
function poll(){
  var x=new XMLHttpRequest();
  x.open('GET','/api/state',true);
  x.onload=function(){try{ui(JSON.parse(x.responseText))}catch(_){}}
  x.send();
}
setInterval(poll,50);
const qo=document.getElementById('qrOver'),qb=document.getElementById('qrBtn'),qt=document.getElementById('qrTog');
qt.onclick=function(){qo.style.display='none';qb.style.display='block'};
qb.onclick=function(){qo.style.display='block';qb.style.display='none'};
</script>
</body>
</html>'''

# -------------------------------------------------------------------
# MAIN
# -------------------------------------------------------------------

async def main():
    srv = GamepadBridgeServer()
    tcp_server = await asyncio.start_server(srv.handle_gamepad_client, '0.0.0.0', 60001)
    http_server = await asyncio.start_server(srv.handle_http, '0.0.0.0', 8080)
    print('[server] TCP gamepad listener on :60001')
    print('[server] HTTP dashboard on :8080')

    async with asyncio.TaskGroup() as tg:
        tg.create_task(tcp_server.serve_forever())
        tg.create_task(http_server.serve_forever())
        tg.create_task(srv._broadcast_loop())


if __name__ == '__main__':
    asyncio.run(main())
