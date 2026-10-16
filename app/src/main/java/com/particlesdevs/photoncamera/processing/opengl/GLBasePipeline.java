package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Bitmap;
import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_BINDING;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glGetIntegerv;
import static com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing.checkEglError;

public class GLBasePipeline implements AutoCloseable {
    public final ArrayList<Node> Nodes = new ArrayList<>();
    public GLInterface glint = null;
    private long timeStart;
    private static final String TAG = "BasePipeline";
    private final int[] bind = new int[1];
    public GLTexture main1,main2,main3,main4, main5;
    /**
     * P179: main2 made on its first use (getMain) instead of up front: at 200 MP its 1.6 GB RGBA16F need not sit next to the
     * input texture of the first stage. Null: main2 is made by whoever set it.
     */
    public java.util.function.Supplier<GLTexture> lazyMain2;
    /** P179: main1 made again on its next use after {@link #releaseMain} gave its storage back. */
    public java.util.function.Supplier<GLTexture> lazyMain1;

    /**
     * P179: a ping-pong texture whose content nothing reads any more gives its storage back now and is made again (same size
     * and format) on its next use - at 200 MP an RGBA16F texture is 1.6 GB. False when {@code t} is not main1 / main2.
     */
    public boolean releaseMain(GLTexture t) {
        if (t == null || (t != main1 && t != main2)) return false;
        final Point size = new Point(t.mSize);
        final GLFormat format = t.mFormat;
        final java.util.function.Supplier<GLTexture> make = () -> new GLTexture(size, format, null,
                android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
        t.close();
        if (t == main1) { main1 = null; lazyMain1 = make; } else { main2 = null; lazyMain2 = make; }
        return true;
    }
    public Settings mSettings;
    public Parameters mParameters;
    private final String Name;
    public Point workSize;
    public float noiseS;
    public float noiseO;
    private String currentProg;

    public int texnum = 0;

    public GLBasePipeline(String name){
        Name = name;
    }
    public GLTexture getMain(){
        if(texnum == 1) {
            texnum = 2;
            if (main2 == null && lazyMain2 != null) {
                java.util.function.Supplier<GLTexture> make = lazyMain2;
                lazyMain2 = null;
                main2 = make.get();
            }
            return main2;
        } else {
            texnum = 1;
            if (main1 == null && lazyMain1 != null) {
                java.util.function.Supplier<GLTexture> make = lazyMain1;
                lazyMain1 = null;
                main1 = make.get();
            }
            return main1;
        }
    }

    // Swaps main3 with main1 and main2
    public GLTexture swap3() {
        if(texnum == 1) {
            GLTexture temp = main1;
            main1 = main3;
            main3 = temp;
            return main1;
        } else {
            GLTexture temp = main2;
            main2 = main3;
            main3 = temp;
            return main2;
        }
    }

    public void startTimeMeasure() {
        timeStart = System.currentTimeMillis();
    }

    public void endTimeMeasure(String Name) {
        long ms = System.currentTimeMillis() - timeStart;
        Log.d("Pipeline", "Node:" + Name + " elapsed:" + ms + " ms");
        nodeTimings.add(new long[]{ms, nodeTimings.size()});
        nodeNames.add(Name);
    }

    private final java.util.List<long[]> nodeTimings = new java.util.ArrayList<>();
    private final java.util.List<String> nodeNames = new java.util.ArrayList<>();

    /**
     * Dump one table of where the time went, in the order the nodes ran, with a
     * total and the slowest few called out.
     *
     * <p>Per-node lines already go to the log as the pipeline runs, but scattered
     * through everything else they are near useless for answering "why did this
     * shot take four seconds". The reference prints exactly such a table at the end of a
     * shot, which is what made its timings readable in the dumps we compared
     * against.
     */
    public void dumpTimings(String label) {
        if (nodeTimings.isEmpty()) return;
        Log.d(TAG, label + " " + GLProg.takeProgramStats() + ", textures " + GLTexture.takeCreated());
        long total = 0;
        for (long[] t : nodeTimings) total += t[0];
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== ").append(label).append(" timings =====\n");
        for (int i = 0; i < nodeTimings.size(); i++) {
            long ms = nodeTimings.get(i)[0];
            double pct = total > 0 ? (100.0 * ms / total) : 0.0;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  %-38s %7d ms  %5.1f%%%n", nodeNames.get(i), ms, pct));
        }
        sb.append(String.format(java.util.Locale.ROOT,
                "  %-38s %7d ms%n", "TOTAL", total));

        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < nodeTimings.size(); i++) order.add(i);
        order.sort((x, y) -> Long.compare(nodeTimings.get(y)[0], nodeTimings.get(x)[0]));
        sb.append("  slowest: ");
        for (int i = 0; i < Math.min(5, order.size()); i++) {
            int idx = order.get(i);
            if (i > 0) sb.append(", ");
            sb.append(nodeNames.get(idx)).append(' ').append(nodeTimings.get(idx)[0]).append("ms");
        }
        Log.d("Timings", sb.toString());
    }

