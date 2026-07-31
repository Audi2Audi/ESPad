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

**CORRECTION, based on confirmed evidence, not the assumption this
section was originally written on:** the "✅ in scope, already works"
claim below for motors and servos turned out to be wrong for the
actual stock car. Verified directly from Freenove's own source
(`Freenove_4WD_Car_Kit_for_ESP32` on GitHub, their `fnk0053-docs`
tutorial): **all 4 wheel motors AND both pan/tilt servos are driven
through a PCA9685 — an I2C PWM controller board — not direct ESP32
GPIO pins at all.** Confirmed from their own setup code:
`PCA9685_Setup(); //Initialize PCA9685 to control motor`, with servos
on PCA9685 channels 0-7 and each wheel motor on its own channel pair.
This framework's `SET`/`SETV`/`SETA` only know how to drive something
wired directly to a GPIO — they have no way to talk to a PCA9685 at
all. That's not a missing profile or a missing pin assignment, it's a
**missing firmware capability** — this device has never had any I2C
peripheral support, same underlying gap as the LED matrix question
logged separately above, just blocking the part of the car that
actually matters most (driving and looking around), not an accessory.
**This means the "full circle" milestone is currently blocked on
PCA9685 I2C PWM support existing at all — servo angle support (already
built) doesn't help here, since it's built for GPIO-attached servos,
not ones behind an I2C PWM expander.**

**What IS genuinely direct-GPIO on the stock car, also confirmed from
the same source, and would work with the current framework as-is,
right now, with no new firmware:**
- **Buzzer/horn** — GPIO2, driven via the ESP32's own PWM
  (`PWM_OUTPUT` role — works today).
- **Battery voltage** — GPIO32, a plain ADC read (`ANALOG_INPUT` role
  — works today, and is already one of the confirmed ADC1 pins).

**What that test would realistically cover, and what it wouldn't (as
originally written, now corrected above for motors/servos):**
- ❌ **Motors and pan/tilt servos — NOT achievable without new
  firmware** (PCA9685 I2C PWM support doesn't exist yet — see the
  correction above). This is the actual core of "driving the car,"
  and it's a bigger, more fundamental blocker than previously scoped.
- ✅ A headlight or indicator LED, if wired to a spare direct GPIO
  (already works) — the buzzer/horn already works via a real,
  confirmed direct-GPIO pin on the stock board (GPIO2, see above).
- ❌ **Likely permanently out of scope, and worth being upfront about
  that now rather than treating it as a gap to eventually fill:** the
  8x8 dot-matrix face display — confirmed **WS2812** (Freenove's own
  sketch comments: *"Use WS2812"*), correcting an earlier guess
  (VK16K33) logged for this in the LED matrix option-space entry
  further down — a genuinely different protocol from simple digital/
  PWM, needing its own dedicated role type and firmware library, not
  just a pin assignment (see that entry for the full option-space).
  There may ALSO be a separate WS2812 "Colorful Light" underglow strip
  distinct from the face matrix (the product listing lists them as
  separate features) — not independently confirmed which is which.
  Also out of scope: camera video streaming (a real subsystem tied to
  the ESP32-CAM/WROVER-CAM module specifically, unrelated to the
  pin-role framework, already handled separately by the app's existing
  camera-streaming code), and the autonomous light-follow/line-track
  sensor modes (these need actual programmable *behavior* — "if sensor
  reads X, drive motor Y" — not just declarative I/O mapping, a
  fundamentally different kind of feature than anything this framework
  does today).

  The realistic, honest version of "full circle" is recreating the
  car's **manual driving experience** end to end through generic
  tools — not literally 100% of the original firmware's feature set.
  **As of this correction, that manual driving experience is blocked
  on PCA9685 I2C PWM support, which doesn't exist in this firmware at
  all yet — a real, substantial piece of new work, not a small gap.**

## Status: done so far

