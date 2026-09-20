package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_BINDING;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glGetIntegerv;
import static com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing.checkEglError;
import static com.particlesdevs.photoncamera.util.FileManager.sPHOTON_TUNING_DIR;

public class GLBasePipeline implements AutoCloseable {
    public final ArrayList<Node> Nodes = new ArrayList<>();
    public GLInterface glint = null;
    private long timeStart;
    private static final String TAG = "BasePipeline";
    private final int[] bind = new int[1];
    public GLTexture main1,main2,main3,main4, main5;
    public Settings mSettings;
    /**
     * Set once the remosaic has rearranged the mosaic into plain bayer. The
     * quad paths downstream key off the settings value, which still says quad
     * because that is what the sensor delivers; after the node it no longer
     * describes the frame in flight.
     */
    public boolean remosaicApplied = false;
    public Parameters mParameters;
    public Properties mProp;
    private final boolean loggedTuning = false;
    private final String Name;
    public Point workSize;
    public float noiseS;
    public float noiseO;
    private String currentProg;

    public int texnum = 0;

    public GLBasePipeline(String name){
        Name = name;
        Properties properties = new Properties();
        try {
            File init = new File(sPHOTON_TUNING_DIR, "PhotonCameraTuning.ini");
            if(!init.exists()) {
                init.createNewFile();
                /*InputStream inputStream = PhotonCamera.getAssetLoader().getInputStream("tuning/PhotonCameraTuning.ini");
                byte[] buffer = new byte[inputStream.available()];
                inputStream.read(buffer);
                OutputStream outputStream = new FileOutputStream(init);
                outputStream.write(buffer);
                outputStream.close();*/
            }
            properties.load(new FileInputStream(init));
        } catch (Exception e) {
            // Optional tuning file; absence is normal, not an error.
            Log.d("PostPipeline", "No tuning properties, using built-in defaults (" + e + ")");
        }
        mProp = properties;
    }
    public GLTexture getMain(){
        if(texnum == 1) {
            texnum = 2;
            return main2;
        } else {
            texnum = 1;
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

    private void tuningLog(String name, String value){
        if(loggedTuning) Log.d("Tuning",name+" = "+ value);
    }
    public boolean getTuning(String name, boolean Default){
        tuningLog(Name+"_"+name,String.valueOf(Default));
        return Boolean.parseBoolean(mProp.getProperty(Name+"_"+name,String.valueOf(Default)));
    }
    public float getTuning(String name,float Default){
        tuningLog(Name+"_"+name,String.valueOf(Default));
        return Float.parseFloat(mProp.getProperty(Name+"_"+name,String.valueOf(Default)));
    }
    public float[] getTuning(String name,float[] Default){
        String ins = Arrays.toString(Default).replace("[","").replace("]","");
        tuningLog(Name+"_"+name,ins);
        String inp = mProp.getProperty(Name+"_"+name, ins);
        String[] divided = inp.split(",");
        float[] output = new float[Default.length];
        for(int i = 0; i<divided.length;i++){
            output[i] = Float.parseFloat(divided[i]);
        }
        return output;
    }
    public double getTuning(String name,double Default){
        tuningLog(Name+"_"+name,String.valueOf(Default));
        return Double.parseDouble(mProp.getProperty(Name+"_"+name,String.valueOf(Default)));
    }
    public short getTuning(String name,short Default){
        tuningLog(Name+"_"+name,String.valueOf(Default));
        return Short.parseShort(mProp.getProperty(Name+"_"+name,String.valueOf(Default)));
    }
    public int getTuning(String name,int Default){
        tuningLog(Name+"_"+name,String.valueOf(Default));
        return Integer.parseInt(mProp.getProperty(Name+"_"+name,String.valueOf(Default)));
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
     * shot take four seconds". GCam prints exactly such a table at the end of a
     * shot, which is what made its timings readable in the dumps we compared
     * against.
     */
    public void dumpTimings(String label) {
        if (nodeTimings.isEmpty()) return;
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
        lastI();
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
            endTimeMeasure(node.Name);
            if (i != Nodes.size() - 1) {
                drawProgramTexture(node);
            }
            if (i != Nodes.size()-1) com.particlesdevs.photoncamera.processing.opengl.postpipeline.NiceDiagnostics.gpu(node.Name,node.WorkingTexture);
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
        glint.glProcessing.drawBlocksToOutput();
        if(texnum == 1){
            if (main1 != null) main1.close();
        }else {
            if (main2 != null) main2.close();
        }
        if (main3 != null) main3.close();
        glint.glProgram.close();
        dumpTimings("runAll");
        Nodes.clear();
        logTimeSummary(getClass().getSimpleName());
        return glint.glProcessing.mOut;
    }

    private static IllegalStateException nodeFailure(Node node, String phase,
                                                     RuntimeException cause) {
        return new IllegalStateException("post node " + node.Name + " (" + phase + ")", cause);
    }

    private static boolean canBypassFailedNode(Node node) {
        String name = node.Name;
        return "ABLC".equals(name)
                || "AutoExposureCurve".equals(name)
                || "ES3D".equals(name)
                || "LocalLaplacian".equals(name)
                || "CaptureSharpening".equals(name)
                || "CorrectingFlow".equals(name)
                || "Sharpening".equals(name);
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
        node.mProp = mProp;
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
