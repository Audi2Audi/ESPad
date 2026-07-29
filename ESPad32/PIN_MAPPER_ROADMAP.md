# Pin Mapper — Future Ideas & Roadmap

Tracking doc for where the mappable pin-config system could go, beyond
the current two hardcoded profiles (Train, RC Car) on one board (WeMos
D1 Mini32). Nothing here is committed to — it's a working list to pull
from when picking the next increment.

## The full-circle test (a concrete "definition of done")

This whole project started by pulling ESPad out of a Freenove-specific
codebase — stripping their hardcoded LED/horn/camera-servo/matrix-face
commands out in favor of a generic, user-defined pin-role system. The
fitting way to prove that generalization actually holds up: once the
web-based profile creator (see below) and servo support exist, use
**that** — the fully generic tooling, not any Freenove-specific code —
to build a profile that recreates the original Freenove 4WD car's
core R/C functionality from scratch.

**What that test would realistically cover, and what it wouldn't:**
- ✅ **In scope, achievable with the generic framework:** drive motors
  (direction + PWM speed — already works), pan/tilt camera servos
  (needs real servo angle support first — see below, currently only
  PWM duty exists, not `Servo.attach()`/angle control), a headlight
  or indicator LED (already works), a horn/buzzer (already works via
  DIGITAL_OUTPUT or PWM_OUTPUT).
- ❌ **Likely permanently out of scope, and worth being upfront about
  that now rather than treating it as a gap to eventually fill:** the
  WS2812 addressable RGB LED strip (a genuinely different protocol
  from simple digital/PWM — would need its own dedicated role type and
  firmware library, not just a pin assignment), the 8x8 dot-matrix
  face display (specialized addressable hardware, not a simple pin
  role at all), camera video streaming (a real subsystem tied to the
  ESP32-CAM module specifically, unrelated to the pin-role framework),
  and the autonomous light-follow/line-track sensor modes (these need
  actual programmable *behavior* — "if sensor reads X, drive motor Y" —
  not just declarative I/O mapping, which is a fundamentally different
  kind of feature than anything this framework does today).

  The realistic, honest version of "full circle" is recreating the
  car's **manual driving experience** end to end through generic
  tools — not literally 100% of the original firmware's feature set.

## Status: done so far

- [x] **Guided Device Setup wizard** — board → functions → pins →
      buttons as one continuous flow, instead of needing to already
      know to visit Pin Mapper then Controls separately. New
      `DeviceWizardActivity`, reachable via a "🧭 Guided Setup
      (recommended)" button at the top of Pin Mapper's existing "+ New
      Device" dialog (the quick name/board form and paste-import stay
      available right below it — this adds a path, doesn't remove the
      others).
      Deliberately reuses every existing storage/validation class
      rather than reimplementing anything — `CustomProfileStorage`,
      `CustomRoleStorage`, `PinConfigStorage`, `ControlButtonStorage`,
      `PinValidation` — just walks through them in one sequence:
      1. Name the device, pick its board
      2. Add functions one at a time (name, type: On/Off/PWM/Analog
         In) — **the pin is assigned in the same step**, not
         separately afterward like Pin Mapper does normally, since
         fewer round-trips is the whole point of this flow. Pin
         choices are filtered live through the same
         `PinValidation.canAssign()` (and ADC1-only-for-analog)
         logic Pin Mapper's own dropdown uses, and already-used pins
         within the session are excluded automatically.
      3. For each function just added, offers to create a matching
         Controls button (Toggle/Momentary for digital, auto-slider
         for PWM, skipped entirely for Analog In — a reading, not
         something to control) — skippable per-function, not
         all-or-nothing.
      4. Summary screen, with a "Use \\"X\\" Now" button that sets it as
         the active profile and jumps straight to the main driving
         screen.
      Also added `PinMapperActivity.onResume()` (didn't exist before) to
      refresh its device tabs — needed now that a device can be created
      from a separate Activity (the wizard) while Pin Mapper sits
      underneath on the back stack.


- [x] **Per-device connection IP.** `DeviceProfile`/`CustomProfile` now
      carry an optional `connectionIp` — set via a new "Connection IP"
      option in Pin Mapper's device-management dialog (long-press a
      device tab). Deliberately just an IP, not SSID/password — joining
      the right WiFi network is an OS-level phone setting outside the
      app's control; once the phone's already on the right network,
      the IP is the only thing worth remembering per device.
