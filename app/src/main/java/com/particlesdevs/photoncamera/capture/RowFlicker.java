package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P212d (M4 phase B): the lamp flicker from the merge's own frames. A rolling shutter starts row r of a frame r / H of the skew
 * after its first row (SENSOR_ROLLING_SHUTTER_SKEW; 40.11 ms in the PHY110's native modes), so a lamp flickering at 100 / 120 Hz
 * lights the rows of a short exposure in waves of 4.0 / 4.8 cycles a frame; a 40 ms base frame integrates them away. The worker's
 * P197 row check (scam-hybrid.h hybridUltrashortRows) already measures the ultrashort frame against the base band by band - the
 * scene's texture cancels in the ratio, motion scatters a band's tiles and drops it - and reports the green profile as a line
 * ("... ultrashort frame F row profile N bands G g0 g1 ... ", "-" for a band that does not count; report only, no pixel changes).
 * Here every profile is fitted in log units with a quadratic trend plus a sinusoid at each candidate frequency (the edge bands
 * left out); a shot votes when the better frequency's amplitude is at least {@link #MIN_AMP} and its residual at most
 * {@link #MAX_RSS_RATIO} of the other's. MainsFlicker.shotVote latches on two agreeing shots. Opportunistic: a burst without an
 * ultrashort frame, or a night shot whose ultrashort frame is too dark for the row check ("no row profile"), votes nothing.
 * scam_dev.txt "flicker_rows 0": the vote is only logged.
 */
public final class RowFlicker {
    private RowFlicker() {}

    static final double MIN_AMP = 0.03, MAX_RSS_RATIO = 0.7;
    /** Bands a profile needs (the worker makes 4..64; fewer cannot tell 4.0 from 4.8 cycles) and of them counting, at least half. */
    static final int MIN_BANDS = 16;
    static final int[] HZ = {100, 120};
    private static final Pattern LINE = Pattern.compile("ultrashort frame (\\d+) row profile (\\d+) bands G((?: (?:-|[0-9]+(?:\\.[0-9]+)?))+)");

    /** One profile's fit: the bands it used, the sinusoid's amplitude (log units ~ a fraction) and the residual at 100 / 120 Hz. */
    static final class Fit {
        final int bands;
        final double[] amp, rss;

        Fit(int bands, double[] amp, double[] rss) {
            this.bands = bands;
            this.amp = amp;
            this.rss = rss;
        }
    }

    /** A shot's vote: 100 / 120 Hz or 0 (none), the amplitude at the better frequency, residual ratio better / worse, profiles, bands. */
    static final class Vote {
        final int hz, profiles, bands;
        final double amp, ratio;

        Vote(int hz, double amp, double ratio, int profiles, int bands) {
            this.hz = hz;
            this.amp = amp;
            this.ratio = ratio;
            this.profiles = profiles;
            this.bands = bands;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%s (amp %.1f %%, rss ratio %.2f, %d profile%s, %d bands)",
                    hz > 0 ? hz + " Hz" : "none", 100 * amp, ratio, profiles, profiles == 1 ? "" : "s", bands);
        }
    }

    /** The green profile of a worker line (NaN for "-"), null when the line is no profile or its band count disagrees. */
    @Nullable
    static float[] parse(@Nullable String line) {
        if (line == null) return null;
        final Matcher m = LINE.matcher(line);
        if (!m.find()) return null;
        final String[] v = m.group(3).trim().split(" ");
        final int n;
        try { n = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return null; }
        if (v.length != n || n <= 0) return null;
        final float[] g = new float[n];
        for (int i = 0; i < n; i++) {
            try { g[i] = "-".equals(v[i]) ? Float.NaN : Float.parseFloat(v[i]); } catch (NumberFormatException e) { g[i] = Float.NaN; }
        }
        return g;
    }

    /**
     * Fits ln(g) of the counting bands (the first and last band left out) with 1, u, u^2 (u: the band centre over the frame, -1..1)
     * and cos / sin of 2 pi f t (t: the band centre's row start, a fraction of {@code skewNs}) for f = 100 and 120 Hz; null when
     * fewer than MIN_BANDS bands, or fewer than half of them, count, or without a skew.
     */
    @Nullable
    static Fit fit(@Nullable float[] g, double skewNs) {
        if (g == null || g.length < MIN_BANDS || !(skewNs > 0) || Double.isInfinite(skewNs)) return null;
        final int nb = g.length;
        final double[] u = new double[nb], t = new double[nb], y = new double[nb];
        int n = 0;
        for (int k = 1; k + 1 < nb; k++) {
            if (!(g[k] > 0f) || Float.isInfinite(g[k])) continue;
            final double c = (k + 0.5) / nb;
            u[n] = 2 * c - 1;
            t[n] = c * skewNs * 1e-9;
            y[n] = Math.log(g[k]);
            n++;
        }
        if (n < MIN_BANDS || 2 * n < nb) return null;
        final double[] amp = new double[HZ.length], rss = new double[HZ.length];
        for (int i = 0; i < HZ.length; i++) {
            final double w = 2 * Math.PI * HZ[i];
            final double[][] x = new double[n][];
            for (int k = 0; k < n; k++) x[k] = new double[]{1, u[k], u[k] * u[k], Math.cos(w * t[k]), Math.sin(w * t[k])};
            final double[] b = leastSquares(x, y, n);
            if (b == null) return null;
            double r = 0;
            for (int k = 0; k < n; k++) {
                double e = y[k];
                for (int j = 0; j < b.length; j++) e -= b[j] * x[k][j];
                r += e * e;
            }
            amp[i] = Math.hypot(b[3], b[4]);
            rss[i] = r;
        }
        return new Fit(n, amp, rss);
    }

    /** The shot's vote over its profile lines (the residuals summed, the amplitudes weighted by bands); skew of the reference frame. */
    static Vote vote(@Nullable List<String> lines, double skewNs) {
        double rss100 = 0, rss120 = 0, amp100 = 0, amp120 = 0;
        int profiles = 0, bands = 0;
        if (lines != null) for (String line : lines) {
            final Fit f = fit(parse(line), skewNs);
            if (f == null) continue;
            profiles++;
            bands += f.bands;
            rss100 += f.rss[0];
            rss120 += f.rss[1];
            amp100 += f.amp[0] * f.bands;
            amp120 += f.amp[1] * f.bands;
        }
        if (profiles == 0) return new Vote(0, 0, 1, 0, 0);
        amp100 /= bands;
        amp120 /= bands;
        final boolean first = rss100 <= rss120;
        final double eps = 1e-12 * bands;
        final double ratio = (Math.min(rss100, rss120) + eps) / (Math.max(rss100, rss120) + eps);
        final double amp = first ? amp100 : amp120;
        final int hz = amp >= MIN_AMP && ratio <= MAX_RSS_RATIO ? (first ? 100 : 120) : 0;
        return new Vote(hz, amp, ratio, profiles, bands);
    }

    /**
     * After a merge (ScamProcessor): the worker's row-profile lines of the shot (ScamNeuralClient.lastRowProfiles) and the
     * reference frame's rolling shutter skew. Logs the vote; MainsFlicker counts it unless scam_dev.txt "flicker_rows 0". Never
     * throws (the photo does not depend on it).
     */
    public static void afterMerge(@Nullable List<String> lines, @Nullable Long skewNs) {
        if (lines == null || lines.isEmpty()) return;
        try {
            if (skewNs == null || skewNs <= 0) {
                Log.i("SCAM_CAPTURE", "flicker vote: " + lines.size() + " row profiles but no rolling shutter skew");
                return;
            }
            final Vote v = vote(lines, skewNs);
            final boolean on = PreferenceKeys.scamDevSwitch("flicker_rows", true);
            final boolean changed = on && MainsFlicker.shotVote(v.hz);
            Log.i("SCAM_CAPTURE", String.format(Locale.ROOT, "flicker vote %s, skew %.2f ms%s%s", v, skewNs / 1e6,
                    on ? "" : " (flicker_rows 0: logged only)", changed ? " -> mains flicker " + MainsFlicker.hz() + " Hz" : ""));
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "flicker vote failed: " + e);
        }
    }

    /** Least squares by the normal equations (Gauss-Jordan, partial pivoting); null when singular. */
    @Nullable
    static double[] leastSquares(double[][] x, double[] y, int n) {
        final int m = x[0].length;
        final double[][] a = new double[m][m + 1];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) a[i][j] += x[k][i] * x[k][j];
                a[i][m] += x[k][i] * y[k];
            }
        }
        for (int c = 0; c < m; c++) {
            int p = c;
            for (int r = c + 1; r < m; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            if (Math.abs(a[p][c]) < 1e-12) return null;
            final double[] tmp = a[c]; a[c] = a[p]; a[p] = tmp;
            for (int r = 0; r < m; r++) {
                if (r == c) continue;
                final double f = a[r][c] / a[c][c];
                if (f == 0) continue;
                for (int j = c; j <= m; j++) a[r][j] -= f * a[c][j];
            }
        }
        final double[] b = new double[m];
        for (int i = 0; i < m; i++) b[i] = a[i][m] / a[i][i];
        return b;
    }
}
