# Camera resume / first shutter after settings — 30210

Reported: the first shutter after editing a setting restarts the camera; subsequent shots work. Available older logs do not establish the exact crash site. These changes address concrete session lifecycle races found during inspection, not a device-confirmed root cause.

- Close the camera before pausing the GL view and releasing its preview texture.
- Invalidate preview state under the same lock as preview callbacks and shutter submission. Reject callbacks from old sessions and obsolete session configurations after reopening.
- Clear queued ZSL images and timestamp results before replacing/closing their reader. Reject late frames; independently close the RAW reader even if there is no preview reader.
- Restart through the normal output-preparation/open path. Previously restart opened the camera with a cleared background handler before creating replacement readers.
- Require current-session metadata before shutter submission, and reject empty ZSL captures before entering image processing. A press during initial camera preparation restores the shutter UI and requests a retry rather than submitting stale state.
- Handle absent FLASH_STATE without null-unboxing.

Validation: CameraResumeTest exercises stale callbacks, image-before-reader release, readiness followed by request submission, and restart ordering. Added to the Actions APK gate. Local Robolectric run: 4 passed. Physical-device verification remains required.

Device check: edit an ordinary processing setting, return and take a photo; repeat several times, on different modules, with ZSL and live RAW both enabled and disabled. Verify the first ready shot saves without a camera/app restart. If it still restarts, collect a new log including the settings return and first shutter.

SoftPQE processing and slider algorithms are unchanged in this build; the report of ineffective noise controls still needs fresh device logs and paired original images.