- [x] **A deliberate device picker at session start.** On launch, if
      more than one profile exists, a dialog asks "Which device are you
      using?" before connecting — picking one sets `ActiveProfile` and,
      if that device has a saved connection IP, switches to it
      automatically. Skips the dialog entirely when there's nothing to
      choose (0 or 1 profiles) — a picker with a single option would
      just be annoying, not useful. Dismissing without picking just
      connects with whatever was already set, rather than blocking.


- [x] **On-screen virtual gamepad buttons** — the app can now be fully
      used without a physical gamepad, joysticks (already existed) plus
      buttons (new). Deliberately plugs into the *existing* gamepad
      mapping system rather than building a parallel one: tapping a
      virtual button calls the exact same dispatch path a real
      controller's `KeyEvent` would (`handleGamepadButtonEvent()`,
      extracted from what was separate `onKeyDown`/`onKeyUp` logic), so
      whatever's mapped via Controller Mapping — `CUSTOM_CONTROL` to a
      Controls button, or a legacy function — works identically whether
      triggered by a real gamepad or an on-screen tap.
      - Toggle in Settings ("Buttons," next to the existing "Joysticks"
        toggle), same persistence pattern (`SharedPreferences` boolean).
        Required extending `SettingsDialogFragment`'s `onSave` callback
        signature (threaded through `newInstance` and its consumption
        in `MainActivity.showSettings()`).
      - Renders all 12 physical buttons `ControllerMapping.ALL_BUTTONS`
        already knows about (A/B/X/Y, L1/R1/L2/R2, stick clicks, Start/
        Select) as a compact 4-per-row grid, centered between the two
        joysticks, above the control panel.
      - **Labels show what's actually mapped**, not just raw button
        names — a `CUSTOM_CONTROL` button resolves to the real Controls
        button's label (e.g. shows "LED" under "A"), so someone using
        this without ever touching a physical gamepad can tell what
        each button does, not just see cryptic "A"/"B"/"X" labels.
      - Real press/release via `OnTouchListener` (not tap), matching the
        momentary-button fix from the same session — consistent
        behavior whether it's a MOMENTARY-mapped custom function or the
        legacy horn.
      **No D-pad** — `ALL_BUTTONS` doesn't include discrete D-pad
      entries (handled as axes/hat input on real controllers, not
      separate `KeyEvent`s in this system), so the virtual overlay
      matches exactly what's already mappable — not a scope expansion
      beyond what Controller Mapping already supports.


- [x] **Momentary buttons now have real press/release**, not just a
      tap. Fixed in all three places momentary control lives: the
      Controls screen's own button row, the main screen's live panel,
      and gamepad-mapped `CUSTOM_CONTROL` buttons. Each now uses actual
      touch-down/touch-up handling (`OnTouchListener` for on-screen,
      `onKeyDown`/`onKeyUp` for gamepad) sending the real ON/OFF state
      rather than always sending ON. **Found the same bug existed in
      the gamepad path too** while fixing this — `onKeyUp` only ever
      special-cased the old hardcoded horn function, never forwarded
      release events for the generic `CUSTOM_CONTROL` path at all.
      Fixed carefully so legacy `ButtonFunction` cases (LED_CYCLE,
      PAN_LEFT, etc.) don't double-fire on release — only the horn and
      `CUSTOM_CONTROL` cases get release events at all; everything else
      still only reacts to press, unchanged.
- [x] **Button reordering** — simple ▲▼ controls per row, not full
      drag-and-drop. The button list is a plain rendered `LinearLayout`,
      not a `RecyclerView`, so true drag gestures would need a bigger
      migration than this deserves at the current scale (a handful of
      buttons per profile). Swapping list position + re-saving gets the
      actual value people want with much less risk. Works for both
      toggle/momentary rows and slider rows. Reordering here
      automatically reflects on the main screen's live panel too, since
      that just reads whatever order is persisted.
