package com.particlesdevs.photoncamera.ui.camera;

import android.Manifest;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The startup permission flow of {@link CameraActivity}, kept free of Android calls so it can be unit tested.
 *
 * <p>It follows ArkCam: every missing runtime permission goes into one batched system request with no dialog
 * of ours in front of it (ColorOS shows the batch as one combined sheet, other ROMs show the standard prompts
 * one after another). A refusal is asked again, in one batch, only while the system can still show its prompt
 * and at most {@link #MAX_REQUESTS} times in a round; after that the user is sent to the app settings. On
 * Android 11+ the SAF access to DCIM is asked for after the runtime permissions.
 *
 * <p>A round starts with the activity and starts again when the user comes back from the app settings or
 * taps Retry ({@link #restart()}).
 */
final class PermissionPlan {
    /** Runtime requests per round: the first batch plus at most two automatic re-asks. */
    static final int MAX_REQUESTS = 3;
    /** DCIM folder picker launches per round before the "select DCIM" dialog. */
    static final int MAX_DCIM_PICKS = 3;

    enum Step {
        /** Request the missing runtime permissions in one batch. */
        REQUEST,
        /** The system will not ask again: offer the app settings. */
        SETTINGS,
        /** Open the SAF folder picker at DCIM (Android 11+). */
        PICK_DCIM,
        /** The DCIM pick was cancelled or kept failing: ask the user before opening the picker again. */
        DCIM_FAILED,
        /** Everything the camera needs is granted. */
        START
    }

    /** Permission groups as the system app settings name them, for the settings dialog text. */
    enum Group {CAMERA, MICROPHONE, PHOTOS, FILES, STORAGE}

    final int sdk;
    private int requests;
    private boolean lastAnswered = true;
    private int dcimPicks;
    private boolean dcimCancelled;
    private boolean dcimGranted;

    PermissionPlan(int sdk) {
        this.sdk = sdk;
    }

    /**
     * The runtime permissions the camera needs on {@code sdk}: camera, microphone and media read (Android 13+:
     * photos and videos; 11-12: read storage; 10 and older: read and write storage). INTERNET is a normal
     * permission and is never requested.
     */
    static String[] runtimePermissions(int sdk) {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        }
        if (sdk >= Build.VERSION_CODES.R) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** The runtime permissions of this SDK that {@code granted} rejects, in request order. */
    String[] missing(Predicate<String> granted) {
        List<String> out = new ArrayList<>();
        for (String permission : runtimePermissions(sdk)) {
            if (!granted.test(permission)) out.add(permission);
        }
        return out.toArray(new String[0]);
    }

    /** Android 11+ also needs SAF access to DCIM (RAW video, device configs). */
    boolean needsDcimAccess() {
        return sdk >= Build.VERSION_CODES.R;
    }

    /**
     * What to do now.
     *
     * @param missing       {@link #missing} at this moment
     * @param canAskAgain   whether the system would still show its prompt for a permission
     *                      (shouldShowRequestPermissionRationale); only asked after a request was made
     * @param hasDcimAccess whether SAF access to DCIM exists (ignored below Android 11)
     */
    Step next(String[] missing, Predicate<String> canAskAgain, boolean hasDcimAccess) {
        if (missing.length > 0) {
            if (requests == 0) return Step.REQUEST;
            if (requests >= MAX_REQUESTS) return Step.SETTINGS;
            // An empty result means the request was interrupted before the user answered: ask again.
            if (!lastAnswered) return Step.REQUEST;
            for (String permission : missing) {
                if (canAskAgain.test(permission)) return Step.REQUEST;
            }
            return Step.SETTINGS;
        }
        if (needsDcimAccess() && !hasDcimAccess && !dcimGranted) {
            if (dcimCancelled || dcimPicks >= MAX_DCIM_PICKS) return Step.DCIM_FAILED;
            return Step.PICK_DCIM;
        }
        return Step.START;
    }

    /** A batched runtime request was launched. */
    void onRequest() {
        requests++;
    }

    /** The runtime request returned; {@code answered} is false when its result was empty. */
    void onResult(boolean answered) {
        lastAnswered = answered;
    }

    /** The DCIM folder picker was launched. */
    void onDcimPick() {
        dcimPicks++;
    }

    /** DCIM picks in this round; above zero the previous pick returned a wrong folder. */
    int dcimPicks() {
        return dcimPicks;
    }

    /** The user backed out of the DCIM picker, or it could not be opened. */
    void onDcimCancelled() {
        dcimCancelled = true;
    }

    /** The picker granted DCIM; trust it even if the accessible-path check disagrees. */
    void onDcimGranted() {
        dcimGranted = true;
    }

    /** A new round: the user came back from the app settings or tapped Retry. */
    void restart() {
        requests = 0;
        lastAnswered = true;
        dcimPicks = 0;
        dcimCancelled = false;
    }

    /** The settings groups of {@code missing}, without repeats, in request order. */
    List<Group> groups(String[] missing) {
        List<Group> out = new ArrayList<>();
        for (String permission : missing) {
            Group group = groupOf(permission);
            if (group != null && !out.contains(group)) out.add(group);
        }
        return out;
    }

    private Group groupOf(String permission) {
        switch (permission) {
            case Manifest.permission.CAMERA:
                return Group.CAMERA;
            case Manifest.permission.RECORD_AUDIO:
                return Group.MICROPHONE;
            case Manifest.permission.READ_MEDIA_IMAGES:
            case Manifest.permission.READ_MEDIA_VIDEO:
                return Group.PHOTOS;
            case Manifest.permission.READ_EXTERNAL_STORAGE:
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return sdk >= Build.VERSION_CODES.R ? Group.FILES : Group.STORAGE;
            default:
                return null;
        }
    }

    /**
     * True when only the media read of Android 11+ is missing: the settings dialog then keeps its
     * "full access" text, which also covers the partial photo access of Android 14.
     */
    static boolean mediaOnly(List<Group> groups) {
        if (groups.isEmpty()) return false;
        for (Group group : groups) {
            if (group != Group.PHOTOS && group != Group.FILES) return false;
        }
        return true;
    }

    /** The round state, for onSaveInstanceState. */
    int[] save() {
        return new int[]{requests, lastAnswered ? 1 : 0, dcimPicks, dcimCancelled ? 1 : 0, dcimGranted ? 1 : 0};
    }

    /** A plan for {@code sdk} with the round state of {@link #save()}; a fresh one for null or a short array. */
    static PermissionPlan restore(int sdk, int[] state) {
        PermissionPlan plan = new PermissionPlan(sdk);
        if (state != null && state.length >= 5) {
            plan.requests = state[0];
            plan.lastAnswered = state[1] != 0;
            plan.dcimPicks = state[2];
            plan.dcimCancelled = state[3] != 0;
            plan.dcimGranted = state[4] != 0;
        }
        return plan;
    }
}
