package com.gamepadbridge

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue

class NetworkClient(
    private val host: String,
    private val port: Int,
    private val onConnected: (Int) -> Unit,
    private val onDisconnected: (Exception?) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var connectThread: Thread? = null
    private var sendThread: Thread? = null
    private var watchThread: Thread? = null
    private var lastSendOk = 0L
    @Volatile
    private var running = false
    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        running = true
        lastSendOk = System.currentTimeMillis()
        val thread = Thread({ ->
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                socket = sock
                outputStream = sock.getOutputStream()
                inputStream = sock.getInputStream()
                val playerByte = inputStream?.read() ?: 1
                val playerNum = playerByte.coerceIn(1, 4)
                Log.i(TAG, "Connected to $host:$port as player $playerNum")
                onConnected(playerNum)
                sendLoop()
                watchConnection()
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
                            lastSendOk = System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.e(TAG, "Send failed: ${e.message}")
                            running = false
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

    private fun watchConnection() {
        var reported = false
        watchThread = Thread({
            while (true) {
                Thread.sleep(1000)
                if (!running && !reported) {
                    reported = true
                    onDisconnected(SocketException("Host not responding"))
                    break
                }
                if (!running) break
                val elapsed = System.currentTimeMillis() - lastSendOk
                if (elapsed > DISCONNECT_TIMEOUT_MS) {
                    Log.w(TAG, "No send for ${elapsed}ms, disconnecting")
                    running = false
                }
            }
        }, "net-watch")
        watchThread?.start()
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
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
        Log.i(TAG, "Disconnected")
    }

    companion object {
        private const val TAG = "NetworkClient"
        private const val TIMEOUT_MS = 5000
        private const val DISCONNECT_TIMEOUT_MS = 5000L
    }
}