    /**
     * Summary of where the time went, written once at the end of the pipeline.
     *
     * The per-node lines are already logged as they happen, but scattered through
     * everything else they are hard to add up - and the question when optimising
     * is which handful of nodes dominate, not what each one cost in isolation.
     * Sorted by cost, with each node's share of the total.
     */
    public void logTimeSummary(String pipelineName) {
        long total = 0;
        for (long[] t : nodeTimings) total += t[0];
        if (total <= 0 || nodeTimings.isEmpty()) return;

        java.util.List<long[]> sorted = new java.util.ArrayList<>(nodeTimings);
        sorted.sort((a, b) -> Long.compare(b[0], a[0]));

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(pipelineName).append(" timing: ")
          .append(total).append(" ms over ").append(nodeTimings.size()).append(" nodes ===");
        for (long[] t : sorted) {
            if (t[0] <= 0) continue;
            sb.append(String.format(java.util.Locale.ROOT, "%n  %-28s %6d ms  %5.1f%%",
                    nodeNames.get((int) t[1]), t[0], 100.0 * t[0] / total));
        }
        Log.d("Pipeline", sb.toString());
        nodeTimings.clear();
        nodeNames.clear();
    }

    public void add(Node in) {
        if (Nodes.size() != 0) in.previousNode = Nodes.get(Nodes.size() - 1);
        in.basePipeline = this;
        in.glInt = glint;
        in.glUtils = glint.glUtils;
        in.glProg = glint.glProgram;
        Nodes.add(in);
    }

