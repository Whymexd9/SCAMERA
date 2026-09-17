# Vivo backend status

This change adds the backend selection UI and an on-device native loader
probe. It does NOT yet implement stock remosaic processing. SCAMERA is the
only executable backend; selecting the labelled unavailable Vivo entry
shows the reason and does not persist that selection. Unexpected backend
values imported from configuration are rejected at RemosaicCore, never
silently interpreted as SCAMERA.

The explicit probe button loads the device's own vendor wrapper and HP3
library on an ARM64 worker thread, then checks the six known wrapper symbols.
The supplied Vivo adapter explicitly uses this HP3 integration for HP9.
No proprietary binaries are bundled. Loading can fail because of Android
linker namespace restrictions or missing dependencies; the exact loader
error is displayed and can be copied. Success establishes only loading and
symbol presence, not a compatible processing ABI or supported ISZ mode.
No remosaic entry point is called. As with any dlopen, library constructors
may run. Successful handles stay loaded for process lifetime.

To advance the integration on the HP9 phone:
1. Enable Full debug, open remosaic settings and press the Vivo check button.
2. Copy the result or retrieve Download/SCAMERA/SCAMERA-debug.log.
3. Capture a normal still with the existing telephoto Tetra 4x4 / 4x ISZ
   configuration to also collect the calibration metadata probe from #9.

Still unresolved: exact usable calibration buffers (adapter lengths 9216
and 38272), native processing ABI/packing/CFA enums, and selection of the
already-cropped 4080x3072 4x ISZ mode. No zero-filled calibration or guessed
process structs are passed into proprietary code. Enabling Vivo processing
requires resolving these gates and testing it on the device.
