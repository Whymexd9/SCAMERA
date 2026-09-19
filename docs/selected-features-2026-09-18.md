# Selected camera features and viewfinder repair — source changes

User selection: 1 RAW live viewfinder, 3 favourites, 4 variable-step ruler,
6 DCP matrices, 8 lens feedback. The user's later report that both live
viewfinder modes fail takes priority. No APK assembly or GitHub push is authorized
for this turn; a push would launch Actions.

## Viewfinder defects corrected

- RAW rotated texture coordinates around (0,0); at 180 degrees clamping reduced
  the whole image to one Bayer cell. Vertex rotation and sensor UV mapping now
  match the regular preview; front-camera mirroring uses the same axis.
- The scene meter disabled vertex arrays that the main renderer assumed were
  still enabled. Each main draw now restores its program, OES binding and arrays.
- External texture initialization used GL_TEXTURE_2D instead of the OES target,
  leaving GL errors that RAW upload could misinterpret as its own permanent failure.
- RAW and scene-meter resources now reset on EGL-context recreation. New RAW
  sessions reset exposure smoothing and permit recovery from a prior upload failure.
- Live tone curves are display-domain data. Removed a second gamma decode and
  switched their storage from filterable-only-with-extension R32F to core R16F.
- RAW stream enablement belongs to the capture session, including dual-session
  preview output configuration. Video/unlimited paths do not request an unconfigured
  RAW preview surface. A rejected optional RAW preview retries the regular session.
- RAW publication requests a draw independently of ISP frame arrival. Stale RAW
  frames fall back to ISP rather than remaining indefinitely on screen. Session
  generations discard copies that finish after disable/module changes.
- Outside ZSL, preview/still RAW images are paired with capture-start timestamps,
  in either callback order. A shutter press no longer turns queued preview images
  into still images. Pending images are bounded and closed on teardown.
- RAW image dimensions/stride/available bytes and CFA are checked before upload.
  Metadata uses the physical camera where available; crop information is carried
  to the shader. Shading upload reuses its buffer.

The RAW path still uses SCAMERA's GPU renderer, not the uploaded host-dependent
.so. The Camera2 stream is RAW_SENSOR (16-bit), not a newly negotiated RAW10/12
stream. Preview is approximate; it cannot show burst denoise, HDR merge or the
complete final processing pipeline. Hardware/device validation remains necessary.

## Selected additions

- Favourites use the production preference catalogue (switches, lists, numeric
  controls). Selection/order are global; values follow the active module.
  Access: star above the manual-controls row; selection also under viewfinder/
  interface settings. Changes are confirmed in a dialog, then refresh the camera
  session. Unavailable controls retain explanatory dependency messages.
- Numeric controls, including their normal settings pages, use a relative ruler
  with fling, haptic ticks, editable exact value and reset. Decimal steps:
  0.001 / 0.01 / 0.1 / 1; integer controls: 1 / 10 / 100. Changing step alone
  does not rewrite the value. Persistence preserves original string/float/int types.
- DCP imports copy into private storage after bounded TIFF/DCP parsing. Both
  byte orders, signed/unsigned rationals, dual illuminants, ColorMatrix and
  ForwardMatrix are supported. Matrix interpolation uses neutral-derived CCT;
  ColorMatrix-only profiles use Bradford adaptation to D50. The same selected
  matrix feeds capture and RAW preview (with appropriate WB/matrix storage order).
  No DCP look tables, hue/saturation tables or tone curves are imported. Selection
  is per-module and included in the existing copy-settings catalogue.
- Lens buttons survive selection refreshes so their 220 ms pulse is visible;
  final dimensions stay unchanged. Haptics obey system settings. Rotation takes
  the shortest path and new buttons inherit current orientation.

## Checks performed without an APK build

- Standalone javac compilation of all 23 modified/new production Java classes,
  against the cached Android/project/dependency classpath: passed.
- 13 standalone JUnit tests: malformed DCP offsets/truncation/IFD cycles/zero
  denominators, both byte orders and magic signatures, neutral/WB matrix handling,
  RAW buffer ownership/validation/session reset, timestamp image routing and bounds.
- `python tools/checks/preview_gl.py`: actual headless GLES3 shader compilation,
  link and rendering. A 4-cell synthetic Bayer input at 180 degrees retains four
  distinct output luminances rather than collapsing to a single point.
- Modified resource XML parsing and git diff whitespace check: passed.

No Gradle build, APK, Actions run or device claim. On-device follow-up must check
both switches, return from gallery/settings, module changes, capture while RAW
preview is enabled, DCP import/copy, favourites, and ruler interaction. The user's
precise symptom (black/frozen preview versus no settings response) has been asked
but was not available during these source changes.

## Single live-viewfinder switch

Follow-up request: use one switch, analogous to the supplied libvf_demosaic.so
bridge. DemosaicProxy.tryProcessRawImage checks customDemosaicEnabled, populated
from per-lens pref_custom_demosaic_enable, before calling native processing.

The settings catalogue now exposes one “Живой видоискатель” switch. Its existing
RAW key is retained, preserving module profiles and copy-settings behaviour.
On enables RAW development; off or unavailable RAW leaves the ordinary ISP
preview. The old independent ISP tone-approximation preference is removed from
the catalogue and ignored at runtime, including old backups and module profiles.
This implements the same enable/bypass principle using SCAMERA's RAW renderer;
it does not load the supplied .so or claim identical native image processing.
No APK or Actions run for this follow-up.
