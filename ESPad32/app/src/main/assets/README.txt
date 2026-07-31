espad_default_firmware.bin is in place — this is what "Flash Default"
flashes from the OTA screen or Settings > OTA tab.

IMPORTANT: this is a static snapshot, not auto-rebuilt from source.
Whenever the .ino/.h firmware files change, this .bin needs to be
manually recompiled (Arduino IDE: Sketch > Export Compiled Binary) and
replaced here, or "Flash Default" will silently offer outdated
firmware. No automated check currently catches this drift — worth
remembering by habit when bumping FW_VERSION in the sketch.

