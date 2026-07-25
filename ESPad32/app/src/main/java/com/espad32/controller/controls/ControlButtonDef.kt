package com.espad32.controller.controls

/**
 * How a button behaves when tapped.
 * TOGGLE: stays on until tapped again (e.g. headlight on/off).
 * MOMENTARY: on only while held/tapped, then reverts (e.g. horn).
 */
enum class ControlType {
    TOGGLE,
    MOMENTARY
}

/**
 * A single on-screen control button, bound to a pin-mapped role.
 *
 * This is deliberately separate from PinRoleDef: a role describes what
 * a pin *does* electrically (its type, its GPIO), a ControlButtonDef
 * describes how it's *presented* on screen. Splitting these means the
 * same role could eventually back more than one kind of control (a
 * toggle today, a slider later for a dimmable LED) without redefining
 * the underlying pin mapping.
 *
 * Only roles with type DIGITAL_OUTPUT are offered when adding a button
 * right now — PWM_OUTPUT and SERVO roles need a continuous control
 * (slider), which isn't built yet. See PIN_MAPPER_ROADMAP.md.
 */
data class ControlButtonDef(
    val id: String,          // unique per profile, e.g. "btn_" + timestamp
    val label: String,       // user-editable display label, e.g. "Headlight"
    val roleKey: String,     // which PinRoleDef this button controls
    val controlType: ControlType
)
