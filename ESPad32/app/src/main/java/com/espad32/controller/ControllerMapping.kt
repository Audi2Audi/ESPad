package com.espad32.controller

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ── All assignable functions ──────────────────────────────────────────
enum class ButtonFunction(val label: String) {
    NONE("Nothing"),
    HORN_ON("Horn (hold)"),
    PHOTO("Take Photo"),
    RECORD("Toggle Record"),
    LED_CYCLE("LED Cycle"),
    LED_OFF("LED Off"),
    FACE_CYCLE("Face Cycle"),
    FACE_OFF("Face Off"),
    CAMERA_FLIP("Camera Flip"),
    SERVO_RESET("Servo Reset (both)"),
    PAN_LEFT("Pan Left"),
    PAN_RIGHT("Pan Right"),
    TILT_UP("Tilt Up"),
    TILT_DOWN("Tilt Down"),
    PAN_CENTER("Pan Center (90°)"),
    TILT_CENTER("Tilt Center (90°)"),
    LIGHT_FOLLOW("Light Follow Mode"),
    LINE_TRACK("Line Track Mode"),
    STOP("Stop Car"),
    // Triggers one of the user-defined Controls buttons (e.g. "LED")
    // instead of a fixed car command — see ButtonMapping.customButtonId.
    CUSTOM_CONTROL("Custom Control Button")
}

enum class AxisFunction(val label: String) {
    NONE("Nothing"),
    DRIVE("Drive (throttle + steer)"),
    TRIGGER_DRIVE("Trigger Drive (fwd/rev)"),
    STEER_ONLY("Steer Only (left/right)"),
    PAN_TILT("Camera Pan/Tilt"),
    // Drives one of the user-defined Controls sliders (a PWM_OUTPUT
    // role, e.g. "Motor speed") using a single axis — see
    // AxisMapping.customButtonId. Only axisX is used; axisY is ignored
    // for this function.
    CUSTOM_PWM("Custom PWM Function"),
    // Drives a single-motor forward/reverse setup from ONE axis (a
    // stick's Y, or the D-Pad read as an axis rather than discrete
    // buttons) — above center triggers forward + proportional speed,
    // below center triggers reverse + proportional speed, center
    // stops. Needed because CUSTOM_PWM only ever sends one scalar to
    // one PWM slider — it has no concept of a paired direction role
    // that should flip based on which side of center the axis is on.
    // See customForwardButtonId/customReverseButtonId below.
    CUSTOM_BIDIRECTIONAL_DRIVE("Bidirectional Drive (fwd/rev + speed)")
}

// ── A single button mapping ───────────────────────────────────────────
data class ButtonMapping(
    val keyCode: Int,
    val label: String,          // friendly name e.g. "A Button"
    val function: ButtonFunction,
    // Only used when function == CUSTOM_CONTROL — the id of a
    // ControlButtonDef (from the Controls screen) this button triggers.
    val customButtonId: String? = null
)

// ── A single axis mapping ─────────────────────────────────────────────
data class AxisMapping(
    val axisX: Int,             // horizontal axis constant
    val axisY: Int,             // vertical axis constant
    val label: String,
    val function: AxisFunction,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    // Only used when function == CUSTOM_PWM (the speed slider it
    // drives) or CUSTOM_BIDIRECTIONAL_DRIVE (also the speed slider,
    // same meaning — the other two roles for that function are below).
    val customButtonId: String? = null,
    // Only used when function == CUSTOM_BIDIRECTIONAL_DRIVE — ids of
    // TOGGLE-type Controls buttons (e.g. "Motor direction A"/"Motor
    // direction B") that get set on/off based on which side of center
    // the axis is on.
    val customForwardButtonId: String? = null,
    val customReverseButtonId: String? = null
)

// ── Preset profiles ───────────────────────────────────────────────────
data class ControllerProfile(
    val name: String,
    val buttons: List<ButtonMapping>,
    val axes: List<AxisMapping>
)

object ControllerMapping {

    private const val PREFS_KEY_PROFILE = "activeProfile"
    private const val PREFS_KEY_BUTTONS = "customButtons"
    private const val PREFS_KEY_AXES    = "customAxes"
    private val gson = Gson()

