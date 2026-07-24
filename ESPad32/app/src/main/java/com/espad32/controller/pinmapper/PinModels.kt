package com.espad32.controller.pinmapper

// Adjust the package above to match ESPad's actual current namespace
// (whatever you renamed it to when scrubbing audi2audi/freenove refs).

/** A single assignable function on a device profile (e.g. "motor_dir_a"). */
data class PinRoleDef(
    val key: String,
    val label: String,
    val group: String
)

/** Status of a physical GPIO pin on the WeMos D1 Mini32 header. */
enum class PinStatus(val displayLabel: String) {
    AVAILABLE("Available"),
    STRAPPING("Strapping pin — usable, boot risk"),
    INPUT_ONLY("Input only — no motor/audio output"),
    UART("UART0 — losing this drops Serial debug"),
    RESERVED("Reserved for onboard flash")
}

/** One physical pin position on the board header (left or right row). */
data class BoardPin(
    val silkLabel: String,   // what's printed on the board, e.g. "26", "TX0"
    val gpio: Int?,          // null for GND/EN/VIN/3V3 non-GPIO pads
    val status: PinStatus?   // null for non-GPIO pads
)

/** A device profile: train, RC car, etc. Mirrors the firmware profile concept. */
data class DeviceProfile(
    val key: String,
    val displayName: String,
    val roles: List<PinRoleDef>,
    val defaults: Map<String, Int>
)

object BoardLayout {
    // Matches pin_validation.h on the firmware side. Keep these two in sync
    // if the validation rules ever change.
    val LEFT_HEADER = listOf(
        BoardPin("EN", null, null),
        BoardPin("VP", 36, PinStatus.INPUT_ONLY),
        BoardPin("VN", 39, PinStatus.INPUT_ONLY),
        BoardPin("34", 34, PinStatus.INPUT_ONLY),
        BoardPin("35", 35, PinStatus.INPUT_ONLY),
        BoardPin("32", 32, PinStatus.AVAILABLE),
        BoardPin("33", 33, PinStatus.AVAILABLE),
        BoardPin("25", 25, PinStatus.AVAILABLE),
        BoardPin("26", 26, PinStatus.AVAILABLE),
        BoardPin("27", 27, PinStatus.AVAILABLE),
        BoardPin("14", 14, PinStatus.AVAILABLE),
        BoardPin("12", 12, PinStatus.STRAPPING),
        BoardPin("GND", null, null),
        BoardPin("13", 13, PinStatus.AVAILABLE)
    )

    val RIGHT_HEADER = listOf(
        BoardPin("VIN", null, null),
        BoardPin("GND", null, null),
        BoardPin("23", 23, PinStatus.AVAILABLE),
        BoardPin("22", 22, PinStatus.AVAILABLE),
        BoardPin("TX0", 1, PinStatus.UART),
        BoardPin("RX0", 3, PinStatus.UART),
        BoardPin("21", 21, PinStatus.AVAILABLE),
        BoardPin("GND", null, null),
        BoardPin("19", 19, PinStatus.AVAILABLE),
        BoardPin("18", 18, PinStatus.AVAILABLE),
        BoardPin("5", 5, PinStatus.AVAILABLE),
        BoardPin("17", 17, PinStatus.AVAILABLE),
        BoardPin("16", 16, PinStatus.AVAILABLE),
        BoardPin("4", 4, PinStatus.AVAILABLE),
        BoardPin("0", 0, PinStatus.STRAPPING),
        BoardPin("2", 2, PinStatus.STRAPPING),
        BoardPin("15", 15, PinStatus.STRAPPING)
    )

    fun allPins(): List<BoardPin> = LEFT_HEADER + RIGHT_HEADER

    fun findByGpio(gpio: Int): BoardPin? = allPins().find { it.gpio == gpio }
}

object Profiles {
    val TRAIN = DeviceProfile(
        key = "train",
        displayName = "Train (TB6612FNG + MAX98357A)",
        roles = listOf(
            PinRoleDef("motor_dir_a", "Motor direction A", "Motor"),
            PinRoleDef("motor_dir_b", "Motor direction B", "Motor"),
            PinRoleDef("motor_pwm", "Motor speed (PWM)", "Motor"),
            PinRoleDef("motor_standby", "Motor standby", "Motor"),
            PinRoleDef("audio_bclk", "Audio bit clock", "Audio"),
            PinRoleDef("audio_lrc", "Audio L/R clock", "Audio"),
            PinRoleDef("audio_din", "Audio data in", "Audio")
        ),
        defaults = mapOf(
            "motor_dir_a" to 26, "motor_dir_b" to 27, "motor_pwm" to 14,
            "motor_standby" to 12, "audio_bclk" to 25, "audio_lrc" to 33, "audio_din" to 32
        )
    )

    val RC_CAR = DeviceProfile(
        key = "rc_car",
        displayName = "RC Car",
        roles = listOf(
            PinRoleDef("motor_a_dir1", "Motor A direction 1", "Drive"),
            PinRoleDef("motor_a_dir2", "Motor A direction 2", "Drive"),
            PinRoleDef("motor_a_pwm", "Motor A speed (PWM)", "Drive"),
            PinRoleDef("steering_servo", "Steering servo", "Steering"),
            PinRoleDef("headlight", "Headlights", "Lights")
        ),
        defaults = mapOf(
            "motor_a_dir1" to 16, "motor_a_dir2" to 17, "motor_a_pwm" to 4,
            "steering_servo" to 18, "headlight" to 19
        )
    )

    val ALL = listOf(TRAIN, RC_CAR)
}