- [x] **Customizable on-screen layout — hold and drag to reposition
      joysticks and the gamepad button cluster anywhere on screen**
      (app-only, no firmware involved). Persisted per-widget in
      `SharedPreferences`, safety-netted with a "Reset Layout to
      Defaults" button in Settings' Theme tab.
      - **The real design problem this needed solving**: a joystick
        can't tolerate ANY added lag before knob-movement starts —
        driving needs immediate response on touch-down. Solved by
        layering a SEPARATE, concurrent long-press-without-movement
        check on top of the existing immediate knob-drag behavior,
        rather than delaying knob-drag to first check "is this a long
        press." Real steering involves immediate, intentional
        movement and essentially never triggers the "stayed still for
        500ms" condition by accident — only a deliberate hold does.
        Normal joystick use is completely unaffected.
      - **Joysticks**: the long-press detection lives directly inside
        `JoystickView`'s own touch handling (it already owns its full
        touch event stream, no child-view conflicts to arbitrate).
      - **Gamepad buttons — refined after initial feedback.** First
        version relocated the WHOLE cluster together on any long-press.
        Per direct follow-up feedback, split this: **A/B/X/Y (the
        diamond) still move together as one group** — now via their own
        `diamondGroup` sub-`FrameLayout` (positioned within the outer
        `clusterFrame` at the same default spot as before — visually
        unchanged), sharing that sub-container as a common drag target.
        **L1/L2/R1/R2 and Select/L3/R3/Start are each now independently
        draggable** — same long-press-without-movement pattern, but
        `dragTarget` is the button itself rather than any shared
        container. Utility row buttons (Select/L3/R3/Start) previously
        had no drag capability at all (plain press/release only) — now
        get the identical treatment as everything else. 9 separate
        persisted offsets now (diamond + 4 shoulders + 4 utility) rather
        than 1. Default visual layout is completely unchanged — same
        positions, same geometry — only what moves together vs.
        independently changed. `resetCustomLayout()` updated to
        re-render the whole cluster after clearing prefs rather than
        manually tracking and zeroing 9 individual view references.
        **Real correctness fix carried through to every one of these**:
        a button's press fires immediately on `ACTION_DOWN` (unchanged,
        no added lag); entering relocate mode explicitly RELEASES that
        press right away, rather than leaving whatever it's mapped to
        stuck "on" for the entire drag.
      - **A real coordinate-space bug caught and fixed before
        shipping**: the relocate-drag delta must use raw (screen-
        absolute) coordinates, not a view's own local coordinates —
        local coordinates are relative to the view's OWN position,
        which is exactly what's changing during a relocate-drag,
        creating a feedback loop where moving the view shifts what
        "local" means for the next event. The gamepad button
        implementation used raw coordinates correctly from the start;
        `JoystickView` initially didn't and was caught and fixed
        before this was committed.
      - Offsets are clamped so a widget's translated bounds can't be
        dragged fully off-screen — doesn't prevent overlapping other
        elements (that's the point of customizing layout), just
        prevents losing a widget somewhere ungrabbable.
      - **Real bug found and fixed via a screenshot, after the initial
        diamond-vs-independent split shipped**: gamepad buttons could
        only be dragged within a small rectangle, not the whole
        screen. `clampOffset()` was using the button's IMMEDIATE
        parent's width/height as the screen bounds — correct for
        joysticks (direct children of the actual root layout, so their
        "immediate parent" already IS the full screen), but wrong for
        gamepad buttons nested inside small sub-containers
        (`clusterFrame`/`diamondGroup`/`utilityRow`) sized just to fit
        their own children. Fixed by walking all the way up to the
        true root, accumulating each intermediate ancestor's own
        layout position, rather than only checking the immediate
        parent — traced through the joystick case specifically to
        confirm this produces the identical result there (no
        intermediate ancestors to walk through, so the fix is a no-op
        for them), not just assumed no regression.



- [x] **Device name auto-defaults to the profile name — a novice user
      never needs to find the manual name field at all** (v18
      firmware, delivered as a zip; app-side pushed as `b67c972`).
      Direct follow-up to the previous device-name feature (v17): that
      one required actively visiting Settings/the web UI to set a
      name at all, which a novice user realistically wouldn't do.
      - **Required a fix to what v17 shipped, not just an addition**:
        `CMD_GET_NAME` and the web UI's `GET /api/name` both returned
        `device_getDisplayName()` — the AP_SSID-fallback version — which
        meant a caller could never actually tell "never set" apart
        from "user genuinely set it to something." Changed both to
        return the raw stored value instead (possibly blank),
        consistent between both front doors. `discovery.h`'s actual
        broadcast reply still correctly uses the fallback version —
        that one genuinely needs to show *something* even if unset,
        unlike this new auto-default logic which needs to know the
        difference.
      - **App**: Pin Mapper's `validateAndSave()` — the one place that
        currently pushes config to a live device — now chains a
        `CMD_GET_NAME` check after a successful push, and only if the
        raw stored name is blank, follows up with
        `CMD_SET_NAME#<profile displayName>`. A name someone
        deliberately set is never silently overwritten, even if it
        happens to differ from the profile's current display name.
      **Known coverage gap, not introduced by this change, worth being
      upfront about rather than letting it be silently incomplete**:
      the Guided Setup wizard never talks to a live device at all —
      it only saves config locally on the phone. Since this new hook
      lives specifically in `validateAndSave()`, someone who uses only
      the wizard and never separately visits Pin Mapper wouldn't get
      the auto-default at all. Not fixed here — giving the wizard a
      real device-push step is a separate, bigger piece of work than
      today's ask.



- [x] **User-settable device name — fixes a real UDP discovery gap
      found by direct investigation** (v17 firmware, delivered as a
      zip; app-side pushed as `7dd9994`). Every device previously
      reported the identical hardcoded `AP_SSID` as its "name" in
      discovery replies — with 2+ devices on the network at once, the
      existing "Select a device" picker (already correctly built,
      confirmed working — collects all distinct replies, doesn't just
      grab the first one) would show entries like "ESPad_Test
      (192.168.4.1)" / "ESPad_Test (192.168.4.2)": technically
      distinguishable by IP, not meaningfully by name.
      - **Firmware**: new `device_identity.h` — a genuinely separate
        header from `board_defs.h` despite sharing the same `Preferences`
        storage pattern (`"espad_meta"` namespace), since "which board"
        and "what's this device called" are different concepts.
        `discovery.h`'s reply now uses the settable name, falling back
        to `AP_SSID` only if none has been set. New TCP commands
        `CMD_SET_NAME#<name>` / `CMD_GET_NAME`, and matching web UI
        endpoints `GET`/`POST /api/name` (folded into the existing
        "Board" section, retitled "Device," since they're both one-time
        device-identity settings).
      - **App**: new `DeviceCommand.sendSetDeviceName()`, and a Device
        Name field in Settings' WiFi tab. **Real ordering bug caught
        and fixed while building this**: the name query needed to be
        chained AFTER the existing WiFi status query's response
        arrives, not fired independently alongside it — both share one
        TCP connection, and firing both at once risked their responses
        arriving out of order, each getting caught by the wrong
        listener (the WiFi status handler catching the name reply, or
        vice versa).
      - **A second, unrelated bug caught in the same pass**: a
        `str_replace` mid-edit left stray leftover closing braces and a
        duplicate `MainTcpHolder.enqueue` call from the original file
        structure — caught by a full brace-balance check on the whole
        file afterward, not by re-reading the diff and assuming it was
        clean. Consistent with the same verification discipline
        established after the earlier WiFi-handlers mistake this
        session.
      - Both the app (Settings' WiFi tab) and the web UI ("Device"
        section) can set the name — same "equally valid front doors"
        pattern already used for WiFi/board selection.



- [x] **"Flash Default" is now board-aware — disabled with a clear
      explanation unless the active board is one the bundled binary
      was actually verified for, rather than one static binary offered
      unconditionally regardless of `Boards.ALL` spanning multiple chip
      architectures.** Kept `DEFAULT_FIRMWARE_SUPPORTED_BOARDS`
      deliberately conservative — just `d1_mini32`, not the whole
      original-ESP32 family it happens to share a chip with:
      - **D1 Mini32, ESP32 DevKit V1, ESP32-CAM (AI-Thinker)** — all
        the same underlying chip (original ESP32, Xtensa LX6). The
        binary would actually boot and run on these, but its baked-in
        assumptions (I2S pins at 26/25/22, whatever GPIOs the compiled
        role config points at) reflect the D1 Mini32's layout
        specifically — could land on reserved/wrong pins for the
        others (camera pins on ESP32-CAM, for instance). Deliberately
        NOT included in the supported set despite sharing a chip —
        "would boot" isn't the same as "verified correct," and being
        conservative here costs nothing.
      - **ESP32-S3** — a genuinely different chip variant (Xtensa LX7,
        different peripheral register map) — needs its own Arduino IDE
        board selection and its own compiled binary. Flashing the D1
        binary here would not run correctly at all, not just have
        wrong pins.
      - **ESP32-C3** — RISC-V, a completely different instruction set
        from Xtensa. Flashing the D1 binary here wouldn't boot at
        all — this isn't a "wrong pins" problem, it's not the same CPU.
      **Implementation**: new `Boards.DEFAULT_FIRMWARE_SUPPORTED_BOARDS`
      constant, checked in both places "Flash Default" independently
      exists — `OtaActivity` and Settings' OTA tab (the same pre-
      existing duplication noted elsewhere in this doc, still not
      consolidated, but both kept consistent with this same check
      rather than fixing one and leaving the other stale). A dedicated,
      persistent text view (`tvDefaultBoardSupport`) shows which
      board(s) are supported either way — always visible, not just
      when something's wrong — since `OtaActivity`'s existing `tvInfo`
      gets overwritten constantly by other transient status messages
      and would have made the note disappear the moment anything else
      happened on screen.
      **A real bypass caught and fixed while implementing this**:
      `OtaActivity` has a second path that auto-flashes when launched
      from Settings with a `flashDefault` intent extra — this called
      `flashFromAssets()` directly, completely independent of the
      button's disabled state, meaning the UI could show "disabled,
      unsupported board" while a different entry point flashed it
      anyway. Gated with the same board-compatibility check rather
      than left as a silent bypass of the safety just added.