- [x] **Two more `BoardDef` entries: ESP32-S3 DevKitC-1, ESP32-C3
      DevKitM-1.** Same "verify against your specific board" caveat as
      the existing DevKit V1/ESP32-CAM entries — S3/C3 dev boards vary
      between vendors/variants more than the original ESP32 does.
      **Real gap discovered while adding these, not yet fixed:**
      `PinValidation.ADC1_PINS` is a single hardcoded set
      (`32/33/34/35/36/39`) — correct for the *original* ESP32, but
      completely wrong for S3 (real ADC1 pins: roughly GPIO1-10) and C3
      (real ADC1 pins: GPIO0-4). Right now, creating an `ANALOG_INPUT`
      role on either new board would incorrectly reject their actual
      valid ADC1 pins. Needs `PinValidation.canAssign()` to become
      board-aware (an ADC1 set per `BoardDef`, not one global constant)
      before Analog Input is trustworthy on anything but the original
      ESP32-family boards. Flagging clearly rather than silently
      shipping a board where that specific feature quietly misbehaves.


- [x] **Found and removed a SECOND, independent copy of the dead
      Presets UI** — living in `SettingsDialogFragment.kt`'s own
      Controller tab, entirely separate from the copy already removed
      from `ControllerMappingActivity.kt`. Same root cause (canned
      presets sending `CMD_MOTOR`/`CMD_CAMERA`), but a genuinely
      different file/screen — missed in the first pass because it
      wasn't the same code, just the same dead functionality
      hand-duplicated in two places. Kept the legitimate "Open Full
      Controller Mapping" navigation button in Settings' Controller
      tab, just removed the redundant inline Presets list above it.
      **Worth remembering as a pattern, not just a one-off:** this is
      now the *second* time a Freenove-era feature turned out to be
      duplicated between a dedicated screen and an embedded copy
      inside Settings (the OTA flow was the first — `OtaActivity` vs.
      a second copy in Settings' OTA tab, found much earlier). Checked
      this time whether "Pin Ref" or any of the other removed CMD_*
      commands (`CMD_LED_MOD`/`CMD_MATRIX_MOD`/`CMD_BUZZER`/
      `CMD_CAR_MODE`/`CMD_POWER`) had a similar hidden twin in
      `SettingsDialogFragment.kt` — confirmed clean, no other
      duplicates found this time.


- [x] **Removed the Controller Mapping "Presets" tab — same category as
      the Pin Ref tab, confirmed before touching anything.** All three
      canned presets (Default, Trigger Drive, D-Pad Drive) exclusively
      used `AxisFunction.DRIVE`/`TRIGGER_DRIVE`/`STEER_ONLY`/`PAN_TILT`,
      which dispatch to `CMD_MOTOR`/`CMD_CAMERA` — the same Freenove-
      specific command family as everything else already removed this
      session (LED, buzzer, car mode, power). Applying any of them
      would silently do nothing against this firmware. Verified
      `ControllerMapping.init()` doesn't depend on the Presets/
      `ControllerProfile` system before removing the UI — per-button/
      per-axis mappings (`CUSTOM_CONTROL`/`CUSTOM_PWM`, the ones that
      actually work) persist independently via their own JSON storage,
      so nothing about actual saved mappings was at risk.
      Removed the tab, renumbered the remaining three (Buttons/Axes/
      Advanced now 0/1/2), and updated every `showTab()` call site
      accordingly.
      **Left as-is, noted as a smaller follow-up:** the underlying
      `ControllerProfile`/`PRESETS`/`applyPreset` data layer in
      `ControllerMapping.kt` is now unreachable dead code, not yet
      fully removed — the UI was the actively misleading part users
      interact with; the data-layer cleanup is lower-value and lower-
      risk to leave for later.
- [x] **Settings dialog now genuinely fills the screen**, matching
      Controller Mapping's presentation (previously ~95%/80% width/
      height, with visible dim area around it used for tap-to-dismiss).
      Added a matching header (icon + title + explicit "✕ Close"
      button) — this became functionally necessary, not just cosmetic:
      filling the screen removes the "outside the dialog" area
      entirely, so there was no longer any visible surface left to tap
      for dismissal without it. New `FullscreenDialogTheme` strips the
      default dialog theme's floating-window chrome (dim border,
      rounded corners) that `setLayout(MATCH_PARENT, MATCH_PARENT)`
      alone doesn't remove, applied via `setStyle()` in a new
      `onCreate()` override.


