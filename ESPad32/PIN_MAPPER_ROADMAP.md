# Pin Mapper — Future Ideas & Roadmap

Tracking doc for where the mappable pin-config system could go, beyond
the current two hardcoded profiles (Train, RC Car) on one board (WeMos
D1 Mini32). Nothing here is committed to — it's a working list to pull
from when picking the next increment.

## Status: done so far

- [x] Pin-mapping UI (tap-a-role, tap-a-pin) for Train + RC Car profiles
- [x] App-side GPIO validation mirroring firmware's `pin_validation.h`
- [x] Local persistence + JSON payload builder (not yet sent to device)
- [x] `BoardDef` abstraction — board pin layout is now separate from
      device profiles, so a board can be reused across multiple devices
      and new boards can be added without touching profile code
      (see `pinmapper/PinModels.kt` — `Boards` object)
- [x] `RoleType` added to `PinRoleDef` (DIGITAL_OUTPUT, PWM_OUTPUT,
      SERVO, DIGITAL_INPUT, AUDIO_SIGNAL) — prerequisite for both custom
      roles and on-screen Controls below
- [x] **Controls screen** (`controls/` package): add/rename/remove
      on-screen buttons bound to a pin-mapped role. Only DIGITAL_OUTPUT
      roles are offered (e.g. an LED/headlight) — PWM/servo need a
      slider control that doesn't exist yet. Buttons support TOGGLE
      (stays on/off) or MOMENTARY (on while pressed).
      **Local-only right now** — tapping a button flips locally stored
      state and logs what command *would* be sent; nothing reaches the
      ESP32 yet. Wiring this to a live GPIO toggle depends on the same
      transport work tracked under "Bigger lift: firmware-side
      generality" below.
- [x] **Live buttons on the main driving screen** — buttons configured
      in the Controls screen now also render directly on MainActivity
      (middle-left, clear of joysticks and the camera overlay). Tap to
      toggle, long-press to rename/remove right there — same underlying
      storage as the Controls screen, so either screen stays in sync.
      Required introducing `ActiveProfile` (`controls/ActiveProfile.kt`)
      since MainActivity previously had no concept of "which profile am
      I controlling" at all. **Known limitation:** this is last-selected-
      wins (whichever profile you opened last in Pin Mapper/Controls) —
      there's no way yet for the app to know which profile the actually-
      connected ESP32 is running, since that requires the device to tell
      it (part of the live device sync work below).

## Near-term: board selection

**Decision (confirmed):** stay ESP32-family only for now. Arduino support
is explicitly deferred — different pin scheme, no native WiFi/BLE, and
likely a different transport story (companion module rather than talking
to the phone directly). Revisit only if a specific project needs it.

- [x] Board picker dropdown in the Pin Mapper UI (implemented as a tab
      row, same pattern as the profile tabs) — switching boards carries
      over assignments that are still valid (or previously saved for
      that profile+board combo) and clears/logs any that aren't
- [x] ESP32 DevKit V1 (38-pin) added as second `BoardDef`
- [ ] Add more ESP32-family `BoardDef` entries, roughly in likely-usefulness
      order:
      - [x] ESP32 DevKit V1 (generic 30-pin/38-pin dev board — probably
            the single most common alternative to the D1 Mini32)
      - [ ] ESP32-S3 DevKit (newer, more GPIOs, native USB — worth it if
            a future project needs more pins than the D1 Mini32 offers)
      - [ ] ESP32-C3 (RISC-V, fewer pins, smaller/cheaper — good fit for
            simple single-function builds)
      - ESP8266 (NodeMCU etc.) is a different chip family with its own
        GPIO numbering/restrictions and no direct code reuse from the
        ESP32 validation rules — worth a note but treat as its own
        research item, not a quick add, if it ever comes up
- [ ] ~~Arduino support~~ — deferred, see decision above

## Near-term: renaming & custom roles

- [ ] Relabel an existing role (e.g. "Motor direction A" → "Left wheel
      forward") — straightforward, just an editable text field per role
- [ ] Add a brand-new role the app doesn't know about yet (e.g. "Fog
      machine trigger") — `RoleType` now exists (done above), so this is
      mostly UI work: a "new role" flow that lets someone pick a type
      and have `PinValidation` filter correctly automatically

## Near-term: Controls screen follow-ups

- [ ] **Live device sync** — the big one. Buttons currently only flip
      local state and log what they'd send. Needs the same transport
      (WiFi/TCP or BLE) already tracked below, plus a firmware command
      handler that actually does `digitalWrite` on TOGGLE/MOMENTARY
- [ ] Slider control for PWM_OUTPUT and SERVO roles (motor speed,
      steering angle) — buttons only make sense for DIGITAL_OUTPUT;
      continuous values need a different UI element entirely
- [ ] Momentary buttons currently log on tap only — for a real "on
      while held" feel this needs press/release touch handling
      (`OnTouchListener`, not `OnClickListener`), which matters more
      once live device sync exists (a real horn should stop honking on
      release, not stay on until tapped again)
- [ ] Reordering buttons (drag-and-drop) once someone has more than a
      handful — not needed yet with only 1-2 buttons per profile

## Medium-term: user-defined devices

- [ ] "New Device" flow: pick a board → name the device → add roles one
      at a time (with type) → assign pins → save as a new profile
      alongside Train/RC Car
- [ ] Custom/unlisted boards: let someone define a board that isn't in
      `Boards.ALL` — name it, mark which physical pins exist, flag
      restrictions manually. Bigger lift (needs its own validation UI
      and persisted custom-board definitions)
- [ ] Export/import a device profile as JSON — lets one person's
      "smart terrarium controller" profile get shared and reused by
      someone else with the same hardware, without remapping from
      scratch

## Bigger lift: firmware-side generality

This is the harder half, and probably the long pole for "control any
ESP32/Arduino project" as a real goal:

- [ ] Right now `pin_validation.h` / `pin_store.h` on the firmware side
      assume a fixed, known set of roles per profile. Supporting
      arbitrary user-defined devices means firmware also needs a
      generic capability system — e.g. "here's a PWM output on pin X,
      here's a digital sensor on pin Y" — resolved at runtime, rather
      than compiled-in driver logic per profile
- [ ] Decide on a transport strategy that scales beyond the current
      Serial test harness — likely the same WiFi/TCP or BLE the RC car
      profile already uses, but a custom/user-defined device may need
      its own negotiated command set rather than fixed roles

## Open questions (not yet decided)

- Does "any ESP32 powered device" imply supporting arbitrary
  *sensors* (reading data back), or stay focused on *actuators* (motors,
  servos, lights, audio) like the current two profiles?
- How much of the custom-device flow needs firmware code generation
  (e.g. the app produces a firmware sketch, not just a config payload)
  vs. assuming the user already has firmware that speaks a generic
  protocol?
- Should custom boards/devices be phone-local only, or shareable
  (a small community library of profiles)?
