# Pin Mapper — Future Ideas & Roadmap

Tracking doc for where the mappable pin-config system could go, beyond
the current two hardcoded profiles (Train, RC Car) on one board (WeMos
D1 Mini32). Nothing here is committed to — it's a working list to pull
from when picking the next increment.

## Status: done so far

- [x] **Fixed:** Controls' "Add Button" role picker was an unbounded
      horizontal row of buttons with no scrolling or wrapping — once
      there were more than ~3-4 eligible roles, longer labels (e.g.
      "Motor speed (PWM)") pushed later roles off the visible dialog
      width, making them appear to not exist even though the data was
      fine. Replaced with a vertical, scrollable, single-select list —
      no limit on how many roles can be offered. Also dropped the
      redundant auto-appended " (PWM)" suffix (built-in role labels can
      already contain "(PWM)" themselves, producing "(PWM) (PWM)") in
      favor of a small separate type tag next to each role name.
- [x] **Fixed:** deleting a custom function in Pin Mapper was only
      reachable via a hidden long-press with no visible hint it
      existed. Added a visible 🗑 icon on custom role rows (built-in
      roles still can't be deleted, only renamed, since other logic
      depends on their key existing) — tapping it opens the same
      rename/delete dialog long-press already did.

- [x] **UDP broadcast device discovery** — solves "the STA IP wasn't
      reported in time during the AP->STA channel switch" (the ESP32
      has one radio, so AP+STA share a channel; when STA connects to a
      router the AP's channel often has to shift to match, which can
      drop the connection right as the CMD_WIFI_OK response with the
      new IP is being sent). Instead of needing to log into the router
      to find the device, the app broadcasts "ESPAD_DISCOVER" and any
      ESPad device replies directly with "ESPAD_HERE#<ip>#<name>".
      - Firmware: `discovery.h` (test sketch v6) — a UDP listener on
        port 4210, replies with the STA IP if connected, AP IP as
        fallback. `<name>` is currently just the AP SSID; once devices
        have real user-given names (see the device-profile work below),
        this should report that instead.
      - App: `controls/DeviceDiscovery.kt` broadcasts and collects
        replies for ~2s on a background thread. Wired into
        `SettingsDialogFragment` via a new 🔍 button next to the IP
        field — one result auto-fills the IP field, multiple results
        show a picker. Still requires tapping Save to actually connect,
        matching the existing pattern for auto-populated IPs elsewhere
        in that screen.
      - Also added directly on the **main driving screen**, next to
        the live IP display in the top status bar — since there's no
        "Save" step there, a found device is applied and reconnected
        to immediately (or a picker shown for multiple results),
        rather than just filling a field for later confirmation.
      - Not yet done: only the test sketch speaks this protocol. The
        real car firmware (separate ESPad32 repo) doesn't implement
        `discovery.h`'s UDP responder — this needs porting over there
        too before it helps with the actual car, not just test builds.

- [x] **Fixed:** reassigning a GPIO away from a required built-in role
      (e.g. stealing GPIO 4 from "Motor standby" to give it to a new
      custom "LED" function) silently left the built-in role unassigned,
      and `Validate & Save` correctly refused to save — but without
      making that consequence obvious, so it just looked like the
      reassignment "didn't take." Now `onPinTapped` logs explicitly
      when bumping another role, and calls out clearly when the bumped
      role is a required built-in one that needs a new pin before
      saving will succeed.
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

**Usage model (confirmed): per-session, not hot-swapping.** This isn't
about juggling multiple live connections at once — it's "today I drive
my train, tomorrow my RC car, the day after my robot arm." You pick
which device you're operating **before** connecting for that session,
then use it as normal. This simplifies the transport question a lot:
there's still only one live connection at a time, but that's fine,
because there's only ever supposed to be one active session anyway.
No need to solve concurrent connections or live-switching — just "pick
your device, then connect to it."

- [ ] `DeviceProfile` (or a new parallel type) needs to be something a
      user creates and persists, not a compile-time constant — a
      "New Device" flow: name it → pick a board → add functions one at
      a time (reusing the custom-role creation flow already built) →
      assign pins → save as its own profile, stored alongside (not
      replacing) the built-in Train/RC Car ones
      **Note:** different profiles will genuinely use different board
      types, not just different pin assignments on the same board —
      e.g. train on a D1 Mini32, a future robot arm on an ESP32-WROVER.
      Already fine structurally (`DeviceProfile.boardKey` is already
      per-profile, not global), but the "New Device" flow needs to make
      board choice a real, deliberate step every time — never default
      to "whichever board is first in the list." Also means `Boards.ALL`
      needs to keep growing to cover whatever hardware people actually
      build on — **ESP32-WROVER** (extra PSRAM, larger form factor,
      common for camera/robotics projects) is a concrete candidate given
      the robot arm mention, not yet added (only D1 Mini32 and ESP32
      DevKit V1 exist today).
- [ ] A **settings entry point** that walks through board → pins →
      functions → buttons as one guided sequence, instead of requiring
      someone to already know to visit Pin Mapper then Controls
      separately
- [ ] A **device picker at session start** — e.g. a launch screen or a
      menu item on the main screen, picked once before connecting, not
      swapped mid-session. `ActiveProfile` already exists as the "which
      profile is active" concept (currently last-selected-wins from Pin
      Mapper/Controls); this becomes the deliberate "choose your
      device" moment instead of an implicit side effect of whichever
      screen you opened last
- [ ] Each device profile should carry **its own connection info**
      (AP SSID/password, or IP if on a shared network) alongside its
      board/pins/functions/buttons — so picking "Train" for today's
      session also means the app knows which network/IP to connect to
      for that specific board, without the user having to separately
      remember and re-enter it. This is where the earlier "editable
      per-device SSID/password" note connects directly to this work,
      rather than being a separate feature.