- [x] **Removed the Settings dialog's Save/Close buttons — they were
      already functionally redundant with each other and with simply
      closing the dialog.** Investigation before touching anything:
      "Close" was actually wired as Cancel-then-dismiss, and
      `onDismiss()` already had its own inline fallback that
      auto-persisted everything (theme, joystick toggle, controller
      sensitivity sliders) if the explicit Save button hadn't been
      tapped first. Since `onDismiss()` fires on every close path
      (button tap, back gesture, or tapping outside) uniformly, Save
      and Close were doing the exact same thing via two different code
      paths — there was no actual "discard changes" behavior working
      at all. Consolidated the duplicated save logic into one shared
      `persistSettings()` method (previously existed as two nearly-
      identical inline copies) and removed both buttons entirely —
      closing the dialog any way still saves everything, same as
      before, just without the redundant explicit step.
      Also de-emphasized "Forget" (clears saved WiFi credentials) from
      a boxed button matching Connect's visual weight to a plain
      text-style action — it's a rarer, semi-destructive action and
      shouldn't visually compete with Connect, the thing people
      actually tap most of the time. Flattened the IP/SSID/Password
      fields from solid filled boxes to a thin underline style
      (`edittext_underline.xml`) — a first pass at the "flatten the
      appearance" ask, scoped to what was actually visible in the
      screenshot; the same treatment could extend to the Theme/
      Controller/OTA tabs later if wanted.


- [x] **Removed the "Pin Ref" tab from Controller Mapping entirely —
      not made conditional, actually removed.** This was a static
      reference table describing the *original* Freenove car's
      specific ancillary hardware (a PCA9685 servo/motor PWM driver, a
      VK16K33 dual 8×8 LED matrix, an OV2640 camera module's exact
      pinout, a PCF8574-based line tracker) — hardcoded GPIO/I²C-address
      documentation that doesn't correspond to anything in this app's
      generic pin-role framework at all. Unlike Photo/Record/Flip
      (genuinely conditional on `BoardDef.supportsCamera`), there was
      no real board or profile this content could ever apply to — it
      wasn't a "hide unless relevant" case, it was pure dead
      documentation for hardware this app no longer targets. Some of
      it wasn't even accurate anymore either (listed AP password and
      camera port didn't match this project's actual values). Removed
      the tab, its dispatch case, and the `pinRow()` helper exclusively
      used by it; kept `addSectionHeader()`/`addDivider()` since the
      Advanced tab still uses those.
      **Also found and removed:** the actual compiled Freenove firmware
      binary (`06_3_Multi_Functional_Car.ino.bin`, ~1.1MB) was still
      physically sitting in `app/src/main/assets/` — dead weight
      bloating the APK, unused since the code was already renamed to
      look for `espad_default_firmware.bin` instead. The earlier
      filename fix only updated the code references; the actual old
      file itself had been missed.


- [x] **Camera support handled as a board property, not a pin role.**
      A camera can't be expressed as "assign this GPIO to a function"
      the way everything else in this framework works — it's a
      dedicated peripheral hardwired to a fixed set of ~18 pins
      determined entirely by the physical board (an AI-Thinker
      ESP32-CAM, for instance), not something a user picks a pin for.
      - New `BoardDef.supportsCamera` flag, plus a new
        `Boards.ESP32_CAM_AI_THINKER` board definition with its camera
        interface pins marked `RESERVED` (same status already used for
        the DevKit V1's onboard flash pins) — they simply don't show as
        assignable in Pin Mapper, same mechanism, new use.
      - `MainActivity`'s Photo/Record buttons (both the control-panel
        drawer versions and the overlay-stack versions — there were two
        separate copies) **and Camera Flip** now hide entirely unless
        the *active profile's board* actually supports a camera,
        checked on load and resume. Previously always shown regardless
        of device — a leftover assumption from when this app only ever
        talked to the Freenove car. (Camera Flip was missed in the
        first pass — it survived the earlier Freenove-cleanup work
        since it's local-only with no device command involved, but it's
        still meaningless without an actual camera feed to flip.)
      **Firmware side deliberately deferred, per explicit agreement:**
      this only addresses the app-side "should this UI even appear"
      question. Actual camera driver init (`esp_camera_init()`) and an
      MJPEG streaming server are real, substantial ESP32-CAM-specific
      firmware work — nothing like the lightweight pin-config test
      sketch, and doesn't reuse any of `SET`/`SETV`/`GET`. Revisit as
      its own effort later.