    // ── All remappable buttons ────────────────────────────────────────
    val ALL_BUTTONS = listOf(
        Triple(KeyEvent.KEYCODE_BUTTON_A,    "A Button",      ButtonFunction.CAMERA_FLIP),
        Triple(KeyEvent.KEYCODE_BUTTON_B,    "B Button",      ButtonFunction.LED_CYCLE),
        Triple(KeyEvent.KEYCODE_BUTTON_X,    "X Button",      ButtonFunction.HORN_ON),
        Triple(KeyEvent.KEYCODE_BUTTON_Y,    "Y Button",      ButtonFunction.FACE_CYCLE),
        Triple(KeyEvent.KEYCODE_BUTTON_L1,   "L1",            ButtonFunction.SERVO_RESET),
        Triple(KeyEvent.KEYCODE_BUTTON_R1,   "R1",            ButtonFunction.NONE),
        Triple(KeyEvent.KEYCODE_BUTTON_L2,   "L2 / Left Trigger",  ButtonFunction.PHOTO),
        Triple(KeyEvent.KEYCODE_BUTTON_R2,   "R2 / Right Trigger", ButtonFunction.RECORD),
        Triple(KeyEvent.KEYCODE_DPAD_UP,     "D-Pad Up",      ButtonFunction.TILT_UP),
        Triple(KeyEvent.KEYCODE_DPAD_DOWN,   "D-Pad Down",    ButtonFunction.TILT_DOWN),
        Triple(KeyEvent.KEYCODE_DPAD_LEFT,   "D-Pad Left",    ButtonFunction.PAN_LEFT),
        Triple(KeyEvent.KEYCODE_DPAD_RIGHT,  "D-Pad Right",   ButtonFunction.PAN_RIGHT),
        Triple(KeyEvent.KEYCODE_BUTTON_THUMBL, "Left Stick Click",  ButtonFunction.LIGHT_FOLLOW),
        Triple(KeyEvent.KEYCODE_BUTTON_THUMBR, "Right Stick Click", ButtonFunction.LINE_TRACK),
        Triple(KeyEvent.KEYCODE_BUTTON_START,  "Start/Menu",  ButtonFunction.STOP),
        Triple(KeyEvent.KEYCODE_BUTTON_SELECT, "Select",      ButtonFunction.NONE)
    )

    // ── All remappable axes ───────────────────────────────────────────
    val ALL_AXES = listOf(
        Triple(
            Pair(MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
            "Left Stick",
            AxisFunction.DRIVE
        ),
        Triple(
            Pair(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ),
            "Right Stick",
            AxisFunction.PAN_TILT
        ),
        Triple(
            Pair(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y),
            "D-Pad Axes",
            AxisFunction.NONE
        )
    )

    // ── Presets ───────────────────────────────────────────────────────
    val PRESETS: List<ControllerProfile> = listOf(

        ControllerProfile("Default",
            buttons = ALL_BUTTONS.map { (kc, label, fn) -> ButtonMapping(kc, label, fn) },
            axes = ALL_AXES.map { (pair, label, fn) ->
                AxisMapping(pair.first, pair.second, label, fn)
            }
        ),

        ControllerProfile("Trigger Drive",
            buttons = ALL_BUTTONS.map { (kc, label, _) ->
                ButtonMapping(kc, label, when(kc) {
                    KeyEvent.KEYCODE_BUTTON_A    -> ButtonFunction.CAMERA_FLIP
                    KeyEvent.KEYCODE_BUTTON_B    -> ButtonFunction.LED_CYCLE
                    KeyEvent.KEYCODE_BUTTON_X    -> ButtonFunction.HORN_ON
                    KeyEvent.KEYCODE_BUTTON_Y    -> ButtonFunction.FACE_CYCLE
                    KeyEvent.KEYCODE_BUTTON_L1   -> ButtonFunction.SERVO_RESET
                    KeyEvent.KEYCODE_BUTTON_L2   -> ButtonFunction.NONE  // used as drive axis
                    KeyEvent.KEYCODE_BUTTON_R2   -> ButtonFunction.NONE  // used as drive axis
                    KeyEvent.KEYCODE_DPAD_UP     -> ButtonFunction.TILT_UP
                    KeyEvent.KEYCODE_DPAD_DOWN   -> ButtonFunction.TILT_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT   -> ButtonFunction.PAN_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT  -> ButtonFunction.PAN_RIGHT
                    KeyEvent.KEYCODE_BUTTON_R1   -> ButtonFunction.PHOTO
                    KeyEvent.KEYCODE_BUTTON_THUMBL -> ButtonFunction.RECORD
                    else -> ButtonFunction.NONE
                })
            },
            axes = listOf(
                // AXIS_GAS = right trigger (forward), AXIS_BRAKE = left trigger (reverse)
                // X steering from left stick AXIS_X
                AxisMapping(MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE, "Triggers (fwd/rev)", AxisFunction.TRIGGER_DRIVE),
                AxisMapping(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, "Left Stick (steer)", AxisFunction.STEER_ONLY),
                AxisMapping(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, "Right Stick", AxisFunction.PAN_TILT)
            )
        ),

        ControllerProfile("D-Pad Drive",
            buttons = ALL_BUTTONS.map { (kc, label, _) ->
                ButtonMapping(kc, label, when(kc) {
                    KeyEvent.KEYCODE_BUTTON_X    -> ButtonFunction.HORN_ON
                    KeyEvent.KEYCODE_BUTTON_B    -> ButtonFunction.LED_CYCLE
                    KeyEvent.KEYCODE_BUTTON_Y    -> ButtonFunction.FACE_CYCLE
                    KeyEvent.KEYCODE_BUTTON_A    -> ButtonFunction.CAMERA_FLIP
                    KeyEvent.KEYCODE_BUTTON_L1   -> ButtonFunction.SERVO_RESET
                    KeyEvent.KEYCODE_BUTTON_L2   -> ButtonFunction.PHOTO
                    KeyEvent.KEYCODE_BUTTON_R2   -> ButtonFunction.RECORD
                    KeyEvent.KEYCODE_DPAD_UP     -> ButtonFunction.NONE
                    KeyEvent.KEYCODE_DPAD_DOWN   -> ButtonFunction.NONE
                    KeyEvent.KEYCODE_DPAD_LEFT   -> ButtonFunction.NONE
                    KeyEvent.KEYCODE_DPAD_RIGHT  -> ButtonFunction.NONE
                    else -> ButtonFunction.NONE
                })
            },
            axes = listOf(
                AxisMapping(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, "D-Pad (drive)", AxisFunction.DRIVE),
                AxisMapping(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, "Right Stick (pan/tilt)", AxisFunction.PAN_TILT),
                AxisMapping(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, "Left Stick", AxisFunction.NONE)
            )
        )
    )

    // ── Active mapping (loaded from prefs or default) ─────────────────
    private var _buttons = mutableListOf<ButtonMapping>()
    private var _axes    = mutableListOf<AxisMapping>()
    var activeProfileName = "Default"

    val buttons: List<ButtonMapping> get() = _buttons
    val axes: List<AxisMapping>      get() = _axes

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("ESPad32Prefs", Context.MODE_PRIVATE)
        activeProfileName = prefs.getString(PREFS_KEY_PROFILE, "Default") ?: "Default"

        val savedButtons = prefs.getString(PREFS_KEY_BUTTONS, null)
        val savedAxes    = prefs.getString(PREFS_KEY_AXES, null)

        if (savedButtons != null && savedAxes != null) {
            try {
                val btType = object : TypeToken<List<ButtonMapping>>() {}.type
                val axType = object : TypeToken<List<AxisMapping>>() {}.type
                _buttons = gson.fromJson(savedButtons, btType)
                _axes    = gson.fromJson(savedAxes, axType)
                return
            } catch (e: Exception) { /* fall through to default */ }
        }
        applyPreset(PRESETS.find { it.name == activeProfileName } ?: PRESETS[0], save = false)
    }

