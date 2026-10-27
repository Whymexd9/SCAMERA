package com.particlesdevs.photoncamera.gallery.editor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.opengl.EGL14;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * P351: the editor's live picture: a GLSurfaceView (OpenGL ES 3, its own context, drawn when something changed) running
 * {@link EditRenderer} on the preview decode. In the crop tab it shows the whole turned frame (the crop overlay on top uses
 * {@link #canvas}), else the crop fitted into the view. A long press elsewhere shows the photo without the tone edits.
 *
 * <p>P367 (owner, 2026-10-27: «В редакторе фото дай возможность кропать фото, при этом оно должно быть в полном разрешении» -
 * «Я имею ввиду кропать предпосмотр во время редактирования»): outside the crop tab the picture zooms ({@link EditZoom}: pinch
 * about the fingers, drag to pan, double tap fit <-> 100 %, up to 400 % of the original's pixels) - a view only, the copy does
 * not change; a geometry change or the crop tab puts it back to the fit. Zoom and pan redraw from the textures already on the
 * GPU. Past the preview decode's resolution the visible area comes from the original: {@link EditZoomLoader} decodes it on its
 * worker once the gesture settled ({@link #SETTLE_MS}; the export's reader, the region + the export's overlap + a margin to pan
 * in, at most ~2x the view's pixels), the GL thread uploads it (in onDrawFrame: only there is the context surely current) and
 * renders it through a second EditRenderer with an export tile's math (radii for the source's long side, the copy's grain size
 * and lattice aspect - EditExporter.Plan -, the proxy bases of the preview's decode), so the zoomed picture at 100 % is a crop
 * of what «Сохранить копию» writes, pixel on pixel (EditZoom.placement). Until the region is there (and while the view is
 * outside it) the preview is shown magnified, with the copy's grain too. Tone edits re-render from the region's texture; the
 * histogram stays the whole crop's (the preview's), computed only when the tone, geometry or source changed. Back at the fit
 * the region and its renderer's textures are freed.
 */
public final class EditorView extends GLSurfaceView {
    private static final String TAG = "EditorView";
    /** P367: the full-resolution region is asked for this long after the last zoom / pan change. */
    static final long SETTLE_MS = 120;
    /** P367: the histogram at most this often (ms). */
    static final long HIST_MS = 120;
    /**
     * P367: what the zoomed picture shows ({@link Callbacks#zoom}): nothing more than the preview needed, the full resolution
     * loading / shown / unavailable, or shown from a reader below the original's resolution (memory: «100 %» magnifies it).
     */
    public static final int DETAIL_NONE = 0, DETAIL_LOADING = 1, DETAIL_FULL = 2, DETAIL_UNAVAILABLE = 3, DETAIL_REDUCED = 4;

    public interface Callbacks {
        void histogram(int[][] counts);

        void failed(String why);

        /** P367 (UI thread): the zoom in % of the original's pixels, whether it is past the fit, what it shows (DETAIL_*). */
        void zoom(int percent, boolean zoomed, int detail);

        /** P367 (UI thread): a line for the note pill (full resolution unavailable or reduced for this photo). */
        void note(String text);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile EditSource source;
    private volatile EditState state = new EditState();
    private volatile boolean cropMode, before;
    @Nullable private volatile Callbacks callbacks;
    private final int bg = GalleryUi.BG;
    private final Gl gl;
    // P367: the zoom (UI thread), its snapshot for the GL thread, the full-resolution regions
    private final EditZoom zoom = new EditZoom();
    @Nullable private volatile ZoomView zoomView;
    private final EditZoomLoader loader;
    @Nullable private ValueAnimator zoomAnim;
    @Nullable private Object animToken;
    @Nullable private float[] pendingZoom;
    @Nullable private String geometry;
    private boolean frameReady, stateSet;
    private volatile int detail = DETAIL_NONE;
    /** P367: the export's pixel limit for this source's free memory (the copy's size the zoom matches: 100 %, grain). */
    private volatile long outMaxHint = EditSource.OUT_MAX;
    /** P367: a decoded region waiting for the next frame (the only place GL work is done), and a drop asked for by the UI. */
    private final AtomicReference<EditZoomLoader.Result> pending = new AtomicReference<>();
    private final AtomicBoolean dropRequested = new AtomicBoolean();
    private volatile boolean released;
    private final Runnable settle = this::requestRegion;
    private final Runnable histRetry = this::requestRender;

    public EditorView(Context c) {
        super(c);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        gl = new Gl(c.getApplicationContext());
        setRenderer(gl);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        loader = new EditZoomLoader(c, new EditZoomLoader.Sink() {
            @Override
            public void decoded(EditZoomLoader.Result r) {
                // GLSurfaceView runs queued events while paused, without a current context: the upload waits for a frame
                EditZoomLoader.Result old = pending.getAndSet(r);
                if (old != null) old.region.free();
                if (released) freePending(); // the view went meanwhile
                else requestRender();
            }

            @Override
            public void unavailable(EditSource s, String why) {
                note(s, why);
            }

            @Override
            public void note(EditSource s, String text) {
                main.post(() -> {
                    Callbacks cb = callbacks;
                    if (cb != null && s == source) cb.note(text);
                    requestRender();
                });
            }
        });
    }

    public void setCallbacks(@Nullable Callbacks c) {
        callbacks = c;
    }

    public void setSource(EditSource s) {
        boolean changed = s != source;
        source = s;
        queueEvent(() -> uploaded = false);
        if (changed) {
            loader.closeReader();
            freePending();
            // P367: the export's output limit for this photo (a binder call: once a source, on the UI thread)
            outMaxHint = s != null ? EditExporter.outMax(EditSource.memoryBudget(getContext())) : EditSource.OUT_MAX;
        }
        stopZoomAnimation();
        refreshFrame(false);
        requestRender();
    }

    public void setState(EditState s, boolean cropMode, boolean before) {
        EditState copy = s.copy();
        String g = EditZoom.geometryKey(copy);
        boolean geometryChanged = !g.equals(geometry);
        this.state = copy;
        this.cropMode = cropMode;
        this.before = before;
        geometry = g;
        stateSet = true;
        // P367: the zoom lives in the displayed crop's frame: a new geometry (or the crop tab) puts it back to the fit
        if (geometryChanged || cropMode) stopZoomAnimation();
        refreshFrame(!geometryChanged && !cropMode);
        requestRender();
    }

    /** Margin around the picture (px): room for the crop handles in the crop tab. */
    static int margin(Context c, boolean crop) {
        return GalleryUi.dp(c, crop ? 28 : 10);
    }

    /**
     * The view rect the picture takes: in the crop tab the whole turned frame (wb x hb), else the crop; fitted into a
     * {@code w x h} view with {@code margin}.
     */
    static RectF canvas(float w, float h, float margin, EditState s, double ow, double oh, boolean crop) {
        double[] b = CropMath.baseSize(ow, oh, s.quarterTurns);
        double aw = crop ? b[0] : b[0] * (s.cropR - s.cropL), ah = crop ? b[1] : b[1] * (s.cropB - s.cropT);
        float aw2 = w - 2 * margin, ah2 = h - 2 * margin;
        double k = Math.min(aw2 / Math.max(1e-6, aw), ah2 / Math.max(1e-6, ah));
        float fw = (float) (aw * k), fh = (float) (ah * k);
        float l = (w - fw) / 2f, t = (h - fh) / 2f;
        return new RectF(l, t, l + fw, t + fh);
    }

    /** The oriented preview size (what the crop math works in). */
    static double[] oriented(EditSource s) {
        int w = s.previewWidth(), h = s.previewHeight();
        return CropMath.swapsSides(s.orientation) ? new double[]{h, w} : new double[]{w, h};
    }

    /** P367: the oriented size of the original (the zoom's 100 %); the preview's when the route did not say. */
    static double[] orientedFull(EditSource s) {
        int w = s.orientedWidth(), h = s.orientedHeight();
        return w > 0 && h > 0 ? new double[]{w, h} : oriented(s);
    }

    /** P367: preview px per original px (1 when the preview is the original). */
    static double previewPerOriginal(EditSource s) {
        int full = Math.max(s.fullW, s.fullH);
        return full > 0 ? Math.min(1.0, Math.max(s.previewWidth(), s.previewHeight()) / (double) full) : 1.0;
    }

    // ---------------------------------------------------------------- P367: zoom (UI thread)

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refreshFrame(true);
    }

    /** Whether the picture can zoom now: a source, laid out, not the crop tab. */
    public boolean zoomable() {
        return frameReady && !cropMode;
    }

    /** Whether the picture is zoomed past the fit. */
    public boolean isZoomed() {
        return zoomView != null;
    }

    /** Pinch: {@code factor} about the fingers' focal point (view px). */
    public void zoomBy(float factor, float fx, float fy) {
        if (!zoomable()) return;
        stopZoomAnimation();
        if (zoom.zoomAbout(factor, fx, fy)) publishZoom();
    }

    /** Drag: the picture follows the finger(s) by dx, dy view px (only while zoomed). */
    public void panBy(float dx, float dy) {
        if (!zoomable() || !zoom.isZoomed()) return;
        stopZoomAnimation();
        zoom.panBy(dx, dy);
        publishZoom();
    }

    /** Double tap at (x, y): the fit <-> 100 % centred there, animated. */
    public void toggleZoom(float x, float y) {
        if (!zoomable()) return;
        animateTo(zoom.toggleTarget(x, y));
    }

    /** Back to the fit (animated when asked). */
    public void resetZoom(boolean animate) {
        if (!frameReady) return;
        if (animate && zoom.isZoomed()) {
            EditZoom t = zoom.copy();
            t.reset();
            animateTo(new double[]{t.scale, t.left, t.top});
            return;
        }
        stopZoomAnimation();
        zoom.reset();
        publishZoom();
    }

    /**
     * P367 test hook (ExternalEdit.ARG_ZOOM): start at {@code scale} (1.0 = 100 % of the original's pixels: the copy's 1:1 when
     * it has them, EditZoom.hundred) with the crop point (cx, cy) (0..1) at the view's centre, once the picture is shown.
     */
    public void startZoom(float scale, float cx, float cy) {
        pendingZoom = new float[]{scale, cx, cy};
        refreshFrame(true);
    }

    /** Stops a running double-tap / back animation where it is (a finger came down). */
    public void stopZoomAnimation() {
        if (zoomAnim == null) return;
        ValueAnimator a = zoomAnim;
        zoomAnim = null;
        animToken = null;
        a.cancel();
        zoom.clamp();
        publishZoom();
    }

    /** Between two clamped states (the scale and the corner linearly: the displayed rect morphs), EditMotion's timing. */
    private void animateTo(double[] to) {
        stopZoomAnimation();
        final double s0 = zoom.scale, l0 = zoom.left, t0 = zoom.top;
        final Object token = new Object();
        animToken = token;
        // the first update may come inside start() (animations off: straight to the end), before zoomAnim is assigned
        zoomAnim = EditMotion.fraction(0f, 1f, EditMotion.MEDIUM, EditMotion.MOVE, f -> {
            if (animToken != token) return;
            if (f >= 1f) zoom.set(to[0], to[1], to[2]);
            else zoom.set(s0 + (to[0] - s0) * f, l0 + (to[1] - l0) * f, t0 + (to[2] - t0) * f);
            publishZoom();
        });
    }

    /** The fit for the current view, state and source; {@code keep}: a zoomed view stays zoomed (a resized view). */
    private void refreshFrame(boolean keep) {
        EditSource s = source;
        int w = getWidth(), h = getHeight();
        frameReady = s != null && w > 0 && h > 0;
        if (frameReady) {
            double[] o = orientedFull(s);
            EditState st = state;
            RectF fit = canvas(w, h, margin(getContext(), false), st, o[0], o[1], false);
            double[] crop = CropMath.cropPixels(st, o[0], o[1]);
            zoom.frame(w, h, fit.left, fit.top, fit.width(), fit.height(), crop[0], crop[1], keep && !cropMode);
            // the copy «Сохранить копию» would write of this crop: its 1:1 is «100 %», its pixels the screen's there
            int[] copy = EditExporter.outputSize(crop[0], crop[1], outMaxHint);
            zoom.copySize(copy[0], copy[1]);
            if (pendingZoom != null && stateSet && !cropMode) {
                double sc = Math.abs(pendingZoom[0] - 1f) < 1e-6 ? zoom.hundred() : pendingZoom[0];
                zoom.zoomTo(sc, pendingZoom[1], pendingZoom[2]);
                pendingZoom = null;
            }
        }
        publishZoom();
    }

    /** The zoom to the GL thread (a snapshot), the region asked for once it settles, the pill told. */
    private void publishZoom() {
        ZoomView z = frameReady && !cropMode && zoom.isZoomed() && geometry != null ? new ZoomView(zoom, geometry) : null;
        ZoomView was = zoomView;
        zoomView = z;
        requestRender();
        main.removeCallbacks(settle);
        if (z != null) main.postDelayed(settle, SETTLE_MS);
        else if (was != null) loader.cancel(); // back at the fit: a region still decoding is not wanted
        notifyZoom();
    }

    private void scheduleRegion() {
        main.removeCallbacks(settle);
        if (zoomView != null) main.postDelayed(settle, SETTLE_MS);
    }

    private void notifyZoom() {
        Callbacks cb = callbacks;
        if (cb != null) cb.zoom((int) Math.round(zoom.percent()), zoomView != null, zoomView != null ? detail : DETAIL_NONE);
    }

    /** The settled view past the preview's resolution: its full-resolution region from the loader's worker. */
    private void requestRegion() {
        EditSource s = source;
        ZoomView z = zoomView;
        if (s == null || z == null || cropMode) return;
        if (!EditZoom.fullResWanted(z.scale, previewPerOriginal(s))) {
            loader.cancel(); // the preview serves this view: nothing to decode
            return;
        }
        loader.request(new EditZoomLoader.Request(s, state, z.scale, z.left, z.top, z.dw, z.dh, getWidth(), getHeight()));
    }

    private void freePending() {
        EditZoomLoader.Result p = pending.getAndSet(null);
        if (p != null) p.region.free();
    }

    /**
     * P367: the zoom as the GL thread draws it (immutable): view px per original px, where the displayed crop goes
     * (EditZoom.placement: the copy's pixels on the screen's at 100 %) and the copy's size the grain follows.
     */
    private static final class ZoomView {
        final double scale, left, top, dw, dh;
        final int copyW, copyH;
        final String geometry;

        ZoomView(EditZoom z, String geometry) {
            scale = z.scale;
            double[] p = z.placement();
            left = p[0];
            top = p[1];
            dw = p[2];
            dh = p[3];
            copyW = Math.max(1, z.copyW());
            copyH = Math.max(1, z.copyH());
            this.geometry = geometry;
        }

        RectF rect() {
            return new RectF((float) left, (float) top, (float) (left + dw), (float) (top + dh));
        }
    }

    /** P367: the region on the GPU (GL thread): its texture, where it came from, for the one log line. */
    private static final class Held {
        final EditZoomLoader.Info info;
        final EditSource.Region region;
        final String geometry;
        final int[] rect;
        final int tex;
        final boolean own;
        final long decodeMs;
        long uploadNs;
        boolean logged;

        Held(EditZoomLoader.Result r, int tex, boolean own) {
            info = r.info;
            region = r.region;
            geometry = r.request.geometry;
            rect = new int[]{r.region.x, r.region.y, r.region.w, r.region.h};
            this.tex = tex;
            this.own = own;
            decodeMs = r.decodeMs;
        }
    }

    private volatile boolean uploaded;

    private final class Gl implements Renderer {
        private final Context context;
        private EditRenderer r;
        private int srcTex, vw, vh;
        private boolean ownTex, broken;
        private String toneKey;
        private EditMath.Uniforms uniforms;
        private long lastHist;
        private ByteBuffer histBuf;
        /** P367: what the last histogram was of (tone, geometry, source): zoom and pan alone do not redo it. */
        @Nullable private String histKey;
        @Nullable private EditSource histSource;
        // P367: the full-resolution region and the renderer it goes through (its own targets, the same programs' sources)
        @Nullable private EditRenderer zr;
        private boolean zProxy, zActive, heldFine;
        @Nullable private Held held;
        @Nullable private EditMath.Uniforms zUniforms;
        @Nullable private String zKey;
        @Nullable private EditMath.SourceColour zColour;
        private int lastDetail = DETAIL_NONE;

        Gl(Context c) {
            context = c;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            r = new EditRenderer();
            uploaded = false;
            broken = false;
            // P367: a new context: the old region's textures went with the old one; ask again when zoomed
            forgetZoom();
            main.post(EditorView.this::scheduleRegion);
            try {
                r.init(context.getAssets());
            } catch (Exception e) {
                broken = true;
                Log.e(TAG, "editor GL init: " + e);
                Callbacks cb = callbacks;
                if (cb != null) main.post(() -> cb.failed(String.valueOf(e.getMessage())));
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            vw = width;
            vh = height;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, vw, vh);
            GLES30.glClearColor(((bg >> 16) & 0xFF) / 255f, ((bg >> 8) & 0xFF) / 255f, (bg & 0xFF) / 255f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            EditSource s = source;
            if (s == null || broken) return;
            if (!uploaded) {
                dropZoom(false);
                if (ownTex) EditGl.deleteTexture(srcTex);
                if (s.previewRaw != null) {
                    srcTex = r.rawSource(s.previewRaw, 0, 0, s.previewRaw.width, s.previewRaw.height);
                    ownTex = false;
                } else if (s.preview != null) {
                    srcTex = EditRenderer.bitmapSource(s.preview);
                    ownTex = true;
                } else return;
                r.invalidate();
                r.setProxySource(srcTex, s.previewWidth(), s.previewHeight());
                uploaded = true;
                toneKey = null;
            }
            // P367: the UI's drop (the export starting) and a decoded region: GL work here, where the context is current
            if (dropRequested.getAndSet(false)) dropZoom(false);
            EditZoomLoader.Result res = pending.getAndSet(null);
            if (res != null) install(res);
            EditState st = state;
            EditState tone = before ? st.withNeutralTone() : st;
            String key = toneKey(tone);
            if (!key.equals(toneKey) || uniforms == null) {
                uniforms = EditMath.Uniforms.of(tone, s.colour);
                toneKey = key;
            }
            int pw = s.previewWidth(), ph = s.previewHeight();
            double[] o = oriented(s);
            boolean crop = cropMode;
            // P367: the zoom (null at the fit, in the crop tab, or for a geometry the UI is just resetting)
            ZoomView z = crop ? null : zoomView;
            if (z != null && !z.geometry.equals(EditZoom.geometryKey(st))) z = null;
            if (z == null && zActive) dropZoom(false);
            double pk = previewPerOriginal(s);
            boolean wanted = z != null && EditZoom.fullResWanted(z.scale, pk);
            Held h = wanted ? usable(s, st, z, pk) : null;
            boolean fine = h != null && heldFine;
            long now = SystemClock.uptimeMillis();
            Callbacks cb = callbacks;
            // P367: the histogram (always the whole crop) only when what it shows changed, at most every HIST_MS
            String hk = crop ? null : key + "|" + EditZoom.geometryKey(st);
            boolean histWanted = cb != null && hk != null && (!hk.equals(histKey) || s != histSource);
            boolean histDue = histWanted && now - lastHist >= HIST_MS;
            if (histWanted && !histDue) {
                // throttled: one more frame when it is due, so the last change gets its histogram
                main.removeCallbacks(histRetry);
                main.postDelayed(histRetry, HIST_MS - (now - lastHist) + 1);
            }
            if (h == null || histDue) {
                // the preview's chain: the picture (when no region serves) and the histogram
                r.updateProxy(uniforms);
                r.renderSource(srcTex, pw, ph, Math.max(pw, ph), CropMath.Affine.IDENTITY, uniforms, true);
            }
            if (h == null) {
                RectF c = z != null ? z.rect() : canvas(vw, vh, margin(context, crop), st, o[0], o[1], crop);
                EditRenderer.Finish f = finishFor(st, c, crop, o);
                if (z != null) {
                    // zoomed: the copy's grain (its output size, its lattice), faded below its 1:1 as the preview fades it
                    int[] copy = copySize(s, st, z);
                    f.outLongSide = (float) Math.min(Math.max(z.dw, z.dh), Math.max(copy[0], copy[1]));
                    r.latticeAspect = copy[0] / (float) copy[1];
                }
                f.fbo = 0;
                f.w = vw;
                f.h = vh;
                f.flipY = true;
                r.finish(f, st, uniforms);
            } else drawRegion(h, s, st, tone, z, pw, ph);
            if (histDue) {
                lastHist = now;
                histKey = hk;
                histSource = s;
                histogram(st, o, cb);
            }
            int d;
            if (!wanted) d = DETAIL_NONE;
            else if (fine) d = h.info.perOriginal < 0.999 && z.scale > h.info.perOriginal * EditZoom.PREVIEW_LIMIT ? DETAIL_REDUCED : DETAIL_FULL;
            else d = loader.unavailable(s) ? DETAIL_UNAVAILABLE : DETAIL_LOADING;
            if (d != lastDetail) {
                lastDetail = d;
                detail = d;
                main.post(EditorView.this::notifyZoom);
            }
        }

        private EditRenderer.Finish finishFor(EditState st, RectF c, boolean crop, double[] o) {
            EditRenderer.Finish f = new EditRenderer.Finish();
            f.mode = crop ? 1 : 0;
            CropMath.Affine toCanvas = CropMath.Affine.translate(-c.left, -c.top).then(CropMath.Affine.scale(1.0 / c.width(), 1.0 / c.height()));
            f.outToBase = crop ? toCanvas : toCanvas.then(CropMath.cropToBase(st));
            f.baseToImg = CropMath.baseToOriented(st, o[0], o[1]);
            f.imgToTex = CropMath.orientationToDisplay(source.orientation).inverse();
            f.bg = bgRgb();
            f.outLongSide = crop ? Math.max(c.width() * (st.cropR - st.cropL), c.height() * (st.cropB - st.cropT)) : Math.max(c.width(), c.height());
            double[] px = CropMath.cropPixels(st, o[0], o[1]);
            r.latticeAspect = (float) (px[0] / Math.max(1e-6, px[1]));
            return f;
        }

        private float[] bgRgb() {
            return new float[]{((bg >> 16) & 0xFF) / 255f, ((bg >> 8) & 0xFF) / 255f, (bg & 0xFF) / 255f};
        }

        // ------------------------------------------------------------ P367: the full-resolution region (GL thread)

        /**
         * The copy's size {W, H} for this view (the zoomed preview's grain): the held reader's plan (EditExporter's own) when
         * there is one for this source, else the UI's from the original's size and the export's limit.
         */
        private int[] copySize(EditSource s, EditState st, ZoomView z) {
            Held h = held;
            if (h != null && h.info.source == s) {
                EditExporter.Plan p = h.info.plan(st);
                return new int[]{p.W, p.H};
            }
            return new int[]{z.copyW, z.copyH};
        }

        /**
         * The held region when it serves this view: this source and geometry, holding the visible area + overlap, fine enough
         * ({@link #heldFine}) - or at least sharper than the preview (zooming in past a coarser region: it shows until the finer
         * one is there).
         */
        @Nullable
        private Held usable(EditSource s, EditState st, ZoomView z, double pk) {
            heldFine = false;
            Held h = held;
            if (h == null || zr == null || h.info.source != s || !h.geometry.equals(z.geometry)) return null;
            double[] need = h.info.need(st, vw, vh, z.left, z.top, z.dw, z.dh);
            if (need == null || !EditZoom.covers(h.rect, need, h.info.sw, h.info.sh, h.info.slack(h.region.level))) return null;
            boolean fine = h.info.fine(h.region.level, z.scale, need, vw, vh);
            if (!fine && h.region.level >= h.info.perOriginal / Math.max(1e-9, pk) * 0.95) return null;
            heldFine = fine;
            return h;
        }

        /**
         * A decoded region (from {@link #pending}, in onDrawFrame: the context is current): uploaded (the previous one freed),
         * its CPU picture dropped. A failed upload turns full resolution off for the source (said once) instead of asking again.
         */
        private void install(EditZoomLoader.Result res) {
            EditSource.Region g = res.region;
            ZoomView z = cropMode ? null : zoomView;
            if (broken || r == null || !uploaded || res.request.source != source || z == null || !res.request.geometry.equals(z.geometry)) {
                // not for what is shown any more: the view asks again when it is zoomed
                g.free();
                loader.forget();
                if (z != null) main.post(EditorView.this::scheduleRegion);
                return;
            }
            long t0 = System.nanoTime();
            dropHeld();
            try {
                if (zr == null) {
                    EditRenderer n = new EditRenderer();
                    try {
                        n.init(context.getAssets());
                    } catch (IOException | RuntimeException e) {
                        n.release();
                        throw e;
                    }
                    zr = n;
                    zProxy = false;
                }
                zr.invalidate();
                int tex = g.upload(zr);
                if (tex == 0) throw new IOException("empty region");
                Held h = new Held(res, tex, !g.rendererOwnsTexture());
                h.uploadNs = System.nanoTime() - t0;
                held = h;
                zActive = true;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "full-resolution region upload: " + e);
                dropZoom(false);
                loader.markFailed(res.request.source, Lang.t("Полное разрешение недоступно (GPU: " + e.getClass().getSimpleName()
                        + "): увеличение по превью", "Full resolution unavailable (GPU: " + e.getClass().getSimpleName() + "): zooming the preview"));
            } finally {
                g.free();
            }
        }

        /**
         * The visible area from the region, an export tile's math: the region's texture (reader px x, y, w, h at texW x texH)
         * through renderSource with the source's long side in its texels, the proxy of the preview's decode (as the export), then
         * finish with view px -> crop uv of the zoom (EditZoom.placement), the reader's oriented size, the copy's grain size and
         * lattice aspect (EditExporter.Plan).
         */
        private void drawRegion(Held h, EditSource s, EditState st, EditState tone, ZoomView z, int pw, int ph) {
            EditSource.Region g = h.region;
            EditZoomLoader.Info in = h.info;
            long t0 = h.logged ? 0 : System.nanoTime();
            if (zUniforms == null || !toneKey.equals(zKey) || zColour != g.colour) {
                zUniforms = g.colour == s.colour ? uniforms : EditMath.Uniforms.of(tone, g.colour);
                zKey = toneKey;
                zColour = g.colour;
            }
            if (!zProxy) {
                zr.setProxySource(srcTex, pw, ph);
                zProxy = true;
            }
            zr.updateProxy(uniforms);
            CropMath.Affine texToSrcUv = CropMath.Affine.scale(g.w, g.h).then(CropMath.Affine.translate(g.x, g.y))
                    .then(CropMath.Affine.scale(1.0 / in.sw, 1.0 / in.sh));
            zr.renderSource(h.tex, g.texW, g.texH, (float) (Math.max(in.sw, in.sh) / g.level), texToSrcUv, zUniforms, true);
            EditRenderer.Finish f = new EditRenderer.Finish();
            f.fbo = 0;
            f.w = vw;
            f.h = vh;
            f.flipY = true;
            f.mode = 0;
            f.outToBase = CropMath.Affine.translate(-z.left, -z.top).then(CropMath.Affine.scale(1.0 / z.dw, 1.0 / z.dh))
                    .then(CropMath.cropToBase(st));
            f.baseToImg = CropMath.baseToOriented(st, in.ow, in.oh);
            f.imgToTex = CropMath.orientationToDisplay(in.orientation).inverse().then(texToSrcUv.inverse());
            f.bg = bgRgb();
            // the copy's grain amplitude (its output pixels); below its 1:1 the screen's, as the preview fades it
            EditExporter.Plan plan = in.plan(st);
            f.outLongSide = (float) Math.min(plan.longSide(), Math.max(z.dw, z.dh));
            zr.latticeAspect = plan.latticeAspect();
            zr.finish(f, st, zUniforms);
            if (!h.logged) {
                h.logged = true;
                GLES30.glFinish();
                long renderMs = (System.nanoTime() - t0) / 1_000_000, uploadMs = h.uploadNs / 1_000_000;
                Log.i(TAG, String.format(Locale.ROOT, "P367 full-res region shown: route %s, region x %d y %d w %d h %d (source px of %dx%d; "
                                + "texture %dx%d, level %.2f), decode %d ms, upload+render %d ms (upload %d, render %d), zoom %.0f %%",
                        in.route, g.x, g.y, g.w, g.h, in.sw, in.sh, g.texW, g.texH, g.level, h.decodeMs, uploadMs + renderMs, uploadMs,
                        renderMs, z.scale * 100));
            }
        }

        private void dropHeld() {
            Held h = held;
            held = null;
            heldFine = false;
            if (h != null && h.own) EditGl.deleteTexture(h.tex);
        }

        /** The region and its renderer's textures freed ({@code all}: the renderer too); the loader decodes anew next time. */
        void dropZoom(boolean all) {
            dropHeld();
            if (zr != null) {
                if (all) {
                    zr.release();
                    zr = null;
                } else zr.releaseTargets();
            }
            clearZoom();
        }

        /**
         * The region and its renderer forgotten without GL calls: a new context (the old one took their objects with it), or
         * none current (the view is going, its context with it).
         */
        void forgetZoom() {
            held = null;
            zr = null;
            clearZoom();
        }

        private void clearZoom() {
            heldFine = false;
            zProxy = false;
            zActive = false;
            zUniforms = null;
            zKey = null;
            zColour = null;
            loader.forget();
        }

        private void histogram(EditState st, double[] o, Callbacks cb) {
            double[] px = CropMath.cropPixels(st, o[0], o[1]);
            double k = 160.0 / Math.max(px[0], px[1]);
            int w = Math.max(8, (int) Math.round(px[0] * k)), h = Math.max(8, (int) Math.round(px[1] * k));
            r.hist.ensure(w, h, GLES30.GL_RGBA8, false, 1);
            RectF c = new RectF(0, 0, w, h);
            EditRenderer.Finish f = finishFor(st, c, false, o);
            f.fbo = r.hist.fbo;
            f.w = w;
            f.h = h;
            f.flipY = false;
            r.finish(f, st, uniforms);
            if (histBuf == null || histBuf.capacity() < w * h * 4) histBuf = ByteBuffer.allocateDirect(w * h * 4);
            histBuf.clear();
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, histBuf);
            byte[] bytes = new byte[w * h * 4];
            histBuf.get(bytes);
            int[][] counts = EditHistogramView.of(bytes, w * h);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            main.post(() -> cb.histogram(counts));
        }
    }

    /** P367: whether this thread has a current EGL context (GLSurfaceView runs queued events without one while paused). */
    static boolean hasContext() {
        return !EGL14.EGL_NO_CONTEXT.equals(EGL14.eglGetCurrentContext());
    }

    /** The state without its geometry, as text: equal keys share the uniforms (a crop drag does not rebuild the tone tables). */
    static String toneKey(EditState s) {
        EditState t = s.copy();
        t.resetGeometry();
        return t.toJson();
    }

    /**
     * P367: the export starts: the zoom back at the fit, its region dropped on the next frame, its reader closed on the loader's
     * worker. The future is done once that reader is closed: the export waits for it (EditorViewModel.export), so the zoom's
     * memory is the export's.
     */
    public Future<?> closeFullRes() {
        resetZoom(false);
        freePending();
        dropRequested.set(true);
        requestRender();
        return loader.closeReader();
    }

    /**
     * Frees the GL side (the fragment's view is going); P367: the zoom's reader closed on its worker, the worker ended, a region
     * still waiting freed. The context goes with the view (GLSurfaceView ends its thread on detach), so GL objects are deleted
     * only while it is current.
     */
    public void release() {
        released = true;
        if (zoomAnim != null) {
            ValueAnimator a = zoomAnim;
            zoomAnim = null;
            animToken = null;
            a.cancel();
        }
        main.removeCallbacks(settle);
        main.removeCallbacks(histRetry);
        loader.shutdown();
        freePending();
        queueEvent(() -> {
            if (hasContext()) gl.dropZoom(true);
            else gl.forgetZoom();
            uploaded = false;
        });
    }
}
