package com.gamepadbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.animation.Animator
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlin.random.Random

class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private val deviceAxes = mutableMapOf<Int, Set<Int>>()
    private lateinit var inputManager: InputManager
    private lateinit var prefs: SharedPreferences

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnScanQR: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnToggleLog: Button
    private lateinit var btnCloseOptions: TextView
    private lateinit var tvOptions: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPlayerNumber: TextView
    private lateinit var tvGamepadStatus: TextView
    private var blinkAnimator: ObjectAnimator? = null
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var connectPanel: View
    private lateinit var optionsPanel: View
    private lateinit var swScreenOff: Switch

    private lateinit var themeBlack: View
    private lateinit var themePink: View
    private lateinit var themeBlue: View
    private lateinit var themeYellow: View
    private lateinit var themeGreen: View
    private lateinit var themeBlackO: View
    private lateinit var themePinkO: View
    private lateinit var themeBlueO: View
    private lateinit var themeYellowO: View
    private lateinit var themeGreenO: View
    private val allThemeViews = mutableListOf<View>()

    private var service: GamepadBridgeService? = null
    private lateinit var soundManager: SoundManager
    private var bound = false
    private var pendingConnect: Pair<String, Int>? = null
    private var connecting = false
    private var logVisible = false
    private var optionsVisible = false
    private var screenOff = false
    private val sbLog = StringBuilder()
    private val GAMEPAD_SOURCES = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
    private var currentTheme = 0
    private lateinit var gestureDetector: GestureDetector
    private val connectTimeoutHandler = Handler(Looper.getMainLooper())
    private val CONNECT_TIMEOUT_MS = 3000L

    data class ThemeColors(
        val bg: Int, val bg2: Int, val accent: Int, val accent2: Int,
        val text: Int, val text2: Int, val name: String
    )

    private val themes = listOf(
        ThemeColors(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF666666.toInt(), 0xFF444444.toInt(), 0xFF888888.toInt(), 0xFF555555.toInt(), "OLED Black"),
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
            service?.onConnectionStateChanged = { runOnUiThread { updateUI() } }
            service?.onSoundTrigger = { playerNum -> runOnUiThread { soundManager.playConnectedSequence(playerNum) } }
            pendingConnect?.let { (h, p) ->
                log("Pending connect to $h:$p")
                service?.connect(h, p)
                pendingConnect = null
                connecting = false
            }
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.onConnectionStateChanged = null
            service = null
            bound = false
            connecting = false
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
        soundManager = SoundManager(this)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        btnConnect = findViewById(R.id.btnConnect)
        btnScanQR = findViewById(R.id.btnScanQR)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnToggleLog = findViewById(R.id.btnToggleLog)
        btnCloseOptions = findViewById(R.id.btnCloseOptions)
        tvOptions = findViewById(R.id.tvOptions)
        tvLog = findViewById(R.id.tvLog)
        tvPlayerNumber = findViewById(R.id.tvPlayerNumber)
        tvGamepadStatus = findViewById(R.id.tvGamepadStatus)
        rootLayout = findViewById(R.id.rootLayout)
        connectPanel = findViewById(R.id.connectPanel)
        optionsPanel = findViewById(R.id.optionsPanel)
        swScreenOff = findViewById(R.id.swScreenOff)
        themeBlack = findViewById(R.id.themeBlack)
        themePink = findViewById(R.id.themePink)
        themeBlue = findViewById(R.id.themeBlue)
        themeYellow = findViewById(R.id.themeYellow)
        themeGreen = findViewById(R.id.themeGreen)
        themeBlackO = findViewById(R.id.themeBlackO)
        themePinkO = findViewById(R.id.themePinkO)
        themeBlueO = findViewById(R.id.themeBlueO)
        themeYellowO = findViewById(R.id.themeYellowO)
        themeGreenO = findViewById(R.id.themeGreenO)
        allThemeViews.addAll(listOf(themeBlack, themePink, themeBlue, themeYellow, themeGreen,
            themeBlackO, themePinkO, themeBlueO, themeYellowO, themeGreenO))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 && !etHost.isFocused && !etPort.isFocused) {
                window.decorView.postDelayed({ hideSystemUI() }, 3000)
            }
        }
        hideSystemUI()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (screenOff) {
                    screenOff = false
                    swScreenOff.isChecked = false
                    runOnUiThread { updateUI() }
                    return true
                }
                if (service?.connected == true) {
                    screenOff = true
                    swScreenOff.isChecked = true
                    runOnUiThread { updateUI() }
                    return true
                }
                return false
            }
        })
        rootLayout.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); false }
        rootLayout.isClickable = true
        rootLayout.isFocusable = true

        prefs.getString("host", "")?.takeIf { it.isNotEmpty() }?.let { savedHost ->
            etHost.setText(savedHost)
            etPort.setText(prefs.getInt("port", 60001).toString())
        }
        currentTheme = prefs.getInt("theme", 0).coerceIn(0, 4)
        logVisible = false
        tvLog.visibility = View.GONE
        applyTheme()

        themeBlack.setOnClickListener { selectTheme(0) }
        themePink.setOnClickListener { selectTheme(1) }
        themeBlue.setOnClickListener { selectTheme(2) }
        themeYellow.setOnClickListener { selectTheme(3) }
        themeGreen.setOnClickListener { selectTheme(4) }
        themeBlackO.setOnClickListener { selectTheme(0) }
        themePinkO.setOnClickListener { selectTheme(1) }
        themeBlueO.setOnClickListener { selectTheme(2) }
        themeYellowO.setOnClickListener { selectTheme(3) }
        themeGreenO.setOnClickListener { selectTheme(4) }

        tvOptions.setOnClickListener { toggleOptions() }
        btnCloseOptions.setOnClickListener { toggleOptions() }
        btnConnect.setOnClickListener { doConnect() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        btnScanQR.setOnClickListener { scanQR() }
        btnToggleLog.setOnClickListener { toggleLog() }
        swScreenOff.setOnCheckedChangeListener { _, checked ->
            screenOff = checked
            applyScreenOff()
        }

        log("App started")
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        inputManager.registerInputDeviceListener(this, null)
        val savedHost = prefs.getString("host", "") ?: ""
        val savedPort = prefs.getInt("port", 60001)
        val autoDone = prefs.getBoolean("auto_connect_done", false)
        val isConn = service?.connected == true || service?.connecting == true || connecting

        if (savedHost.isNotEmpty() && !isConn && !autoDone) {
            prefs.edit().putBoolean("auto_connect_done", true).apply()
            etHost.setText(savedHost)
            etPort.setText(savedPort.toString())
            connecting = true
            updateUI()
            log("Auto-connecting to $savedHost:$savedPort...")
            startService(Intent(this, GamepadBridgeService::class.java))
            pendingConnect = Pair(savedHost, savedPort)
            if (!bound) {
                bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
            } else {
                service?.connect(savedHost, savedPort)
                pendingConnect = null
            }
        } else if (!bound) {
            bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !etHost.isFocused && !etPort.isFocused) hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        blinkAnimator?.cancel()
        inputManager.unregisterInputDeviceListener(this)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun hideSystemUI() {
        if (etHost.isFocused || etPort.isFocused) return
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onBackPressed() {
        if (optionsVisible) {
            toggleOptions()
            return
        }
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
        setViewBg(optionsPanel, (t.bg and 0x00FFFFFF) or 0xCC000000.toInt())
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

        tvLog.setTextColor(t.text2)
        tvPlayerNumber.setTextColor(t.accent)
        tvGamepadStatus.setTextColor(t.text2)
        tvOptions.setTextColor((t.accent and 0x00FFFFFF) or 0x55000000.toInt())

        allThemeViews.forEachIndexed { i, v ->
            val themeIdx = i % 5
            val bg = v.background?.mutate()
            if (bg is GradientDrawable) {
                bg.setStroke(if (themeIdx == currentTheme) 3 else 2, if (themeIdx == currentTheme) 0xFFFFFFFF.toInt() else t.text2)
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
            tvOptions.visibility = View.GONE
            optionsPanel.visibility = View.GONE
            tvLog.visibility = View.GONE
            optionsVisible = false
            rootLayout.setBackgroundColor(Color.BLACK)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            if (service?.connected == true) {
                tvPlayerNumber.visibility = View.VISIBLE
                tvOptions.visibility = View.VISIBLE
            } else {
                connectPanel.visibility = View.VISIBLE
            }
            applyTheme()
            tvLog.visibility = if (logVisible) View.VISIBLE else View.GONE
        }
    }

    private fun toggleOptions() {
        optionsVisible = !optionsVisible
        optionsPanel.visibility = if (optionsVisible) View.VISIBLE else View.GONE
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
        startService(Intent(this, GamepadBridgeService::class.java))
        pendingConnect = Pair(host, port)
        if (!bound) {
            bindService(Intent(this, GamepadBridgeService::class.java), connection, BIND_AUTO_CREATE)
        } else {
            service?.connect(host, port)
            pendingConnect = null
        }
        startConnecting()
    }

    private fun startConnecting() {
        connecting = true
        log("Connecting...")
        updateUI()
        connectTimeoutHandler.removeCallbacksAndMessages(null)
        val targetHost = etHost.text.toString().trim()
        val targetPort = etPort.text.toString().trim()
        connectTimeoutHandler.postDelayed({
            if (isConnecting()) {
                val reason = when {
                    targetHost.isEmpty() -> "no IP entered"
                    targetPort.isEmpty() -> "no port entered"
                    else -> "could not reach $targetHost:$targetPort"
                }
                log("ERROR: $reason")
                stopConnecting()
                if (bound) {
                    service?.disconnect()
                } else {
                    pendingConnect = null
                }
                updateUI()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun stopConnecting() {
        connecting = false
        connectTimeoutHandler.removeCallbacksAndMessages(null)
    }

    private fun doDisconnect() {
        log("Disconnecting...")
        pendingConnect = null
        connecting = false
        screenOff = false
        swScreenOff.isChecked = false
        logVisible = false
        optionsVisible = false
        optionsPanel.visibility = View.GONE
        tvLog.visibility = View.GONE
        btnToggleLog.text = "Log"
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

    private fun isConnecting(): Boolean {
        return if (bound) service?.connecting == true else connecting
    }

    private fun isConnected(): Boolean {
        return service?.connected == true
    }

    private fun updateUI() {
        if (screenOff) {
            applyScreenOff()
            if (isConnected() && service?.gamepadManager?.hasSlots() == true) {
                tvGamepadStatus.visibility = View.GONE
                blinkAnimator?.cancel()
            }
            return
        }

        val connected = isConnected()
        val connecting = isConnecting()

        btnConnect.isEnabled = !connected && !connecting
        btnScanQR.isEnabled = !connected && !connecting
        btnConnect.text = if (connecting) "..." else "Connect"
        connectPanel.visibility = if (connected || connecting) View.GONE else View.VISIBLE
        tvOptions.visibility = if (connected) View.VISIBLE else View.GONE
        tvLog.visibility = if (connected && logVisible) View.VISIBLE else View.GONE

        if (connected) {
            val playerNum = service?.playerNumber ?: 1
            tvPlayerNumber.text = "PLAYER $playerNum"
            tvPlayerNumber.visibility = View.VISIBLE
            tvPlayerNumber.bringToFront()
            val hasGamepad = service?.gamepadManager?.hasSlots() == true
            if (hasGamepad) {
                tvGamepadStatus.visibility = View.GONE
                blinkAnimator?.cancel()
            } else {
                tvGamepadStatus.visibility = View.VISIBLE
                tvGamepadStatus.bringToFront()
                blinkAnimator?.cancel()
                blinkAnimator = ObjectAnimator.ofFloat(tvGamepadStatus, "alpha", 1f, 0f).apply {
                    duration = 500
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else if (connecting) {
            tvPlayerNumber.text = "CONNECTING..."
            tvPlayerNumber.visibility = View.VISIBLE
            tvPlayerNumber.bringToFront()
            tvGamepadStatus.visibility = View.GONE
            blinkAnimator?.cancel()
        } else {
            tvPlayerNumber.visibility = View.GONE
            tvGamepadStatus.visibility = View.GONE
            blinkAnimator?.cancel()
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
        runOnUiThread { updateUI() }
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
        val dev = InputDevice.getDevice(deviceId)
        if (dev != null && (dev.sources and GAMEPAD_SOURCES) != 0) {
            val slot = service?.gamepadManager?.getOrAssignSlot(deviceId) ?: return
            if (slot >= 0) {
                Log.i(TAG, "Gamepad added: ${dev.name} (id=$deviceId) -> slot $slot")
            }
        }
        updateGamepadDisplay()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        service?.gamepadManager?.releaseSlot(deviceId)
        deviceAxes.remove(deviceId)
        updateGamepadDisplay()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        updateGamepadDisplay()
    }

    companion object {
        private const val TAG = "GamepadBridge"
        private const val PREFS_NAME = "gb_prefs"
    }
}