    fun applyPreset(profile: ControllerProfile, save: Boolean = true) {
        activeProfileName = profile.name
        _buttons = profile.buttons.toMutableList()
        _axes    = profile.axes.toMutableList()
        if (save) saveToPrefs(null)
    }

    fun updateButton(index: Int, function: ButtonFunction, context: Context, customButtonId: String? = null) {
        _buttons[index] = _buttons[index].copy(function = function, customButtonId = customButtonId)
        saveToPrefs(context)
    }

    fun updateAxis(
        index: Int, function: AxisFunction, context: Context, customButtonId: String? = null,
        customForwardButtonId: String? = null, customReverseButtonId: String? = null
    ) {
        _axes[index] = _axes[index].copy(
            function = function,
            customButtonId = customButtonId,
            customForwardButtonId = customForwardButtonId,
            customReverseButtonId = customReverseButtonId
        )
        saveToPrefs(context)
    }

    private var _ctx: Context? = null
    fun init(context: Context) { _ctx = context.applicationContext; load(context) }

    private fun saveToPrefs(context: Context?) {
        val ctx = context ?: _ctx ?: return
        ctx.getSharedPreferences("ESPad32Prefs", Context.MODE_PRIVATE).edit()
            .putString(PREFS_KEY_PROFILE, activeProfileName)
            .putString(PREFS_KEY_BUTTONS, gson.toJson(_buttons))
            .putString(PREFS_KEY_AXES,    gson.toJson(_axes))
            .apply()
    }

    // ── Lookup helpers ────────────────────────────────────────────────
    fun functionForKey(keyCode: Int): ButtonFunction =
        _buttons.find { it.keyCode == keyCode }?.function ?: ButtonFunction.NONE

    fun axisForPair(axisX: Int): AxisMapping? =
        _axes.find { it.axisX == axisX }
}
