package com.espad32.controller.controls

/**
 * How a button behaves when tapped.
 * TOGGLE: stays on until tapped again (e.g. headlight on/off).
 * MOMENTARY: on only while held/tapped, then reverts (e.g. horn).
 * SLIDER: continuous 0-255 value (e.g. motor speed) — backs a
 * PWM_OUTPUT role, sends SETV instead of SET.
 */
enum class ControlType {
    TOGGLE,
    MOMENTARY,
    SLIDER
}

/**
 * A single on-screen control, bound to a pin-mapped role.
 *
 * This is deliberately separate from PinRoleDef: a role describes what
 * a pin *does* electrically (its type, its GPIO), a ControlButtonDef
 * describes how it's *presented* on screen.
 *
 * DIGITAL_OUTPUT roles back a TOGGLE or MOMENTARY button. PWM_OUTPUT
 * roles back a SLIDER. SERVO roles aren't offered yet — firmware has
 * no angle-control command (SETV is PWM duty only). See
 * PIN_MAPPER_ROADMAP.md.
 */
data class ControlButtonDef(
    val id: String,          // unique per profile, e.g. "btn_" + timestamp
    val label: String,       // user-editable display label, e.g. "Headlight"
    val roleKey: String,     // which PinRoleDef this button controls
    val controlType: ControlType
)
