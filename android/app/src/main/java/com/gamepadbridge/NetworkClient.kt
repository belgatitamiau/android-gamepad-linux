package com.gamepadbridge

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

class NetworkClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: (Exception?) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var connectThread: Thread? = null
    private var sendThread: Thread? = null
    @Volatile
    private var running = false
    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        running = true
        val thread = Thread({ ->
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                sock.tcpNoDelay = true
                socket = sock
                outputStream = sock.getOutputStream()
                Log.i(TAG, "Connected to $host:$port")
                onConnected()
                sendLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                running = false
                onDisconnected(e)
            }
        }, "net-connect")
        thread.start()
        connectThread = thread
    }

    private fun sendLoop() {
        val thread = Thread({ ->
            try {
                while (running) {
                    val data = sendQueue.poll()
                    if (data == null) {
                        Thread.sleep(1)
                    } else {
                        try {
                            outputStream?.write(data)
                            outputStream?.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Send error: ${e.message}")
                        }
                    }
                }
            } finally {
                cleanup()
            }
        }, "net-send")
        thread.start()
        sendThread = thread
    }

    fun send(state: GamepadState) {
        if (running) {
            sendQueue.offer(state.toByteArray())
        }
    }

    fun stop() {
        running = false
        cleanup()
    }

    private fun cleanup() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
        Log.i(TAG, "Disconnected")
    }

    companion object {
        private const val TAG = "NetworkClient"
        private const val TIMEOUT_MS = 5000
    }
}
