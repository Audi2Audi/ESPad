package com.espad32.controller.controls

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds ESPad devices on the local network by broadcasting
 * "ESPAD_DISCOVER" over UDP and collecting "ESPAD_HERE#<ip>#<name>"
 * replies. Solves the case where a device's STA IP wasn't received via
 * the normal TCP response — e.g. if the AP->STA channel switch (the
 * ESP32 has one radio, so both modes share a channel) dropped the
 * connection right as the response was sent. Instead of needing to log
 * into the router to find the new IP, this asks the device directly.
 *
 * Matches the protocol implemented in the test firmware's discovery.h.
 */
object DeviceDiscovery {

    data class FoundDevice(val ip: String, val name: String)

    private const val DISCOVERY_PORT = 4210
    private const val DEFAULT_DURATION_MS = 2000L

    /** Runs on a background thread; onResult is called back on the main thread. */
    fun discover(durationMs: Long = DEFAULT_DURATION_MS, onResult: (List<FoundDevice>) -> Unit) {
        Thread {
            val results = mutableListOf<FoundDevice>()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 300 // short, so we can keep checking the overall deadline
                }

                val message = "ESPAD_DISCOVER".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(message, message.size, broadcastAddr, DISCOVERY_PORT))

                val buf = ByteArray(128)
                val deadline = System.currentTimeMillis() + durationMs

                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith("ESPAD_HERE#")) {
                            val parts = text.split("#")
                            val ip = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                                ?: packet.address.hostAddress
                            val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "ESPad device"
                            if (results.none { it.ip == ip }) {
                                results.add(FoundDevice(ip, name))
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // expected — just keep looping until the deadline
                    }
                }
            } catch (e: Exception) {
                // Network unavailable, permission issue, etc — just return
                // whatever was found (possibly nothing) rather than crash.
            } finally {
                socket?.close()
            }

            Handler(Looper.getMainLooper()).post { onResult(results) }
        }.start()
    }
}
