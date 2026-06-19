package com.espad32.controller

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

class TcpClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onData: (String) -> Unit
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    @Volatile private var connected = false
    private var receiveJob: Job? = null

    fun connect(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    socket = Socket(host, port).apply {
                        // No read timeout — we stay connected indefinitely
                        // The car sends data only when queried so we must not timeout
                        soTimeout = 0
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    writer = PrintWriter(socket!!.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                    connected = true
                    onConnected()
                    startReceiving(scope)
                    // Wait here until disconnected
                    while (isActive && connected) {
                        delay(500)
                    }
                } catch (e: Exception) {
                    connected = false
                    onDisconnected()
                } finally {
                    connected = false
                    try { socket?.close() } catch (e: Exception) { }
                    socket = null
                    writer = null
                    reader = null
                }
                // Retry after delay
                if (isActive) {
                    delay(2000)
                }
            }
        }
    }

    private fun startReceiving(scope: CoroutineScope) {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && connected) {
                    val line = try {
                        reader?.readLine()
                    } catch (e: SocketTimeoutException) {
                        continue  // timeout is OK — just keep waiting
                    }
                    if (line == null) {
                        // null means connection closed by remote
                        break
                    }
                    if (line.isNotEmpty()) onData(line)
                }
            } catch (e: SocketException) {
                // Socket closed normally
            } catch (e: Exception) {
                // Other errors
            } finally {
                connected = false
                onDisconnected()
            }
        }
    }

    fun send(command: String) {
        if (!connected) throw Exception("Not connected")
        synchronized(this) {
            try {
                writer?.print(command)
                writer?.flush()
            } catch (e: Exception) {
                connected = false
                onDisconnected()
                throw e  // propagate so senderJob knows send failed
            }
        }
    }

    fun disconnect() {
        connected = false
        receiveJob?.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) { /* ignore */ }
    }

    fun isConnected() = connected
}
