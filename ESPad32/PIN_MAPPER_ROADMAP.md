# Pin Mapper — Future Ideas & Roadmap

Tracking doc for where the mappable pin-config system could go, beyond
the current two hardcoded profiles (Train, RC Car) on one board (WeMos
D1 Mini32). Nothing here is committed to — it's a working list to pull
from when picking the next increment.

## Status: done so far

- [x] Removed Freenove-firmware-specific panel buttons (LED, LED Off,
      Face, Face Off, Horn, Servo Reset, Custom Face/Matrix Canvas) —
      these sent `CMD_LED_MOD`/`CMD_MATRIX_MOD`/`CMD_BUZZER`/`CMD_CAMERA`
      commands specific to the original Freenove car firmware, which
      lives in the separate, untouched ESPad32 repo and doesn't apply
      to devices built for this project. Kept: Camera Flip (local video
      flip, no device command), Photo/Record/View Log/Settings (generic
      app features). Note: `currentLedMode`/`currentEmotionMode` and the
      gamepad `ButtonFunction` mapping that also triggers these same
      CMD_* commands were left untouched — that's a separate system
      from the on-screen panel and out of scope for this cleanup.
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
- [x] **Live buttons in the existing control panel drawer** — buttons
      configured in the Controls screen now render as extra rows inside
      the same bottom drawer as LED/Horn/Reset/etc (`ControlPanelView` /
      `view_control_panel.xml`), using the identical `@style/CarButton`
      pill styling — not a separate floating widget. Tap to toggle,
      long-press to rename/remove right there — same underlying storage
      as the Controls screen, so either screen stays in sync.
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

- [x] **Relabel an existing (built-in) role** — long-press any function
      row in Pin Mapper → rename. Built-in roles (motor_dir_a, etc)
      store the override separately (`CustomRoleStorage`
      label-override map) rather than mutating the source data, since
      other logic depends on the key existing — only the displayed
      label changes.
- [x] **Add a brand-new custom role** — "+ Add Function" in Pin Mapper.
      Solves the original motivating problem directly: previously the
      only way to test an LED was to repurpose an unrelated built-in
      role (e.g. "Motor standby") just because its GPIO was free. Now
      a genuine "LED" function can be created instead. Custom roles are
      always created as `DIGITAL_OUTPUT` for now (the only type
      Controls buttons support) and can be renamed or deleted freely
      (unlike built-ins, which can be renamed but not deleted, since
      their key is depended on elsewhere).
      Implementation: `CustomRoleStorage` (per-profile custom role list
      + label overrides), `RoleResolver.effectiveRoles()` (shared
      built-in + custom merge for screens that only read the list, e.g.
      Controls), and `PinMapperActivity.effectiveRoles()` (same merge,
      inline, since Pin Mapper also needs to mutate the list). Both the
      Pin Mapper role list and Controls' "Add Button" role picker now
      show custom roles, not just built-ins.

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

## Medium-term: user-defined device profiles (sharpened end goal)

**The actual end goal (confirmed):** a settings-driven flow —
pick your board → pick/assign your pins → name your functions → add
and name your buttons — producing a **user-created device profile**,
not one of the two hardcoded `Profiles.TRAIN`/`Profiles.RC_CAR`
entries. With that in place, someone with two trains (or a train and
an RC car, or three unrelated projects) creates a named profile for
each and switches between them, rather than the app only ever knowing
about its two built-in device types.

This is a real architecture change, not just new UI on top of what
exists — `DeviceProfile` today is a hardcoded `object Profiles { val
TRAIN = ...; val RC_CAR = ... }`. Getting to user-created profiles
means:

- [ ] `DeviceProfile` (or a new parallel type) needs to be something a
      user creates and persists, not a compile-time constant — a
      "New Device" flow: name it → pick a board → add functions one at
      a time (reusing the custom-role creation flow already built) →
      assign pins → save as its own profile, stored alongside (not
      replacing) the built-in Train/RC Car ones
- [ ] A **settings entry point** that walks through board → pins →
      functions → buttons as one guided sequence, instead of requiring
      someone to already know to visit Pin Mapper then Controls
      separately
- [ ] A **profile switcher** — likely on the main screen — to pick
      which device you're currently driving. `ActiveProfile` already
      exists as the "which profile is active" concept (currently
      last-selected-wins from Pin Mapper/Controls); this becomes the
      real UI for that instead of an implicit side effect
- [ ] **Real wrinkle to solve, not yet solved:** there's exactly one
      physical connection at a time (`MainTcpHolder`, one IP). Switching
      which *device profile* you're configuring in software is easy;
      switching which *physical board* you're actually connected to
      means also changing the IP being connected to. Profile switching
      and connection/WiFi settings are linked in a way they aren't
      today — this is where the "editable per-device SSID/password"
      note above connects to this work directly, rather than being a
      separate feature
- [ ] Custom/unlisted boards: let someone define a board that isn't in
      `Boards.ALL` — name it, mark which physical pins exist, flag
      restrictions manually. Bigger lift (needs its own validation UI
      and persisted custom-board definitions)
- [ ] Export/import a device profile as JSON — lets one person's
      "smart terrarium controller" profile get shared and reused by
      someone else with the same hardware, without remapping from
      scratch

## Live device transport — confirmed architecture

**Decision (confirmed):**
- **Phone ↔ ESP32:** WiFi/TCP, same pattern as the original car app (which
  used TCP port 4000). Not BLE, at least for now.
