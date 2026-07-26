package com.espad32.controller.controls

import android.os.Handler
import android.os.Looper
import com.espad32.controller.MainTcpHolder

/**
 * Sends a "SET <role> <0|1>" command to the ESP32 through the existing
 * shared TCP connection (MainTcpHolder), rather than opening a second
 * socket — the firmware's command server only accepts one client at a
 * time, so a second connection would just hang waiting for accept().
 * Same constraint and same pattern as OtaActivity.queryFirmwareVersion.
 *
 * onResult receives the device's response line (e.g.
 * "OK: role 'test_led' (GPIO 4) -> HIGH") or null if there was no
 * response within the timeout, or if there's no active connection at all.
 */
object DeviceCommand {

    private const val TIMEOUT_MS = 3000L

    fun sendSet(role: String, on: Boolean, onResult: (String?) -> Unit) {
        sendRaw("SET $role ${if (on) 1 else 0}\n", onResult)
    }

    /**
     * Sends any single-line command (a SET command, or a full JSON pin
     * config payload — both are just one line ending in \n) and captures
     * the device's first response line. For multi-line firmware replies
     * (JSON payloads get VALIDATION OK / SAVED to NVS / ACK / a config
     * dump), only that first line is captured — enough to know success
     * (VALIDATION OK) vs failure (NACK: ...), even though the fuller
     * dump that follows isn't surfaced here.
     */
    fun sendRaw(command: String, onResult: (String?) -> Unit) {
        if (MainTcpHolder.enqueue == null) {
            onResult(null)
            return
        }

        val prevHandler = MainTcpHolder.onNextData
        var resumed = false
        val mainHandler = Handler(Looper.getMainLooper())

        MainTcpHolder.onNextData = { data ->
            if (!resumed) {
                resumed = true
                MainTcpHolder.onNextData = prevHandler
                mainHandler.post { onResult(data) }
            }
        }

        MainTcpHolder.enqueue?.invoke(if (command.endsWith("\n")) command else "$command\n")

        mainHandler.postDelayed({
            if (!resumed) {
                resumed = true
                MainTcpHolder.onNextData = prevHandler
                onResult(null)
            }
        }, TIMEOUT_MS)
    }
}
