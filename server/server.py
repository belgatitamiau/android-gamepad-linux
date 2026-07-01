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
body{background:#1a0a12;color:#f0d0d8;font-family:'Segoe UI',sans-serif;padding:10px;min-height:100vh}
h1{font-size:1.2rem;color:#ff69b4;text-align:center;margin-bottom:8px;text-shadow:0 0 8px #ff69b466}
.gw{position:relative;margin-bottom:8px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#qrOver{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:10;background:#2a0a12e6;border-radius:12px;padding:10px;border:1px solid #ff69b466;text-align:center;backdrop-filter:blur(4px);transition:opacity .3s}
#qrOver img{width:300px;height:300px;background:#fff;border-radius:6px;display:block}
#qrOver .l{font-size:.5rem;color:#b06080;margin-top:2px}
#qrTog{position:absolute;top:4px;right:4px;background:none;border:none;color:#ff69b4;font-size:.9rem;cursor:pointer;z-index:12;line-height:1;padding:2px 6px;border-radius:4px}
#qrTog:hover{background:#ff69b433}
#qrBtn{display:none;position:fixed;bottom:12px;right:12px;z-index:20;background:#ff69b4;color:#1a0a12;border:none;border-radius:8px;padding:8px 12px;font-size:.7rem;font-weight:600;cursor:pointer}
#qrBtn:hover{background:#ff1493}
.gc{background:#2a0a1a;border-radius:10px;padding:8px;border:1px solid #ff69b433;min-height:360px;display:flex;flex-direction:column}
.gc .nc{font-size:1rem;color:#b06080;margin:auto;text-align:center}
.gp{display:flex;flex-direction:column;flex:1}
.gp-h{font-size:.65rem;color:#ff69b4;text-align:center;letter-spacing:1px;margin-bottom:4px}
.gp-row{display:flex;justify-content:space-between;align-items:center;gap:4px;flex:1}
.gp-st{position:relative;width:100px;height:100px;flex-shrink:0}
.gp-st canvas{width:100px;height:100px;display:block}
.gp-mb{display:flex;gap:4px;flex-wrap:wrap;justify-content:center}
.gp-mb>div{padding:3px 8px;border-radius:4px;font-size:.6rem;background:#1a0a12;color:#444;font-weight:600}
.gp-mb>div.on{background:#ff69b4;color:#111}
.gp-tg{flex:1;height:8px;background:#1a0a12;border-radius:4px;overflow:hidden}
.gp-tg>div{height:100%;background:linear-gradient(90deg,#ff69b4,#ff1493);border-radius:4px}
.gp-dp{display:grid;grid-template-columns:repeat(3,28px);gap:2px;justify-content:center}
.gp-dp>div{width:28px;height:28px;border-radius:4px;background:#1a0a12;display:flex;align-items:center;justify-content:center;font-size:.55rem;color:#444}
.gp-dp>div.on{background:#ff69b4;color:#111}
.gp-ab{display:grid;grid-template-columns:36px 36px;gap:3px;justify-content:center}
.gp-ab>div{width:36px;height:36px;border-radius:50%;background:#1a0a12;display:flex;align-items:center;justify-content:center;font-size:.7rem;font-weight:700;color:#555}
.gp-ab>div.on{color:#111}
.gp-ab .a{background:#1a8a1a}
.gp-ab .a.on{background:#2aff2a}
.gp-ab .b{background:#8a1a1a}
.gp-ab .b.on{background:#ff2a2a}
.gp-ab .x{background:#1a1a8a}
.gp-ab .x.on{background:#2a2aff}
.gp-ab .y{background:#8a8a1a}
.gp-ab .y.on{background:#ffff2a}

.sg{display:grid;grid-template-columns:1fr 1fr;gap:2px}
.sl{font-size:.5rem;color:#b06080}
.sv{font-size:.6rem;font-weight:600;color:#f0d0d8}
#lat{color:#ff69b4;font-family:monospace;font-size:.7rem}
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
const gpGrid=document.getElementById('gpGrid');
for(let i=0;i<4;i++){
  const c=document.createElement('div');c.className='gc';c.id='gc'+i;
  c.innerHTML='<div class="nc" id="nc'+i+'">not connected</div><div class="gp" id="gp'+i+'" style="display:none"><div class="gp-h">GP'+(i+1)+'</div><div class="gp-row"><div class="gp-mb"><div id="lb'+i+'">LB</div><div id="rb'+i+'">RB</div></div></div><div class="gp-row"><div class="gp-tg"><div id="ltf'+i+'" style="width:0%"></div></div><div class="gp-tg"><div id="rtf'+i+'" style="width:0%"></div></div></div><div class="gp-row" style="justify-content:center;gap:8px"><div class="gp-st"><canvas id="lc'+i+'" width="100" height="100"></canvas></div><div class="gp-mb" style="flex-direction:column;gap:2px"><div id="sel'+i+'" style="font-size:.5rem">SEL</div><div id="sta'+i+'" style="font-size:.5rem">STA</div></div><div class="gp-st"><canvas id="rc'+i+'" width="100" height="100"></canvas></div></div><div class="gp-row" style="justify-content:center;gap:16px;margin-top:2px"><div class="gp-dp"><div></div><div data-d="up" id="dup'+i+'">↑</div><div></div><div data-d="left" id="dl'+i+'">←</div><div data-d="neutral" id="dn'+i+'">·</div><div data-d="right" id="dr'+i+'">→</div><div></div><div data-d="down" id="dd'+i+'">↓</div><div></div></div><div class="gp-ab"><div id="y'+i+'" class="y">Y</div><div id="x'+i+'" class="x">X</div><div id="b'+i+'" class="b">B</div><div id="a'+i+'" class="a">A</div></div></div><div class="gp-row" style="justify-content:center;margin-top:2px"><div class="gp-mb"><div id="hm'+i+'">HOME</div></div></div></div>';
  gpGrid.appendChild(c);
  // init stick canvases
  ['l','r'].forEach(sd=>{const cx=document.getElementById(sd+'c'+i).getContext('2d');cx.fillStyle='#1a0a12';cx.fillRect(0,0,100,100)});
}
function ds(cx,x,y,m,a){
  const ox=50,oy=50,r=40;
  cx.clearRect(0,0,100,100);
  cx.beginPath();cx.arc(ox,oy,r,0,Math.PI*2);cx.fillStyle='#1a0a12';cx.fill();cx.strokeStyle='#2a0a1a';cx.lineWidth=2;cx.stroke();
  cx.beginPath();cx.moveTo(ox-24,oy);cx.lineTo(ox+24,oy);cx.moveTo(ox,oy-24);cx.lineTo(ox,oy+24);cx.strokeStyle='#2a0a1a';cx.lineWidth=1.5;cx.stroke();
  if(m>0.01){const rad=a*Math.PI/180,dx=Math.cos(rad)*m*r,dy=-Math.sin(rad)*m*r;
    cx.beginPath();cx.arc(ox+dx,oy+dy,6,0,Math.PI*2);cx.fillStyle='#ff69b4';cx.fill()}
  cx.beginPath();cx.arc(ox,oy,3,0,Math.PI*2);cx.fillStyle='#ff1493';cx.fill()}
function ui(d){
  const g=d.gamepads||[d];
  for(let i=0;i<4;i++){
    const s=g[i]||{};
    const conn=s.connected;
    document.getElementById('nc'+i).style.display=conn?'none':'block';
    document.getElementById('gp'+i).style.display=conn?'flex':'none';
    if(conn){
      const bt=new Set(s.buttons||[]);
      const bp=n=>bt.has(n);
      ['A','B','X','Y'].forEach(n=>document.getElementById(n.toLowerCase()+i).classList.toggle('on',bp(n)));
      ['LB','RB'].forEach(n=>document.getElementById(n.toLowerCase()+i).classList.toggle('on',bp(n)));
      document.getElementById('sel'+i).classList.toggle('on',bp('SELECT'));
      document.getElementById('sta'+i).classList.toggle('on',bp('START'));
      document.getElementById('hm'+i).classList.toggle('on',bp('HOME'));
      document.getElementById('ltf'+i).style.width=Math.min(100,(s.lt||0)/2.55)+'%';
      document.getElementById('rtf'+i).style.width=Math.min(100,(s.rt||0)/2.55)+'%';
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
    tcp_server = await asyncio.start_server(srv.handle_gamepad_client, '0.0.0.0', 60001, reuse_port=True)
    http_server = await asyncio.start_server(srv.handle_http, '0.0.0.0', 8080, reuse_port=True)
    print('[server] TCP gamepad listener on :60001')
    print('[server] HTTP dashboard on :8080')

    async with asyncio.TaskGroup() as tg:
        tg.create_task(tcp_server.serve_forever())
        tg.create_task(http_server.serve_forever())
        tg.create_task(srv._broadcast_loop())


if __name__ == '__main__':
    asyncio.run(main())
