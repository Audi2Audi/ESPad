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
     * Sends SETV <role> <0-255> for PWM_OUTPUT roles (motor speed,
     * dimming, etc).
     *
     * IMPORTANT: `value` here is the INTENDED brightness/speed as the
     * caller understands it (0 = off, 255 = full) — the actual wire
     * value is inverted (255 - value) before sending, to compensate
     * for a confirmed hardware/firmware quirk on the current test rig
     * (WeMos D1 Mini32, GPIO4 test LED): requesting a raw duty of 255
     * visually produced OFF and 0 produced FULL BRIGHT, confirmed by
     * both direct visual LED observation and multimeter voltage
     * magnitude readings. This is centralized here (rather than at
     * each call site) so every caller — the Controls slider, gamepad
     * axis mapping, anything added later — automatically gets correct
     * behavior without needing to know about or duplicate this
     * compensation.
     *
     * If this ever needs to differ per-board or per-pin (e.g. a
     * different board that isn't inverted), this is the one place
     * that would need to become conditional — see PIN_MAPPER_ROADMAP.md.
     */
    fun sendSetValue(role: String, value: Int, onResult: (String?) -> Unit) {
        val wireValue = (255 - value).coerceIn(0, 255)
        sendRaw("SETV $role $wireValue\n", onResult)
    }

    /** Sends GET <role> for ANALOG_INPUT roles (e.g. battery voltage via a divider). */
    fun sendGet(role: String, onResult: (String?) -> Unit) {
        sendRaw("GET $role\n", onResult)
    }

    /** Sends SETA <role> <0-180> for SERVO roles — real angle control, not PWM duty. */
    fun sendSetAngle(role: String, angle: Int, onResult: (String?) -> Unit) {
        sendRaw("SETA $role ${angle.coerceIn(0, 180)}\n", onResult)
    }

    /**
     * Sends GET_CONFIG# — asks the device for its full role list
     * (key/label/type/gpio) as JSON. Used by "Sync from Device" to pull
     * in anything created via the device's own web UI, which the phone
     * would otherwise have no way to find out about at all.
     */
    fun sendGetConfig(onResult: (String?) -> Unit) {
        sendRaw("GET_CONFIG#\n", onResult)
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
