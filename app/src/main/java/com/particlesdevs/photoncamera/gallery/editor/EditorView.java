package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.RectF;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * P351: the editor's live picture: a GLSurfaceView (OpenGL ES 3, its own context, drawn when something changed) running
 * {@link EditRenderer} on the preview decode. In the crop tab it shows the whole turned frame (the crop overlay on top uses
 * {@link #canvas}), else the crop fitted into the view. A long press elsewhere shows the photo without the tone edits.
 */
public final class EditorView extends GLSurfaceView {
    private static final String TAG = "EditorView";

    public interface Callbacks {
        void histogram(int[][] counts);

        void failed(String why);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile EditSource source;
    private volatile EditState state = new EditState();
    private volatile boolean cropMode, before;
    @Nullable private volatile Callbacks callbacks;
    private final int bg = GalleryUi.BG;

    public EditorView(Context c) {
        super(c);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(new Gl(c.getApplicationContext()));
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setCallbacks(@Nullable Callbacks c) {
        callbacks = c;
    }

    public void setSource(EditSource s) {
        source = s;
        queueEvent(() -> uploaded = false);
        requestRender();
    }

    public void setState(EditState s, boolean cropMode, boolean before) {
        this.state = s.copy();
        this.cropMode = cropMode;
        this.before = before;
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

        Gl(Context c) {
            context = c;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            r = new EditRenderer();
            uploaded = false;
            broken = false;
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
            EditState st = state;
            EditState tone = before ? st.withNeutralTone() : st;
            String key = toneKey(tone);
            if (!key.equals(toneKey) || uniforms == null) {
                uniforms = EditMath.Uniforms.of(tone, s.colour);
                toneKey = key;
            }
            int pw = s.previewWidth(), ph = s.previewHeight();
            r.updateProxy(uniforms);
            r.renderSource(srcTex, pw, ph, Math.max(pw, ph), CropMath.Affine.IDENTITY, uniforms, true);
            double[] o = oriented(s);
            boolean crop = cropMode;
            RectF c = canvas(vw, vh, margin(context, crop), st, o[0], o[1], crop);
            EditRenderer.Finish f = finishFor(st, c, crop, o);
            f.fbo = 0;
            f.w = vw;
            f.h = vh;
            f.flipY = true;
            r.finish(f, st, uniforms);
            long now = SystemClock.uptimeMillis();
            Callbacks cb = callbacks;
            if (cb != null && !crop && now - lastHist > 120) {
                lastHist = now;
                histogram(st, o, cb);
            }
        }

        private EditRenderer.Finish finishFor(EditState st, RectF c, boolean crop, double[] o) {
            EditRenderer.Finish f = new EditRenderer.Finish();
            f.mode = crop ? 1 : 0;
            CropMath.Affine toCanvas = CropMath.Affine.translate(-c.left, -c.top).then(CropMath.Affine.scale(1.0 / c.width(), 1.0 / c.height()));
            f.outToBase = crop ? toCanvas : toCanvas.then(CropMath.cropToBase(st));
            f.baseToImg = CropMath.baseToOriented(st, o[0], o[1]);
            f.imgToTex = CropMath.orientationToDisplay(source.orientation).inverse();
            f.bg = new float[]{((bg >> 16) & 0xFF) / 255f, ((bg >> 8) & 0xFF) / 255f, (bg & 0xFF) / 255f};
            f.outLongSide = crop ? Math.max(c.width() * (st.cropR - st.cropL), c.height() * (st.cropB - st.cropT)) : Math.max(c.width(), c.height());
            double[] px = CropMath.cropPixels(st, o[0], o[1]);
            r.latticeAspect = (float) (px[0] / Math.max(1e-6, px[1]));
            return f;
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

    /** The state without its geometry, as text: equal keys share the uniforms (a crop drag does not rebuild the tone tables). */
    static String toneKey(EditState s) {
        EditState t = s.copy();
        t.resetGeometry();
        return t.toJson();
    }

    /** Frees the GL side (the fragment's view is going). */
    public void release() {
        queueEvent(() -> uploaded = false);
    }
}