- [ ] **Final piece of the settings flow: map GameSir/gamepad buttons to
      user-defined functions too**, not just on-screen Controls buttons.
      There's already real infrastructure for this — `ControllerMapping.kt`
      + `ControllerMappingActivity.kt` is a full gamepad button/axis
      remapping system with presets, wired to real `KeyEvent`/
      `MotionEvent` handling. It's currently hardcoded to the old
      Freenove-specific `ButtonFunction` enum (LED_CYCLE, SERVO_RESET,
      etc), some of which no longer correspond to anything now that
      those were removed from the on-screen panel. Generalizing this
      means a gamepad button press needs to trigger the same
      `DeviceCommand.sendSet()` path an on-screen Controls tap already
      does, targeting whichever custom/built-in role the user assigns —
      not a fixed enum of car commands.
      Same split as elsewhere in this doc: **buttons** (on/off — face
      buttons, triggers, D-pad) are the easy half, since they map
      directly onto `DIGITAL_OUTPUT` roles that already work end to end.
      **Axes/sticks** (continuous values — steering, throttle) need the
      PWM/servo slider equivalent that's already flagged as unbuilt
      above, so gamepad-axis-to-custom-function mapping naturally
      follows behind that, not before it.
- [x] **Gamepad buttons mapped to user-defined functions** — added
      `ButtonFunction.CUSTOM_CONTROL`, which targets one of the
      currently-defined Controls buttons (by id) instead of a fixed car
      command. `ControllerMappingActivity` shows a secondary picker
      ("which button?") when this function is selected, listing the
      active profile's Controls buttons by label. Firing it calls the
      exact same `DeviceCommand.sendSet()` path the on-screen tap uses,
      so behavior (and the device log) is identical either way.
      **Axes/sticks still not done** — this covers buttons only, per
      the split noted above.
- [x] **PWM functions wired end-to-end in the app** — Pin Mapper's
      "Add Function" now has a real type picker (On/Off vs. PWM 0-255),
      not hardcoded to digital-only. Controls' "Add Button" adapts its
      behavior options based on the selected role's type: DIGITAL_OUTPUT
      still offers Toggle/Momentary, PWM_OUTPUT is always a slider (no
      choice to make, since that's the only sensible control for a
      continuous value). The Controls screen renders PWM roles as an
      actual `SeekBar` (0-255) instead of a toggle, sending
      `DeviceCommand.sendSetValue()` (`SETV <role> <value>`) only on
      `onStopTrackingTouch` — not on every drag tick, since that would
      flood the single-client TCP connection with dozens of commands a
      second for one gesture.
      **Scoped out for now:** PWM sliders don't render on the main
      driving screen's compact pill-button panel (no slider widget
      there) — they're fully usable from the Controls screen only.
      **Also noted:** a gamepad button mapped to a PWM/slider function
      logs a clear "can't drive it yet" message instead of doing
      something wrong — driving a continuous value needs axis mapping
      (a stick), not a button. **Axis mapping is now built — see below.**
      SERVO roles still aren't offered anywhere — firmware has no angle
      command yet (`SETV` is PWM duty only, confirmed via multimeter
      testing on the D1 Mini32 test rig).
- [x] **Confirmed and fixed PWM inversion, centralized the fix.** Direct
      visual LED testing (not just multimeter readings, which had their
      own probe-placement confusion along the way) confirmed requesting
      a high value produced OFF and a low value produced FULL BRIGHT —
      genuinely backwards from user expectation. Fixed by inverting the
      wire value inside `DeviceCommand.sendSetValue()` itself (255 -
      value) rather than at each call site, so every current and future
      caller (the Controls slider, gamepad axis mapping below) gets
      correct behavior automatically. If a future board/pin turns out
      NOT to need this compensation, this is the one place that would
      need to become conditional.
- [x] **Gamepad axis mapped to a custom PWM slider.** Mirrors the
      CUSTOM_CONTROL button pattern: new `AxisFunction.CUSTOM_PWM`,
      `AxisMapping.customButtonId` referencing a SLIDER-type Controls
      button, a secondary "which slider?" picker in Controller Mapping
      settings when CUSTOM_PWM is selected. Only `axisX` is used (single
      continuous input, e.g. a trigger or one stick axis) — `axisY` is
      ignored for this function. Rate-limited to the same 80ms interval
      already used for camera pan/tilt, and only sends when the
      normalized value actually changes (not every identical repeat
      event). **Known rough edge:** the -1..1-to-0..255 normalization
      assumes a stick-style axis range; a trigger axis (typically 0..1)
      will only span the upper half of the output range until tested
      and adjusted for real trigger hardware.
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

## Known issue to investigate

- **Suspected ESP32 crash/reboot during gamepad axis PWM testing** — app
  showed "Reconnecting..." after driving the axis-mapped PWM slider for
  a bit. Not root-caused yet, deferred for a dedicated session. Worth
  checking when picked back up:
  - Whether it's actually a crash (full reboot — check Serial Monitor
    for a boot banner reappearing) vs. just the same stale-TCP-client
    issue from earlier (v4's fix), vs. an unrelated WiFi hiccup.
  - Whether the axis rate limit (80ms / ~12.5 sends/sec) is actually
    being respected, or whether rapid repeated `ledcWrite()` calls
    combined with TCP/WiFi traffic in the same `loop()` iteration could
    be tripping the ESP32's task watchdog.
  - Whether this only happens during axis-driven PWM (continuous rapid
    changes) or also with the Controls slider's single on-release send
    (which would point to something in the PWM/LEDC path itself rather
    than to rate/frequency of commands).

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
