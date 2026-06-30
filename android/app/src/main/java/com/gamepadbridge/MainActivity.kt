package com.gamepadbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private val deviceAxes = mutableMapOf<Int, Set<Int>>()
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnScanQR: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvGamepads: TextView
    private lateinit var tvFurryArt: TextView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var swBlackMode: Switch
    private lateinit var connectPanel: View
    private lateinit var connectedPanel: View
    private var gestureDetector: GestureDetector? = null

    private var service: GamepadBridgeService? = null
    private var bound = false
    private var pendingConnect: Pair<String, Int>? = null
    private var autoConnectAttempted = false
    private var blackMode = false
    private val sbLog = StringBuilder()
    private val GAMEPAD_SOURCES = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or 0x00000011

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as GamepadBridgeService.LocalBinder).getService()
            bound = true
            log("Service bound")
            pendingConnect?.let { (h, p) ->
                log("Pending connect to $h:$p")
                service?.connect(h, p)
                pendingConnect = null
            }
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            log("Service unbound")
            updateUI()
        }
    }

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val host = result.data?.getStringExtra(QRScannerActivity.EXTRA_HOST) ?: ""
            val port = result.data?.getIntExtra(QRScannerActivity.EXTRA_PORT, 0) ?: 0
            if (host.isNotEmpty() && port > 0) {
                etHost.setText(host)
                etPort.setText(port.toString())
                log("QR scanned: $host:$port")
                doConnect()
            }
        } else {
            log("QR scan cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        btnConnect = findViewById(R.id.btnConnect)
        btnScanQR = findViewById(R.id.btnScanQR)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvGamepads = findViewById(R.id.tvGamepads)
        tvFurryArt = findViewById(R.id.tvFurryArt)
        rootLayout = findViewById(R.id.rootLayout)
        swBlackMode = findViewById(R.id.swBlackMode)
        connectPanel = findViewById(R.id.connectPanel)
        connectedPanel = findViewById(R.id.connectedPanel)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs.getString("host", "")?.takeIf { it.isNotEmpty() }?.let { savedHost ->
            etHost.setText(savedHost)
            etPort.setText(prefs.getInt("port", 60001).toString())
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (blackMode) {
                    blackMode = false
                    swBlackMode.isChecked = false
                    applyBlackMode()
                }
                return true
            }
        })

        rootLayout.setOnTouchListener { _, event -> gestureDetector?.onTouchEvent(event) ?: false }
        rootLayout.isClickable = true

        btnConnect.setOnClickListener { doConnect() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        btnScanQR.setOnClickListener { scanQR() }
        swBlackMode.setOnCheckedChangeListener { _, isChecked ->
            blackMode = isChecked
            applyBlackMode()
        }

        applyFurryArt()
        log("App started")
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        if (!bound) {
            bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
        }
        listGamepads()

        val savedHost = prefs.getString("host", "") ?: ""
        if (savedHost.isNotEmpty() &&
            (service?.connected != true) &&
            pendingConnect == null &&
            !autoConnectAttempted
        ) {
            autoConnectAttempted = true
            etHost.postDelayed({ doConnect() }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(this)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun doConnect() {
        val host = etHost.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        if (host.isEmpty() || portStr.isEmpty()) {
            log("ERROR: Enter IP and port")
            return
        }
        val port = portStr.toIntOrNull()
        if (port == null) {
            log("ERROR: Invalid port")
            return
        }
        prefs.edit().putString("host", host).putInt("port", port).apply()
        log("Connecting to $host:$port...")
        startService(Intent(this, GamepadBridgeService::class.java))
        if (bound && service != null) {
            service?.connect(host, port)
        } else {
            pendingConnect = Pair(host, port)
            bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
        }
        updateUI()
    }

    private fun doDisconnect() {
        log("Disconnecting...")
        if (bound) {
            service?.disconnect()
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, GamepadBridgeService::class.java))
        updateUI()
    }

    private fun scanQR() {
        qrLauncher.launch(Intent(this, QRScannerActivity::class.java))
    }

    private fun applyFurryArt() {
        val lines = FURRY_ART.split("\n")
        val spannable = SpannableString(FURRY_ART)
        var start = 0
        for ((i, line) in lines.withIndex()) {
            val end = line.length + start + 1
            val color = TRANS_COLORS[i % TRANS_COLORS.size]
            spannable.setSpan(ForegroundColorSpan(color), start, minOf(end, spannable.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end
        }
        tvFurryArt.text = spannable
    }

    private fun applyBlackMode() {
        if (blackMode) {
            rootLayout.setBackgroundColor(Color.BLACK)
            connectPanel.visibility = View.GONE
            connectedPanel.visibility = View.GONE
            tvGamepads.visibility = View.GONE
            tvLog.visibility = View.GONE
        } else {
            rootLayout.setBackgroundColor(Color.BLACK)
            connectPanel.visibility = View.VISIBLE
            connectedPanel.visibility = View.VISIBLE
            tvGamepads.visibility = View.VISIBLE
            tvLog.visibility = View.VISIBLE
            tvStatus.setTextColor(Color.WHITE)
            tvStatus.textSize = 18.0f
        }
    }

    private fun updateUI() {
        val srv = service
        val isConnected = srv?.connected == true
        val hasError = srv?.connectionError != null

        btnConnect.isEnabled = !isConnected
        btnScanQR.isEnabled = !isConnected
        btnDisconnect.isEnabled = isConnected
        connectPanel.visibility = if (isConnected) View.GONE else View.VISIBLE
        connectedPanel.visibility = if (isConnected) View.VISIBLE else View.GONE
        tvStatus.text = when {
            isConnected -> ""
            hasError -> "Error: ${srv?.connectionError}"
            else -> ""
        }
        tvStatus.setTextColor(
            when {
                isConnected -> Color.GREEN
                hasError -> Color.RED
                else -> Color.GRAY
            }
        )
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        sbLog.append("[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + msg)
        sbLog.append('\n')
        if (sbLog.length > 5000) {
            sbLog.delete(0, sbLog.length / 2)
        }
        runOnUiThread {
            tvLog.text = sbLog.toString()
            tvLog.post { tvLog.scrollTo(0, tvLog.bottom) }
        }
    }

    private fun updateGamepadDisplay() {
        val srv = service ?: return
        val mgr = srv.gamepadManager
        val sb = StringBuilder()
        for (slot in mgr.getActiveSlotIds()) {
            mgr.getStateBySlot(slot)?.let { sb.append(it.debugString()).append('\n') }
        }
        if (sb.isEmpty()) sb.append("No gamepads detected\n")
        sb.append("\nConnected gamepads: ${mgr.getSlotCount()}\n")
        runOnUiThread { tvGamepads.text = sb.toString() }
    }

    private fun listGamepads() {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val dev = InputDevice.getDevice(id)
            if (dev != null && (dev.sources and GAMEPAD_SOURCES) != 0) {
                val slot = service?.gamepadManager?.getOrAssignSlot(id) ?: continue
                if (slot >= 0) {
                    Log.i(TAG, "Found gamepad: ${dev.name} (id=$id) -> slot $slot")
                }
            }
        }
        updateGamepadDisplay()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and GAMEPAD_SOURCES != 0) {
            handleGamepadKey(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and GAMEPAD_SOURCES != 0) {
            handleGamepadMotion(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.source and GAMEPAD_SOURCES != 0) {
            handleGamepadKey(event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.source and GAMEPAD_SOURCES != 0) {
            handleGamepadKey(event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && event.source and GAMEPAD_SOURCES != 0) {
            handleGamepadMotion(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun handleGamepadKey(event: KeyEvent) {
        val srv = service ?: return
        val mgr = srv.gamepadManager
        val deviceId = event.deviceId
        val slot = mgr.getOrAssignSlot(deviceId)
        if (slot < 0) return
        val state = mgr.getStateBySlot(slot) ?: return
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val repeat = event.repeatCount > 0
        if (repeat) return

        synchronized(mgr) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> state.buttons = if (pressed) state.buttons or 1 else state.buttons and 1.inv()
                KeyEvent.KEYCODE_BUTTON_B -> state.buttons = if (pressed) state.buttons or 2 else state.buttons and 2.inv()
                KeyEvent.KEYCODE_BUTTON_X -> state.buttons = if (pressed) state.buttons or 4 else state.buttons and 4.inv()
                KeyEvent.KEYCODE_BUTTON_Y -> state.buttons = if (pressed) state.buttons or 8 else state.buttons and 8.inv()
                KeyEvent.KEYCODE_BUTTON_L1 -> state.buttons = if (pressed) state.buttons or 16 else state.buttons and 16.inv()
                KeyEvent.KEYCODE_BUTTON_R1 -> state.buttons = if (pressed) state.buttons or 32 else state.buttons and 32.inv()
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    state.buttons = if (pressed) state.buttons or 64 else state.buttons and 64.inv()
                    if (!pressed) state.leftTrigger = 0
                }
                KeyEvent.KEYCODE_BUTTON_R2 -> {
                    state.buttons = if (pressed) state.buttons or 128 else state.buttons and 128.inv()
                    if (!pressed) state.rightTrigger = 0
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> state.buttons = if (pressed) state.buttons or 256 else state.buttons and 256.inv()
                KeyEvent.KEYCODE_BUTTON_START -> state.buttons = if (pressed) state.buttons or 512 else state.buttons and 512.inv()
                KeyEvent.KEYCODE_BUTTON_THUMBL -> state.buttons = if (pressed) state.buttons or 1024 else state.buttons and 1024.inv()
                KeyEvent.KEYCODE_BUTTON_THUMBR -> state.buttons = if (pressed) state.buttons or 2048 else state.buttons and 2048.inv()
                KeyEvent.KEYCODE_DPAD_UP -> state.buttons = if (pressed) state.buttons or 4096 else state.buttons and 4096.inv()
                KeyEvent.KEYCODE_DPAD_DOWN -> state.buttons = if (pressed) state.buttons or 8192 else state.buttons and 8192.inv()
                KeyEvent.KEYCODE_DPAD_LEFT -> state.buttons = if (pressed) state.buttons or 16384 else state.buttons and 16384.inv()
                KeyEvent.KEYCODE_DPAD_RIGHT -> state.buttons = if (pressed) state.buttons or 32768 else state.buttons and 32768.inv()
                KeyEvent.KEYCODE_BUTTON_MODE -> state.buttons = if (pressed) state.buttons or 65536 else state.buttons and 65536.inv()
                KeyEvent.KEYCODE_BACK -> state.buttons = if (pressed) state.buttons or 2 else state.buttons and 2.inv()
                KeyEvent.KEYCODE_MENU -> state.buttons = if (pressed) state.buttons or 512 else state.buttons and 512.inv()
                else -> {
                    Log.v(TAG, "Unhandled key ${event.keyCode} from gamepad device #$deviceId")
                    return
                }
            }
            val action = if (pressed) "PRESS" else "RELEASE"
            Log.v(TAG, "GP#$slot key=${event.keyCode} $action")
            runOnUiThread { updateGamepadDisplay() }
        }
    }

    private fun handleGamepadMotion(event: MotionEvent) {
        val srv = service ?: return
        val mgr = srv.gamepadManager
        val deviceId = event.deviceId
        val slot = mgr.getOrAssignSlot(deviceId)
        if (slot < 0) return
        val state = mgr.getStateBySlot(slot) ?: return

        val axes = deviceAxes.getOrPut(deviceId) {
            InputDevice.getDevice(deviceId)
                ?.motionRanges
                ?.map { it.axis }
                ?.toSet()
                ?: emptySet()
        }

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hasRX = MotionEvent.AXIS_RX in axes
        val hasRY = MotionEvent.AXIS_RY in axes

        val (rx, ry) = if (hasRX && hasRY) {
            event.getAxisValue(MotionEvent.AXIS_RX) to event.getAxisValue(MotionEvent.AXIS_RY)
        } else {
            event.getAxisValue(MotionEvent.AXIS_Z) to event.getAxisValue(MotionEvent.AXIS_RZ)
        }

        val lz = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rz = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        synchronized(mgr) {
            val f = 32767f
            state.leftStickX = (x * f).toInt().coerceIn(-32768, 32767).toShort()
            state.leftStickY = (y * f).toInt().coerceIn(-32768, 32767).toShort()
            state.rightStickX = (rx * f).toInt().coerceIn(-32768, 32767).toShort()
            state.rightStickY = (ry * f).toInt().coerceIn(-32768, 32767).toShort()

            if (lz >= 0f) {
                state.leftTrigger = (255 * lz).toInt().coerceIn(0, 255)
                state.buttons = if (state.leftTrigger > 30) state.buttons or 64 else state.buttons and 64.inv()
            }
            if (rz >= 0f) {
                state.rightTrigger = (255 * rz).toInt().coerceIn(0, 255)
                state.buttons = if (state.rightTrigger > 30) state.buttons or 128 else state.buttons and 128.inv()
            }

            state.buttons = state.buttons and 0xFFFF0FFF.toInt()
            if (hatY < 0f) state.buttons = state.buttons or 4096
            if (hatY > 0f) state.buttons = state.buttons or 8192
            if (hatX < 0f) state.buttons = state.buttons or 16384
            if (hatX > 0f) state.buttons = state.buttons or 32768

            Log.v(TAG, "GP#$slot axes out lx=${state.leftStickX} ly=${state.leftStickY} rx=${state.rightStickX} ry=${state.rightStickY} lt=${state.leftTrigger} rt=${state.rightTrigger}")
            runOnUiThread { updateGamepadDisplay() }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        listGamepads()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        service?.gamepadManager?.releaseSlot(deviceId)
        deviceAxes.remove(deviceId)
        listGamepads()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        listGamepads()
    }

    companion object {
        private const val TAG = "GamepadBridge"
        private const val PREFS_NAME = "gb_prefs"

        private val TRANS_COLORS = intArrayOf(
            Color.CYAN, Color.MAGENTA, Color.WHITE, Color.MAGENTA, Color.CYAN
        )

        private val FURRY_ART = "\n" +
            " ⠀⠀ ⠀⠀⠀⠀⠀⠀⠀⣠⠦⣄⠀⠀⠀⠀⠀⠀⠀⡷⣄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⠀⠀⠀⢀⢞⡡⠤⠒⠓⠒⠒⠒⠒⠢⠤⣇⠈⠢⡀⠀⠀⠀⠀⠀⣀⣀⣀⠤⠤⠔⠒⠒⠂⢇⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⠀⠀⢀⠎⠉⢉⡚⠯⠴⠦⠄⠀⠀⠀⠀⠀⠁⠀⠬⠖⠒⠊⠉⠉⠀⠀⠀⠀⠀⠀⠀⢀⣀⢸⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⠀⢀⡼⢒⠉⠰⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⠠⠒⠉⢀⠃⠸⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⢠⠞⠊⠀⠈⢢⡱⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⡮⠁⢀⠃⠀⡘⠀⡇⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⢀⠇⣀⣤⠖⠊⠁⠈⢷⠀⠀⠀⠀⠀⢀⡴⠁⠀⠀⠀⠀⠀⠀⠀⠀⣛⡅⠀⣠⠦⡄⢠⠁⠸⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⢸⢾⠟⠁⠀⠀⠀⢀⣀⣀⣀⢠⡴⣞⢕⣡⠤⠤⠤⠤⠤⢄⡀⠀⠀⢠⠀⠀⢀⠜⡠⠃⢠⠃⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⡜⠀⠀⠀⢠⣈⣡⣴⣽⣯⠑⠉⠀⢀⢤⣰⣶⣤⣤⣤⣤⣀⣀⣠⠘⠠⠤⣒⠟⠁⡰⠃⠀⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⡇⠀⠀⠀⠨⣿⡏⠉⢹⣿⠂⠀⠀⢰⡛⠉⠸⣿⣿⡇⠍⣿⣿⠅⠀⢗⠊⢀⣰⠾⡆⠀⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⢱⠀⠀⠀⡘⡘⡇⠀⠰⣿⠀⠀⠀⢸⠀⠀⠈⣿⡿⠃⢰⠇⠀⠀⠀⠠⡿⠅⠃⠀⢸⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⢧⡀⠀⡇⡷⠿⠀⣴⣯⣤⠤⠒⠈⠀⠀⠀⠀⢠⠠⡸⠲⡀⠀⠀⠀⠃⠀⢄⠀⠀⡆⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⠀⢳⣄⢻⠐⡀⠀⠁⠉⠁⠀⠀⠀⢀⠄⠀⠀⠀⠀⠀⣠⠁⠀⡆⢀⠆⠀⠈⢆⢀⠃⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⢀⣤⣫⠆⠙⢣⠈⡒⢤⣈⠉⠉⠁⠉⠁⠀⢀⡀⡤⠒⢍⢸⢀⡜⠹⠊⠀⠀⠀⠘⡞⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠉⠉⡙⠀⠀⠀⡗⠧⠎⠀⠉⠐⠒⢲⡶⠟⠋⠀⢀⠀⠀⠙⠋⠀⣀⠴⠀⠀⠀⠀⡇⠀⠀⠀⠀⠀⠀⠀⠀\n" +
            " ⠀⠀⠀⠀⠀⠀⡇⠀⠀⠀⣁⡀⠀⠀⠀⠀⠀⣸⠀⠀⢀⣀⠨⠭⠙⠛⠉⠀⠀⠈⠹⠶⠀⢸⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀\n"
    }
}
