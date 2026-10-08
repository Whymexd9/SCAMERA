package com.particlesdevs.photoncamera.settings;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one format choice of the top bar, the shade's FORMAT tile and «Формат фото» (MANUAL_TASK.md §4): nine options,
 * each with its icon, mapped onto the two stored settings without changing capture or saving. The save mode
 * (pref_save_raw_key: 0 photo only, 1 RAW + photo, 2 RAW only) and the photo codec («Формат фото», pref_photo_format:
 * JPEG / HEIC / WebP / AVIF). RAW only keeps whatever codec is stored. HEIC needs Android 9 (PhotoFormat.HEIC_MIN_SDK),
 * AVIF Android 12 and its encoder library (PhotoFormat.avifOffered): without them those options are not offered.
 * The ordinals are the shade's list values: new options are appended (AVIF, RAW_AVIF), the menus show {@link #ORDER}.
 */
public enum FormatChoice {
    JPEG(0, PhotoFormat.JPEG, R.drawable.ic_shade_jpeg),
    HEIC(0, PhotoFormat.HEIC, R.drawable.ic_shade_heic),
    WEBP(0, PhotoFormat.WEBP, R.drawable.ic_shade_webp),
    RAW(2, null, R.drawable.ic_shade_raw),
    RAW_JPEG(1, PhotoFormat.JPEG, R.drawable.ic_shade_rawjpeg),
    RAW_HEIC(1, PhotoFormat.HEIC, R.drawable.ic_shade_rawheic),
    RAW_WEBP(1, PhotoFormat.WEBP, R.drawable.ic_shade_rawwebp),
    AVIF(0, PhotoFormat.AVIF, R.drawable.ic_shade_avif),
    RAW_AVIF(1, PhotoFormat.AVIF, R.drawable.ic_shade_rawavif);

    /** Menu order: the photo codecs, RAW, then RAW + each codec. */
    static final FormatChoice[] ORDER = {JPEG, HEIC, WEBP, AVIF, RAW, RAW_JPEG, RAW_HEIC, RAW_WEBP, RAW_AVIF};

    /** The save mode: 0 photo only, 1 RAW + photo, 2 RAW only. */
    public final int saveMode;
    /** The codec of the photo; null for RAW only (the stored codec is kept). */
    @Nullable public final PhotoFormat codec;
    @DrawableRes public final int icon;

    FormatChoice(int saveMode, @Nullable PhotoFormat codec, @DrawableRes int icon) {
        this.saveMode = saveMode;
        this.codec = codec;
        this.icon = icon;
    }

    /** The option of a stored save mode and codec (an unknown save mode reads as photo only, as the shade did). */
    public static FormatChoice of(int saveMode, PhotoFormat codec) {
        if (saveMode == 2) return RAW;
        PhotoFormat c = codec == null ? PhotoFormat.JPEG : codec;
        boolean raw = saveMode == 1;
        switch (c) {
            case HEIC: return raw ? RAW_HEIC : HEIC;
            case WEBP: return raw ? RAW_WEBP : WEBP;
            case AVIF: return raw ? RAW_AVIF : AVIF;
            default: return raw ? RAW_JPEG : JPEG;
        }
    }

    /** The options this phone offers, in menu order (AvifEncoder.available() tells whether the AVIF encoder loaded). */
    public static List<FormatChoice> offered(int sdk) {
        return offered(sdk, AvifEncoder.available());
    }

    /** The options offered, in menu order: no HEIC below Android 9, no AVIF below Android 12 or without its encoder. */
    public static List<FormatChoice> offered(int sdk, boolean avifEncoder) {
        List<FormatChoice> out = new ArrayList<>();
        for (FormatChoice c : ORDER) {
            if (c.codec == PhotoFormat.HEIC && sdk < PhotoFormat.HEIC_MIN_SDK) continue;
            if (c.codec == PhotoFormat.AVIF && !PhotoFormat.avifOffered(sdk, avifEncoder)) continue;
            out.add(c);
        }
        return Collections.unmodifiableList(out);
    }

    /** The codec to store with this option: its own, or (RAW only) the one stored now. */
    public PhotoFormat codecToStore(PhotoFormat stored) {
        return codec != null ? codec : stored == null ? PhotoFormat.JPEG : stored;
    }

    /** Short label (shade tile): JPEG, HEIC, WEBP, AVIF, RAW, R+J, R+H, R+W, R+A. */
    public String shortLabel() {
        return codec == null ? "RAW" : codec.saveModeShort(saveMode);
    }

    /** Full label (chooser, tooltip, toast): JPEG, HEIC, WebP, AVIF, RAW, RAW + JPEG, RAW + HEIC, RAW + WebP, RAW + AVIF. */
    public String longLabel() {
        return codec == null ? "RAW" : codec.saveModeLong(saveMode);
    }

    /** The stored option (codec as effective on this Android version). */
    public static FormatChoice current() {
        return of(PreferenceKeys.isSaveRaw(), PreferenceKeys.getPhotoFormat());
    }

    /** Stores the option: the save mode and (unless RAW only) the codec. Nothing else changes. */
    public static void store(FormatChoice choice) {
        PreferenceKeys.setSaveRaw(choice.saveMode);
        if (choice.codec != null) PreferenceKeys.setChosenPhotoFormat(choice.codec);
    }
}
