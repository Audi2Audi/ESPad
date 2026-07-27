package com.espad32.controller.pinmapper

/**
 * What kind of signal a role represents. Determines what UI makes sense
 * for it: a DIGITAL_OUTPUT can back a simple on/off button; PWM_OUTPUT
 * and SERVO need a continuous control (slider) rather than a toggle —
 * not built yet, tracked in the roadmap. AUDIO_SIGNAL and DIGITAL_INPUT
 * exist so those roles are correctly excluded from "add a button" flows.
 */
enum class RoleType {
    DIGITAL_OUTPUT,
    PWM_OUTPUT,
    SERVO,
    DIGITAL_INPUT,
    AUDIO_SIGNAL,
    // A readable analog value (0-3.3V range) — e.g. battery voltage via
    // a voltage divider. Only assignable to ADC1 pins (32/33/34/35/36/39)
    // — ADC2 pins conflict with WiFi on the ESP32 and give unreliable
    // readings whenever the radio is active, which for this app is
    // always. See PinValidation.ADC1_PINS.
    ANALOG_INPUT
}

/** A single assignable function on a device profile (e.g. "motor_dir_a"). */
data class PinRoleDef(
    val key: String,
    val label: String,
    val group: String,
    val type: RoleType = RoleType.DIGITAL_OUTPUT
)

/** Status of a physical GPIO pin on a board header. */
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

/**
 * Describes a physical board's pin layout and restrictions — separate from
 * any particular device built on it. A board is reusable across many
 * DeviceProfiles (a WeMos D1 Mini32 might run a train today, an RC car
 * tomorrow, both sharing this same BoardDef).
 *
 * This is the seam for adding board selection later: registering a new
 * BoardDef in `Boards` is all a new supported board needs on the app side.
 * Each board's rules should stay in sync with its firmware-side
 * pin_validation equivalent, since the app only enforces this as a
 * convenience layer — firmware is the real safety net.
 */
data class BoardDef(
    val key: String,
    val displayName: String,
    val leftHeader: List<BoardPin>,
    val rightHeader: List<BoardPin>
) {
    fun allPins(): List<BoardPin> = leftHeader + rightHeader
    fun findByGpio(gpio: Int): BoardPin? = allPins().find { it.gpio == gpio }
}

/** A device profile: train, RC car, etc. Mirrors the firmware profile concept. */
data class DeviceProfile(
    val key: String,
    val displayName: String,
    val boardKey: String,  // which BoardDef (in Boards.ALL) this profile targets
    val roles: List<PinRoleDef>,
    val defaults: Map<String, Int>
)

object Boards {
    val D1_MINI32 = BoardDef(
        key = "d1_mini32",
        displayName = "WeMos D1 Mini32",
        leftHeader = listOf(
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
        ),
        rightHeader = listOf(
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
    )

    // Common 38-pin DOIT ESP32 DEVKIT V1 layout. Same WROOM-32 chip and
    // GPIO restrictions as the D1 Mini32, but breaks out more pins —
    // including the flash pins (6-11), which are physically present as
    // header pins on this board but still reserved/unusable.
    // NOTE: pinout varies by seller/silkscreen — verify against the
    // specific board in hand before relying on this for a real build.
    val ESP32_DEVKIT_V1 = BoardDef(
        key = "esp32_devkit_v1",
        displayName = "ESP32 DevKit V1 (38-pin)",
        leftHeader = listOf(
            BoardPin("3V3", null, null),
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
            BoardPin("13", 13, PinStatus.AVAILABLE),
            BoardPin("D2", 9, PinStatus.RESERVED),
            BoardPin("D3", 10, PinStatus.RESERVED),
            BoardPin("CMD", 11, PinStatus.RESERVED),
            BoardPin("5V", null, null)
        ),
        rightHeader = listOf(
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
            BoardPin("15", 15, PinStatus.STRAPPING),
            BoardPin("D1", 8, PinStatus.RESERVED),
            BoardPin("D0", 7, PinStatus.RESERVED),
            BoardPin("CLK", 6, PinStatus.RESERVED)
        )
    )

    // Future boards get added here as their own BoardDef entries, e.g.:
    // val ESP32_S3_DEVKIT = BoardDef(key = "esp32_s3_devkit", ...)
    // val ESP32_C3        = BoardDef(key = "esp32_c3", ...)

    val ALL = listOf(D1_MINI32, ESP32_DEVKIT_V1)

    fun byKey(key: String): BoardDef = ALL.find { it.key == key } ?: D1_MINI32
}

// Profiles.TRAIN and Profiles.RC_CAR are now just fallback KEY/BOARD
// constants for code that needs a sensible default before real data has
// loaded (e.g. ActiveProfile's fallback). The actual profiles — their
// functions, defaults, everything — are seeded into CustomProfileStorage/
// CustomRoleStorage once on first run (see ProfileResolver.kt) and from
// then on are indistinguishable from any other user-created device:
// renamable, and now genuinely DELETABLE, same as their individual
// functions. See PIN_MAPPER_ROADMAP.md for why this changed.
object Profiles {
    val TRAIN = DeviceProfile(
        key = "train",
        displayName = "Train (TB6612FNG + MAX98357A)",
        boardKey = Boards.D1_MINI32.key,
        roles = emptyList(),
        defaults = emptyMap()
    )

    val RC_CAR = DeviceProfile(
        key = "rc_car",
        displayName = "RC Car",
        boardKey = Boards.D1_MINI32.key,
        roles = emptyList(),
        defaults = emptyMap()
    )
}
