package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_NO_ERROR;
import static android.opengl.GLES20.GL_RENDERBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindRenderbuffer;
import static android.opengl.GLES20.glFramebufferRenderbuffer;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenRenderbuffers;
import static android.opengl.GLES20.glGetError;
import static android.opengl.GLES20.glRenderbufferStorage;
import static android.opengl.GLES30.GL_DRAW_FRAMEBUFFER;
import static android.opengl.GLES30.GL_RGBA8;
import static android.opengl.GLES30.glReadPixels;
import static android.opengl.GLES30.glViewport;

import com.particlesdevs.photoncamera.util.Allocator;

public class GLCoreBlockProcessing extends GLContext implements AutoCloseable {
    private static String TAG = "GLCoreBlockProcessing";
    public GLImage mOut = null;
    public Point shift = new Point(0,0);
    private final int mOutWidth, mOutHeight;
    public ByteBuffer mBlockBuffer;
    public ByteBuffer mOutBuffer;
    private final GLFormat mglFormat;
    /**
     * Full-frame readback buffer, allocated at the first full readback instead of with the context: during the
     * passes it was idle native memory (~200 MB at 50 MP RGBA8), and pipelines that never read the full frame
     * back (the Ultra HDR gain-map pass: ~400 MB FP16 at 50 MP) never allocate it at all. The allocation kind is
     * fixed here: {@link #allocation} is reassigned by the sized readback variants.
     */
    private final GLDrawParams.Allocate mOutAllocation;
    /** Bytes of the full-frame readback; above 2 GB (one Java buffer) the readback fails with a clear message when it is needed. */
    private final long mOutCapacity;

    public GLDrawParams.Allocate allocation = GLDrawParams.Allocate.Heap;
    /**
     * 10-bit HEIC: the tile target is RGB10_A2 and {@link #drawBlocksToBitmap()} returns an RGBA_1010102 bitmap
     * ({@link #drawBlocksToBitmap1010102()}). Off for every other use; cleared when the driver cannot render or read it.
     */
    private boolean tenBit;

