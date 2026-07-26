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

        MainTcpHolder.enqueue?.invoke("SET $role ${if (on) 1 else 0}\n")

        mainHandler.postDelayed({
            if (!resumed) {
                resumed = true
                MainTcpHolder.onNextData = prevHandler
                onResult(null)
            }
        }, TIMEOUT_MS)
    }
}
