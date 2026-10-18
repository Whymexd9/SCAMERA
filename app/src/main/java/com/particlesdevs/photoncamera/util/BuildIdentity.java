package com.particlesdevs.photoncamera.util;

import com.particlesdevs.photoncamera.BuildConfig;

/**
 * P214b (engineering.md A4): which tree an APK was built from, as one line:
 * {@code SCAMERA <versionName> (<versionCode>) <package> <sha>[+dirty] <CI|local>}, e.g.
 * {@code SCAMERA 0.98 (30966) android.camera.cts cab4480a CI}. Printed at every session start of the daily log
 * ({@link Log}), in the full debug log's session header and its rotated files ({@link ScameraDebugLog}), in the timings log
 * ({@link ScameraTimingLog}), in every crash report ({@link ScameraCrashReporter}) and in each diagnostics ZIP
 * (ScamDiagnostics). Before, a log or a crash could not be tied to a build: the daily log had no version line at all, the
 * crash report no version, and a local APK has the same versionCode scheme as CI.
 *
 * <p>The SHA, the dirty flag and CI/local come from gradle/build-identity.gradle (GITHUB_SHA in CI, else git rev-parse at
 * build time; "unknown" without git). The SHA is cut to 8 hex digits, the length the plan and the commit lists use;
 * {@link BuildConfig#GIT_SHA} keeps all of it.
 */
public final class BuildIdentity {
    private static String line;

    private BuildIdentity() {}

    /** The identity line of this APK (built once per process). */
    public static String line() {
        String l = line;
        if (l == null) {
            l = line(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID,
                    BuildConfig.GIT_SHA, BuildConfig.GIT_DIRTY, BuildConfig.CI_BUILD);
            line = l;
        }
        return l;
    }

    static String line(String versionName, long versionCode, String packageName, String sha, boolean dirty, boolean ci) {
        return "SCAMERA " + versionName + " (" + versionCode + ") " + packageName + " " + shortSha(sha)
                + (dirty ? "+dirty" : "") + " " + (ci ? "CI" : "local");
    }

    /** The first 8 hex digits of a commit; "unknown" for none (a build without git). */
    static String shortSha(String sha) {
        if (sha == null) return "unknown";
        String s = sha.trim();
        if (s.isEmpty()) return "unknown";
        return s.length() > 8 ? s.substring(0, 8) : s;
    }
}
