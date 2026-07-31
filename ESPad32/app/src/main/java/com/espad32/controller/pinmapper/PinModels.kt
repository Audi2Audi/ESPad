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
    val rightHeader: List<BoardPin>,
    // Whether this board has a camera module wired to fixed pins. Not
    // something a pin-role mapping can express — camera pins are a
    // property of the physical board itself (marked RESERVED below on
    // camera-capable boards), not something a user assigns like any
    // other function.
    val supportsCamera: Boolean = false,
    // Which pins are ADC1 (reliable with WiFi active) on THIS chip —
    // genuinely different between chip families, not just a global
    // ESP32 constant. Originally hardcoded as one global set correct
    // only for the original ESP32; that quietly meant Analog Input
    // creation on the S3/C3 boards would reject their actual valid
    // pins, since neither uses the same ADC1 numbering. Defaults to
    // the original ESP32's set for any board that doesn't override it.
    val adc1Pins: Set<Int> = setOf(32, 33, 34, 35, 36, 39)
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
    val defaults: Map<String, Int>,
    val connectionIp: String? = null
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

    // AI-Thinker ESP32-CAM. Unlike every other board here, most of its
    // pins aren't general-purpose at all — they're hardwired to the
    // camera module itself and marked RESERVED, the same status used
    // for the DevKit's onboard-flash pins. A camera isn't something a
    // pin role can express (there's no "assign this GPIO to be a
    // camera" — the wiring is fixed by the board), so this is handled
    // as a board-level property instead: supportsCamera = true.
    // NOTE: this board exposes very few free GPIOs beyond the camera
    // itself (most are consumed by the camera interface) — verify
    // against your specific board/schematic before relying on this.
    val ESP32_CAM_AI_THINKER = BoardDef(
        key = "esp32_cam_ai_thinker",
        displayName = "ESP32-CAM (AI-Thinker)",
        supportsCamera = true,
        leftHeader = listOf(
            BoardPin("D0/Y2", 5, PinStatus.RESERVED),
            BoardPin("D1/Y3", 18, PinStatus.RESERVED),
            BoardPin("D2/Y4", 19, PinStatus.RESERVED),
            BoardPin("D3/Y5", 21, PinStatus.RESERVED),
            BoardPin("D4/Y6", 36, PinStatus.RESERVED),
            BoardPin("D5/Y7", 39, PinStatus.RESERVED),
            BoardPin("D6/Y8", 34, PinStatus.RESERVED),
            BoardPin("D7/Y9", 35, PinStatus.RESERVED),
            BoardPin("XCLK", 0, PinStatus.RESERVED),
            BoardPin("PCLK", 22, PinStatus.RESERVED)
        ),
        rightHeader = listOf(
            BoardPin("VSYNC", 25, PinStatus.RESERVED),
            BoardPin("HREF", 23, PinStatus.RESERVED),
            BoardPin("SIOD", 26, PinStatus.RESERVED),
            BoardPin("SIOC", 27, PinStatus.RESERVED),
            BoardPin("PWDN", 32, PinStatus.RESERVED),
            BoardPin("Flash LED", 4, PinStatus.AVAILABLE),
            BoardPin("Status LED", 33, PinStatus.AVAILABLE),
            BoardPin("12", 12, PinStatus.STRAPPING),
            BoardPin("13", 13, PinStatus.AVAILABLE),
            BoardPin("14", 14, PinStatus.AVAILABLE),
            BoardPin("15", 15, PinStatus.STRAPPING),
            BoardPin("2", 2, PinStatus.STRAPPING),
            BoardPin("16", 16, PinStatus.AVAILABLE)
        )
    )

    // ESP32-S3-DevKitC-1 — newer chip, more GPIOs, native USB. S3 dev
    // boards vary more between variants (plain WROOM-1 vs octal-PSRAM
    // versions use different flash/PSRAM pin counts) than the original
    // ESP32 does, so this is a reasonable common-case layout — verify
    // against your specific board/schematic before relying on it,
    // same caveat as the other boards here.
    val ESP32_S3_DEVKIT = BoardDef(
        key = "esp32_s3_devkit",
        displayName = "ESP32-S3 DevKitC-1",
        leftHeader = listOf(
            BoardPin("3V3", null, PinStatus.RESERVED),
            BoardPin("EN", null, PinStatus.RESERVED),
            BoardPin("4", 4, PinStatus.AVAILABLE),
            BoardPin("5", 5, PinStatus.AVAILABLE),
            BoardPin("6", 6, PinStatus.AVAILABLE),
            BoardPin("7", 7, PinStatus.AVAILABLE),
            BoardPin("15", 15, PinStatus.AVAILABLE),
            BoardPin("16", 16, PinStatus.AVAILABLE),
            BoardPin("17", 17, PinStatus.AVAILABLE),
            BoardPin("18", 18, PinStatus.AVAILABLE),
            BoardPin("8", 8, PinStatus.AVAILABLE),
            BoardPin("3", 3, PinStatus.STRAPPING),
            BoardPin("46", 46, PinStatus.STRAPPING),
            BoardPin("9", 9, PinStatus.AVAILABLE),
            BoardPin("10", 10, PinStatus.AVAILABLE),
            BoardPin("11", 11, PinStatus.AVAILABLE),
            BoardPin("12", 12, PinStatus.AVAILABLE),
            BoardPin("13", 13, PinStatus.AVAILABLE),
            BoardPin("14", 14, PinStatus.AVAILABLE)
        ),
        rightHeader = listOf(
            BoardPin("GND", null, PinStatus.RESERVED),
            BoardPin("43 (TX0)", 43, PinStatus.UART),
            BoardPin("44 (RX0)", 44, PinStatus.UART),
            BoardPin("1", 1, PinStatus.AVAILABLE),
            BoardPin("2", 2, PinStatus.AVAILABLE),
            BoardPin("42", 42, PinStatus.AVAILABLE),
            BoardPin("41", 41, PinStatus.AVAILABLE),
            BoardPin("40", 40, PinStatus.AVAILABLE),
            BoardPin("39", 39, PinStatus.AVAILABLE),
            BoardPin("38", 38, PinStatus.AVAILABLE),
            BoardPin("37", 37, PinStatus.RESERVED), // often PSRAM on octal variants
            BoardPin("36", 36, PinStatus.RESERVED), // often PSRAM on octal variants
            BoardPin("35", 35, PinStatus.RESERVED), // often PSRAM on octal variants
            BoardPin("0", 0, PinStatus.STRAPPING),
            BoardPin("45", 45, PinStatus.STRAPPING),
            BoardPin("48", 48, PinStatus.AVAILABLE),
            BoardPin("47", 47, PinStatus.AVAILABLE),
            BoardPin("21", 21, PinStatus.AVAILABLE),
            BoardPin("20 (USB D+)", 20, PinStatus.STRAPPING), // native USB if used - risky to repurpose
            BoardPin("19 (USB D-)", 19, PinStatus.STRAPPING)  // native USB if used - risky to repurpose
        ),
        // S3's ADC1 is GPIO1-10 — completely different from the
        // original ESP32's 32/33/34/35/36/39. Getting this wrong meant
        // Analog Input creation on this board would reject its actual
        // valid pins.
        adc1Pins = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    )

    // ESP32-C3-DevKitM-1 — RISC-V, single core, fewer pins, cheaper.
    // Good fit for simple single-function builds. Same verify-against-
    // your-board caveat as above; C3 boards are a newer, less
    // standardized family than the original ESP32.
    val ESP32_C3_DEVKIT = BoardDef(
        key = "esp32_c3_devkit",
        displayName = "ESP32-C3 DevKitM-1",
        leftHeader = listOf(
            BoardPin("3V3", null, PinStatus.RESERVED),
            BoardPin("EN", null, PinStatus.RESERVED),
            BoardPin("4", 4, PinStatus.AVAILABLE),
            BoardPin("5", 5, PinStatus.AVAILABLE),
            BoardPin("6", 6, PinStatus.AVAILABLE),
            BoardPin("7", 7, PinStatus.AVAILABLE),
            BoardPin("8", 8, PinStatus.STRAPPING),
            BoardPin("9", 9, PinStatus.STRAPPING),
            BoardPin("10", 10, PinStatus.AVAILABLE)
        ),
        rightHeader = listOf(
            BoardPin("GND", null, PinStatus.RESERVED),
            BoardPin("0", 0, PinStatus.AVAILABLE),
            BoardPin("1", 1, PinStatus.AVAILABLE),
            BoardPin("2", 2, PinStatus.STRAPPING),
            BoardPin("3", 3, PinStatus.AVAILABLE),
            BoardPin("21 (TX0)", 21, PinStatus.UART),
            BoardPin("20 (RX0)", 20, PinStatus.UART),
            BoardPin("18", 18, PinStatus.AVAILABLE),
            BoardPin("19", 19, PinStatus.AVAILABLE)
        ),
        // C3's ADC1 is GPIO0-4 — again, nothing like the original
        // ESP32's numbering.
        adc1Pins = setOf(0, 1, 2, 3, 4)
    )

    val ALL = listOf(D1_MINI32, ESP32_DEVKIT_V1, ESP32_CAM_AI_THINKER, ESP32_S3_DEVKIT, ESP32_C3_DEVKIT)

    // Populated by BoardResolver.allBoards(context) as a side effect —
    // lets byKey() (which has no Context available, since Boards is a
    // plain object) resolve custom boards too, as long as
    // allBoards(context) has run at least once this session (which
    // every screen's board-tab-building already does). Avoids a much
    // larger refactor threading Context through every byKey() call
    // site throughout the app.
    internal val customBoardsCache = mutableListOf<BoardDef>()

    fun byKey(key: String): BoardDef = (ALL + customBoardsCache).find { it.key == key } ?: D1_MINI32

    // Which board(s) the bundled espad_default_firmware.bin ("Flash
    // Default") was actually compiled for and verified against — NOT
    // every board this app can define pin layouts for. D1 Mini32 and
    // ESP32 DevKit V1/ESP32-CAM share the same underlying chip (so the
    // binary would technically boot on those too), but its pin
    // assumptions (I2S at 26/25/22, wherever the compiled role config
    // points) reflect the D1 Mini32 specifically — conservative here
    // rather than assuming "same chip" means "same binary is fine."
    // ESP32-S3/C3 are genuinely different chip architectures the
    // binary won't even run on correctly (S3) or boot on at all (C3,
    // RISC-V) — see PIN_MAPPER_ROADMAP.md.
    val DEFAULT_FIRMWARE_SUPPORTED_BOARDS = setOf("d1_mini32")
}

// Merges built-in boards with user-defined ones (CustomBoardStorage)
// for anywhere that needs "every board that exists" — New Device's
// board picker, the Guided Setup wizard, and Pin Mapper's board-switch
// tabs within an existing profile. Also refreshes Boards.customBoardsCache
// as a side effect, so plain Boards.byKey() calls elsewhere in the same
// screen correctly resolve custom boards too.
object BoardResolver {
    fun allBoards(context: android.content.Context): List<BoardDef> {
        val custom = CustomBoardStorage(context).loadBoards().map { it.toBoardDef() }
        Boards.customBoardsCache.clear()
        Boards.customBoardsCache.addAll(custom)
        return Boards.ALL + custom
    }
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