- **Physical input:** a Bluetooth gamepad (e.g. GameSir) connects to the
  **phone**, not the ESP32 — this is already how the existing gamepad/
  `KeyEvent`/`MotionEvent` handling and `ButtonFunction` mapping work in
  MainActivity. The gamepad is just a phone-side input source; it has
  no direct relationship to the ESP32 connection at all.
- **BLE to the ESP32** is explicitly deferred — revisit only once the
  WiFi/TCP path is fully built, proven, and stable. Not a blocking
  decision now, just not the default.

This is a straightforward extension of what already exists, not a new
architecture: the original car app already speaks TCP to an ESP32 and
already reads gamepad input on the phone side. What's missing is
extending that same TCP command channel to also carry the generic
role-based commands from Pin Mapper/Controls (`SET <role> <value>`,
matching what the Serial test harness already proved works on the
firmware side — see below), rather than only the fixed car-specific
commands.

**Confirmed working (Serial-only, phone not yet involved):** flashed
`ESPad_PinConfig_Test.ino` to a real WeMos D1 Mini32 with the motor
shield soldered on, assigned a test role to GPIO 4 via the JSON config
payload, and verified with a multimeter that `SET test_led 1/0` over
Serial actually drives the pin HIGH/LOW. This confirms the firmware-side
chain (saved role → resolved GPIO → real hardware output) works
correctly — the prerequisite before wiring the same command up over
TCP instead of Serial.

**Confirmed working (WiFi/TCP, still phone-as-manual-client):** same
sketch extended with a WiFi soft-AP + TCP server (port 4000, matching
the original car app's port), tested from Termux via `nc 192.168.4.1
4000` — identical `SET test_led 1/0` commands, identical LED response,
now over the network instead of USB.

**Confirmed working (app-wired):** the app now actually sends commands
instead of just logging them locally, on both sides:
- **Controls buttons** send `SET <role> <0|1>` (see below, unchanged
  from the original entry).
- **Pin Mapper's "Validate & Save"** now also pushes the full JSON pin
  config payload to the device (previously local-only, same TODO as
  Controls had). Response is a single captured line — `VALIDATION OK`
  means the device accepted and saved the config to its own NVS;
  `NACK: ...` means it was rejected (e.g. a reserved/invalid pin) even
  though the app-side validation passed, which can legitimately happen
  if app and firmware validation rules ever drift out of sync.
- New `controls/DeviceCommand.kt`: `sendSet()` for role toggles,
  `sendRaw()` as the general-purpose version both Pin Mapper and
  Controls now share — sends any single-line command through
  `MainTcpHolder` — the same shared connection singleton other screens
  (`OtaActivity`, `MatrixCanvasActivity`, `SettingsDialogFragment`)
  already use, rather than opening a second socket. This isn't just
  convenient — the firmware's command server only accepts **one client
  at a time**, so a second connection would hang waiting for `accept()`.
- Both the Controls screen and the live buttons on the main driving
  screen call `DeviceCommand.sendSet()` and log the device's actual
  response instead of "local only."
- **Fixed a bug found along the way:** `MainActivity.handleIncomingData`
  only ever routed `CMD_WIFI*` responses to `MainTcpHolder.onNextData`.
  This meant `OtaActivity`'s firmware version query was silently always
  timing out — it was never actually wired to receive its response. Added
  a generic passthrough for anything not already handled by CMD_WIFI/
  CMD_POWER, which both fixes that latent bug and is what our SET
  command responses now rely on.
- **Known limitation, not yet solved:** responses aren't tagged with
  request IDs. If a `SET` response and some other unsolicited line
  (e.g. a `CMD_POWER` battery poll reply, or — since our test firmware
  doesn't understand `CMD_POWER` — the JSON-parse `NACK` it sends back
  instead) arrive at the same moment, the one-shot response handler
  could attribute the wrong line to the wrong command. Low probability
  for manual testing, but real — proper request/response framing
  (matching a command ID) is worth doing before this is load-bearing
  for anything more than testing.
- **Momentary buttons still just send ON** — true press/release needs
  touch-down/up handling (tracked above), not implemented yet, so
  nothing currently sends the matching OFF.

**Future settings feature (not built yet):** the test sketch currently
hardcodes a fixed AP SSID/password ("ESPad_Test" / "espad1234"). Once
someone has more than one ESP32 device (train, RC car, future builds),
identical default credentials across all of them will collide/confuse
which network you're actually connecting to. The app's Settings screen
should let the user view/edit the SSID and password **for the specific
device they're configuring** — meaning this also needs a way to push
that changed SSID/password to the firmware itself (e.g. a command like
`SET_WIFI <ssid> <password>` that writes new AP credentials to NVS and
reboots), not just store it app-side. Worth scoping alongside the rest
of the live-transport work rather than bolting on later, since it
touches both the connection-settings UI and the firmware's WiFi setup
code.

## Bigger lift: firmware-side generality

This is the harder half, and probably the long pole for "control any
ESP32/Arduino project" as a real goal:

- [ ] Right now `pin_validation.h` / `pin_store.h` on the firmware side
      assume a fixed, known set of roles per profile. Supporting
      arbitrary user-defined devices means firmware also needs a
      generic capability system — e.g. "here's a PWM output on pin X,
      here's a digital sensor on pin Y" — resolved at runtime, rather
      than compiled-in driver logic per profile
- [ ] Extend the confirmed-working `SET <role> <value>` command (proven
      over Serial) to run over the TCP connection instead — this is the
      concrete next build step, not just a research question anymore

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