- [x] **Export/import a full device profile.** Long-press any device
      tab → Export shares the profile as JSON via the system share
      sheet (any app — Drive, email, Bluetooth, Slack, "copy to
      clipboard," etc) — deliberately not a file-picker/SAF flow,
      since a small JSON blob doesn't need Android's storage
      permission complexity. "+ New Device" now also offers pasting
      an exported profile's text as an alternative to filling in the
      name/board form, importing it as a brand-new profile with a
      fresh, guaranteed-unique key (never overwrites an existing one,
      even re-importing onto the same device that exported it).
      Exports functions (with any label already flattened in — no
      separate override layer needed for a portable snapshot), pin
      assignments for the profile's board, and Controls buttons.
      **Deliberately NOT included:** gamepad mappings. `ControllerMapping`
      is one global list for the whole app (which physical GameSir
      button does what), not scoped per device profile at all — there's
      no clean "this profile's gamepad mappings" to export today. A
      real limitation, not an oversight.

## Future idea, not started

- **Two different hosting models for the "PC-based profile creator"
  idea — worth comparing rather than assuming one is obviously right:**

  **Option A — self-hosted on the ESP32 itself (favored as the nearer-
  term option).** Once the default sketch is flashed, visiting the
  device's own IP in a browser shows a page for creating/editing
  functions and pin mappings, directly on the device — no separate
  export/import round-trip needed at all, since the page could just
  read/write the device's own stored config in real time. This reuses
  infrastructure already built and proven: the OTA HTTP server (`ota.h`)
  already established the pattern of a raw `WiFiServer`-based request
  handler on this firmware, which a small config-editing page could
  extend (serve a lightweight HTML/vanilla-JS page, plus simple
  endpoints to read/write the same JSON pin-config format the app
  already sends over the TCP command channel).
  - *Pros:* zero external hosting/maintenance, works fully offline on
    the device's own local AP (matches the existing "AP always up"
    philosophy), always talking to the actual physical device rather
    than a disconnected builder that syncs later, no new
    infrastructure beyond what this firmware already does.
  - *Cons:* ESP32 has limited RAM/flash for a rich UI — needs to stay
    genuinely lightweight, same hand-rolled-HTTP constraints the OTA
    server already lives with; only reachable from a PC on the same
    network as that specific device (its AP, or the same LAN in STA
    mode) — no "plan a profile before you own a device" workflow.

  **Option B — a separately-hosted web service.** Covers two things
  Option A can't: (1) flashing the default firmware to a brand-new
  ESP32 from a PC with no Arduino IDE at all (browser-based, similar to
  esphome/esp-web-tools style in-browser flashing via WebSerial), and
  (2) building/editing a profile before you even own or have flashed a
  device. Would use the same JSON export/import format already built,
  so it could interoperate with both the app and Option A without
  needing either to change.
  - *Pros:* works with zero hardware in hand, one central place
    regardless of which device you're near.
  - *Cons:* real hosting/maintenance overhead, needs internet access
    (unlike the device-local option), and duplicates a chunk of what
    Option A would already do for the profile-editing half.

  These aren't mutually exclusive — Option A is the more natural next
  step given what already exists; Option B's *flashing* half (getting
  a brand-new blank ESP32 running the default sketch in the first
  place) is the one piece Option A can't cover, since a device with no
  firmware yet can't host anything.

