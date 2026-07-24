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

## Near-term: board selection

- [ ] Board picker dropdown in the Pin Mapper UI (currently board is
      implied by the profile; `DeviceProfile.boardKey` already exists
      as the seam for this)
- [ ] Add more `BoardDef` entries: plain ESP32 DevKit, ESP8266 NodeMCU,
      others as needed
- [ ] Arduino support is a bigger question than "add a BoardDef" —
      Arduino boards have fewer pins, no native WiFi/BLE, different PWM
      behavior, and likely a different transport (Arduino boards often
      need a companion WiFi/BLE module, e.g. ESP-01 or HC-05, rather
      than talking to the phone directly). Decide whether v1 stays
      ESP32-only before committing to Arduino support.

## Near-term: renaming & custom roles

- [ ] Relabel an existing role (e.g. "Motor direction A" → "Left wheel
      forward") — straightforward, just an editable text field per role
- [ ] Add a brand-new role the app doesn't know about yet (e.g. "Fog
      machine trigger") — needs each role to declare a **type**
      (digital-out, PWM, servo, I2C, analog-in, sensor-input) so pin
      validation can filter correctly per role automatically, instead
      of the current approach where "requires output" is hardcoded
      per-call in `PinValidation.canAssign`

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

- Does "any ESP or Arduino powered device" imply supporting arbitrary
  *sensors* (reading data back), or stay focused on *actuators* (motors,
  servos, lights, audio) like the current two profiles?
- How much of the custom-device flow needs firmware code generation
  (e.g. the app produces a firmware sketch, not just a config payload)
  vs. assuming the user already has firmware that speaks a generic
  protocol?
- Should custom boards/devices be phone-local only, or shareable
  (a small community library of profiles)?