    public static void checkEglError(String op) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            String msg = op + ": glError: " + GLUtils.getEGLErrorString(error) + " (" + Integer.toHexString(error) + ")";
            String TAG = "GLCoreBlockProcessing";
            Log.v(TAG, msg);
        }
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat, GLDrawParams.Allocate alloc) {
        this(size, glFormat,alloc);
        allocation = alloc;
        mOut = out;
    }

    /**
     * The post pipeline's output processing; {@code tenBitOutput} (Android 13+, an RGBA8 output format) renders the last
     * pass into an RGB10_A2 tile target and reads it back as RGBA_1010102 for the 10-bit HEIC. Same 4 bytes per pixel.
     */
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat, GLDrawParams.Allocate alloc, boolean tenBitOutput) {
        this(size, out, glFormat, alloc);
        if (tenBitOutput) enableTenBit();
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat) {
        this(size, glFormat, GLDrawParams.Allocate.Direct);
        mOut = out;
    }
    public GLCoreBlockProcessing(Point size, GLFormat glFormat) {
        this(size,glFormat, GLDrawParams.Allocate.Direct);
    }
    public GLCoreBlockProcessing(Point size, GLFormat glFormat, GLDrawParams.Allocate alloc) {
        super(size.x, GLDrawParams.TileSize);
        allocation = alloc;
        mOutAllocation = alloc;
        mglFormat = glFormat;
        mOutWidth = size.x;
        mOutHeight = size.y;
        mBlockBuffer = ByteBuffer.allocateDirect(mOutWidth * GLDrawParams.TileSize * mglFormat.mFormat.mSize * mglFormat.mChannels);
        createTileTarget(glFormat);
        mOutCapacity = (long) mOutWidth * mOutHeight * mglFormat.mFormat.mSize * mglFormat.mChannels;
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat,ByteBuffer output) {
        super(size.x, GLDrawParams.TileSize);
        output.position(0);
        mglFormat = glFormat;
        mOutWidth = size.x;
        mOutHeight = size.y;
        mBlockBuffer = ByteBuffer.allocateDirect(mOutWidth * GLDrawParams.TileSize * mglFormat.mFormat.mSize * mglFormat.mChannels);
        createTileTarget(glFormat);
        mOutBuffer = output;
        mOutAllocation = GLDrawParams.Allocate.None;
        // A null output was accepted before (texture-only scripts): keep that instead of failing here.
        mOutCapacity = output != null ? output.capacity() : 0;
        mOut = out;
    }

    /**
     * Render target of the readbacks: every readback draws one tile of at most TileSize rows at the origin
     * (viewport 0,0,w,tileRows) and reads it back, so the renderbuffer only needs those rows. A full-frame
     * renderbuffer was ~200 MB (RGBA8) / ~400 MB (FP16) of GPU memory at 50 MP for the whole pipeline.
     */
    private void createTileTarget(GLFormat glFormat) {
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glGenFramebuffers(1,bindFB,0);
        glGenRenderbuffers(1,bindRB,0);
        glBindRenderbuffer(GL_RENDERBUFFER,bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, glFormat.getGLFormatInternal(), mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
    }

    /** Whether the readback gives the RGBA_1010102 bitmap of the 10-bit HEIC. */
    public boolean isTenBit() {
        return tenBit;
    }

    /**
     * Re-stores the tile target as RGB10_A2 (a colour-renderable format of GL ES 3.0 that glReadPixels returns as RGBA /
     * UNSIGNED_INT_2_10_10_10_REV: R in the low 10 bits, the layout of Bitmap.Config.RGBA_1010102). The output stays
     * RGBA8-sized (4 bytes per pixel). Falls back to the RGBA8 target when the framebuffer is not complete.
     */
    private void enableTenBit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mglFormat.mChannels != 4 || mglFormat.mFormat.mSize != 1) {
            Log.w(TAG, "10-bit readback needs Android 13 and an RGBA8 output: 8-bit output");
            return;
        }
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glBindRenderbuffer(GL_RENDERBUFFER, bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, GLES30.GL_RGB10_A2, mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
        final int status = GLES30.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        final int error = glGetError();
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE && error == GL_NO_ERROR) {
            tenBit = true;
            Log.d(TAG, "10-bit output: RGB10_A2 tile target " + mOutWidth + "x" + rows);
        } else {
            Log.w(TAG, "RGB10_A2 target unusable (status 0x" + Integer.toHexString(status) + ", error 0x" + Integer.toHexString(error)
                    + "): 8-bit output");
            restoreRgba8Target();
        }
    }

    /** Back to the RGBA8 tile target of the output format (after a failed 10-bit setup or readback). */
    private void restoreRgba8Target() {
        tenBit = false;
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glBindRenderbuffer(GL_RENDERBUFFER, bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, mglFormat.getGLFormatInternal(), mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
    }

    /** Allocates the full-frame readback buffer on first use (see {@link #mOutAllocation}). */
    private void ensureOutBuffer() {
        if (mOutBuffer != null || mOutAllocation == GLDrawParams.Allocate.None) return;
        final int capacity = GLLimits.bufferBytes(mOutWidth, mOutHeight, (long) mglFormat.mFormat.mSize * mglFormat.mChannels, "full-frame readback");
        if (mOutAllocation == GLDrawParams.Allocate.Direct) mOutBuffer = Allocator.allocate(capacity);
        else {
            // Full-frame output on the Java heap scales with resolution
            // (~256 MB at 64 MP) and was a direct OOM source.
            // From RealJohnGalt/PhotonCamera 6d2291eb.
            mOutBuffer = ByteBuffer.allocateDirect(capacity);
        }
        if (mOutBuffer == null)
            throw new IllegalStateException("readback buffer allocation of " + mOutCapacity + " bytes failed");
    }

    public void drawBlocksToOutput() {
        ensureOutBuffer();
        glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
        GLProg program = super.mProgram;
        GLBlockDivider divider = new GLBlockDivider(mOutHeight, GLDrawParams.TileSize);
        int[] row = new int[2];
        mOutBuffer.position(0);
        mBlockBuffer.position(0);
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];
            glViewport(0, 0, mOutWidth, height);
            checkEglError("glViewport");
            program.setVar("yOffset", y);
            program.draw();
            checkEglError("program");
            mBlockBuffer.position(0);
            glReadPixels(0, 0, mOutWidth, height, mglFormat.getGLFormatExternal(), mglFormat.getGLType(), mBlockBuffer);
            checkEglError("glReadPixels");
            if (height < GLDrawParams.TileSize) {
                // This can only happen 2 times at edges
                byte[] data = new byte[mOutWidth * height * mglFormat.mFormat.mSize * mglFormat.mChannels];
                mBlockBuffer.get(data);
                mOutBuffer.put(data);
            } else {
                mOutBuffer.put(mBlockBuffer);
            }
        }
        mOutBuffer.position(0);
        mBlockBuffer = null;
        if (mOut != null) mOut.byteBuffer = mOutBuffer;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }


    /** Rows per copy into the destination bitmap: a small strip bitmap instead of a full-frame buffer. */
    private static final int BITMAP_STRIP_ROWS = 16;

    /**
     * Renders the output program tile by tile straight into a new ARGB_8888 bitmap of the output size: the same
     * pixels as {@link #drawBlocksToOutput()} followed by {@code Bitmap.copyPixelsFromBuffer} (raw RGBA8 bytes,
     * copied with SRC), but without the full-frame readback buffer next to the bitmap (~200 MB at 50 MP). Only
     * for 4-channel 8-bit output formats.
     */
    public Bitmap drawBlocksToBitmap() {
        if (tenBit) return drawBlocksToBitmap1010102();
        if (mglFormat.mChannels != 4 || mglFormat.mFormat.mSize != 1)
            throw new IllegalStateException("bitmap readback needs an RGBA8 output, not " + mglFormat.mFormat + "x" + mglFormat.mChannels);
        final int tileRows = GLDrawParams.TileSize;
        if (mBlockBuffer == null || mBlockBuffer.capacity() < mOutWidth * 4 * Math.min(tileRows, mOutHeight))
            throw new IllegalStateException("tile buffer does not match TileSize " + tileRows);
        final int stripRows = tileRows % BITMAP_STRIP_ROWS == 0 ? BITMAP_STRIP_ROWS : tileRows;
        final Bitmap dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);
        try (TileBlitter blitter = new TileBlitter(dst, stripRows)) {
            glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
            GLProg program = super.mProgram;
            GLBlockDivider divider = new GLBlockDivider(mOutHeight, tileRows);
            int[] row = new int[2];
            while (divider.nextBlock(row)) {
                int y = row[0];
                int height = row[1];
                glViewport(0, 0, mOutWidth, height);
                checkEglError("glViewport");
                program.setVar("yOffset", y);
                program.draw();
                checkEglError("program");
                mBlockBuffer.clear();
                glReadPixels(0, 0, mOutWidth, height, mglFormat.getGLFormatExternal(), mglFormat.getGLType(), mBlockBuffer);
                checkEglError("glReadPixels");
                blitter.blit(mBlockBuffer, y, height);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            mBlockBuffer = null;
            return dst;
        } catch (RuntimeException e) {
            dst.recycle();
            throw e;
        }
    }

    /**
     * {@link #drawBlocksToBitmap()} of the 10-bit HEIC: the same tiles rendered into the RGB10_A2 target, read back as
     * GL_UNSIGNED_INT_2_10_10_10_REV words and blitted into an RGBA_1010102 bitmap (the bytes of both are the same packed
     * 32-bit pixels). If the driver rejects the first 10-bit readback, the whole output is rendered again in 8 bits.
     */
    private Bitmap drawBlocksToBitmap1010102() {
        final int tileRows = GLDrawParams.TileSize;
        if (mBlockBuffer == null || mBlockBuffer.capacity() < mOutWidth * 4 * Math.min(tileRows, mOutHeight))
            throw new IllegalStateException("tile buffer does not match TileSize " + tileRows);
        final int stripRows = tileRows % BITMAP_STRIP_ROWS == 0 ? BITMAP_STRIP_ROWS : tileRows;
        final Bitmap dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.RGBA_1010102);
        boolean done = false;
        try (TileBlitter blitter = new TileBlitter(dst, stripRows)) {
            glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
            GLProg program = super.mProgram;
            GLBlockDivider divider = new GLBlockDivider(mOutHeight, tileRows);
            int[] row = new int[2];
            boolean first = true;
            while (divider.nextBlock(row)) {
                int y = row[0];
                int height = row[1];
                glViewport(0, 0, mOutWidth, height);
                checkEglError("glViewport");
                program.setVar("yOffset", y);
                program.draw();
                checkEglError("program");
                mBlockBuffer.clear();
                glReadPixels(0, 0, mOutWidth, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, mBlockBuffer);
                if (first) {
                    first = false;
                    final int error = glGetError();
                    if (error != GL_NO_ERROR) {
                        Log.w(TAG, "10-bit readback rejected (0x" + Integer.toHexString(error) + "): the output is rendered in 8 bits");
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                        restoreRgba8Target();
                        dst.recycle();
                        return drawBlocksToBitmap();
                    }
                }
                blitter.blit(mBlockBuffer, y, height);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            mBlockBuffer = null;
            done = true;
            return dst;
        } finally {
            if (!done && !dst.isRecycled()) dst.recycle();
        }
    }

    /**
     * Copies tightly packed RGBA8 rows (a read-back tile, row 0 first) into rows of a destination bitmap through
     * a small strip bitmap: raw bytes as {@code Bitmap.copyPixelsFromBuffer} takes them, blitted with SRC (no
     * blending, filtering or colour conversion between two sRGB ARGB_8888 bitmaps). The strip has the destination's
     * config: an RGBA_1010102 destination (10-bit HEIC) takes packed 2_10_10_10 words the same way.
     */
    static final class TileBlitter implements AutoCloseable {
        private final Bitmap strip;
        private final Canvas canvas;
        private final Paint copy = new Paint();
        private final Rect from = new Rect();
        private final Rect to = new Rect();
        private final int width;
        private final int rowBytes;
        private final int stripRows;
        private final int stripBytes;

        TileBlitter(Bitmap dst, int stripRows) {
            width = dst.getWidth();
            rowBytes = width * 4;
            this.stripRows = Math.max(1, Math.min(stripRows, dst.getHeight()));
            strip = Bitmap.createBitmap(width, this.stripRows, dst.getConfig());
            stripBytes = rowBytes * this.stripRows;
            canvas = new Canvas(dst);
            copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            copy.setFilterBitmap(false);
            copy.setDither(false);
        }

        /**
         * Copies {@code height} rows of {@code tile} (from its start, whatever its position) to rows
         * {@code y .. y+height-1}. copyPixelsFromBuffer always takes a whole strip: the tile buffer must hold
         * whole strips (TileSize rows); bytes past the tile are stale and never drawn.
         */
        void blit(ByteBuffer tile, int y, int height) {
            final int capacity = tile.capacity();
            try {
                for (int r = 0; r < height; r += stripRows) {
                    final int rows = Math.min(stripRows, height - r);
                    final int start = r * rowBytes;
                    if (start + stripBytes > capacity)
                        throw new IllegalStateException("strip " + start + "+" + stripBytes + " outside the tile buffer " + capacity);
                    tile.limit(start + stripBytes);
                    tile.position(start);
                    strip.copyPixelsFromBuffer(tile);
                    tile.limit(capacity);
                    from.set(0, 0, width, rows);
                    to.set(0, y + r, width, y + r + rows);
                    canvas.drawBitmap(strip, from, to, copy);
                }
            } finally {
                tile.clear();
            }
        }

        @Override
        public void close() {
            strip.recycle();
        }
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat) {
        return drawBlocksToOutput(size,glFormat, GLDrawParams.Allocate.Heap);
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat,GLDrawParams.Allocate alloc) {
        ByteBuffer mOutBuffer;
        allocation = alloc;
        final int bytes = GLLimits.bufferBytes(size.x, size.y, (long) glFormat.mFormat.mSize * glFormat.mChannels, "readback");
        if(alloc == GLDrawParams.Allocate.Direct) mOutBuffer = Allocator.allocate(bytes);
        else
            mOutBuffer = ByteBuffer.allocateDirect(bytes);
        return drawBlocksToOutput(size,glFormat,mOutBuffer);
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat,ByteBuffer mOutBuffer) {
        glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
        checkEglError("glBindFramebuffer");
        GLProg program = super.mProgram;
        GLBlockDivider divider = new GLBlockDivider(size.y, GLDrawParams.TileSize);
        int[] row = new int[2];
        ByteBuffer mBlockBuffert = mBlockBuffer;
        mOutBuffer.position(0);
        mBlockBuffert.position(0);
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];
            glViewport(0, 0, size.x, height);
            checkEglError("glViewport");
            program.setVar("yOffset", y);
            program.draw();
            checkEglError("program");
            mBlockBuffert.position(0);
            glReadPixels(0, 0, size.x, height, glFormat.getGLFormatExternal(), glFormat.getGLType(), mBlockBuffert);
            checkEglError("glReadPixels");
            if (height < GLDrawParams.TileSize) {
                // This can only happen 2 times at edges
                byte[] data = new byte[size.x * height * glFormat.mFormat.mSize * glFormat.mChannels];
                mBlockBuffert.get(data);
                mOutBuffer.put(data);
            } else {
                int lim = mBlockBuffert.limit();
                mOutBuffer.put((ByteBuffer) mBlockBuffert.limit(size.x * GLDrawParams.TileSize * glFormat.mFormat.mSize * glFormat.mChannels));
                mBlockBuffert.limit(lim);
            }
        }
        mOutBuffer.position(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return mOutBuffer;
    }

    @Override
    public void close() {
        // Ensure GPU work is complete before tearing down EGL state.
        try {
            GLES30.glFinish();
        } catch (Exception ignored) {}
        try {
            super.close();
        } catch (Exception ignored) {}
        if (mOut != null) {
            try {
                mOut.close();
            } catch (Exception ignored) {}
            mOut = null;
        }
        if (mBlockBuffer != null) {
            try {
                mBlockBuffer.clear();
            } catch (Exception ignored) {}
            mBlockBuffer = null;
        }
        if (mOutBuffer != null) {
            try {
                if (allocation == GLDrawParams.Allocate.Direct) {
                    // Intentionally temporarily leaked: must hold the malloc
                } else {
                    mOutBuffer.clear();
                }
            } catch (Exception ignored) {}
            mOutBuffer = null;
        }
        // FBO/RBO are owned by this context; delete while context was current.
        // They are recreated per-pipeline, so stale IDs must not survive eglTerminate.
        try {
            if (bindFB[0] != 0) GLES30.glDeleteFramebuffers(1, bindFB, 0);
        } catch (Exception ignored) {}
        try {
            if (bindRB[0] != 0) GLES30.glDeleteRenderbuffers(1, bindRB, 0);
        } catch (Exception ignored) {}
    }
}
