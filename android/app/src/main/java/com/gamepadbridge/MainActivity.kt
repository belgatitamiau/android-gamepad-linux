package com.gamepadbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
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

    // Views
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnScanQR: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnToggleLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvGamepads: TextView
    private lateinit var tvFurryArt: TextView
    private lateinit var tvPlayerNumber: TextView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var connectPanel: View
    private lateinit var connectedPanel: View
    private lateinit var settingsPanel: View
    private lateinit var swScreenOff: Switch

    // Theme views
    private lateinit var themeBlack: View
    private lateinit var themePink: View
    private lateinit var themeBlue: View
    private lateinit var themeYellow: View
    private lateinit var themeGreen: View
    private val allThemeViews = mutableListOf<View>()

    private var service: GamepadBridgeService? = null
    private var bound = false
    private var pendingConnect: Pair<String, Int>? = null
    private var logVisible = false
    private var screenOff = false
    private val sbLog = StringBuilder()
    private val GAMEPAD_SOURCES = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or 0x00000011

    private var currentTheme = 0

    data class ThemeColors(
        val bg: Int, val bg2: Int, val accent: Int, val accent2: Int,
        val text: Int, val text2: Int, val name: String
    )

    private val themes = listOf(
        ThemeColors(0xFF000000.toInt(), 0xFF111111.toInt(), 0xFF666666.toInt(), 0xFF444444.toInt(), 0xFF888888.toInt(), 0xFF555555.toInt(), "OLED Black"),
        ThemeColors(0xFF1A0A12.toInt(), 0xFF2A0A1A.toInt(), 0xFFFF69B4.toInt(), 0xFFFF1493.toInt(), 0xFFF0D0D8.toInt(), 0xFFB06080.toInt(), "My Melody"),
        ThemeColors(0xFF0A1220.toInt(), 0xFF0A1A2A.toInt(), 0xFF69C4FF.toInt(), 0xFF1493FF.toInt(), 0xFFD0E0F0.toInt(), 0xFF6080B0.toInt(), "Cinnamoroll"),
        ThemeColors(0xFF1A140A.toInt(), 0xFF2A1A0A.toInt(), 0xFFFFD700.toInt(), 0xFFDAA520.toInt(), 0xFFF0E8D0.toInt(), 0xFFB09860.toInt(), "Sugarbunnies"),
        ThemeColors(0xFF0A1A0A.toInt(), 0xFF0A2A0A.toInt(), 0xFF69FF69.toInt(), 0xFF32CD32.toInt(), 0xFFD0F0D0.toInt(), 0xFF60B060.toInt(), "Kerokerokeroppi"),
    )

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
        btnToggleLog = findViewById(R.id.btnToggleLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvGamepads = findViewById(R.id.tvGamepads)
        tvFurryArt = findViewById(R.id.tvFurryArt)
        tvPlayerNumber = findViewById(R.id.tvPlayerNumber)
        rootLayout = findViewById(R.id.rootLayout)
        connectPanel = findViewById(R.id.connectPanel)
        connectedPanel = findViewById(R.id.connectedPanel)
        settingsPanel = findViewById(R.id.settingsPanel)
        swScreenOff = findViewById(R.id.swScreenOff)
        themeBlack = findViewById(R.id.themeBlack)
        themePink = findViewById(R.id.themePink)
        themeBlue = findViewById(R.id.themeBlue)
        themeYellow = findViewById(R.id.themeYellow)
        themeGreen = findViewById(R.id.themeGreen)
        allThemeViews.addAll(listOf(themeBlack, themePink, themeBlue, themeYellow, themeGreen))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Restore prefs
        prefs.getString("host", "")?.takeIf { it.isNotEmpty() }?.let { savedHost ->
            etHost.setText(savedHost)
            etPort.setText(prefs.getInt("port", 60001).toString())
        }
        currentTheme = prefs.getInt("theme", 0).coerceIn(0, 4)
        logVisible = false
        tvLog.visibility = View.GONE
        applyTheme()

        // Theme clicks
        themeBlack.setOnClickListener { selectTheme(0) }
        themePink.setOnClickListener { selectTheme(1) }
        themeBlue.setOnClickListener { selectTheme(2) }
        themeYellow.setOnClickListener { selectTheme(3) }
        themeGreen.setOnClickListener { selectTheme(4) }

        btnConnect.setOnClickListener { doConnect() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        btnScanQR.setOnClickListener { scanQR() }
        btnToggleLog.setOnClickListener { toggleLog() }
        swScreenOff.setOnCheckedChangeListener { _, checked ->
            screenOff = checked
            applyScreenOff()
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
        val autoDone = prefs.getBoolean("auto_connect_done", false)
        if (savedHost.isNotEmpty() &&
            (service?.connected != true) &&
            pendingConnect == null &&
            !autoDone
        ) {
            prefs.edit().putBoolean("auto_connect_done", true).apply()
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

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun selectTheme(index: Int) {
        currentTheme = index
        prefs.edit().putInt("theme", index).apply()
        applyTheme()
        log("Skin: ${themes[index].name}")
    }

    private fun applyTheme() {
        val t = themes[currentTheme]
        rootLayout.setBackgroundColor(t.bg)
        setViewBg(connectPanel, t.bg2)
        setViewBg(connectedPanel, t.bg2)
        setViewBg(settingsPanel, t.bg2)
        setViewBg(tvGamepads, (t.bg2 and 0x00FFFFFF) or 0x44000000.toInt())
        setViewBg(tvLog, (t.bg and 0x00FFFFFF) or 0x88000000.toInt())

        val btnStyle = GradientDrawable().apply {
            setColor(t.accent)
            setStroke(1, t.accent2)
            cornerRadius = 8f
        }
        btnConnect.background = btnStyle
        btnConnect.setTextColor(t.bg)
        btnScanQR.background = btnStyle
        btnScanQR.setTextColor(t.bg)
        btnDisconnect.background = btnStyle
        btnDisconnect.setTextColor(t.bg)
        val logBtnStyle = GradientDrawable().apply {
            setColor(t.bg2)
            setStroke(1, t.accent)
            cornerRadius = 8f
        }
        btnToggleLog.background = logBtnStyle
        btnToggleLog.setTextColor(t.accent)

        tvGamepads.setTextColor(t.text)
        tvLog.setTextColor(t.text2)
        tvPlayerNumber.setTextColor(t.accent)

        allThemeViews.forEachIndexed { i, v ->
            val bg = v.background?.mutate()
            if (bg is GradientDrawable) {
                bg.setStroke(if (i == currentTheme) 3 else 2, if (i == currentTheme) 0xFFFFFFFF.toInt() else t.text2)
            }
        }
    }

    private fun setViewBg(view: View?, color: Int) {
        if (view == null) return
        val bg = view.background?.mutate()
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            view.setBackgroundColor(color)
        }
    }

    private fun applyScreenOff() {
        if (screenOff) {
            tvPlayerNumber.visibility = View.GONE
            connectedPanel.visibility = View.GONE
            settingsPanel.visibility = View.GONE
            tvGamepads.visibility = View.GONE
            tvLog.visibility = View.GONE
            rootLayout.setBackgroundColor(Color.BLACK)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            if (service?.connected == true) {
                connectedPanel.visibility = View.VISIBLE
                settingsPanel.visibility = View.VISIBLE
                tvPlayerNumber.visibility = View.VISIBLE
            } else {
                connectPanel.visibility = View.VISIBLE
            }
            applyTheme()
            if (logVisible) tvLog.visibility = View.VISIBLE
        }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        tvLog.visibility = if (logVisible) View.VISIBLE else View.GONE
        btnToggleLog.text = if (logVisible) "Hide" else "Log"
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
        pendingConnect = Pair(host, port)
        if (!bound) {
            bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
        } else {
            service?.connect(host, port)
            pendingConnect = null
        }
        updateUI()
    }

    private fun doDisconnect() {
        log("Disconnecting...")
        pendingConnect = null
        screenOff = false
        swScreenOff.isChecked = false
        if (bound) {
            service?.disconnect()
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, GamepadBridgeService::class.java))
        prefs.edit().putBoolean("auto_connect_done", false).apply()
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

    private fun updateUI() {
        val srv = service
        val isConnected = srv?.connected == true

        if (screenOff) {
            applyScreenOff()
            return
        }

        btnConnect.isEnabled = !isConnected
        btnScanQR.isEnabled = !isConnected
        btnDisconnect.isEnabled = isConnected
        btnToggleLog.isEnabled = isConnected
        connectPanel.visibility = if (isConnected) View.GONE else View.VISIBLE
        connectedPanel.visibility = if (isConnected) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (isConnected) View.VISIBLE else View.GONE

        if (isConnected) {
            val mgr = srv?.gamepadManager
            val activeSlots = mgr?.getActiveSlotIds() ?: emptyList()
            if (activeSlots.isNotEmpty()) {
                tvPlayerNumber.text = activeSlots.joinToString("\n") { slot ->
                    "PLAYER ${slot + 1}"
                }
            } else {
                tvPlayerNumber.text = "CONNECTED"
            }
            tvPlayerNumber.visibility = View.VISIBLE
            tvStatus.text = "Connected: ${activeSlots.size} gamepad(s)"
            tvStatus.setTextColor(themes[currentTheme].accent)
        } else {
            tvPlayerNumber.visibility = View.GONE
            val hasError = srv?.connectionError != null
            tvStatus.text = if (hasError) "Error: ${srv?.connectionError}" else ""
            tvStatus.setTextColor(if (hasError) Color.RED else themes[currentTheme].text2)
        }

        applyTheme()
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
        sb.append("\nActive: ${mgr.getSlotCount()}/4\n")
        runOnUiThread {
            tvGamepads.text = sb.toString()
            tvGamepads.visibility = View.VISIBLE
            updateUI()
        }
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
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> state.buttons = if (pressed) state.buttons or 2 else state.buttons and 2.inv()
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
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> state.buttons = if (pressed) state.buttons or 512 else state.buttons and 512.inv()
                KeyEvent.KEYCODE_BUTTON_THUMBL -> state.buttons = if (pressed) state.buttons or 1024 else state.buttons and 1024.inv()
                KeyEvent.KEYCODE_BUTTON_THUMBR -> state.buttons = if (pressed) state.buttons or 2048 else state.buttons and 2048.inv()
                KeyEvent.KEYCODE_DPAD_UP -> state.buttons = if (pressed) state.buttons or 4096 else state.buttons and 4096.inv()
                KeyEvent.KEYCODE_DPAD_DOWN -> state.buttons = if (pressed) state.buttons or 8192 else state.buttons and 8192.inv()
                KeyEvent.KEYCODE_DPAD_LEFT -> state.buttons = if (pressed) state.buttons or 16384 else state.buttons and 16384.inv()
                KeyEvent.KEYCODE_DPAD_RIGHT -> state.buttons = if (pressed) state.buttons or 32768 else state.buttons and 32768.inv()
                KeyEvent.KEYCODE_BUTTON_MODE -> state.buttons = if (pressed) state.buttons or 65536 else state.buttons and 65536.inv()
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