- [x] **Live Controls in the web UI — actually control the device from
      a browser, no phone needed** (v16 firmware, delivered as a zip).
      Requested as "a copy of the on-screen gamepad controls in the
      web UI" — **worth recording why it isn't literally that**, since
      the reasoning matters for anything similar later: the app's
      virtual gamepad relies on `ControllerMapping` (which A/B/X/Y maps
      to which function) — that mapping lives ENTIRELY on the phone,
      the device has never known about it at all. A literal "12 fixed
      gamepad slots" would have nothing to map to on the device side.
      **The better-fitted equivalent, built instead**: one real control
      per function actually configured on the device, styled with the
      same circular look — since the device already knows its own role
      list (`GET_CONFIG`/`/api/config`), this needs no new mapping
      concept at all, unlike trying to replicate the phone's
      abstraction.
      - **Real gap this exposed and had to fix first**: the web UI
        could only read/write *configuration* — it had no way to fire
        a live command (`SET`/`SETV`/`SETA`/`GET`) at all. New
        `POST /api/trigger` (`{role, action, value}`), reusing the
        EXACT SAME `handleSetCommand()`/`handleSetValueCommand()`/
        `handleGetCommand()`/`handleSetAngleCommand()` the TCP command
        channel already calls — not a second, parallel implementation.
        Required a new `StringPrint` class (a `Stream` subclass that
        captures `print()`/`println()` output into a `String` instead
        of sending it over a real connection) so those existing
        Stream-based handlers could be called and their response
        captured for wrapping in an HTTP JSON reply — and forward
        declarations for those three handler functions at the top of
        `webui.h`, since they're textually defined later in the main
        `.ino`, after `webui.h` gets `#include`d.
      - **Frontend**: a "Live Controls" section rendering one control
        per role from the same `/api/config` data `load()` already
        fetches for the function list (reused, not a duplicate fetch)
        — `DIGITAL_OUTPUT`/`AUDIO_SIGNAL` as circular buttons (Toggle
        vs. tap-to-play, matching the same distinction already made in
        the app's Controls screen), `PWM_OUTPUT`/`SERVO` as sliders
        (0-255 / 0-180, sent on release not on every drag tick, same
        reasoning as the app's own sliders), `ANALOG_INPUT` as a
        refresh button showing the live reading. New CSS matching the
        app's circular gamepad aesthetic (dark translucent fill, thin
        cyan outline, brighter fill when active).
      **Not yet tested against real hardware** — same honest caveat as
      everything else built this session. Good first thing to verify
      once wiring's confirmed on either test vehicle: load the page,
      confirm a `DIGITAL_OUTPUT` toggle button actually flips a real
      pin, before trusting the sliders/audio trigger too.



- [x] **AUDIO_SIGNAL — the first real test of a "driver beyond simple
      GPIO" role type, built per direct request** (v15 firmware,
      delivered as a zip; app-side changes pushed as commit
      `d769fca`). Deliberately scoped down from a general audio system:
      ONE FIXED, embedded clip baked into flash, not dynamically
      loadable sounds — that needs SD card support first (a separate,
      larger piece of work, see the driver-abstraction entry above).
      This proves the *pattern* — a role that isn't simple GPIO — not
      the hardest part of a general system (dynamic bus-sharing the
      way PCA9685 would need).
      - **Firmware**: new `audio_signal.h`, using the *modern*
        `driver/i2s_std.h` API (`i2s_new_channel`/
        `i2s_channel_init_std_mode`/`i2s_channel_write`) — confirmed
        against ESP-IDF's own official docs before writing anything,
        since the legacy `driver/i2s.h` is deprecated on Arduino-ESP32
        core 3.x (proven already in use here via `ledcAttach`'s
        simplified API). Pins (BCLK=26, LRCLK=25, DIN=22) come from a
        real, tested MAX98357A wiring example found during research,
        not arbitrary numbers — still needs checking against the
        Train's actual physical wiring before relying on it, same
        caveat as every other board-pin assumption in this project.
      - **The clip itself is a synthesized descending tone sweep
        (900Hz->450Hz, 0.6s), generated in Python — explicitly NOT a
        real train whistle recording**, purely to prove the playback
        mechanism works end to end. ~18.8KB embedded in flash. Swap in
        a real recording later by regenerating the same byte array
        from an actual WAV file.
      - **Deliberate shortcut, clearly documented rather than left as
        a silent surprise**: AUDIO_SIGNAL roles don't have a real "pin"
        the way other role types do — the 3 I2S pins are fixed
        firmware constants, not user-assignable via Pin Mapper or the
        web UI for this first test. A role's stored "gpio" value is
        instead reinterpreted as a clip index (only index 0 exists
        right now). Avoided a `pin_store.h` schema change for a first
        test rather than build the bigger multi-pin-role change this
        would eventually need for real generality.
      - **Closed two landmines this reinterpretation created**, found
        while implementing rather than left for later: firmware's
        `validateGpio()`/`board_allowsGpio()` and the web UI's
        `/api/board/pins` endpoint would otherwise apply real chip/
        board pin rules (reserved flash, board availability) to what's
        actually a clip index — harmless today since clip index 0
        happens to be a valid, unreserved value, but would have broken
        confusingly for a hypothetical future clip index that
        collided with a reserved GPIO number. Bypassed explicitly for
        `AUDIO_SIGNAL` type in all three places rather than leave the
        coincidence unexamined.
      - **App side**: Pin Mapper's Add Function offers "Audio Signal"
        as a 5th type, with no pin picker shown (explained in the note
        text why) — auto-assigns the placeholder value on creation via
        the existing `assignGpioToRole()` machinery, which happens to
        work unmodified since clip index 0 coincides with a real (if
        strapping-flagged) board pin. **Flagged as fragile for a
        future second clip, not fixed further** — genuinely only
        works by coincidence for index 0, and would need real handling
        (a proper clip-index picker, not a board-diagram pin tap)
        before a second clip could be added safely. Controls forces
        AUDIO_SIGNAL to a Momentary trigger button — a persistent
        Toggle state doesn't make sense for "play this sound once."
        No changes needed to the actual touch-handling/send logic —
        Momentary's existing press-sends-1/release-sends-0 behavior
        already matches exactly what the firmware expects (1 triggers
        playback, 0 is a harmless no-op).
      - **Found and fixed genuinely stale seed data while implementing
        this**: Train's profile had 3 separate `audio_bclk`/`audio_lrc`/
        `audio_din` AUDIO_SIGNAL roles seeded early in the session,
        before real support existed — assumed a completely different
        model (each I2S pin as its own user-assignable role) than what
        actually got built (fixed firmware pins, one role per clip).
        Replaced with a single `whistle` role matching reality. **Only
        fixes this for fresh seeds** — a profile that already ran the
        old seed (flag-guarded to run once) keeps the 3 stale roles
        until manually deleted; the seed-data fix doesn't retroactively
        reach already-seeded local storage.
      **Not yet tested against real hardware** — same honest caveat as
      everything else built this session while away from the Train.
      Good first thing to verify once wiring's confirmed: `SET whistle 1`
      should produce an audible ~0.6s tone through the speaker.



- [x] **Checked the Train's own hardware (TB6612FNG motor driver +
      MAX98357A audio DAC) for the same category of issue just found
      with the Freenove car's PCA9685/WS2812 — verified independently
      rather than assumed, since this project's whole habit this
      session has been checking hardware claims rather than trusting
      memory.** Split result, not a flat yes/no:
      - **TB6612FNG motor driver — confirmed NO issue, genuinely
        compatible with this framework as it exists today.** Multiple
        independent sources confirm it's controlled entirely through
        plain GPIO — `AIN1`/`AIN2` (direction, digital), `PWMA` (speed,
        PWM), `STBY` (standby enable, digital). No I2C, no shared bus,
        nothing hidden behind an unreachable peripheral. Matches
        `SET`+`SETV` exactly. Not just theoretical either — this is
        the same combo already tested on real hardware earlier this
        session (D1 Mini32 + TB6612FNG driving an actual test LED/
        motor over the live TCP connection).
      - **MAX98357A audio DAC — a real parallel to the PCA9685/WS2812
        gap, just a different protocol.** It's I2S, not I2C — three
        dedicated lines (`BCLK`/`LRCLK`/`DIN`) streaming actual PCM
        audio sample data continuously via the ESP32's dedicated I2S
        peripheral hardware. There's no way to represent "play this
        sound" as a `SET`/`SETV`/`SETA` command — those only ever
        carry a single scalar value (on/off, a PWM number, a servo
        angle), never a continuous audio stream. If the Train's
        whistle/sound effects were meant to go through this framework,
        that's currently just as unreachable as the Freenove car's
        motors were, for the same underlying reason: a real streaming
        subsystem this firmware has never had any support for.
        **Since addressed** — see the `AUDIO_SIGNAL` entry further down
        this doc, a scoped-down first test (one fixed embedded clip,
        not general dynamic audio) built specifically because this is
        the part of the driver-abstraction question most relevant to
        this project's actual origin as a train conversion.



