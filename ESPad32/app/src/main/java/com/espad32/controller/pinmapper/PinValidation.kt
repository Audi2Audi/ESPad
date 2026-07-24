package com.espad32.controller.pinmapper

/**
 * App-side validation, mirroring pin_validation.h on the firmware.
 * This is the "nice UX, catch it early" layer — firmware still
 * re-validates independently and is the real safety net.
 */
object PinValidation {

    data class Result(val ok: Boolean, val reason: String? = null)

    fun canAssign(pin: BoardPin, roleKey: String): Result {
        if (pin.gpio == null) {
            return Result(false, "Not a GPIO pin")
        }
        if (pin.status == PinStatus.INPUT_ONLY) {
            return Result(false, "Input-only pin — can't drive an output role")
        }
        if (pin.status == PinStatus.RESERVED) {
            return Result(false, "Reserved for onboard flash")
        }
        // Strapping and UART pins are allowed but should surface a warning
        // in the UI (handled by caller via pin.status), not a hard block.
        return Result(true)
    }

    /** True if this pin's status should show a warning badge even when allowed. */
    fun isRisky(pin: BoardPin): Boolean =
        pin.status == PinStatus.STRAPPING || pin.status == PinStatus.UART

    /** Checks a full assignment map for duplicate GPIOs across roles. */
    fun findDuplicates(assignments: Map<String, Int?>): List<Int> {
        val seen = mutableMapOf<Int, Int>()
        val dupes = mutableListOf<Int>()
        assignments.values.filterNotNull().forEach { gpio ->
            seen[gpio] = (seen[gpio] ?: 0) + 1
            if (seen[gpio] == 2) dupes.add(gpio)
        }
        return dupes
    }
}