    private void lastI() {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, bind, 0);
        checkEglError("glGetIntegerv");
    }

    private void lastR() {
        glBindFramebuffer(GL_FRAMEBUFFER, bind[0]);
        checkEglError("glBindFramebuffer");
    }

    public GLImage runAll() {
        runNodes();
        glint.glProcessing.drawBlocksToOutput();
        finishRun();
        return glint.glProcessing.mOut;
    }

    /**
     * {@link #runAll()} for an RGBA8 output that ends up in a Bitmap anyway: the last node is rendered tile by
     * tile straight into a new ARGB_8888 bitmap ({@link GLCoreBlockProcessing#drawBlocksToBitmap()}), so the
     * full-frame readback buffer (~200 MB at 50 MP) never exists next to the bitmap. Construct the processing
     * with {@code GLDrawParams.Allocate.None} (or any kind: the buffer is only allocated by a full readback).
     */
    public Bitmap runAllToBitmap() {
        runNodes();
        Bitmap bitmap = glint.glProcessing.drawBlocksToBitmap();
        finishRun();
        return bitmap;
    }

    /** Runs every node; the last one stays bound for the readback. */
    private void runNodes() {
        lastI();
        final boolean syncNodes = PostGlMode.syncNodes();
        for (int i = 0; i < Nodes.size(); i++) {
            Node node = Nodes.get(i);
            try {
                prepareNode(node,i);
            } catch (RuntimeException error) {
                throw nodeFailure(node, "prepare", error);
            }
            startTimeMeasure();
            try {
                node.Run();
            } catch (RuntimeException error) {
                if (canBypassFailedNode(node) && node.previousNode != null) {
                    Log.e(TAG, "Bypassing optional node " + node.Name, error);
                    node.WorkingTexture = node.previousNode.WorkingTexture;
                    glint.glProgram.closed = true;
                } else {
                    throw nodeFailure(node, "run", error);
                }
            }
            // post_sync 1: the node's GPU work is finished inside its own time (W1.1 removed the glFinish per pass).
            if (syncNodes) android.opengl.GLES30.glFinish();
            endTimeMeasure(node.Name);
            if (i != Nodes.size() - 1) {
                drawProgramTexture(node);
                // post_sync 1: this draw stays outside every node time, as with the old glFinish in drawBlocks; without it
                // the GPU time of a single-pass node's draw landed in the next node's time.
                if (syncNodes) android.opengl.GLES30.glFinish();
            }
            if (i != Nodes.size()-1) com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamDiagnostics.gpu(node.Name,node.WorkingTexture);
            try {
                node.AfterRun();
            } catch (RuntimeException error) {
                // AfterRun implementations only release temporary resources;
                // cleanup failure must not discard an otherwise valid image.
                Log.e(TAG, "Cleanup failed in node " + node.Name, error);
            }
        }
        if(texnum == 1){
            if (main2 != null) main2.close();
        }else {
            if (main1 != null) main1.close();
        }
    }

    /** Releases the pipeline textures and program after the readback of {@link #runNodes()}. */
    private void finishRun() {
        if(texnum == 1){
            if (main1 != null) main1.close();
        }else {
            if (main2 != null) main2.close();
        }
        if (main3 != null) main3.close();
        lazyMain2 = null;
        lazyMain1 = null;
        glint.glProgram.close();
        dumpTimings("runAll");
        Nodes.clear();
        logTimeSummary(getClass().getSimpleName());
    }

    private static IllegalStateException nodeFailure(Node node, String phase,
                                                     RuntimeException cause) {
        return new IllegalStateException("post node " + node.Name + " (" + phase + ")", cause);
    }

    private static boolean canBypassFailedNode(Node node) {
        String name = node.Name;
        return "Sharpening".equals(name);
    }

    public ByteBuffer runAllRaw() {
        lastI();
        for (int i = 0; i < Nodes.size(); i++) {
            prepareNode(Nodes.get(i),i);
            startTimeMeasure();
            Nodes.get(i).Run();
            if (i != Nodes.size() - 1) {
                Log.d(TAG, "i:" + i + " size:" + Nodes.size());
                drawProgramTexture(Nodes.get(i));
            }
            Nodes.get(i).AfterRun();
            endTimeMeasure(Nodes.get(i).Name);
        }
        glint.glProgram.drawBlocks(Nodes.get(Nodes.size() - 1).GetProgTex());
        if(texnum == 1){
            if (main2 != null) main2.close();
        }else {
            if (main1 != null) main1.close();
        }
        glint.glProcessing.drawBlocksToOutput();
        if(texnum == 1){
            if (main1 != null) main1.close();
        }else {
            if (main2 != null) main2.close();
        }
        if (main3 != null) main3.close();
        lazyMain2 = null;
        lazyMain1 = null;
        glint.glProgram.close();
        dumpTimings("runAllRaw");
        Nodes.clear();
        return glint.glProcessing.mOutBuffer;
    }

    private void drawProgramTexture(Node node) {
        if(!glint.glProgram.closed) {
            glint.glProgram.drawBlocks(node.GetProgTex());
            glint.glProgram.closed = true;
        }
    }

    private void prepareNode(Node node, int index) {
        node.BeforeCompile();
        node.Compile();
        node.BeforeRun();
        if (index == Nodes.size() - 1) {
            lastR();
        }
    }

    @Override
    public void close() {
        if (glint != null) {
            // Ensure any pending Adreno work is complete before eglTerminate.
            try {
                android.opengl.GLES30.glFinish();
            } catch (Exception ignored) {}
            // glProcessing owns the EGL context (extends GLContext); closing it
            // already terminates the context, so glContext is redundant but guarded.
            GLCoreBlockProcessing proc = glint.glProcessing;
            if (proc != null) {
                try {
                    proc.close();
                } catch (Exception ignored) {}
                glint.glProcessing = null;
            }
            if (glint.glContext != null && glint.glContext != proc) {
                try {
                    glint.glContext.close();
                } catch (Exception ignored) {}
                glint.glContext = null;
            }
            if (glint.glProgram != null) {
                try {
                    glint.glProgram.close();
                } catch (Exception ignored) {}
            }
            glint = null;
        }
        try {
            GLTexture.notClosed();
        } catch (Exception ignored) {}
    }
}