- [x] **Board-aware validation, file-based export/import, and OTA —
      all added to the device web UI (v14, firmware, delivered as a
      zip).** Prompted by a direct question worth recording the
      reasoning for: "should board selection and pin planning live in
      the web UI, including planning for a board other than the one
      connected?" **Deliberately drew a line here rather than build
      past it** — board-*awareness* for the device actually being
      looked at is legitimate and was built; planning a profile for a
      DIFFERENT board than what's connected was declined, because the
      web UI is physically embedded on one live device with no
      offline/draft mode — every write validates against and saves to
      that real device's real NVS immediately. That's exactly the
      distinction the roadmap already draws between Option A (this,
      device-hosted) and Option B (a separately-hosted planning tool,
      not yet started) — Option B exists specifically to NOT be tied
      to a live device, which is what "plan for hardware I don't own"
      actually needs. Retrofitting that onto Option A would mean
      re-deriving Option B's reason for existing, awkwardly, inside
      something that's fundamentally live-device-only.
      - **Board-aware validation**: new `board_defs.h` — pin
        availability tables for the built-in boards (mirroring a
        SUBSET of the app's own `BoardDef` knowledge, necessarily
        duplicated since firmware can't call into the phone's Kotlin).
        This device can't auto-detect its own board, so it's a
        one-time setting, same mental model as the app's own board
        picker. Defaults to "generic" (chip-level rules only, no
        board-specific narrowing) until set, so validation doesn't get
        silently MORE restrictive for anyone who hasn't set this yet.
        New `GET /api/boards`, `GET`/`POST /api/board`, and
        `GET /api/board/pins?type=X` (the last populates the Add
        Function form's GPIO field as a dropdown of only the pins
        valid for the current board + type, replacing a raw number
        input — mirrors the app's own Pin Mapper dropdown filtering).
        Both `POST /api/config` and `POST /api/import` now check
        `board_allowsGpio()` in addition to the existing chip-level
        `validateGpio()`.
      - **File-based export/import**: export now triggers an actual
        browser file download (`Blob` + a temporary anchor's `download`
        attribute) instead of a copyable textarea; import reads a
        chosen file via `FileReader` instead of pasted text. Same
        backend endpoints as before — this only changed how the
        request body gets populated on the frontend.
      - **OTA via the web UI**: `POST /api/ota`, deliberately built as
        its own thing rather than reusing `ota.h`'s existing handler —
        that one decodes HTTP chunked transfer encoding (what the
        app's Android OTA client sends); a browser's
        `fetch(url, {body: file})` sends a plain `Content-Length` body
        instead, so this streams directly into `Update.write()` in 4KB
        chunks against that instead. Detected and handled EARLY in
        `webui_poll()`, before the generic small-buffer body reading
        used by every JSON endpoint — a firmware `.bin` can be
        megabytes; that buffer is 512 bytes.
      - **Security note reiterated, not newly introduced**: this has
        no authentication, same gap already logged for the app-facing
        OTA port. Flagged again here because a second, browser-
        reachable OTA path makes that existing gap more concretely
        exploitable, not because this endpoint adds a new category of
        risk beyond what already existed.
      - **Same verification discipline applied again** as the earlier
        WiFi-handlers mistake (checking brace balance against the last
        known-good file before packaging, not just eyeballing the
        diff) — came back clean this time, no fix needed, but worth
        keeping the habit given how large this particular edit was.


- [x] **WiFi setup and Export/Import added to the device web UI**
      (v12, v13 — firmware, delivered as zips rather than tracked in
      this repo).
      - **WiFi (v12)**: a section on the device page showing current
        connection status, SSID/password fields, Connect/Forget
        buttons. Routes to the exact same `wifi_sta.h` functions the
        app's `CMD_WIFI_STA#` already uses — not a second WiFi
        implementation, a second front door to the same one. Same
        ~10s blocking connect behavior as the app already has.
      - **Export/Import (v13)**: `GET /api/export` produces the SAME
        JSON schema the phone app's own `ProfileExportImport.kt`
        already uses — a profile can genuinely move in either
        direction: export from the device, paste into the app's
        "+ New Device" import field, or export from the app and paste
        into the device page. Omits "buttons" — Controls buttons are a
        phone-only concept (which on-screen widget represents a role);
        the device has no notion of that, only role/label/type/gpio.
        `POST /api/import` accepts either the full phone-export shape
        or a bare roles array, all-or-nothing (one invalid entry
        aborts the whole import, nothing already-saved gets touched) —
        same atomic behavior every other config-writing path already
        follows. Full replace, not a merge, matching the same one-way
        model as Sync from Device.
      - **A real editing mistake was caught and fixed before shipping
        v13, worth being honest about**: while inserting the WiFi
        handler functions into `webui.h`, a `str_replace` accidentally
        deleted `webui_handlePage`'s own function signature while
        keeping its body — would have been a hard compile failure
        (code outside any function). Caught by actually verifying
        brace balance against the last known-good file rather than
        eyeballing the diff, not by luck.
      - **App-side interoperability fix**, found while verifying the
        export/import round-trip actually works end to end (not
        assumed): the phone's `ProfileExportImport.importFromJson()`
        only fell back to a default board when `boardKey` was
        *missing* from the JSON. A device export sends it *present but
        empty* (the device has no board concept at all to report), which
        `optString`'s own default doesn't catch. Would have still
        technically worked (`Boards.byKey()` already falls back safely
        wherever it's looked up), but the imported profile's board tab
        would never show as selected anywhere. Fixed with `.ifBlank {}`
        rather than leaving a known rough edge in a feature just
        described as fully interoperable.


- [x] **Custom/unlisted boards — the last remaining formal roadmap
      item.** Lets someone define a board that isn't in `Boards.ALL`
      at all: name it, add whichever pins their specific board exposes
      (label, GPIO number or blank for non-GPIO pads like 3V3/GND,
      status), mark which are ADC1-capable, flag camera support if it
      has one. Same spirit as custom profiles/roles — this is app-only
      logic, doesn't touch firmware at all, since firmware only ever
      deals in raw `{role, gpio}` pairs regardless of which board
      those came from.
      - New `CustomBoardStorage`/`CustomBoard`/`CustomBoardPin` (mirrors
        `CustomProfileStorage`'s pattern), converting to the same
        `BoardDef` type built-in boards use via `toBoardDef()` — every
        existing screen that already consumes a `BoardDef` works
        unchanged, no special-casing needed downstream.
      - New `BoardResolver.allBoards(context)` merges built-in + custom
        for anywhere that needs the full list (New Device's board
        picker, the Guided Setup wizard's board step, Pin Mapper's
        board-switch tabs within an existing profile) — same pattern
        as `ProfileResolver`.
      - **Real architectural wrinkle, solved pragmatically rather than
        with a large refactor:** `Boards.byKey()` is called throughout
        the app with no `Context` available (it's a plain compiled
        object), so it can't read `SharedPreferences` for custom boards
        directly. Rather than thread `Context` through every one of
        those call sites, `Boards` gained a `customBoardsCache` that
        `BoardResolver.allBoards(context)` populates as a side effect —
        as long as a screen calls `allBoards()` at least once (which
        board-tab-building already does everywhere), plain
        `Boards.byKey()` calls elsewhere in that same screen correctly
        resolve custom boards too, without a sprawling signature change
        across the codebase.
      - `showDefineCustomBoardDialog()` is a standalone function (not
        tied to one Activity), so Pin Mapper's picker and the wizard's
        board step share the exact same creation flow instead of two
        copies — deliberately avoiding the same "two independent copies
        of one feature" trap found and fixed twice earlier this session
        (OTA, then Presets).
      **Known cosmetic quirk, not a bug:** custom boards render as a
      single pin column (`rightHeader` is always empty for a converted
      custom board) — the board diagram's two-column layout just shows
      an empty second column rather than breaking, since
      `buildHeaderColumn()` already handles an empty list gracefully.
      Not worth restructuring the rendering for a single-column-vs-two
      distinction given how rarely this will matter in practice.


- [x] **Option A built: device-hosted web UI, full version (create,
      not just adjust) with two-way sync.** The bigger, more honest
      scope was chosen deliberately over a narrower "web UI can only
      tweak pins for functions the phone already knows about" version
      — see the design discussion this required first.
      **The core problem this had to solve:** the phone was the ONLY
      place that knew what a function *is* — firmware only ever stored
      `role → gpio`, nothing about type or label. A function created
      independently on the device (via the new web page) would be
      invisible to the phone app with no way to find out it existed.
      **The model adopted: explicit one-way sync in both directions,
      like `git push`/`git pull` — not live bidirectional merging.**
      Validate & Save was already the push (phone → device); this adds
      an explicit **pull** (device → phone), a deliberate user action
      that REPLACES the phone's local copy for that profile, not a
      background merge.
      - **Firmware storage extended** (`pin_store.h`) — now persists
        `label` and `type` per role, not just `role`/`gpio`.
        Backward-compatible: pre-v11 saves (or a minimal payload that
        only sends role+gpio) default to `type=DIGITAL_OUTPUT` and
        `label=<role key>` when those NVS keys don't exist, rather than
        failing.
      - **New `GET_CONFIG#` TCP command** — reports the full role list
        as JSON (key/label/type/gpio), used by the web page itself and
        by the phone's new sync feature.
      - **New device-hosted web page** (`webui.h`), port 8081 (kept
        separate from OTA's 8080 — chunked-binary-upload handling and
        small-JSON-API handling are two different things, simpler kept
        apart). Plain HTML/vanilla JS, no external dependencies (the AP
        has no internet access, so a CDN script would just fail).
        `GET /`, `GET /api/config`, `POST /api/config` (add/update, run
        through the same `validateGpio()` the JSON-payload path uses),
        `POST /api/delete`.
      - **JSON payload (phone → device) now also carries label/type**,
        not just role/gpio — so a function created via the PHONE is
        just as fully described on the device as one created via the
        web UI. `PinConfigStorage.buildPayload()` now takes the
        resolved roles list to populate these.
      - **Closed a previously-flagged gap as a natural side effect**:
        firmware's `validateGpio()` had no concept of role type at all
        before this (it used a hardcoded-role-name placeholder
        heuristic predating the real type system). Since type now
        genuinely flows through, replaced that placeholder with real
        ADC1-only enforcement for `ANALOG_INPUT`, matching the app's
        `PinValidation.ADC1_PINS` exactly — firmware is no longer the
        only side that DOESN'T check this.
      - **App: "Sync from Device"** — a new button in Pin Mapper
        (`⬇ Sync from Device`, next to Validate & Save), with an
        explicit confirmation dialog spelling out that it replaces the
        phone's local copy for the active profile, since this is a
        genuinely destructive pull, not a safe background refresh.
      **Known limitation, inherited from the existing architecture, not
      introduced by this work:** the device still only has ONE flat
      config, always saved under a hardcoded `"train"` NVS namespace —
      it has no real concept of "which phone-side profile" it belongs
      to. Fine for a single test rig; would need genuine multi-profile
      awareness on the firmware side to mean more than that.
      **Not yet tested against real hardware** — Dave was away from
      the ESP32 for this whole build. The code is internally consistent
      and each piece reuses proven patterns (the OTA server's raw-HTTP
      approach, the existing JSON payload/validation path), but the
      full loop — web UI → GET_CONFIG → Sync from Device → Pin Mapper
      showing the result — needs a real end-to-end run once hardware's
      available again.



- [x] **Real servo angle control, end to end.** The last major gap
      blocking the full-circle Freenove-car test — servo is genuinely
      different from PWM duty (a ~50Hz signal with pulse width mapped
      to angle, not an arbitrary duty cycle), not just PWM with a
      different label.
      - Firmware: new `SETA <role> <0-180>` command (`servo_ctrl.h`),
        using the **ESP32Servo** library (Library Manager — NOT the
        plain Arduino `Servo.h`, which doesn't support ESP32). Each
        GPIO gets its own `Servo` object, lazily attached on first use,
        same pattern as `SETV`'s `ledcAttachedPins` tracking.
      - Pin Mapper's "Add Function" type picker now offers Servo as a
        4th option, alongside On/Off/PWM/Analog In.
      - Controls' slider is now servo-aware: 0-180° range and `SETA`
        for servo roles, 0-255 and `SETV` for PWM roles — resolved by
        checking the underlying role's type, not a separate widget or
        `ControlType`. Reusing `ControlType.SLIDER` for both (rather
        than adding a `SERVO_SLIDER` variant) meant the gamepad axis
        picker in Controller Mapping needed zero changes — it already
        filters on `ControlType.SLIDER` generically.
      - Gamepad axis mapping (`CUSTOM_PWM`) also servo-aware — rescales
        the normalized 0-255 axis value down to 0-180 and calls
        `sendSetAngle()` when the mapped target turns out to be a
        servo, resolved at dispatch time rather than changing how the
        axis itself gets normalized.
      - **Nice validation of earlier design:** RC Car's seeded
        "Steering servo" role was already correctly typed
        `RoleType.SERVO` from early in the session, just waiting for
        real support to catch up — it'll work automatically now with
        zero changes needed to the seed data itself.
      **Not addressed:** true differential-drive/dual-servo *axis*
      mapping (one physical stick driving two things at once, e.g.
      combined pan+tilt from a single 2D stick) — `CUSTOM_PWM` is still
      single-axis-to-single-slider only. A real dual-axis mapping would
      be a separate, more involved feature.
      **KNOWN ISSUE, needs real-hardware testing to confirm/resolve —
      not yet tested as of this note, flagged clearly so it isn't lost
      before that testing happens (could be days out):** `SETV` (our
      own direct `ledcAttach()`/`ledcWrite()` calls) and `SETA`
      (ESP32Servo's own internal allocation via its `ESP32PWM` class)
      both draw from the same 4 physical ESP32 PWM timers, with **no
      coordination between the two systems** — our code doesn't know
      what ESP32Servo has claimed, and vice versa.
      - **Confirmed NOT an issue for basic single-servo use**: ESP32Servo
        does not require an explicit `ESP32PWM::allocateTimer()` call —
        confirmed via its own documentation: *"When using ESP32Servo,
        calling allocateTimer is not necessary. If you don't call the
        function, all timers will be used."* So `SETA` alone, with no
        `SETV` roles active on the same device, should work as shipped.
      - **The real, confirmed risk is specifically when BOTH a PWM_OUTPUT
        role (`SETV`) and a SERVO role (`SETA`) are in use on the SAME
        device at the same time.** Evidence: a forum thread where the
        library's own author responds to exactly this collision — a
        servo and an unrelated PWM channel ended up sharing one of the
        4 timers, and changing one's frequency silently changed the
        other's too (his own words: *"PWM channel 1 shares a timer with
        0, changing the frequency to 1000.00 Hz will ALSO change channel
        0 from its previous frequency of 50.00 Hz"*). Applied to us:
        if `SETV`'s 5000Hz PWM and `SETA`'s 50Hz servo signal end up on
        the same timer, one could silently override the other's
        frequency — likely symptom: an LED/motor speed role that
        suddenly behaves wrong (wrong brightness/speed curve, or stops
        responding correctly) the moment a servo role is also assigned
        and used, with no code change to the PWM role itself.
      - **What to actually test, once hardware access allows:** create
        BOTH a PWM_OUTPUT role and a SERVO role on the same device,
        exercise both (e.g. move the servo, then check the PWM role
        still behaves correctly, and vice versa). If either misbehaves
        only when the other is also active, that confirms the
        timer-sharing collision.
      - **If confirmed, the fix path** (from the library author himself,
        not guessed): explicitly reserve specific timers for one system
        or the other via `ESP32PWM::timerCount[N] = 4;` (locks timer N
        out of ESP32Servo's auto-allocation pool) — or more robustly,
        stop using raw `ledcAttach()`/`ledcWrite()` for `SETV` entirely
        and route it through ESP32Servo's own `ESP32PWM` class instead,
        so both `SETV` and `SETA` go through one coordinated allocator
        rather than two independent ones fighting over the same
        physical resource blind to each other.
      - **Deliberately not guess-patched now** — adding
        `ESP32PWM::allocateTimer()` calls preemptively without hardware
        to verify against risks "fixing" a problem that may not exist
        on this specific core/library version combination while
        introducing a different one (reserving timers away from `SETV`
        before confirming it actually needs them). Logged in enough
        detail here to act on decisively once real hardware testing is
        possible, rather than needing to be re-derived from scratch.


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
- [x] **Device selection at session start — since simplified.** Originally
      showed a "Which device are you using?" dialog on every launch
      whenever more than one profile existed. Changed per direct
      feedback: the extra confirmation step was more friction than
      help, since switching devices is already easy elsewhere (tapping
      a different profile tab in Pin Mapper already sets
      `ActiveProfile`). Now launch silently uses whichever profile was
      active last time — no prompt — applying that device's saved
      connection IP automatically if it has one, same as before, just
      without asking first. `MainActivity.applyLastActiveProfileThenConnect()`
      (renamed from the old picker-showing function, to avoid a
      misleading name now that it doesn't show anything).


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
        Select). **Redesigned twice** — first pass replaced a flat
        4-per-row rectangular grid with circles in a diamond, but used
        plain `LinearLayout` columns for the shoulder buttons; per
        follow-up feedback comparing directly against a reference
        screenshot, that first pass had the shoulder buttons too far
        from the diamond (large gaps instead of tight bracketing) and
        `LinearLayout`'s gravity-based centering didn't reliably land
        them at the intended height. **Second pass rebuilt the cluster
        with a `FrameLayout` and explicit pixel-margin math** instead
        of relying on layout flow — Y/X/B/A placed at exact
        center-to-center diamond coordinates, L1/L2/R1/R2 placed with a
        small fixed offset bracketing the X/B row height, matching the
        reference precisely rather than approximately. New
        `virtual_button_circle.xml` drawable (thin cyan outline,
        translucent dark fill) for the circles.
      - **Trade-off made deliberately**: the first version showed what
        each button is actually mapped to as a caption under the
        circle (e.g. "LED" under "A"). The tight center-to-center
        spacing needed to match the reference leaves no room for a
        caption line between rows without overlapping the row below,
        so captions were dropped from the diamond/shoulder buttons in
        the second pass. `resolveVirtualButtonTarget()` is kept
        (unused for now) rather than deleted, since it's the exact
        logic needed if captions come back in some other form later
        (e.g. a toast on long-press).
      - Select/L3/R3/Start moved out of the main diamond cluster
        entirely (the reference doesn't show equivalents nested with
        its D-pad/diamond either — those are separate small toolbar
        icons elsewhere on its screen) — now a smaller row directly
        below instead.
      - **Open question, not resolved by this pass:** an earlier
        screenshot showed the cluster overlapping the app's centered
        "no camera feed" placeholder logo. The tighter shoulder
        positioning reduces cluster *width*, but total cluster
        *height* (top of Y to bottom of the utility row) isn't
        dramatically smaller than the first version — the utility row
        still adds roughly the same vertical space, just structured as
        a sibling instead of nested inside the diamond column. Worth
        confirming after rebuilding whether the overlap is actually
        gone or still needs a separate fix (e.g. moving the utility row
        further away, or repositioning the whole cluster lower).
      - Real press/release via `OnTouchListener` (not tap) still holds
        in both redesigns, matching the momentary-button fix from the
        same session — consistent behavior whether it's a MOMENTARY-
        mapped custom function or the legacy horn.
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
      **Real gap discovered while adding these — since fixed.**
      `PinValidation.ADC1_PINS` was a single hardcoded set
      (`32/33/34/35/36/39`) — correct for the *original* ESP32, but
      completely wrong for S3 (real ADC1 pins: GPIO1-10) and C3 (real
      ADC1 pins: GPIO0-4). `BoardDef` now carries its own `adc1Pins`
      set (same pattern as `supportsCamera`), and `canAssign()` takes
      it as a parameter instead of reaching for one global constant —
      all three call sites (Pin Mapper's assign flow, its dropdown
      filter, the wizard's pin filter) updated to pass the actual
      board's set. The firmware side of this same gap (its own
      `validateGpio()` not knowing about ADC1 at all) was already
      closed as part of the web UI work — this closes the matching
      app-side half.


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

- **Broader vision, stated directly and worth recording precisely: this
  app becoming "as universal as reasonably possible"** — real compiled
  firmware for every board the app already defines pin layouts for
  (not just D1 Mini32), and potentially non-ESP32 (Arduino-branded)
  boards eventually. Two genuinely different scopes bundled in that
  one sentence, worth separating clearly rather than treating as one
  task, since their feasibility is wildly different.

  **Near-term, concrete: real per-chip-family firmware for the other
  ESP32-family boards already defined in `Boards.ALL`.** Directly
  follows from "Flash Default" just becoming board-aware (disabled
  rather than silently wrong for unsupported boards) — the natural
  next step is making MORE boards actually supported, not just
  correctly refusing the ones that aren't.
  - **ESP32 DevKit V1** — same chip as D1 Mini32 (Xtensa LX6). The
    existing binary would likely run as-is; the real work is
    *verifying* that claim on actual DevKit V1 hardware (pin-by-pin,
    given the I2S/audio pins especially are D1-Mini32-specific
    assumptions) before adding it to the supported set — not assuming
    "same chip" is sufficient, per the reasoning already applied when
    `DEFAULT_FIRMWARE_SUPPORTED_BOARDS` was deliberately kept narrow.
  - **ESP32-CAM (AI-Thinker)** — same chip family too, but genuinely
    needs its own verification pass given camera-reserved pins
    (confirmed in `board_defs.h`'s `ESP32_CAM_RESERVED` list) could
    conflict with whatever the compiled role config or I2S pins
    assume — more than a rebuild, a real check that nothing collides.
  - **ESP32-S3** — a different chip variant (Xtensa LX7, different
    peripheral register map) needing its own Arduino IDE board
    selection and its own compiled `.bin`, not just a recompile flag.
    Likely needs genuine compile-testing against the S3 target to
    confirm the existing source (I2S, LEDC, ESP32Servo, WiFi/web
    server code) builds cleanly — probably does, given how much of
    this already goes through portable Arduino-ESP32 core APIs rather
    than chip-specific register access, but "probably" isn't
    "confirmed," and this hasn't been tested on real S3 hardware at
    all yet.
  - **ESP32-C3** — RISC-V, not Xtensa at all — the biggest lift of the
    four, since it's not just "a different chip variant" but a
    genuinely different instruction set architecture requiring its own
    full compile-and-verify pass, not an assumption that anything
    carries over cleanly from the Xtensa-based boards.
  - **What "done" looks like for this piece**: a genuinely separate
    compiled `.bin` per chip architecture family (not per individual
    board — D1 Mini32/DevKit V1/ESP32-CAM could plausibly share ONE
    Xtensa-LX6 binary once verified, distinct from an S3 binary and a
    C3 binary), with "Flash Default" picking the right one based on
    the active board rather than the current single-binary-or-disabled
    state.

  **Longer-term, genuinely more speculative: non-ESP32 (Arduino-
  branded) board support.** Worth being honest that this is a much
  bigger leap than the ESP32-family work above, not a natural
  extension of it — and that "Arduino board" needs its own definition
  before this is even a well-formed goal, since the answer differs
  enormously depending which:
  - **Classic AVR Arduino boards (Uno, Nano, Mega, etc.)** — no
    built-in WiFi at all, which this entire framework's architecture
    depends on (the AP, the TCP command channel, the embedded web
    server). Would need external WiFi hardware (a shield, an ESP8266
    co-processor) just to have a network to be reachable on at all —
    a fundamentally different connectivity model, not a firmware
    recompile. Separately, RAM/flash are vastly smaller (an Uno has
    2KB RAM, 32KB flash total, vs. this ESP32 firmware alone needing
    roughly 18.8KB just for one embedded audio clip) — the current
    architecture (`ArduinoJson`, a multi-KB embedded HTML/JS web page,
    audio clips baked into flash) would not fit at all, not just need
    trimming. Realistically means dropping major features entirely for
    this class of board, not porting them.
  - **WiFi-capable Arduino-branded boards (Arduino Nano ESP32, Arduino
    UNO R4 WiFi, etc.)** — a much more reasonable candidate if "Arduino
    support" is what's actually meant, since these either ARE an ESP32
    under a different brand name (Nano ESP32) or have comparable
    WiFi/RAM/flash characteristics to what this firmware already
    assumes. This is genuinely closer in spirit to "another ESP32-
    family board" than to "support Arduino" as commonly understood.
  - **Recommendation, if/when this becomes a real want rather than a
    someday-maybe**: clarify which definition of "Arduino board" is
    actually meant first — the answer completely changes whether this
    is "add a few more boards to a list" or "redesign the architecture
    for a fundamentally more constrained platform."



- **Incorporating the Freenove car's LED matrix face display.**
  Logged as an option-space, not a commitment to build — raised as a
  direct question, worth recording the reasoning rather than
  re-deriving it next time it comes up.

  **The core tension, before any specific approach:** everything in
  this framework so far assumes "one role = one GPIO pin + one scalar
  value" (on/off, a PWM number, a servo angle, a voltage reading). An
  LED matrix breaks both halves of that assumption — most of these
  driver chips sit on a shared bus (I2C, or a 3-wire serial protocol)
  rather than owning a single GPIO, and what you'd actually send it
  isn't a scalar, it's a whole 8x8 (or larger) pattern. There's no
  genuinely "quick" version of this — every option below involves some
  real new plumbing, not just another `RoleType` case slotted into
  what already exists.

  **Open question, now resolved with confirmed evidence** (this entry
  originally flagged this as unverified — since checked directly
  against Freenove's own source): the driver is **WS2812**, not the
  VK16K33 originally guessed here. Confirmed from Freenove's own sketch
  comments (`Freenove_4WD_Car_Kit_for_ESP32` on GitHub): *"Use
  WS2812."* This changes which option below is realistic — WS2812 is a
  single-wire addressable protocol (needs a proper driver library like
  FastLED or Adafruit_NeoPixel, bit-banged or RMT-peripheral timing,
  not I2C or a simple digitalWrite/ledcWrite), not the I2C-bus-sharing
  situation this entry originally anticipated. There may ALSO be a
  separate WS2812 "Colorful Light" underglow strip distinct from the
  face matrix itself (the product listing shows them as separate
  features) — not independently confirmed which is which, or whether
  they're actually the same string of pixels serving double duty.

  **Option 1 — minimal, preset patterns only.** A small enum of canned
  faces/animations (closely mirroring the original firmware's "Face
  Cycle"), one new lightweight role type, one command like
  `SETFACE <role> <index>`. Least work by far, but it's really just
  re-adding a Freenove-specific feature under a thin generic wrapper —
  cuts directly against the session's actual direction, which has been
  *removing* that category of hardcoding, not reintroducing it.

  **Option 2 — a real custom-pattern role type (the one that actually
  fits this project).** Genuinely generalizable — works for any WS2812
  matrix/strip, not just this car specifically. Needs: a new role type
  that owns a dedicated GPIO (WS2812 needs precise single-wire timing —
  a proper library like FastLED or Adafruit_NeoPixel, not a shared I2C
  bus, and NOT compatible with sharing a pin the way I2C peripherals
  can), a firmware command accepting a full pixel/bitmap pattern, and —
  the bigger piece — some kind of pattern editor in the app (an 8x8
  toggle grid or color picker, saved as a named "scene," sent as a
  pattern on trigger). Moderate-to-real effort, but this is the only
  option that's actually in the spirit of the generalized framework
  rather than a step back toward hardcoding one car's specific
  hardware.

  **Option 3 — a generic addressable-LED peripheral abstraction.** The
  architecturally "correct" answer IF this project ever needs to
  support other WS2812-family hardware too (a second strip, an RGB
  underglow, other addressable LEDs) — but building a general
  "any addressable-LED device" abstraction to serve exactly one matrix
  on one car would be solving a considerably bigger problem than the
  one actually in front of it. Not worth it unless a second
  WS2812-family peripheral is already on the horizon. (Separately, if
  I2C peripheral support ever gets built for the PCA9685 motor/servo
  gap above, that would be its own distinct abstraction — WS2812 and
  I2C are different buses with different needs, not one abstraction
  covering both.)

  **Recommendation if this ever gets picked up: Option 2.** Real
  effort, but the only one of the three that doesn't quietly
  contradict everything else built this session.

- **A general "pick a driver, assign its pins" system covering common
  peripherals — cameras, SD card readers, motor driver ICs, audio
  DACs, etc.** Raised as a direct question, worth recording the
  feasibility breakdown rather than re-deriving it later — the answer
  varies enormously by peripheral, not one number.

  **The real architectural shift this implies, before any specific
  peripheral:** everything so far assumes one role = one GPIO pin = one
  scalar command. A driver-based model means a role instead references
  a *driver instance* (e.g. "the PCA9685 at I2C address 0x40") plus a
  channel/index within it — and that driver instance owns a set of
  pins that might be *shared* across many roles (I2C's 2 pins can
  serve 16 PCA9685 channels, or a completely separate I2C sensor at a
  different address) rather than *exclusive* per role (a directly-
  wired servo still needs its own dedicated pin). This is legitimate —
  it's essentially what ESPHome does (a `pca9685` "hub" component,
  then individual outputs that reference it + a channel), so there's
  real precedent this pattern works — but it's a genuinely bigger
  shift than anything built so far, not an incremental addition to the
  existing `RoleType` system.

  **Per-peripheral feasibility — this is the part that isn't one
  number:**
  - **Motor driver ICs (PCA9685-style) — the most tractable, and the
    most valuable given the Freenove-car finding above.** Fits the
    existing mental model reasonably well once "I2C bus + channel"
    exists as a pin-reference type alongside plain GPIO. Real work,
    but bounded. **The recommended first candidate if this direction
    gets picked up** — directly unblocks the thing actually blocking
    the Freenove car's motors/servos right now, not a speculative
    nice-to-have.
  - **Addressable LEDs (WS2812)** — moderate. One exclusive pin per
    strip (no bus-sharing complexity, unlike PCA9685), but needs a
    real pattern-data command and an app-side pattern editor. Already
    scoped as its own option-space in the LED matrix entry above.
  - **Audio (I2S/MAX98357A)** — hard, for a reason beyond just "new
    protocol": playing a sound requires the sound to *live* somewhere
    first. That's either baked into firmware flash (inflexible,
    limited) or read from an SD card — meaning this one is actually
    gated behind the SD card driver existing first, not independent of
    it. See the Train hardware verification entry above.
  - **SD card readers** — hard, but for a completely different reason
    than the others: it's not "drive a peripheral," it's "build a
    file-transfer subsystem" (list/read/write/delete over the
    network) — genuinely comparable in scope to the OTA feature or the
    web UI itself, not just another driver type slotted into the
    existing role system.
  - **Cameras — deliberately NOT grouped with the others.** It's a
    continuous video stream, not a discrete controllable peripheral
    with commands, and the app already has bespoke handling for it
    entirely separate from Pin Mapper/roles. Folding it into "just
    another driver" would likely be a worse fit than leaving it where
    it already is.

  **Honest bottom line:** a fully generic "pick any driver, assign its
  pins" system is closer to a small platform redesign than a feature —
  each of PCA9685/WS2812/audio/SD-card is individually about the size
  of something already built this session (servo support, the web UI,
  OTA), and building one abstraction to cleanly cover all of them at
  once is a bigger undertaking than any single one of those was. **Not
  recommended to build the whole generic system in one shot.** PCA9685
  specifically is the one piece worth treating as a real near-term
  candidate; the rest are worth having thought through, not worth
  committing to yet.



- **Two different hosting models for the "PC-based profile creator"
  idea — worth comparing rather than assuming one is obviously right:**

  **Option A — self-hosted on the ESP32 itself. Built (see the "device-
  hosted web UI" entry above) — with one difference from how it was
  originally imagined.** Visiting the device's own IP:8081 in a browser
  shows a page for creating/editing/deleting functions and pin
  mappings directly on the device. The original framing said this
  "could just read/write the device's own stored config in real time,
  no separate export/import round-trip needed at all" — that turned
  out to undersell the actual problem: the *device's* config and the
  *phone's* config are two independently-writable copies with no live
  sync between them, so an explicit pull ("Sync from Device") was
  needed rather than everything just naturally staying in sync. Still
  reuses the OTA server's proven raw-`WiFiServer`-HTTP pattern, exactly
  as anticipated.
  - *Pros, confirmed:* zero external hosting/maintenance, works fully
    offline on the device's own local AP, always talking to the actual
    physical device.
  - *Cons, confirmed:* only reachable from the same network as that
    specific device — no "plan a profile before you own a device"
    workflow (that's still Option B's territory).

  **Option B — a separately-hosted web service. Still not started.**
  Covers two things Option A can't: (1) flashing the default firmware
  to a brand-new ESP32 from a PC with no Arduino IDE at all (browser-
  based, similar to esphome/esp-web-tools style in-browser flashing via
  WebSerial), and (2) building/editing a profile before you even own or
  have flashed a device. Would use the same JSON export/import format
  already built,
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
      **Servo support (angle control, not PWM duty) — since built, see
      the entry above.**
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
- [x] Custom/unlisted boards — done, see the entry above.

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

## Security — not addressed yet, will need to be eventually

Right now **nothing on this device requires authentication at all** —
the web UI is the most obvious exposure (anyone who can reach it in a
browser can freely add/edit/delete pin functions, no login, no
confirmation), but it's really the whole picture that's open:

- **The AP password is a hardcoded, printed-in-the-source-comments
  default** (`espad1234`) — same for every device running this
  firmware, visible to anyone who reads the (public) repo. Anyone who
  knows it, or just tries it, can join the AP.
- **The web UI (port 8081) has zero authentication** — no password, no
  confirmation before a destructive action (delete a function). Once
  someone's on the network, they can freely reconfigure the device
  from any browser.
- **The TCP command port (4000) and OTA port (8080) are equally
  open** — no auth on `SET`/`SETV`/`SETA`/`GET`/`GET_CONFIG`, and
  critically, **no auth on OTA firmware flashing** — anyone on the
  network could push arbitrary firmware to the device. This is
  arguably the more serious exposure of the two, even though the web
  UI is the more *obvious/inviting* one (a browser is a much lower
  barrier than crafting raw TCP commands or an OTA HTTP upload).
- **If the device is on a shared/home network (STA mode)**, this
  exposure isn't limited to whoever's near the device's own AP —
  anyone else on that same home network can reach all of the above
  too, unless the router itself has client isolation enabled.

**Reasonable direction for eventually addressing this** — roughly in
order of effort vs. value, not a commitment to build all of it:
1. A user-settable AP password (already possible technically — the
   password is just a hardcoded string — but worth exposing as an
   actual setting rather than leaving the well-known default in place).
2. Simple shared-secret protection on the web UI and OTA endpoints
   (HTTP Basic Auth, or a plain password field checked against a
   stored value) — lightweight, doesn't need real infrastructure,
   similar effort to the hand-rolled HTTP handling already built for
   OTA/the web UI. This is the "good enough to deter casual/opportunistic
   access" bar, not a full security posture — proportionate to what
   this actually is (a hobbyist device on a local network), not
   something that needs session tokens/CSRF protection/etc.
3. A confirmation step before destructive web UI actions (deleting a
   function) — small, cheap, worth doing regardless of the auth
   question above.



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

- **v16 flash reliability — observed, not root-caused.** On first flash
  of v16, GPIO 4 (an LED wired for testing) came up driven HIGH at
  boot, and the AP wasn't broadcasting at all (unreachable from both
  the app and the web UI) — several power cycles later, it started
  working normally, including the new Live Controls feature. **v15 was
  confirmed working immediately beforehand**, isolating this to v16
  specifically rather than a hardware/wiring issue.
  Reviewed the v15→v16 diff carefully looking for a cause — it's
  confined entirely to `webui.h` (`StringPrint` class, two forward
  declarations, `/api/trigger`, the Live Controls frontend) and doesn't
  touch `pinMode`/`digitalWrite` on any pin, let alone GPIO 4
  specifically. No obvious "here's the line" bug found by static
  review alone. **Genuinely inconclusive** — no Serial Monitor boot log
  was captured during the bad boots, so there's no actual evidence
  distinguishing between a few real possibilities: a corrupted/
  interrupted flash write (unrelated to the code itself), a boot-order
  race in WiFi/I2S peripheral initialization that happens to resolve
  after a cold power cycle, or something in v16 genuinely not yet
  understood. Logged as observed-but-unexplained rather than assumed
  fixed just because it stopped recurring — if it happens again
  (especially on a fresh flash of any future version), a Serial
  Monitor boot log captured at 115200 baud during the bad boot would
  be the single most useful thing to actually diagnose this, rather
  than guessing against the source again.

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
