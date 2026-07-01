package com.gamepadbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

class GamepadBridgeService : Service() {

    private val binder = LocalBinder()
    val gamepadManager = GamepadManager()
    private var networkClient: NetworkClient? = null
    private var senderThread: Thread? = null

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var connectionError: String? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): GamepadBridgeService = this@GamepadBridgeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    fun connect(host: String, port: Int) {
        Log.i(TAG, "Connecting to $host:$port")
        startForeground(NOTIF_ID, buildNotification())
        connectionError = null
        networkClient = NetworkClient(
            host = host,
            port = port,
            onConnected = {
                Log.i(TAG, "Network connected, starting state sender")
                connected = true
                connectionError = null
                startStateSender()
            },
            onDisconnected = { ex ->
                connected = false
                connectionError = ex?.message ?: "Unknown error"
                Log.e(TAG, "Network disconnected: $connectionError")
            }
        )
        networkClient?.start()
    }

    private fun startStateSender() {
        val thread = Thread({ ->
            while (connected) {
                synchronized(gamepadManager) {
                    for (slot in gamepadManager.getActiveSlotIds()) {
                        val state = gamepadManager.getStateBySlot(slot) ?: continue
                        networkClient?.send(state)
                    }
                }
                try {
                    Thread.sleep(SEND_INTERVAL_MS)
                } catch (_: InterruptedException) {
                }
            }
            Log.i(TAG, "State sender stopped")
        }, "state-sender")
        thread.start()
        senderThread = thread
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting")
        connected = false
        networkClient?.stop()
        networkClient = null
        senderThread?.interrupt()
        senderThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "GamepadBridge", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GamepadBridge")
            .setContentText("Gamepad forwarding active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "GBService"
        private const val CHANNEL_ID = "gamepad_bridge_channel"
        private const val NOTIF_ID = 1
        const val SEND_INTERVAL_MS = 16L
    }
}
