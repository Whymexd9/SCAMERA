package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.dng.DngRaw;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P367: the zoom's full-resolution regions, off the GL and UI threads. One worker thread («EditorZoom») owns the export's
 * reader of the source ({@link EditSource#openReader} with the export's parameters, so its pixels are the copy's): opened on
 * the first zoom past the preview's resolution (a RAW is decoded whole there: seconds, up to RAW_MAX samples, binned when
 * memory is short - EditSource.memoryBudget), kept while the editor is open (opened again when a new crop changes the JPEG
 * sample the export would take), closed on its worker. A settled view ({@link #request}) gets the region it needs
 * ({@link EditZoom#cacheRegion}: the visible area, the export's overlap, a margin to pan in) decoded there into a CPU picture
 * ({@link EditSource.Region}) that the GL thread uploads (EditorView). Requests carry a generation: one superseded before the
 * worker took it is skipped, a decoded region that does not serve the newest view is dropped; a view the last region still
 * serves is not decoded again. A source the reader cannot open (or memory too short, or a failed upload) stays on the preview,
 * said once; a reader below the original's resolution is said once too.
 */
final class EditZoomLoader {
    private static final String TAG = "EditZoomLoader";
    /** Less free memory than this (EditSource.memoryBudget): no full-resolution zoom. */
    static final long MIN_BUDGET = 96L << 20;
    /** Bytes a region texel may cost at most: the CPU copy + a full EditRenderer chain (raw, nr, dev, two blurs, main + mips). */
    static final long TEXEL_BYTES = 48;

    /** The open reader as the GL thread needs it (immutable). */
    static final class Info {
        final EditSource source;
        final EditSource.Route route;
        /** The reader's picture (stored orientation), its EXIF orientation and its oriented size. */
        final int sw, sh, orientation;
        final double ow, oh;
        /** Reader px per original px (1, or less for a reduced reader: JPEG sample, binned RAW). */
        final double perOriginal;
        /** The export tile overlap for this reader (EditExporter: overlapPx + 2). */
        final int overlap;
        final boolean even, integerLevels;
        /** The export's pixel limit when the reader was opened; the JPEG reader's sample. */
        final long outMax;
        final int sample;
        /** Texels a region may have at most for the free memory when the reader was opened ({@link #TEXEL_BYTES} each). */
        final double maxTexels;

        Info(EditSource s, EditSource.Reader r, long outMax, long budget) {
            source = s;
            route = s.route;
            sw = r.width();
            sh = r.height();
            orientation = s.orientation;
            boolean swap = CropMath.swapsSides(orientation);
            ow = swap ? sh : sw;
            oh = swap ? sw : sh;
            perOriginal = Math.max(1e-6, ow / (double) Math.max(1, s.orientedWidth()));
            overlap = EditMath.overlapPx(Math.max(sw, sh)) + 2;
            even = r.evenAligned();
            integerLevels = r.integerLevels();
            this.outMax = outMax;
            sample = r.sample();
            maxTexels = Math.max(EditZoom.CAP_MAX, budget / (double) TEXEL_BYTES);
        }

        /** The copy of {@code s} from this reader: EditExporter's own size, mapping and grain (its long side, lattice aspect). */
        EditExporter.Plan plan(EditState s) {
            return EditExporter.plan(s, sw, sh, orientation, outMax);
        }

        /** View px -> reader px for the displayed crop (left, top, dw x dh) of {@code s}. */
        CropMath.Affine viewToSource(EditState s, double left, double top, double dw, double dh) {
            return EditZoom.viewToSource(s, left, top, dw, dh, orientation, sw, sh);
        }

        /** The box (reader px, overlap included) the displayed crop's visible part needs; null when nothing is visible. */
        @Nullable
        double[] need(EditState s, double viewW, double viewH, double left, double top, double dw, double dh) {
            double[] vis = EditZoom.visibleView(viewW, viewH, left, top, dw, dh);
            if (vis == null) return null;
            return EditZoom.grow(EditZoom.box(viewToSource(s, left, top, dw, dh), vis), overlap);
        }

        /** Texels the needed box may have before the decode gets coarser (EditZoom.ceiling, the free memory). */
        double ceiling(double viewW, double viewH) {
            return Math.min(EditZoom.ceiling(viewW, viewH), maxTexels);
        }

        /** The far-edge slack of {@link EditZoom#covers}: a RAW region is trimmed to whole binning cells, a picture's is not. */
        double slack(double level) {
            return integerLevels ? EditZoomLoader.slack(level) : 0;
        }

        /** Whether a region at {@code level} is as sharp as the view at {@code scale} gets (EditZoom.fineEnough). */
        boolean fine(double level, double scale, double[] need, double viewW, double viewH) {
            return EditZoom.fineEnough(level, scale / perOriginal, integerLevels, need, ceiling(viewW, viewH));
        }

        /** Whether the region {@code rect} (reader px) at {@code level} serves a view: it holds the needed box, fine enough. */
        boolean serves(int[] rect, double level, double scale, @Nullable double[] need, double viewW, double viewH) {
            return need != null && EditZoom.covers(rect, need, sw, sh, slack(level)) && fine(level, scale, need, viewW, viewH);
        }
    }

    /** A settled view to cover (a UI-thread snapshot). */
    static final class Request {
        long gen, epoch;
        final EditSource source;
        final EditState state;
        final String geometry;
        final double scale, left, top, dw, dh;
        final int viewW, viewH;

        Request(EditSource source, EditState state, double scale, double left, double top, double dw, double dh, int viewW, int viewH) {
            this.source = source;
            this.state = state;
            this.geometry = EditZoom.geometryKey(state);
            this.scale = scale;
            this.left = left;
            this.top = top;
            this.dw = dw;
            this.dh = dh;
            this.viewW = viewW;
            this.viewH = viewH;
        }

        @Nullable
        double[] need(Info in) {
            return in.need(state, viewW, viewH, left, top, dw, dh);
        }
    }

    /** A decoded region on its way to the GL thread. */
    static final class Result {
        final Request request;
        final Info info;
        final EditSource.Region region;
        final long decodeMs;

        Result(Request request, Info info, EditSource.Region region, long decodeMs) {
            this.request = request;
            this.info = info;
            this.region = region;
            this.decodeMs = decodeMs;
        }
    }

    interface Sink {
        /** Worker thread: a region for the GL thread. */
        void decoded(Result r);

        /** Worker thread: no full resolution for this source (said once). */
        void unavailable(EditSource s, String why);

        /** Worker thread: a line for the note pill about this source's full resolution (a reduced reader; said once). */
        void note(EditSource s, String text);
    }

    /** The last region handed to the GL thread: its reader, key, rect and level, and what was asked for it. */
    private static final class Issued {
        final Info info;
        final String geometry;
        final int[] rect, asked;
        final double level, askedLevel;

        Issued(Info info, String geometry, int[] rect, double level, int[] asked, double askedLevel) {
            this.info = info;
            this.geometry = geometry;
            this.rect = rect;
            this.level = level;
            this.asked = asked;
            this.askedLevel = askedLevel;
        }
    }

    private final Context context;
    private final Sink sink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EditorZoom");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicLong gen = new AtomicLong();
    /** Bumped when the reader is closed (the export, a new source, the editor going): a region decoded before is dropped. */
    private final AtomicLong epoch = new AtomicLong();
    /** The newest view asked for (null: none wanted any more - back at the fit, or under the preview's resolution). */
    private final AtomicReference<Request> latest = new AtomicReference<>();
    // worker thread only
    @Nullable private EditSource.Reader reader;
    @Nullable private EditSource readerSource;
    @Nullable private EditSource failed, noted;
    @Nullable private Info info;
    // shared
    @Nullable private volatile Issued issued;
    @Nullable private volatile EditSource unavailableFor;
    private volatile boolean shut;

    EditZoomLoader(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    /** UI thread: cover this view (after the gesture settled); an older request still queued is skipped. */
    void request(Request r) {
        if (shut) return;
        r.gen = gen.incrementAndGet();
        r.epoch = epoch.get();
        latest.set(r);
        submit(() -> run(r));
    }

    /** UI thread: no view to cover any more (back at the fit, or under the preview's resolution): queued work and results dropped. */
    void cancel() {
        if (latest.getAndSet(null) != null) gen.incrementAndGet();
    }

    /** The GL thread dropped its region (back at the fit, a lost context): the next request decodes again. */
    void forget() {
        issued = null;
    }

    /** Whether full resolution failed for {@code s} (the GL thread's «loading» hint stops). */
    boolean unavailable(@Nullable EditSource s) {
        return s != null && s == unavailableFor;
    }

    /**
     * GL thread: the region of {@code s} could not go on the GPU (a shader, GL memory): full resolution off for this source,
     * said once ({@code why}), its reader closed - no decode again and again for an upload that fails.
     */
    void markFailed(EditSource s, String why) {
        unavailableFor = s;
        issued = null;
        latest.set(null);
        gen.incrementAndGet();
        submit(() -> {
            fail(s, why);
            if (readerSource == s) closeNow();
        });
    }

    /**
     * Closes the reader on its worker (a new source, the export starting); the next request opens it again. The future is done
     * once the reader is closed (behind a decode or a RAW open already running): the export waits for it, so the two readers
     * never hold the memory at once. A region decoding meanwhile is dropped.
     */
    Future<?> closeReader() {
        epoch.incrementAndGet();
        gen.incrementAndGet();
        latest.set(null);
        issued = null;
        FutureTask<Void> t = new FutureTask<>(this::closeNow, null);
        try {
            worker.execute(t);
        } catch (RejectedExecutionException e) {
            t = new FutureTask<>(() -> { }, null); // the worker has ended: its reader was closed then
            t.run();
        }
        return t;
    }

    /** The editor is going: the reader closed on its worker, the worker ends. */
    void shutdown() {
        if (shut) return;
        shut = true;
        epoch.incrementAndGet();
        gen.incrementAndGet();
        latest.set(null);
        issued = null;
        submit(this::closeNow);
        worker.shutdown();
    }

    private void submit(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException ignored) {
        }
    }

    // ---------------------------------------------------------------- worker

    private void run(Request q) {
        if (shut || q.gen != gen.get() || q.epoch != epoch.get()) return; // a newer request is queued, or the reader closed since
        EditSource.Region g = null;
        try {
            if (!open(q)) return;
            EditSource.Reader rd = reader;
            Info in = info;
            if (rd == null || in == null) return;
            double[] need = q.need(in);
            if (need == null) return;
            Issued last = issued;
            boolean same = last != null && last.info == in && last.geometry.equals(q.geometry);
            if (same && in.serves(last.rect, last.level, q.scale, need, q.viewW, q.viewH)) return;
            double level = EditZoom.level(q.scale / in.perOriginal, in.integerLevels, need, in.ceiling(q.viewW, q.viewH));
            int[] rect = EditZoom.cacheRegion(need, level, EditZoom.cap(q.viewW, q.viewH), in.sw, in.sh, in.even);
            if (same && Arrays.equals(last.asked, rect) && last.askedLevel == level) return; // that region is what there is
            long t0 = SystemClock.elapsedRealtime();
            g = rd.decode(rect[0], rect[1], rect[2], rect[3], level);
            long ms = SystemClock.elapsedRealtime() - t0;
            int[] got = {g.x, g.y, g.w, g.h};
            Request now = latest.get();
            boolean current = now == q || now != null && now.source == q.source && now.geometry.equals(q.geometry)
                    && in.serves(got, g.level, now.scale, now.need(in), now.viewW, now.viewH);
            if (shut || q.epoch != epoch.get() || readerSource != q.source || !current) {
                // the reader closed, or the view moved where this region does not serve: the newer request decodes its own
                g.free();
                return;
            }
            issued = new Issued(in, q.geometry, got, g.level, rect, level);
            Log.d(TAG, "region " + g.x + "," + g.y + " " + g.w + "x" + g.h + " level " + String.format(Locale.ROOT, "%.2f", g.level)
                    + " -> " + g.texW + "x" + g.texH + " (" + (g.bytes() >> 10) + " KB) in " + ms + " ms");
            EditSource.Region done = g;
            g = null;
            sink.decoded(new Result(q, in, done, ms));
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            if (g != null) g.free();
            Log.w(TAG, "full-resolution region of " + q.source.name + ": " + e);
            fail(q.source, Lang.t("Полное разрешение недоступно (" + e.getClass().getSimpleName() + "): увеличение по превью",
                    "Full resolution unavailable (" + e.getClass().getSimpleName() + "): zooming the preview"));
            closeNow();
        }
    }

    /** A binned RAW region may end up to 2 x level - 1 px short of the picture's far edges. */
    static double slack(double level) {
        return 2 * Math.ceil(level);
    }

    static double cropFraction(EditState s) {
        return Math.max(1e-6, (s.cropR - s.cropL) * (s.cropB - s.cropT));
    }

    /**
     * The reader of {@code q.source}, opened when needed with the export's parameters (again when the crop changed the JPEG
     * sample the export would take); false when it cannot be.
     */
    private boolean open(Request q) throws IOException {
        if (reader != null && readerSource == q.source) {
            Info in = info;
            if (in == null || in.route != EditSource.Route.JPEG) return true;
            int want = EditSource.regionSample(q.source.fullW, q.source.fullH, cropFraction(q.state), in.outMax);
            if (want == in.sample) return true;
            Log.i(TAG, "P367 zoom reader: the crop changes the export's JPEG sample " + in.sample + " -> " + want + ", opening again");
        }
        if (failed == q.source) return false;
        closeNow();
        long budget = EditSource.memoryBudget(context);
        if (budget < MIN_BUDGET) {
            fail(q.source, Lang.t("Мало свободной памяти: увеличение по превью, без полного разрешения",
                    "Low free memory: zooming the preview, not full resolution"));
            return false;
        }
        if (q.source.isRaw() && rawBytes(q.source, budget) > heapFree() * 0.7) {
            // the RAW's samples live in a direct buffer on the Java heap (largeHeap): not when it would squeeze the app
            fail(q.source, Lang.t("Мало памяти для RAW в полном разрешении: увеличение по превью",
                    "Not enough memory for the full-resolution RAW: zooming the preview"));
            return false;
        }
        long max = EditExporter.outMax(budget);
        long t0 = SystemClock.elapsedRealtime();
        reader = q.source.openReader(context, cropFraction(q.state), max);
        readerSource = q.source;
        info = new Info(q.source, reader, max, budget);
        issued = null; // regions of an older reader are in its own pixels
        String shrink = reader.shrinkNote();
        Log.i(TAG, "P367 zoom reader: route " + q.source.route + ", " + reader.width() + "x" + reader.height() + " in "
                + (SystemClock.elapsedRealtime() - t0) + " ms" + (shrink != null ? " (" + shrink + ")" : "")
                + String.format(Locale.ROOT, ", %.3f px a original px", info.perOriginal));
        if (info.perOriginal < 0.999 && noted != q.source) {
            // «100 %» then magnifies the reader's pixels: say so once
            noted = q.source;
            int pct = (int) Math.round(info.perOriginal * 100);
            String why = shrink != null ? shrink : Lang.t("оригинал читается в " + pct + " %", "the original reads at " + pct + " %");
            sink.note(q.source, Lang.t("Детали при увеличении: ", "Zoom detail: ") + why);
        }
        return true;
    }

    /** About what EditSource.RawReader puts on the Java heap for {@code s} (see {@link #rawHeapBytes}). */
    static long rawBytes(EditSource s, long budget) {
        boolean rgb = s.previewRaw != null && s.previewRaw.info != null && s.previewRaw.info.kind == DngRaw.KIND_RGB;
        return rawHeapBytes((long) s.fullW * s.fullH, rgb, s.route == EditSource.Route.LIBRAW, budget);
    }

    /**
     * What EditSource.RawReader allocates on the Java heap for {@code px} pixels: 2 bytes a sample, 3 samples a pixel for a
     * linear (RGB) DNG, binned 2x2 over its limit (RAW_MAX, budget / 6 pixels); a non-Bayer RAW LibRaw develops lives in native
     * memory (0 here).
     */
    static long rawHeapBytes(long px, boolean rgb, boolean libRaw, long budget) {
        if (libRaw && rgb) return 0;
        long n = px > Math.min(EditSource.RAW_MAX, budget / 6) ? px / 4 : px;
        return n * (rgb ? 3 : 1) * 2;
    }

    private static long heapFree() {
        Runtime rt = Runtime.getRuntime();
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
    }

    private void fail(EditSource s, String why) {
        if (failed == s) return;
        failed = s;
        unavailableFor = s;
        sink.unavailable(s, why);
    }

    private void closeNow() {
        EditSource.Reader r = reader;
        reader = null;
        readerSource = null;
        info = null;
        if (r != null) {
            try {
                r.close();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "closing the zoom reader: " + e);
            }
        }
    }
}
