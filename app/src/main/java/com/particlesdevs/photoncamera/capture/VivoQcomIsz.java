package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P122 Sensor modes of the vivo X300 Ultra (owner, 2026-10-13: «Я даю тебе разрешение переключать сенсормоды», «На виво на
 * снапах сенсормоды можно переключать с помощью vivo.control.forceSensorMode реквеста», and the table of its working modes).
 * <p>
 * vivo.control.forceSensorMode works there as a SESSION key (as a request key alone the HAL does not take it): the modules carry
 * it as a «Session» vendor key (VendorTagUtils.TunableKey.TYPE_SESSION, set in the session parameters, not in the requests).
 * The table (14 mm ultra-wide / 35 mm main / 85 mm tele): 2x ISZ as a Quad mosaic = UW 21, main 10, tele 5; tele 4x ISZ «Quad
 * Quad Bayer» (Tetra) = 7; DCG 14 bit «Open Gate» = UW 11, main 17, tele 20 (owner: ISZ and DCG on all three modules).
 * Modules offered once each (a deleted one stays deleted; the keys are editable in the module settings): «2×» of the main, the
 * UW and the tele (7.4×), «15×» (4x) of the tele, all without remosaic (owner: «Isz используй без ремозаика»: the Quad /
 * Tetra mosaics, not the remosaiced 6 / 31); DCG 14 bit of the three by the manual strip's DCG button (Dcg14Button). The Hybrid merges the mosaics
 * natively (their colour block is measured), P121 sets their white balance; a forced sensor mode is a sensor crop, so the frame is
 * not cropped again. The camera role comes from com.vivo.chi.override.CameraType («Master», «Wide», «Tele3P5x»).
 * scam_dev "vivo_qcom_roi 0": no new modules.
 * <p>
 * P126 (X300 Ultra video / log 2026-10-14: «ISZ модули дублируются и не удаляются через настройки»): an offer is made once
 * whether or not a slot is free (a module the strip had no room for came back as soon as the owner deleted another one), a
 * module already on the strip (same camera, same forced mode) is not made again, and the extras are removed: a second ISZ
 * module of the same camera and mode, and once the plain «2×» crop the CROPPED_RAW module (IszModule) put next to the ISZ one.
 * <p>
 * P214i (owner's list, 2026-10-18: «Quad Bayer это без ремозаика»; forceSensorMode a SESSION key on the X300 Ultra, never a
 * request key): the list is {@link #MODES} (lens -> camera by the role: 14 mm ultra-wide = camera 4, 35 mm main = 3, 85 mm tele = 5
 * in x300u_camera.txt, focal 4.03 / 11.56 / 22.48 mm = 14.5 / 36.4 / 88.6 mm equivalent). The modules are its 4:3 Quad / Tetra
 * rows (UW 21, main 10, tele 5 at 2x, tele 7 at 4x), their declared colour block drives the native mosaic merge
 * ({@link #declaredBlock}); the tele's video Quad rows 10 / 13 / 17 are a mosaic too (an owner-made module merges them natively)
 * but no photo module, the remosaiced 6 / 31 declare nothing. The 14-bit DCG rows of the 14 / 35 mm lenses turn highlights pink
 * (owner): no module of them; a camera known by its focal length only gets the DCG button on the tele ({@link #dcgMode}).
 * Transport, checked against the owner's logs: build 30912 (SCAMERA-debug.log (8), 13:05:09) sent mode 5 / 7 as an Integer
 * session key only (the same int32 a one-element int[] is), next to a PRIVATE viewfinder and a RAW stream with the CROPPED_RAW
 * use case (P100) - the HAL's own in-sensor zoom by the zoom ratio, which may remosaic the cropped sensor; the tele streamed a
 * plain Bayer (block 1, raw crop x1.0), while the main's mode 10 reported a x2.0 raw crop (log-2026-10-14, 06:59:09). A module
 * that forces a sensor mode sets the session key (P274: repeated on every request) and tries the RAW stream of the default use case
 * first, then the 30912 stream ({@link #croppedRawAllowed}, {@link Trial}; scam_dev "vivo_qcom_isz_cropped_raw 1 / 0" fixes
 * it). Its frames are checked ({@link StreamCheck}): a plain Bayer where the row declares a mosaic is said in the log, the next
 * stream is tried, and on both it falls back - Bayer merge, the button's zoom as a crop - noted per camera, mode and
 * Build.FINGERPRINT; a later session whose frames are the mosaic moves the note on.
 * <p>
 * P274 (owner's log 2026-10-19, build 31093: every ISZ session streamed a plain Bayer at the normal sensor mode index 2, «isz не
 * работает на X300 Ultra ... просто через реквест vivo.control.forceSensorMode»): the key (0x811b0126) is one of the camera's
 * android.request.availableSessionKeys (x300u_camera.txt), and the camera service compares every request's session keys with the
 * session parameters - a request without it removes the key and reconfigures the session at the normal mode. The forceSensorMode
 * of the session parameters ({@link #sessionBuilt}: ISZ module or DCG) is now repeated on every request ({@link #mirrorRequest}),
 * as MotionCam's session keys reach the HAL. The trials of the session-only transport are void ({@link #stamp}).
 */
public final class VivoQcomIsz {
    private static final String TAG = "VivoQcomIsz";
    static final String KEY = "vivo.control.forceSensorMode";
    private static final String OFFERED = "module_vivo_qcom_roi_offered_";
    private static final String CLEANUP = "module_vivo_qcom_roi_cleanup_1";

    static final int MAIN_2X = 10, WIDE_2X = 21, TELE_2X = 5, TELE_4X = 7;

    /** P214i: what a sensor mode of the owner's list is. */
    enum Kind {
        /** 2x ISZ, the sensor's own Quad mosaic (no remosaic): an ISZ module, merged natively at block 2. */
        QUAD_2X,
        /** 4x ISZ «Quad-Quad» (Tetra, no remosaic): an ISZ module, merged natively at block 4. */
        TETRA_4X,
        /** 2x ISZ Quad of a video size or rate (16:9, 60 / 120 fps): a Quad mosaic, no photo module. */
        QUAD_2X_VIDEO,
        /** ISZ the HAL remosaics into a plain Bayer: never used as «without remosaic». */
        REMOSAICED
    }

    /** P214i: one mode of the owner's list: the camera role, its forceSensorMode, the zoom it gives and what it is. */
    static final class Row {
        final String role;
        final int mode, factor;
        final Kind kind;
        final String what;

        Row(String role, int mode, int factor, Kind kind, String what) {
            this.role = role;
            this.mode = mode;
            this.factor = factor;
            this.kind = kind;
            this.what = what;
        }

        /** The colour block of its frames: Quad 2, Tetra 4, a remosaiced mode 1 (plain Bayer). */
        int block() {
            return kind == Kind.TETRA_4X ? 4 : kind == Kind.REMOSAICED ? 1 : 2;
        }

        /** Offered as a photo module: the 4:3 Quad / Tetra modes; not the video sizes, not the remosaiced ones. */
        boolean module() {
            return kind == Kind.QUAD_2X || kind == Kind.TETRA_4X;
        }
    }

    /**
     * P214i: the owner's X300 Ultra list (2026-10-18). Sizes in the camera's own SensorModeTable (x300u_camera.txt, Android 17
     * PD2547BF_EX_A_17.1.6.36.W20): UW 21 = 4096x3072@30, main 10 = 4080x3064@30, tele 5 / 7 / 6 / 31 = 4080x3072@30, tele 10 =
     * 4080x2616@30, 13 = 4080x2296@120, 17 = 4080x2616@60.
     */
    static final Row[] MODES = {
            new Row("wide", WIDE_2X, 2, Kind.QUAD_2X, "2x ISZ Quad Bayer"),
            new Row("main", MAIN_2X, 2, Kind.QUAD_2X, "2x ISZ Quad Bayer"),
            new Row("tele", TELE_2X, 2, Kind.QUAD_2X, "2x ISZ Quad Bayer"),
            new Row("tele", TELE_4X, 4, Kind.TETRA_4X, "4x ISZ Quad-Quad (Tetra)"),
            new Row("tele", 10, 2, Kind.QUAD_2X_VIDEO, "16:9 2x ISZ Quad"),
            new Row("tele", 13, 2, Kind.QUAD_2X_VIDEO, "wide 2x ISZ 120 fps Quad"),
            new Row("tele", 17, 2, Kind.QUAD_2X_VIDEO, "16:9 2x ISZ 60 fps Quad"),
            new Row("tele", 6, 4, Kind.REMOSAICED, "4x ISZ remosaiced"),
            new Row("tele", 31, 2, Kind.REMOSAICED, "2x ISZ remosaiced"),
    };

    /** The row of {@code mode} on a camera of {@code role}, null when the list has none. */
    @Nullable
    static Row row(@Nullable String role, int mode) {
        if (role == null) return null;
        for (Row r : MODES) if (r.role.equals(role) && r.mode == mode) return r;
        return null;
    }

    /** The ISZ modules of a camera role, in the order they are offered (2x before 4x). */
    static List<Row> modules(@Nullable String role) {
        List<Row> out = new ArrayList<>();
        for (Row r : MODES) if (r.module() && r.role.equals(role)) out.add(r);
        return out;
    }

    /** An ISZ module mode of the list (no remosaic, a photo size) for a camera of {@code role}. */
    static boolean iszModule(@Nullable String role, int mode) {
        Row r = row(role, mode);
        return r != null && r.module();
    }
    /** DCG 14 bit «Open Gate» (the full 4:3 field) of each camera (owner: «ISZ и DCG доступен у 300 ультра на всех трех модулях»). */
    static final int MAIN_DCG = 17, WIDE_DCG = 11, TELE_DCG = 20;
    /** P229: where the 14 / 35 mm DCG modes clip (our 2026-10-15 log; rawr's X300 Ultra profile: white 8712, black 1024). */
    static final int DCG_CLIP = 8694;

    private VivoQcomIsz() {}

    /**
     * vivo X300 Ultra of any region (owner: «для любого региона»): PD2547 / V2547A / V2547DA (CN, satellite), V2562 (global), or
     * any vivo whose market name (ro.vivo.market.name) says X300 Ultra.
     */
    public static boolean device() {
        return x300Ultra(android.os.Build.MANUFACTURER, android.os.Build.DEVICE, android.os.Build.MODEL, marketName());
    }

    static boolean x300Ultra(@Nullable String manufacturer, @Nullable String device, @Nullable String model, @Nullable String market) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("vivo")) return false;
        String d = device == null ? "" : device.toUpperCase(Locale.ROOT), m = model == null ? "" : model.toUpperCase(Locale.ROOT);
        if (d.startsWith("PD2547") || d.startsWith("PD2562") || d.equals("V2562") || m.startsWith("V2547") || m.startsWith("V2562")) return true;
        return market != null && market.toUpperCase(Locale.ROOT).replace(" ", "").contains("X300ULTRA");
    }

    private static volatile String sMarket;

    @Nullable
    static String marketName() {
        String m = sMarket;
        if (m != null) return m;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            m = (String) sp.getMethod("get", String.class, String.class).invoke(null, "ro.vivo.market.name", "");
        } catch (Exception e) {
            m = "";
        }
        sMarket = m;
        return m;
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("vivo_qcom_roi", true);
    }

    /** The camera's role from its vivo CameraType: "main", "wide", "tele" or null. */
    @Nullable
    static String role(@Nullable String cameraType) {
        if (cameraType == null) return null;
        String t = cameraType.trim().toLowerCase(Locale.ROOT);
        if (t.contains("external") || t.equals("dummy")) return null;
        if (t.equals("master") || t.equals("main")) return "main";
        if (t.equals("wide") || t.startsWith("uw") || t.contains("ultrawide")) return "wide";
        if (t.startsWith("tele")) return "tele";
        return null;
    }

    /**
     * P214i: the role of a back camera by its 35 mm-equivalent focal length, the owner's lens names: 14 mm ultra-wide, 35 mm main,
     * 85 mm tele (X300 Ultra: 14.5 / 36.4 / 88.6 mm); null between them.
     */
    @Nullable
    static String roleByFocal(float equivalent35) {
        if (!(equivalent35 > 0f)) return null;
        if (equivalent35 < 20f) return "wide";
        if (equivalent35 >= 28f && equivalent35 <= 50f) return "main";
        if (equivalent35 >= 70f && equivalent35 <= 110f) return "tele";
        return null;
    }

    /** The 35 mm-equivalent focal length as CameraManager2 gives it to the lens list: 36 mm over the sensor width. */
    static float equivalent35(float focalMm, float sensorWidthMm) {
        return focalMm > 0f && sensorWidthMm > 0f ? 36f / sensorWidthMm * focalMm : 0f;
    }

    /** The role of a camera that lists no vivo CameraType: by its focal length if it faces back, else null. */
    @Nullable
    static String role(@Nullable String cameraType, @Nullable Integer facing, @Nullable float[] focal, float sensorWidthMm) {
        if (cameraType != null && !cameraType.trim().isEmpty()) return role(cameraType);
        if (facing == null || facing != android.hardware.camera2.CameraMetadata.LENS_FACING_BACK || focal == null || focal.length == 0)
            return null;
        return roleByFocal(equivalent35(focal[0], sensorWidthMm));
    }

    /** Camera id -> role ("" none) as {@link #role(String, android.hardware.camera2.CameraCharacteristics)} found it. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> ROLES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * P214i: the camera's role from its vivo CameraType, else (a camera that does not list com.vivo.chi.override.CameraType: the
     * Android 17 dump of the X300 Ultra, x300u_camera.txt, has the tag defined but on no camera) from its focal length; remembered
     * per camera id for {@link #declaredBlock}.
     */
    @Nullable
    public static String role(@Nullable String cameraId, @Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (c == null) return null;
        String role;
        try {
            android.util.SizeF size = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            role = role(com.particlesdevs.photoncamera.api.CameraManager2.vivoCameraType(c),
                    c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING),
                    c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                    size == null ? 0f : size.getWidth());
        } catch (RuntimeException e) {
            role = null;
        }
        if (cameraId != null && !cameraId.isEmpty()) ROLES.put(cameraId, role == null ? "" : role);
        return role;
    }

    /** P214i: the session's camera is known (its role is remembered for the merge's declared block). */
    public static void learn(@Nullable String cameraId, @Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (device() && cameraId != null && !ROLES.containsKey(cameraId)) role(cameraId, c);
    }

    /**
     * P214i: the row {@code mode} of the camera's table (org.quic.camera2.sensormode.info.SensorModeTable: count, values per mode,
     * then width, height, fps) has the size of the camera's RAW stream; true when the table is unreadable (the owner's list is
     * the source then). A firmware whose rows moved does not get a module of another size.
     */
    static boolean rowFits(@Nullable int[] table, int mode, int rawWidth, int rawHeight) {
        if (table == null || table.length < 2) return true;
        final int count = table[0], stride = table[1];
        if (stride < 3 || mode < 0 || mode >= count || table.length < 2 + (mode + 1) * stride) return false;
        return table[2 + mode * stride] == rawWidth && table[3 + mode * stride] == rawHeight;
    }

    /** The row's size and rate as the log says it ("4080x3072@30"), "none" outside the table, "?" without one. */
    static String rowText(@Nullable int[] table, int mode) {
        if (table == null || table.length < 2) return "?";
        final int count = table[0], stride = table[1];
        if (stride < 3 || mode < 0 || mode >= count || table.length < 2 + (mode + 1) * stride) return "none";
        return table[2 + mode * stride] + "x" + table[3 + mode * stride] + "@" + table[4 + mode * stride];
    }

    @Nullable
    private static int[] modeTable(android.hardware.camera2.CameraCharacteristics c) {
        try {
            return c.get(new android.hardware.camera2.CameraCharacteristics.Key<>(OppoNativeFullRes.TABLE_KEY, int[].class));
        } catch (RuntimeException noTag) {
            return null;
        }
    }

    @Nullable
    private static android.util.Size rawSize(android.hardware.camera2.CameraCharacteristics c) {
        try {
            android.hardware.camera2.params.StreamConfigurationMap map =
                    c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR);
            android.util.Size best = null;
            if (sizes != null) for (android.util.Size s : sizes)
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
            return best;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P214i: rows not offered for want of a fitting size, logged once per process. */
    private static final java.util.Set<String> UNFIT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Creates the ISZ modules once each. */
    public static void ensureModules(@Nullable Context context) {
        if (context == null || !enabled()) return;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            removeExtras(p);
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot) || ModuleRegistry.duplicateOf(slot) != null) continue;
                String camera = ModuleRegistry.camera(slot);
                if (camera == null || camera.isEmpty() || camera.contains("-") || ModuleRegistry.sensorMode(slot) != 0) continue;
                android.hardware.camera2.CameraCharacteristics c;
                try {
                    c = manager.getCameraCharacteristics(camera);
                } catch (Exception e) {
                    continue; // a slot of another camera list
                }
                String role = role(camera, c);
                if (role == null) continue;
                final int[] table = modeTable(c);
                final android.util.Size raw = rawSize(c);
                float z = ModuleRegistry.zoom(slot);
                for (Row r : modules(role)) {
                    if (raw != null && !rowFits(table, r.mode, raw.getWidth(), raw.getHeight())) {
                        if (UNFIT.add(camera + "_" + r.mode))
                            Log.w(TAG, "no " + r.factor + "x ISZ module of camera " + camera + " (" + role + "): row " + r.mode + " of its "
                                    + "sensor-mode table is " + rowText(table, r.mode) + ", its RAW " + raw.getWidth() + "x" + raw.getHeight()
                                    + " (the firmware's rows moved?)");
                        continue;
                    }
                    offer(context, p, slot, camera, r.factor + "x", r.mode, z * r.factor);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "sensor-mode modules not created: " + e);
        }
    }

    // ---- P214i: the module's colour block, its frame check and the fallback of a mode the camera did not run

    /**
     * P214i: the colour block a module's forced mode declares (2 Quad, 4 Tetra, 0 none; the merge goes native at once). On the
     * X300 Ultra by the list's row of the camera's role ({@link #MODES}: UW 21 / main 10 / tele 5 Quad, tele 7 Tetra, the tele's
     * video Quad rows; the remosaiced 6 / 31 and any other mode none) and 0 once the frames of that mode proved a plain Bayer on
     * both RAW streams on this firmware ({@link Trial#BAYER}: a declared mosaic wins over a stored block 1 in
     * MosaicBlockStore.choose, the proved Bayer must not merge as one); a camera whose role is not known yet, and any other
     * phone, keep MosaicBlockStore's rule (5 -> 2, 7 -> 4).
     */
    public static int declaredBlock(@Nullable String camera, int mode) {
        if (mode <= 0) return 0;
        final String role = camera == null || !device() ? null : ROLES.get(camera);
        if (role == null) return com.particlesdevs.photoncamera.processing.MosaicBlockStore.declaredBlock(mode);
        return declaredBlock(row(role, mode), bayerFallback(camera, mode));
    }

    /** {@link #declaredBlock(String, int)} of a row: its mosaic block unless its frames proved a plain Bayer. */
    static int declaredBlock(@Nullable Row row, boolean provedPlain) {
        return row == null || row.block() < 2 || provedPlain ? 0 : row.block();
    }

    /** {@link #declaredBlock(String, int)} of a module slot. */
    public static int declaredBlockOf(@Nullable String slot) {
        try {
            return declaredBlock(ModuleRegistry.camera(slot), ModuleRegistry.sensorMode(slot));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** scam_dev line that fixes the RAW stream of the X300 Ultra's forced-mode modules (1 CROPPED_RAW, 0 the default use case). */
    static final String CROPPED_RAW_KEY = "vivo_qcom_isz_cropped_raw";

    /**
     * P214i: the RAW stream of this session may take the CROPPED_RAW use case (P100). For the X300 Ultra's active module on
     * {@code physicalId} that forces a sensor mode: first the default use case - the forced mode is the sensor crop, CROPPED_RAW
     * hands the in-sensor zoom (and its remosaic) to the HAL by the zoom ratio, and the owner's working route (MotionCam: a session
     * key and a plain RAW stream) has none - then, once those frames were a plain Bayer on this firmware ({@link #trial}), the
     * CROPPED_RAW stream of build 30912. scam_dev "vivo_qcom_isz_cropped_raw 1 / 0" fixes the stream (an A/B on the phone).
     */
    public static boolean croppedRawAllowed(@Nullable String physicalId) {
        try {
            if (!device() || physicalId == null) return true;
            final String slot = ModuleRegistry.active();
            final int mode = ModuleRegistry.sensorMode(slot);
            if (!physicalId.equals(ModuleRegistry.camera(slot)) || mode <= 0) return true;
            if (PreferenceKeys.scamDevOverrides("vivo_qcom_isz_cropped_raw"))
                return PreferenceKeys.scamDevSwitch("vivo_qcom_isz_cropped_raw", false);
            // P275: CROPPED_RAW first, as build 30906 ran the modules (owner: ISZ worked there); the default stream second
            final Trial t = trial(physicalId, mode);
            return t != Trial.PLAIN && t != Trial.DEFAULT;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * P214i: what this firmware's frames of an ISZ mode said ({@link StreamCheck}). P275 (owner: ISZ ran on build 30906, whose
     * modules streamed CROPPED_RAW from the first session): NONE: not measured, the CROPPED_RAW stream first. PLAIN: a plain Bayer
     * on CROPPED_RAW - the camera restarts with the default RAW stream, as a FULLRES row the HAL remosaicked makes the next start
     * try the next row (P188). CROPPED: the mosaic with CROPPED_RAW (that stream stays). DEFAULT: the mosaic on the default stream
     * (that stream stays). BAYER: a plain Bayer on both: the module falls back - Bayer merge
     * ({@link #declaredBlock} 0) and the button's zoom as a crop (ModuleRegistry.sensorCrop false: digital, divided by the crop the
     * HAL's CROPPED_RAW frames say they hold, ZoomController.rawCropOfShot), said on the message card once.
     */
    public enum Trial { NONE, PLAIN, CROPPED, BAYER, DEFAULT }

    /** The trial after a session's verdict; {@code croppedRaw}: its RAW stream had CROPPED_RAW; {@code fixed}: scam_dev fixes it. */
    static Trial next(Trial now, StreamCheck.Verdict verdict, boolean croppedRaw, boolean fixed) {
        if (verdict == StreamCheck.Verdict.MOSAIC) return croppedRaw ? Trial.CROPPED : Trial.DEFAULT;
        if (verdict != StreamCheck.Verdict.PLAIN) return now;
        // the default stream's Bayer tries CROPPED_RAW next; a second Bayer (or no other stream to try) is the fallback
        // P275: the first stream is CROPPED_RAW (build 30906's); its Bayer tries the default stream, a second Bayer falls back
        return now == Trial.NONE && croppedRaw && !fixed ? Trial.PLAIN : Trial.BAYER;
    }

    /** The trials of the ISZ modes (camera, mode -> "Build.FINGERPRINT|trial"): an own file, not a setting. */
    private static final String NOTES = "vivo_qcom_isz";

    static String noteKey(String camera, int mode) {
        return "isz_" + camera + "_" + mode;
    }

    static String noteValue(@Nullable String fingerprint, Trial trial) {
        return (fingerprint == null ? "" : fingerprint) + "|" + trial.name();
    }

    /** A note counts on the firmware it was made on only (an update may run the mode: it is tried again from NONE). */
    static Trial noted(@Nullable String note, @Nullable String fingerprint) {
        if (note == null) return Trial.NONE;
        final int bar = note.lastIndexOf('|');
        if (bar <= 0 || !note.substring(0, bar).equals(fingerprint == null ? "" : fingerprint)) return Trial.NONE;
        try {
            return Trial.valueOf(note.substring(bar + 1));
        } catch (IllegalArgumentException unknown) {
            return Trial.NONE;
        }
    }

    @Nullable
    private static SharedPreferences notes() {
        try {
            Context context = ScameraApp.getAppContext();
            return context == null ? null : context.getSharedPreferences(NOTES, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P214i: this firmware's trial of {@code mode} on {@code camera} (NONE off the X300 Ultra). */
    public static Trial trial(@Nullable String camera, int mode) {
        if (camera == null || mode <= 0 || !device()) return Trial.NONE;
        SharedPreferences n = notes();
        if (n == null) return Trial.NONE;
        try {
            return noted(n.getString(noteKey(camera, mode), null), stamp());
        } catch (ClassCastException e) {
            return Trial.NONE;
        }
    }

    /** P214i: the frames of the mode proved a plain Bayer on both RAW streams of this firmware: Bayer merge, the zoom as a crop. */
    public static boolean bayerFallback(@Nullable String camera, int mode) {
        return trial(camera, mode) == Trial.BAYER;
    }

    private static void store(String camera, int mode, Trial trial) {
        SharedPreferences n = notes();
        if (n == null) return;
        if (trial == Trial.NONE) n.edit().remove(noteKey(camera, mode)).apply();
        else n.edit().putString(noteKey(camera, mode), noteValue(stamp(), trial)).apply();
    }

    /**
     * What a note counts on: the firmware and the camera package the camera service sees (owner, 2026-10-19, the X300U table:
     * «forceSensorMode works ... as a session key»; vivo's sensor-mode selector goes by the app's AppType - our CTS package is
     * AppType 21 - so another package of «Подмена пакета» tries the modes again).
     */
    static String stamp() {
        String spoof = "off";
        try {
            final Context c = ScameraApp.getAppContext();
            if (c != null) spoof = com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof.signature(c);
        } catch (RuntimeException ignored) {
            // the firmware alone
        }
        // P274: the trials of the session-only transport (build 31093 and before) do not count for the request copy
        return android.os.Build.FINGERPRINT + "#" + spoof + "#lease";
    }

    /**
     * P214i: an X300 Ultra ISZ module's frame check, one per session: the viewfinder frames MosaicStream measures (P13), a
     * confident plain-Bayer answer with (almost) no mosaic votes three times = the camera did not run the mode as its mosaic
     * ({@code PLAIN}, once); a confident answer of the declared block = it runs ({@code MOSAIC}, once). Other answers say
     * nothing (a flat or dark scene, too few tiles).
     */
    public static final class StreamCheck {
        public enum Verdict { NONE, PLAIN, MOSAIC }

        static final int PLAIN_ANSWERS = 3;
        final String slot, camera, label, role;
        final int mode, expected;
        private int plain;
        private boolean done;

        StreamCheck(String slot, String camera, String label, String role, int mode, int expected) {
            this.slot = slot;
            this.camera = camera;
            this.label = label;
            this.role = role;
            this.mode = mode;
            this.expected = expected;
        }

        /** One detection of the session's stream: the verdict it completes, NONE before / after. */
        public synchronized Verdict observe(boolean confident, int block, @Nullable int[] votes) {
            if (done || !confident) return Verdict.NONE;
            if (block == expected) {
                done = true;
                return Verdict.MOSAIC;
            }
            if (!plainBayer(true, block, votes) || ++plain < PLAIN_ANSWERS) return Verdict.NONE;
            done = true;
            return Verdict.PLAIN;
        }
    }

    /**
     * P214i: a detection that says plain Bayer beyond doubt: confident block 1, at least 200 tiles voting for it and the mosaic
     * votes (block 2 + 4) at most 2 % of them (build 30912's tele: 15020 / 0 / 1 and 10751 / 0 / 5). A Quad / Tetra stream leaves
     * the Bayer model the colour step between its sites, its tiles do not vote block 1 (P35's flat-scene 1 is a few votes).
     */
    static boolean plainBayer(boolean confident, int block, @Nullable int[] votes) {
        if (!confident || block != 1 || votes == null || votes.length < 3) return false;
        return votes[0] >= 200 && (long) (votes[1] + votes[2]) * 50 <= votes[0];
    }

    /**
     * P214i: the check of the session on {@code physicalId}: the X300 Ultra's active module on that camera forces a mode whose
     * row declares a mosaic (a fallback noted before aside: it is measured again); null otherwise.
     */
    @Nullable
    public static StreamCheck check(@Nullable String physicalId) {
        try {
            if (!device() || physicalId == null) return null;
            final String slot = ModuleRegistry.active();
            if (!physicalId.equals(ModuleRegistry.camera(slot))) return null;
            final int mode = ModuleRegistry.sensorMode(slot);
            final String role = ROLES.get(physicalId);
            final Row r = row(role, mode);
            if (mode <= 0 || r == null || r.block() < 2) return null;
            return new StreamCheck(slot, physicalId, ModuleRegistry.label(slot), role, mode, r.block());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The session's line in the log: what the module asks the camera for and how. */
    public static String describe(StreamCheck c, boolean croppedRaw) {
        final Row r = row(c.role, c.mode);
        return "X300 Ultra ISZ module " + c.slot + " «" + c.label + "»: camera " + c.camera + " (" + c.role + "), " + KEY + " "
                + c.mode + " = " + (r == null ? "?" : r.what) + " as a session key, repeated on every request (P274), RAW stream "
                + (croppedRaw ? "with CROPPED_RAW" : "of the default use case") + ", declared block " + declaredBlock(c.camera, c.mode)
                + ", trial " + trial(c.camera, c.mode)
                + (PreferenceKeys.scamDevOverrides("vivo_qcom_isz_cropped_raw") ? " (stream fixed by scam_dev " + CROPPED_RAW_KEY + ")" : "");
    }

    /** What CaptureController does after a verdict: the message card (null: none), restart the camera, re-apply the zoom. */
    public static final class Outcome {
        public final String card;
        public final boolean restart, zoom;

        Outcome(@Nullable String card, boolean restart, boolean zoom) {
            this.card = card;
            this.restart = restart;
            this.zoom = zoom;
        }
    }

    /**
     * P214i: a verdict of the module's frames ({@code measured}: the detection that completed it; {@code croppedRaw}: the session's
     * RAW stream had CROPPED_RAW): logged, the trial moved on ({@link #next}), what to do now.
     */
    public static Outcome onVerdict(StreamCheck c, StreamCheck.Verdict verdict, String measured, boolean croppedRaw) {
        if (verdict == StreamCheck.Verdict.NONE) return new Outcome(null, false, false);
        final Trial now = trial(c.camera, c.mode);
        final Trial next = next(now, verdict, croppedRaw, PreferenceKeys.scamDevOverrides("vivo_qcom_isz_cropped_raw"));
        final String stream = croppedRaw ? "the CROPPED_RAW stream (build 30912's)" : "the default RAW stream";
        final String what = "ISZ module " + c.slot + " «" + c.label + "», camera " + c.camera + ", " + KEY + " " + c.mode + " (session key)";
        if (verdict == StreamCheck.Verdict.MOSAIC) {
            Log.i(TAG, what + ": the frames are the block-" + c.expected + " mosaic on " + stream + " (" + measured + "): the mode runs"
                    + (next != now ? "; trial " + now + " -> " + next : ""));
        } else {
            Log.w(TAG, what + ": the frames are a plain Bayer on " + stream + " (" + measured + "), not the block-" + c.expected
                    + " mosaic of the mode: the camera did not run it as a mosaic; " + (next == Trial.PLAIN
                    ? "the camera restarts with the default RAW stream (the next trial)"
                    : "fallback: Bayer merge, the button's zoom as a crop (digital unless the HAL's frames hold one)")
                    + " (trial " + now + " -> " + next + ")");
        }
        if (next != now) store(c.camera, c.mode, next);
        final boolean fellBack = next == Trial.BAYER && now != Trial.BAYER;
        final String card = !fellBack ? null : com.particlesdevs.photoncamera.util.Lang.t("«" + c.label
                        + "»: камера не включила ISZ-режим " + c.mode + " (кадры — обычный Bayer), снимаю с кропом зума",
                "«" + c.label + "»: the camera did not run ISZ mode " + c.mode + " (plain Bayer frames), shooting with a zoom crop");
        return new Outcome(card, next == Trial.PLAIN && now != Trial.PLAIN, (now == Trial.BAYER) != (next == Trial.BAYER));
    }

    /** P274: the forceSensorMode of the X300 Ultra's current session parameters, null when they carry none. */
    private static volatile Integer sSessionMode;

    /** P274: a new session is being configured: no mode until its parameters are built. */
    public static void sessionStart() {
        sSessionMode = null;
    }

    /** P274: the session parameters are complete: their forceSensorMode goes on every request of the session; the log text. */
    public static String sessionBuilt(@Nullable android.hardware.camera2.CaptureRequest.Builder session) {
        Integer mode = null;
        if (session != null && device()) {
            try {
                mode = session.get(new android.hardware.camera2.CaptureRequest.Key<>(KEY, Integer.class));
            } catch (RuntimeException unknownTag) {
                mode = null;
            }
        }
        remember(mode);
        return mode == null ? "none" : KEY + " " + mode + " on the session and on every request";
    }

    static void remember(@Nullable Integer mode) {
        sSessionMode = mode;
    }

    /** P274: a request of the session carries the session's forceSensorMode (the camera service keeps the mode then). */
    public static void mirrorRequest(@Nullable android.hardware.camera2.CaptureRequest.Builder builder) {
        final Integer mode = sSessionMode;
        if (builder == null || mode == null) return;
        try {
            builder.set(new android.hardware.camera2.CaptureRequest.Key<>(KEY, Integer.class), mode);
        } catch (RuntimeException unknownTag) {
            // the tag is not in this camera's list: nothing to keep
        }
    }

    /**
     * P275: the X300 Ultra's active module on {@code cameraId} forces a sensor mode: the camera opens under the vivo SDK lease
     * (VCF bypass, SDK RAW; CaptureController.openIszLease). scam_dev "vivo_qcom_isz_lease 0": without it.
     */
    public static boolean leaseWanted(@Nullable String cameraId) {
        try {
            if (cameraId == null || !enabled() || !PreferenceKeys.scamDevSwitch("vivo_qcom_isz_lease", true)) return false;
            final String slot = ModuleRegistry.active();
            return cameraId.equals(ModuleRegistry.camera(slot)) && ModuleRegistry.sensorMode(slot) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static VendorTagUtils.TunableKey sessionKey(int mode) {
        return new VendorTagUtils.TunableKey(VendorTagUtils.TunableKey.TYPE_SESSION, KEY, Integer.class, mode);
    }

    /** «3.7×» / «7.4×» / «15×» as the automatic labels are stored. */
    static String label(float zoom) {
        String s = zoom >= 10f || Math.abs(zoom - Math.round(zoom)) < 0.05f
                ? String.valueOf(Math.round(zoom)) : String.format(Locale.US, "%.1f", zoom);
        return s + "×";
    }

    /** A module button as {@link #extras} sees it: camera, zoom, its forced sensor mode (0: none), duplicate or original. */
    static final class Mod {
        final String slot, camera;
        final float zoom;
        final int mode;
        final boolean duplicate;

        Mod(String slot, String camera, float zoom, int mode, boolean duplicate) {
            this.slot = slot;
            this.camera = camera;
            this.zoom = zoom;
            this.mode = mode;
            this.duplicate = duplicate;
        }
    }

    /**
     * P126: the back buttons that are too many: a duplicate with the same camera and forced mode as an earlier one, and (once,
     * {@code plainTwins}) a duplicate without a forced mode at the zoom of an ISZ module of its camera. Originals stay.
     */
    static List<String> extras(List<Mod> mods, boolean plainTwins) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Mod m : mods)
            if (m.mode > 0 && !seen.add(m.camera + "_" + m.mode) && m.duplicate) out.add(m.slot);
        if (plainTwins) for (Mod m : mods) {
            if (!m.duplicate || m.mode > 0) continue;
            for (Mod isz : mods)
                if (isz.mode > 0 && isz.camera.equals(m.camera) && !out.contains(isz.slot) && Math.abs(m.zoom / isz.zoom - 1f) < 0.05f) {
                    out.add(m.slot);
                    break;
                }
        }
        return out;
    }

    private static void removeExtras(SharedPreferences p) {
        List<Mod> mods = new ArrayList<>();
        for (String slot : ModuleRegistry.slots())
            if (slot.startsWith("back") && ModuleRegistry.visible(slot))
                mods.add(new Mod(slot, ModuleRegistry.camera(slot), ModuleRegistry.zoom(slot), ModuleRegistry.sensorMode(slot),
                        ModuleRegistry.duplicateOf(slot) != null));
        final boolean once = !p.getBoolean(CLEANUP, false);
        for (String slot : extras(mods, once)) {
            String label = ModuleRegistry.label(slot), camera = ModuleRegistry.camera(slot);
            if (ModuleRegistry.remove(slot)) Log.i(TAG, "extra module " + slot + " («" + label + "», camera " + camera + ") removed");
        }
        if (once) p.edit().putBoolean(CLEANUP, true).apply();
    }

    /** A visible duplicate of {@code camera} that forces {@code mode} already (made before, or restored with the settings). */
    @Nullable
    private static String existing(String camera, int mode) {
        for (String slot : ModuleRegistry.slots())
            if (slot.startsWith("back") && ModuleRegistry.visible(slot) && ModuleRegistry.duplicateOf(slot) != null
                    && camera.equals(ModuleRegistry.camera(slot)) && ModuleRegistry.sensorMode(slot) == mode) return slot;
        return null;
    }

    private static void offer(Context context, SharedPreferences p, String source, String camera, String what, int mode, float zoom) {
        String offered = OFFERED + camera + "_" + what;
        if (p.getBoolean(offered, false)) return;
        // offered once, made or not: a module deleted (or never made for want of a slot) does not come back
        p.edit().putBoolean(offered, true).apply();
        if (existing(camera, mode) != null) return;
        String target = ModuleRegistry.duplicate(source);
        if (target == null) {
            Log.w(TAG, "no free module slot for the " + what + " ISZ of camera " + camera + " (delete a button and add it by hand: "
                    + "duplicate the module, session key " + KEY + " " + mode + ")");
            return;
        }
        String label = label(zoom);
        p.edit()
                .putString("module_name_" + target, label)
                .putString("module_zoom_" + target, String.format(Locale.US, "%.2f", zoom))
                .apply();
        List<VendorTagUtils.TunableKey> keys = new ArrayList<>();
        keys.add(sessionKey(mode));
        TunableKeyManager.saveKeys(context, target, keys);
        Log.i(TAG, "ISZ module " + target + " («" + label + "», " + what + " of camera " + camera + "): session " + KEY + " " + mode);
    }

    // ---- DCG 14 bit by the button of the manual strip (Dcg14Button, its on / off in Dcg14Mode; owner: «включение DCG по кнопке
    // как у 500 про макс»), on the main, the ultra-wide and the tele (their 14-bit Open Gate modes)

    /** The DCG sensor mode of a camera role, 0 for none. */
    static int dcgMode(@Nullable String role) {
        if (role == null) return 0;
        switch (role) {
            case "main": return MAIN_DCG;
            case "wide": return WIDE_DCG;
            case "tele": return TELE_DCG;
            default: return 0;
        }
    }

    private static int dcgMode(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (c == null) return 0;
        final String type = com.particlesdevs.photoncamera.api.CameraManager2.vivoCameraType(c);
        return dcgMode(type, type != null && !type.trim().isEmpty() ? null : role(null, c));
    }

    /**
     * P214i: the DCG mode of a camera by its vivo CameraType (all three, P122) or, without one, by its focal-length role: the tele
     * only (owner's list, 2026-10-18: the tele's 14-bit DCG modes work, on the 14 / 35 mm lenses they turn highlights pink).
     */
    static int dcgMode(@Nullable String cameraType, @Nullable String focalRole) {
        if (cameraType != null && !cameraType.trim().isEmpty()) return dcgMode(role(cameraType));
        return "tele".equals(focalRole) ? TELE_DCG : 0;
    }

    /** The button is shown: an X300 Ultra camera with a DCG mode (main, ultra-wide, tele). */
    public static boolean dcgAvailable(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        return c != null && enabled() && dcgMode(c) > 0;
    }

    /** The active module forces no sensor mode of its own (an ISZ module): DCG may set one. */
    public static boolean plainModule() {
        try {
            return ModuleRegistry.sensorMode(ModuleRegistry.active()) == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sets the DCG sensor mode as a session parameter; the log text of what was done. */
    public static String applyDcg(android.hardware.camera2.CaptureRequest.Builder session,
                                  @Nullable android.hardware.camera2.CameraCharacteristics c) {
        int mode = dcgMode(c);
        if (session == null || mode <= 0) return "off";
        try {
            session.set(new android.hardware.camera2.CaptureRequest.Key<>(KEY, Integer.class), mode);
            // P229: the 14 / 35 mm DCG modes clip at 8694 of 16383 (rawr: 8712, black 1024); the tele's reach 16383
            final String role = role(null, c);
            if ("main".equals(role) || "wide".equals(role))
                com.particlesdevs.photoncamera.processing.RawLevels.priorClip(DCG_CLIP, "X300 Ultra " + role + " DCG mode " + mode);
            return KEY + " " + mode + " (DCG 14 bit)";
        } catch (RuntimeException e) {
            return "refused: " + e.getMessage();
        }
    }
}