- [x] **Firmware OTA HTTP server built, matching the app's existing
      protocol.** The app-side OTA mechanism (`OtaActivity.kt`, plus a
      duplicate flow in `SettingsDialogFragment.kt`'s OTA tab) was
      already real and complete — it just had nothing to talk to,
      since the test firmware had no HTTP server at all. Now it does:
      - New `ota.h`: raw `WiFiServer` on port 8080 (alongside the
        existing command server on 4000 and UDP discovery on 4210).
        `GET /ota/status` responds 200 with a simple body. `POST
        /ota/upload` decodes HTTP chunked transfer-encoding by hand
        (the app sends `Transfer-Encoding: chunked` via
        `HttpURLConnection.setChunkedStreamingMode()`, not a fixed
        `Content-Length` — deliberately NOT using ESP32's `WebServer`
        library here, since its upload handling assumes multipart
        form-data, not a raw octet-stream body) and writes bytes via
        ESP32's built-in `Update` library as they arrive.
      - Added `CMD_VERSION#` to the TCP command channel too — the OTA
        screen's "Firmware v___" display and post-update verification
        depend on it, and it was previously unimplemented on the
        firmware side even though the app already had a (broken, now
        fixed) code path expecting it.
      - **Renamed the hardcoded default-firmware filename** the app
        expects for its "Flash Default" button, from
        `06_3_Multi_Functional_Car.ino.bin` (the original Freenove
        sketch's literal compiled output name) to
        `espad_default_firmware.bin`, in both `OtaActivity.kt` and the
        duplicate flow in `SettingsDialogFragment.kt`, plus the
        `assets/README.txt` instructions. No functional Freenove
        references remain anywhere in the app or firmware — only a
        few historical code comments explaining *why* earlier
        Freenove-specific features were removed, which are useful
        engineering history rather than functional identifiers.
      **Not yet done:** the two duplicate OTA UIs (`OtaActivity` full
      screen vs. the Settings dialog's OTA tab) both work now, but
      having the exact same upload logic maintained twice in two
      places is worth consolidating into one shared implementation at
      some point, rather than something that needs fixing right now.


- [x] **Generic analog input (battery voltage, or any other live sensor
      reading) — replaces the dead Freenove-specific CMD_POWER
      mechanism.** Previously the app polled `CMD_POWER#` every 15s
      unconditionally, assuming a fixed onboard voltage-divider circuit
      that only the real Freenove car board has — meaningless for any
      custom device, and our test firmware never even understood the
      command. Now battery/sensor voltage is just another pin role
      type, fitting the same framework as everything else:
      - New `RoleType.ANALOG_INPUT`, selectable in Pin Mapper's "Add
        Function" type picker alongside On/Off and PWM.
      - **ADC1-only constraint, for a real hardware reason:** the ESP32
        has two ADC units: ADC2 pins share circuitry with the WiFi
        radio and give unreliable/blocked readings whenever WiFi is
        active — which for this app is always (the AP never turns
        off). `PinValidation` now restricts `ANALOG_INPUT` to the 6
        ADC1 pins (32/33/34/35/36/39) — `canAssign()` had to become
        role-*type*-aware (not just role-key-string-aware) to support
        this, which every call site now reflects.
      - Firmware: new `GET <role>` command, using
        `analogReadMilliVolts()` (ESP32's factory-calibrated ADC read)
        rather than a raw 0-4095 value scaled by hand. Reports the
        actual voltage AT THE PIN — if sensing a battery above the
        ESP32's ADC range (e.g. a 2S+ LiPo), the user needs their own
        voltage divider and their own math to convert pin voltage back
        to real battery voltage; the firmware has no concept of a
        divider ratio.
      - App: `MainActivity` now polls generically — if the *active*
        profile has an `ANALOG_INPUT` role assigned to a real pin, it
        polls that (via `DeviceCommand.sendGet()`) every 15s and shows
        `<function label>: <voltage>V` in the old battery indicator's
        spot. If no such role exists for the active profile, the
        indicator hides entirely instead of showing a permanent,
        meaningless placeholder.
      **Known gap:** firmware doesn't independently re-validate the
      ADC1-only restriction — the JSON config payload has no field for
      role *type* yet, only `{gpio, role}`, so firmware can't tell
      "this is meant to be analog" from "this is meant to be digital."
      The app is currently the only thing enforcing ADC1-only; reading
      an ADC2 pin via `GET` would still return a number, just an
      unreliable one while WiFi is active.

- [x] **GPIO dropdown per function row**, as a faster alternative to
      tap-role-then-tap-pin-on-diagram (which still works too — this
      doesn't replace it, just adds a quicker path). Reuses the exact
      same validation the board-diagram tap flow uses
      (`PinValidation.canAssign`), so the dropdown can never offer a
      pin that tapping would reject — same risky-pin `⚠` marking for
      strapping/UART pins, same "bump the other role holding this pin"
      behavior and logging. Extracted into a shared
      `assignGpioToRole()` so the board-tap flow and dropdown can never
      quietly drift into different behavior over time.

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
- [x] Add more ESP32-family `BoardDef` entries, roughly in likely-usefulness
      order:
      - [x] ESP32 DevKit V1 (generic 30-pin/38-pin dev board — probably
            the single most common alternative to the D1 Mini32)
      - [x] ESP32-S3 DevKit (newer, more GPIOs, native USB) — added, see
            the ADC1-awareness gap noted above
      - [x] ESP32-C3 (RISC-V, fewer pins, smaller/cheaper) — added, same
            ADC1-awareness gap
      - ESP8266 (NodeMCU etc.) is a different chip family with its own
        GPIO numbering/restrictions and no direct code reuse from the
        ESP32 validation rules — worth a note but treat as its own
        research item, not a quick add, if it ever comes up

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

- [x] **Live device sync** — done long ago in practice, this entry just
      never got marked. Buttons/sliders genuinely send `SET`/`SETV`/
      `GET` over the real TCP connection; confirmed working across PWM,
      analog input, gamepad axis mapping, all of it.
- [x] **Slider control for PWM_OUTPUT** — done (`ControlType.SLIDER`).
      **SERVO roles still have no slider or firmware support at all** —
      angle control (`Servo.attach()`/`.write()`) is a different thing
      from PWM duty and hasn't been built; don't conflate the two.
- [x] Momentary press/release — done, see the entry above.
- [x] Reordering buttons — done, see the entry above (simple ▲▼, not
      full drag-and-drop).

## Medium-term: user-defined device profiles

**Done:** the actual "New Device" flow (name → board → functions → pins
→ buttons, persisted as a real profile alongside Train/RC Car, not a
compile-time constant) — see the `[x]` entry above. Train/RC Car
themselves are no longer special either — see the de-specialization
entry above.

**Still open:**
- [x] A **single guided walkthrough** — done, see the entry above
      (`DeviceWizardActivity`). The web-hosted idea below may still be
      worth building eventually too — this doesn't replace that, just
      delivers the phone-based version now while the ESP32 wasn't
      reachable for hardware testing anyway.
- [x] A **device picker at session start** — done, see the entry above.
- [x] Each device profile should carry **its own connection info** —
      done for IP (see the entry above). SSID/password deliberately
      not stored — that's an OS-level WiFi setting, not something this
      app manages.
- [x] **Gamepad buttons mapped to user-defined functions** — added
      `ButtonFunction.CUSTOM_CONTROL`, which targets one of the
      currently-defined Controls buttons (by id) instead of a fixed car
      command. `ControllerMappingActivity` shows a secondary picker
      ("which button?") when this function is selected, listing the
      active profile's Controls buttons by label. Firing it calls the
      exact same `DeviceCommand.sendSet()` path the on-screen tap uses,
      so behavior (and the device log) is identical either way.
      **Axes/sticks were "not done" when this was written — since
      resolved:** see the `CUSTOM_PWM` axis-mapping entry elsewhere in
      this doc (gamepad axis → a Controls PWM slider). Buttons and
      single-axis PWM both work now; only a true differential-drive/
      dual-servo axis mapping remains undone, since neither has a
      generic firmware equivalent yet.
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
- [x] **"New Device" flow — first version.** Solves the original
      motivating problem directly: previously a lamp had to live inside
      Train's skeleton (four required motor/audio roles it didn't need)
      because `DeviceProfile` was only ever `Profiles.TRAIN`/`RC_CAR`,
      two compile-time constants. Now Pin Mapper has a "+" tab that
      opens a name + board picker, creating a real user profile with
      **zero built-in roles** — nothing mandatory, nothing to fill in
      before Validate & Save will work.
      This turned out to be mostly plumbing, not a rewrite: pin
      assignments (`PinConfigStorage`), custom functions
      (`CustomRoleStorage`), buttons (`ControlButtonStorage`), and
      gamepad mappings are all already keyed generically by profile
      string — a user-created profile "just works" with all of them
      the moment it exists. The only genuinely new pieces were
      `CustomProfileStorage` (persists name/board per user profile),
      `ProfileResolver` (merges built-in + custom for anywhere that
      just reads the list), and making the profile-tab UI in both Pin
      Mapper and Controls dynamic instead of two hardcoded tabs.
      **What's still missing from the fuller vision:**
      - No settings-screen entry point yet — creation only happens via
        Pin Mapper's "+" tab, not a guided board→pins→functions→buttons
        walkthrough in one place.
      - Deleting a device profile removes the profile entry itself, but
        NOT its stored functions/pin assignments/buttons — those remain
        under the same key and would reappear if a profile with the
        same generated key were created again. Not dangerous, just
        untidy; worth cleaning up properly later.
      - Still per-session-switch only (as clarified earlier) — no live
        device-to-device switching, and no per-profile connection info
        (SSID/IP) yet, so switching to a different device profile
        doesn't automatically point the app at that device's network.
- [x] **Train and RC Car are no longer a special compiled-in tier.**
      Previously they were hardcoded `DeviceProfile` objects with fixed
      `roles`/`defaults`, and `Validate & Save` required all of Train's
      four motor/audio roles to be assigned even for someone who just
      wanted a lamp. Now they're seeded into `CustomProfileStorage`/
      `CustomRoleStorage` exactly once on first run (see
      `seedBuiltInsIfNeeded`) — after that they're ordinary stored
      profiles indistinguishable from anything created through "New
      Device": **deletable** (long-press the tab), and their individual
      functions are now **deletable and renamable** the same way any
      custom role already was.
      **Migration was additive and non-destructive by design** — it
      only adds Train/RC Car's original role keys to whatever's already
      in `CustomRoleStorage` for those profile keys (so an already-added
      custom role like "LED" is untouched), and existing
      `PinConfigStorage` pin assignments needed no migration at all,
      since they're keyed by role-key strings regardless of where the
      role definition conceptually lives.
      **Seeding runs exactly once, guarded by a persisted flag — not by
      "does Train exist."** That distinction matters: checking existence
      instead would silently resurrect Train the moment someone deleted
      it, which would be worse than not offering deletion at all.
      Both `PinMapperActivity` and `ControlsActivity` also now respect
      whichever profile was last active on load (previously both always
      forced back to Train on open, regardless of `ActiveProfile`), and
      handle "the active profile got deleted" / "zero profiles exist"
      gracefully by falling back to whatever's actually still there.
- [ ] Custom/unlisted boards: let someone define a board that isn't in
      `Boards.ALL` — name it, mark which physical pins exist, flag
      restrictions manually. Bigger lift (needs its own validation UI
      and persisted custom-board definitions)

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

This section was written before almost any of the actual generic
firmware work existed — both items below are now resolved in spirit,
kept here only so the historical framing isn't lost:

- [x] "Firmware needs a generic capability system, not fixed per-profile
      roles" — this is now just how it works: the JSON payload's
      `{role, gpio}` pairs are already fully generic per-profile, not
      hardcoded driver logic. `SET`/`SETV`/`GET` all operate purely on
      whatever role/pin was assigned at runtime.
- [x] "Extend SET to run over TCP" — done ages ago; TCP (port 4000) has
      been the live command transport this whole time, proven far
      beyond the original single-command scope (SETV, GET, CMD_WIFI_*,
      CMD_VERSION, OTA on a separate port).

## Known issue to investigate

- **Suspected ESP32 crash/reboot during gamepad axis PWM testing** — app
  showed "Reconnecting..." after driving the axis-mapped PWM slider for
  a bit. Confirmed by the user to occur specifically during rapid axis
  movement (not observed with the Controls slider's single on-release
  send) — that difference is a real diagnostic signal, not just an
  unconfirmed guess.

  **Leading hypothesis: Arduino `String` heap fragmentation.** Every
  `SETV` call in the firmware allocates several dynamic `String`
  objects (`role`, `valueStr`, substrings, the response text). Arduino's
  `String` class on ESP32 is a well-documented source of heap
  fragmentation under *frequent* allocate/free cycles — sustained rapid
  small allocations, not necessarily a huge total count, which matches
  a gamepad stick's behavior (many quick SETV sends) far better than
  the slider's single send.

  **When picked back up:**
  - Add `Serial.println(ESP.getFreeHeap())` at the top of `loop()` or
    inside `handleSetValueCommand` to directly observe whether free
    heap trends downward during sustained axis movement — would
    confirm or rule out fragmentation with actual data instead of more
    guessing.
  - If confirmed, the fix is rewriting `handleSetValueCommand` (and
    ideally the other command handlers) to avoid Arduino `String`
    allocation entirely — parse with C-string functions
    (`strtok`/`atoi`/fixed char buffers) instead of `String.substring()`.
  - Quick mitigation in the meantime, if needed before a proper rewrite:
    increase the axis rate-limit interval (currently 80ms) to reduce
    allocation frequency, though this only reduces the rate of
    fragmentation rather than fixing the underlying cause.
  - Still worth confirming it's actually a reboot (Serial Monitor
    showing the boot banner reappear) rather than the earlier
    stale-TCP-client issue or an unrelated WiFi hiccup, before assuming
    the heap theory is fully confirmed.

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
