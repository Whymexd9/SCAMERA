package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.ScameraApp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.opengl.GLES31.*;
import static com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing.checkEglError;

public class GLProg implements AutoCloseable {
    private static final String TAG = "GLProgram";
    private final ByteBuffer mFlushBuffer = ByteBuffer.allocateDirect(4 * 4 * 4096);
    private final List<Integer> mPrograms = new ArrayList<>();
    private final Map<String, Integer> mProgramCache = new HashMap<>();
    /**
     * P30: driver binaries of the programs linked in this process (every shot creates a new EGL context and compiled its
     * ~40 post-pipeline programs again). A binary from the same driver runs the identical code; one the driver refuses is
     * compiled as before. Up to 96 MB, least recently used first out.
     */
    private static final class Binary { final int format; final byte[] data; Binary(int f, byte[] d) { format = f; data = d; } }
    private static final java.util.LinkedHashMap<String, Binary> BINARIES = new java.util.LinkedHashMap<>(64, 0.75f, true);
    private static long binaryBytes;
    private static final long BINARY_LIMIT = 96L << 20;
    public static long compileMs, binaryMs;
    public static int compiled, fromBinary;
    private static synchronized Binary binaryFor(String key) { return BINARIES.get(key); }
    private static synchronized void storeBinary(String key, Binary b) {
        Binary old = BINARIES.put(key, b);
        if (old != null) binaryBytes -= old.data.length;
        binaryBytes += b.data.length;
        for (java.util.Iterator<java.util.Map.Entry<String, Binary>> it = BINARIES.entrySet().iterator(); binaryBytes > BINARY_LIMIT && it.hasNext();) {
            binaryBytes -= it.next().getValue().data.length;
            it.remove();
        }
    }
    private static synchronized void dropBinary(String key) {
        Binary old = BINARIES.remove(key);
        if (old != null) binaryBytes -= old.data.length;
        GLProgramDiskCache disk = disk();
        if (disk != null && driver != null) disk.drop(driver, key);
    }
    /** P69: the binaries also on disk (GLProgramDiskCache): the first shot after a start loads them instead of compiling. */
    private static GLProgramDiskCache disk;
    private static boolean diskTried;
    /** Renderer, GL version and build fingerprint: part of every disk name (read with a current context). */
    private static String driver;
    private static synchronized GLProgramDiskCache disk() {
        if (!diskTried) {
            diskTried = true;
            try {
                android.content.Context context = ScameraApp.getAppContext();
                if (context != null) disk = new GLProgramDiskCache(new File(context.getCacheDir(), "glprog"));
            } catch (RuntimeException ignored) {}
        }
        return disk;
    }
    private static synchronized String driver() {
        if (driver == null) {
            String renderer = glGetString(GL_RENDERER), version = glGetString(GL_VERSION);
            glGetError();
            if (renderer == null || version == null) return null;
            driver = renderer + "|" + version + "|" + android.os.Build.FINGERPRINT;
        }
        return driver;
    }
    /** The binary of {@code key}: from this process, else from disk (then kept in this process too). */
    private static Binary loadBinary(String key) {
        Binary b = binaryFor(key);
        if (b != null) return b;
        GLProgramDiskCache disk = disk();
        String id = disk == null ? null : driver();
        Object[] stored = id == null ? null : disk.load(id, key);
        if (stored == null) return null;
        b = new Binary((Integer) stored[0], (byte[]) stored[1]);
        storeBinary(key, b);
        return b;
    }
    /** "programs: N compiled in X ms, M from driver binaries in Y ms" since the last call (logged per shot). */
    public static synchronized String takeProgramStats() {
        GLProgramDiskCache cache = disk;
        String text = "programs: " + compiled + " compiled in " + compileMs + " ms, " + fromBinary + " from driver binaries in " + binaryMs + " ms"
                + (cache == null ? "" : " (" + cache.takeStats() + ")"); // P237
        // P236 (speed-ux F): the shot's shader compiling (the first shot after an install or a driver update: 0.5-0.8 s)
        com.particlesdevs.photoncamera.processing.ShotTimeline.markIfShot("post_compile");
        com.particlesdevs.photoncamera.processing.ShotTimeline.note("post_compile_ms", compiled + "/" + compileMs + "+" + fromBinary + "bin/" + binaryMs);
        compileMs = binaryMs = 0; compiled = fromBinary = 0;
        return text;
    }
    /** P237: idle warm-ups run once per process; the programs compiled by the last one. */
    private static final java.util.concurrent.atomic.AtomicBoolean WARMED = new java.util.concurrent.atomic.AtomicBoolean();
    static final int WARM_UP_LIMIT = 64;

    /**
     * P237 (speed-ux B5): compiles, in a context of its own on the calling (background) thread, the post-pipeline programs whose
     * driver binary is missing from the disk cache although their source was kept (a driver update, an evicted or lost binary),
     * so the next shot loads them instead of compiling (0.5-0.8 s on the first shot). Once per process; nothing when every binary
     * is there or nothing was kept yet. {@code idle}: asked before each program - false stops (a shot started). Never throws.
     */
    public static void warmUp(java.util.function.BooleanSupplier idle) {
        if (!WARMED.compareAndSet(false, true)) return;
        final GLProgramDiskCache cache = disk();
        if (cache == null) return;
        GLContext context = null;
        int done = 0, failed = 0, wanted = 0;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            context = new GLContext(1, 1);
            final String id = driver();
            final java.util.List<String> missing = cache.missingSources(id, WARM_UP_LIMIT);
            wanted = missing.size();
            for (String key : missing) {
                if (idle != null && !idle.getAsBoolean()) break;
                try {
                    if (context.mProgram.precompile(key)) done++; else failed++;
                } catch (RuntimeException e) {
                    failed++;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "program warm-up: " + e);
        } finally {
            if (context != null) try { context.close(); } catch (RuntimeException ignored) { }
        }
        Log.i(TAG, "program warm-up: " + done + " of " + wanted + " missing binaries compiled in idle time in "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms" + (failed > 0 ? ", " + failed + " failed" : ""));
    }

    /**
     * P237: compiles and links one program from its cache key ("C\n" + compute source, or "F\n" + the vertex shader + "\n" +
     * fragment source; P312: or a viewfinder key, {@link #viewfinderKey}) so its binary is kept (keepBinary); true when it linked.
     */
    boolean precompile(String key) {
        if (key.startsWith("V\n")) {
            int program = cachedViewfinderProgram(key);
            if (program == 0) program = linkViewfinderProgram(key);
            if (program != 0) glDeleteProgram(program);
            return program != 0;
        }
        final boolean compute = key.startsWith("C\n");
        final String fragmentHead = "F\n" + vertexShaderSource + "\n";
        if (!compute && !key.startsWith(fragmentHead)) return false;
        final String shader = compute ? key.substring(2) : key.substring(fragmentHead.length());
        final int stored = programFromBinary(key);
        if (stored != 0) { glDeleteProgram(stored); return true; }
        final int shaderHandle = compileShader(compute ? GL_COMPUTE_SHADER : GL_FRAGMENT_SHADER, shader);
        final int program;
        if (compute) {
            program = glCreateProgram();
            glAttachShader(program, shaderHandle);
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            glLinkProgram(program);
        } else program = createProgram(vertexShader, shaderHandle);
        final int[] linked = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
        if (linked[0] != 0) keepBinary(key, program);
        if (compute) glDeleteProgram(program); // a fragment program is the GLProg's (createProgram), deleted with it
        glDeleteShader(shaderHandle);
        glGetError();
        return linked[0] != 0;
    }

    private static int programFromBinary(String key) {
        Binary b = loadBinary(key);
        if (b == null) return 0;
        // P312: errors left by the context's earlier work (the viewfinder's frame) are not this binary's: it was dropped for them
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) { }
        int program = glCreateProgram();
        glProgramBinary(program, b.format, java.nio.ByteBuffer.wrap(b.data), b.data.length);
        int[] ok = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0 || glGetError() != GL_NO_ERROR) { glDeleteProgram(program); dropBinary(key); return 0; }
        return program;
    }

    // ---- P312: the viewfinder's programs in the same cache
    // (owner, 2026-10-21, a video of the camera's start: the viewfinder stood still for 0.4 s - 2.1 s just after it appeared, every
    // start. The developed-RAW viewfinder compiled its programs from source on its GL thread at the first RAW frame - rawdevelop
    // (650 lines) and the ten programs of the photo's WHY tone (combine: 815 lines) - while no ISP frame could be drawn. Their
    // driver binaries are kept here like the post pipeline's: the next start links them in a few ms.)

    /**
     * P312: the cache key of a viewfinder program: "V\n" + its attribute names (bound to locations 0, 1, ... before the link,
     * comma separated) + "\n" + the vertex source + U+0001 + the fragment source (GLSL has no control characters).
     */
    public static String viewfinderKey(String vertex, String fragment, String... attributes) {
        return "V\n" + String.join(",", attributes) + "\n" + vertex + '\u0001' + fragment;
    }

    /** P312: {attribute names (comma separated), vertex source, fragment source} of a viewfinder key, or null. */
    static String[] parseViewfinderKey(String key) {
        if (key == null || !key.startsWith("V\n")) return null;
        final int a = key.indexOf('\n', 2);
        final int s = a < 0 ? -1 : key.indexOf('\u0001', a + 1);
        if (s < 0) return null;
        return new String[]{key.substring(2, a), key.substring(a + 1, s), key.substring(s + 1)};
    }

    /**
     * P312: the linked program of a viewfinder key from its kept driver binary (this process, else the disk), 0 when there is
     * none or the driver refuses it (then dropped). On the thread of a current context.
     */
    public static int cachedViewfinderProgram(String key) {
        return programFromBinary(key);
    }

    /** P312: whether the binary of a key is kept (this process, or on the disk for this driver: then loaded into the process). */
    public static boolean hasBinary(String key) {
        return loadBinary(key) != null;
    }

    /**
     * P312: compiles and links a viewfinder key (its attributes bound to locations 0, 1, ...; the binary retrievable) and keeps its
     * binary; 0 when it does not compile or link (logged). On the thread of a current context.
     */
    public static int linkViewfinderProgram(String key) {
        final String[] parts = parseViewfinderKey(key);
        if (parts == null) return 0;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final int vs = compileQuiet(GL_VERTEX_SHADER, parts[1]);
        final int fs = vs == 0 ? 0 : compileQuiet(GL_FRAGMENT_SHADER, parts[2]);
        int program = 0;
        if (fs != 0) {
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            final String[] attributes = parts[0].isEmpty() ? new String[0] : parts[0].split(",");
            for (int i = 0; i < attributes.length; i++) glBindAttribLocation(program, i, attributes[i]);
            glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            glLinkProgram(program);
            final int[] linked = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                Log.e(TAG, "viewfinder program link: " + glGetProgramInfoLog(program));
                glDeleteProgram(program);
                program = 0;
            }
        }
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        if (program != 0) keepBinary(key, program);
        glGetError();
        Log.d(TAG, "viewfinder program " + (program != 0 ? "compiled" : "failed") + " in " + (android.os.SystemClock.elapsedRealtime() - t0)
                + " ms (" + parts[2].length() + " chars) on " + Thread.currentThread().getName());
        return program;
    }

    /** P312: {@link #cachedViewfinderProgram}, else {@link #linkViewfinderProgram}. */
    public static int viewfinderProgram(String key) {
        final int program = cachedViewfinderProgram(key);
        return program != 0 ? program : linkViewfinderProgram(key);
    }

    private static int compileQuiet(int type, String source) {
        final int shader = glCreateShader(type);
        if (shader == 0) return 0;
        glShaderSource(shader, source);
        glCompileShader(shader);
        final int[] ok = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != 0) return shader;
        Log.e(TAG, "viewfinder shader compile: " + glGetShaderInfoLog(shader));
        glDeleteShader(shader);
        return 0;
    }
    private static void keepBinary(String key, int program) {
        int[] length = new int[1];
        glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, length, 0);
        if (length[0] <= 0) { glGetError(); return; }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(length[0]);
        int[] written = new int[1], format = new int[1];
        glGetProgramBinary(program, length[0], written, 0, format, 0, data);
        if (glGetError() != GL_NO_ERROR || written[0] <= 0) return;
        byte[] bytes = new byte[written[0]];
        data.position(0); data.get(bytes);
        storeBinary(key, new Binary(format[0], bytes));
        GLProgramDiskCache disk = disk();
        String id = disk == null ? null : driver();
        if (id != null) disk.store(id, key, format[0], bytes);
    }
    private final int vertexShader;
    private final GLSquareModel mSquare = new GLSquareModel();
    public int mCurrentProgramActive;
    private final Map<String, Integer> mTextureBinds = new HashMap<>();
    private Map<String, GLComputeLayout> mComputeLayouts = null;
    private int mNewTextureId;
    public boolean closed = true;
    public boolean isCompute = false;
    public int currentShader;
    public final static String glVersion = "#version 310 es\n";
    final String vertexShaderSource = glVersion +
            "precision mediump float;\n" +
            "in vec4 vPosition;\n" +
            "void main() {\n" +
            "gl_Position = vPosition;\n" +
            "}\n";

    public GLProg() {
        this.vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
        mFlushBuffer.mark();
    }
    boolean changedDef = false;
    ArrayList<String[]> Defines = new ArrayList<>();

    public void setDefine(String DefineName, Point in){
        setDefine(DefineName,in.x,in.y);
    }
    public void setDefine(String DefineName, boolean bool){
        if(bool)
            setDefine(DefineName,"1");
        else {
            setDefine(DefineName,"0");
        }
    }
    public void setLayout(int x, int y, int z){
        setDefine("LAYOUT","layout(local_size_x = "+x+", local_size_y = "+y+", local_size_z = "+z+") in;");
    }
    public void setDefine(String DefineName, float... vars){
        setDefine(DefineName,true,vars);
    }
    public void setDefine(String DefineName,boolean transposed, float... vars){
        setDefine(DefineName,Arrays.toString(vars).replace("]","").replace("[",""));
    }
    public void setDefine(String DefineName, int... vars){
        setDefine(DefineName,Arrays.toString(vars).replace("]","").replace("[",""));
    }
    public void setDefine(String DefineName, String DefineVal){
        Defines.add(new String[]{DefineName,DefineVal});
        changedDef = true;
    }
    public void useAssetProgram(String name){
        useAssetProgram(name,false);
    }
    public void useAssetProgram(String name,boolean compute){
        if (PostGlMode.legacy()) {
            useProgram(ScameraApp.getAssetLoader().getString("shaders/"+name+".glsl"),compute);
            return;
        }
        // W1.11: the asset text, its #import / #define processing and the compute layouts are the same for the same name and
        // defines in every pass and every shot; they were read from the APK and parsed again for each of the ~84 passes.
        final String key = assetKey(name);
        Prepared prepared = PREPARED.get(key);
        if (prepared == null) {
            final String source = ScameraApp.getAssetLoader().getString("shaders/"+name+".glsl");
            final String shader = changedDef ? GLInterface.loadShader(source, Defines) : GLInterface.loadShader(source);
            prepared = new Prepared(shader, GLInterface.getLayouts(shader));
            if (PREPARED.size() >= PREPARED_LIMIT) PREPARED.clear();
            PREPARED.put(key, prepared);
        }
        isCompute = compute;
        closed = false;
        useShader(prepared.shader, prepared.layouts, compute);
    }

    /** A processed asset program (W1.11): the source handed to the driver and its layouts. */
    private static final class Prepared {
        final String shader;
        final Map<String, GLComputeLayout> layouts;
        Prepared(String shader, Map<String, GLComputeLayout> layouts) { this.shader = shader; this.layouts = layouts; }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, Prepared> PREPARED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int PREPARED_LIMIT = 512;

    /** Asset name plus the pending defines, in the order readProgram applies them. */
    private String assetKey(String name) {
        if (!changedDef || Defines.isEmpty()) return name;
        StringBuilder key = new StringBuilder(name);
        for (String[] define : Defines) key.append('|').append(define[0]).append('=').append(define[1]);
        return key.toString();
    }
    public void useUtilProgram(String name){
        useUtilProgram(name,false);
    }
    public void useUtilProgram(String name,boolean compute){
        useProgram(ScameraApp.getAssetLoader().getString("shaders/utils/"+name+".glsl"),compute);
    }
    public void useProgram(int fragmentRes){
        useProgram(fragmentRes,false);
    }
    public void useProgram(int fragmentRes, boolean compute) {
        isCompute = compute;
        closed = false;
        String shader;
        if(changedDef) {
            shader = GLInterface.loadShader(fragmentRes,Defines);
        }
        else {
            shader = GLInterface.loadShader(fragmentRes);
        }
        useShader(shader,compute);
    }
    public void useProgram(String programSource) {
        useProgram(programSource,false);
    }
    public void useProgram(String programSource, boolean compute) {
        isCompute = compute;
        closed = false;
        String shader;
        if(changedDef) shader = GLInterface.loadShader(programSource,Defines);
        else {
            shader = GLInterface.loadShader(programSource);
        }
        useShader(shader,compute);
    }
    private void useShader(String shader, boolean compute){
        useShader(shader, GLInterface.getLayouts(shader), compute);
    }
    private void useShader(String shader, Map<String, GLComputeLayout> layouts, boolean compute){
        mComputeLayouts = layouts;
        if(mProgramCache.containsKey(shader)) {
            Defines.clear();
            changedDef = false;
            Integer prog = mProgramCache.get(shader);
            if(prog == null) return;
            glUseProgram(prog);
            checkEglError("glUseProgram");
            mCurrentProgramActive = prog;
        } else {
            int program;
            int nShader;
            final String key = (compute ? "C\n" : "F\n" + vertexShaderSource + "\n") + shader;
            final long t0 = android.os.SystemClock.elapsedRealtime();
            program = programFromBinary(key);
            if (program != 0) {
                nShader = 0;
                if (!compute) mPrograms.add(program);
                synchronized (GLProg.class) { fromBinary++; binaryMs += android.os.SystemClock.elapsedRealtime() - t0; }
            } else {
                if(!compute) {
                    nShader = compileShader(GL_FRAGMENT_SHADER, shader);
                    program = createProgram(vertexShader, nShader);
                } else {
                    nShader = compileShader(GL_COMPUTE_SHADER, shader);
                    program = glCreateProgram();
                    glAttachShader(program,nShader);
                    glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
                    glLinkProgram(program);
                }
                int[] linked = new int[1];
                glGetProgramiv(program, GL_LINK_STATUS, linked, 0);
                if (linked[0] != 0) keepBinary(key, program);
                synchronized (GLProg.class) { compiled++; compileMs += android.os.SystemClock.elapsedRealtime() - t0; }
            }
            currentShader = nShader;
            glGetError();
            glUseProgram(program);
            checkEglError("glUseProgram");
            Defines.clear();
            changedDef = false;
            mCurrentProgramActive = program;
            mProgramCache.put(shader,program);
        }
        mTextureBinds.clear();
        mNewTextureId = 0;
    }

    /**
     * Helper function to compile a shader.
     *
     * @param shaderType   The shader type.
     * @param shaderSource The shader source code.
     * @return An OpenGL handle to the shader.
     */
    public int compileShader(final int shaderType, final String shaderSource) {
        int shaderHandle = glCreateShader(shaderType);
        if (shaderHandle != 0) {
            // Pass in the shader source.
            glShaderSource(shaderHandle, shaderSource);
            // Compile the shader.
            glCompileShader(shaderHandle);
            // Get the compilation status.
            final int[] compileStatus = new int[1];
            glGetShaderiv(shaderHandle, GL_COMPILE_STATUS, compileStatus,0);
            // If the compilation failed, delete the shader.
            if (compileStatus[0] == 0) {
                Log.e(TAG, "Error compiling shader: " + glGetShaderInfoLog(shaderHandle));
                glDeleteShader(shaderHandle);
                shaderHandle = 0;
            }
        }
        if (shaderHandle == 0) {
            throw new RuntimeException("Error creating shader. Program:" + shaderSource);
        }
        return shaderHandle;
    }

    /**
     * Helper function to compile and link a program.
     *
     * @param vertexShaderHandle   An OpenGL handle to an already-compiled vertex shader.
     * @param fragmentShaderHandle An OpenGL handle to an already-compiled fragment shader.
     * @return An OpenGL handle to the program.
     */
    public int createProgram(final int vertexShaderHandle, final int fragmentShaderHandle) {
        int programHandle = glCreateProgram();
        if (programHandle != 0) {
            // Bind the vertex shader to the program.
            glAttachShader(programHandle, vertexShaderHandle);
            // Bind the fragment shader to the program.
            glAttachShader(programHandle, fragmentShaderHandle);
            // P30: the driver binary is kept for the next shot's context (programFromBinary).
            glProgramParameteri(programHandle, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
            // Link the two shaders together into a program.
            glLinkProgram(programHandle);
            // Get the link status.
            final int[] linkStatus = new int[1];
            glGetProgramiv(programHandle, GL_LINK_STATUS, linkStatus,0);
            // If the link failed, delete the program.
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Error compiling program: " + glGetProgramInfoLog(programHandle));
                glDeleteProgram(programHandle);
                programHandle = 0;
            }
        }
        if (programHandle == 0) {
            throw new RuntimeException("Error creating program.");
        }
        mPrograms.add(programHandle);
        return programHandle;
    }

    private int vPosition() {
        return glGetAttribLocation(mCurrentProgramActive, "vPosition");
    }

    public void setLayout(Point layoutSize, int z){

    }
    public void computeAuto(Point size, int z) {
        if(!isCompute) {
            new Exception("Program must be compute!").printStackTrace();
            return;
        }
        GLComputeLayout glComputeLayout = mComputeLayouts.get("in");
        if(glComputeLayout == null){
            new Exception("glComputeLayout is null").printStackTrace();
            return;
        }
        int x = 0;
        int y = 0;
        int z0 = 0;
        if (size.x%glComputeLayout.xy.x != 0) x = 1;
        if (size.y%glComputeLayout.xy.y != 0) y = 1;
        if (z%glComputeLayout.z != 0) z0 = 1;
        glDispatchCompute(size.x/glComputeLayout.xy.x + x,
                size.y/glComputeLayout.xy.y + y,
                z/glComputeLayout.z + z0);
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        glMemoryBarrier(GL_ALL_SHADER_BITS);
        glFinish();
    }
    public void computeManual(int x,int y, int z) {
        if(!isCompute) {
            new Exception("Program must be compute!").printStackTrace();
            return;
        }
        glDispatchCompute(x, y, z);
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        glMemoryBarrier(GL_ALL_SHADER_BITS);
        glFinish();
    }


    public void draw() {
        mSquare.draw(vPosition());
        if (PostGlMode.legacy()) {
            // The old model: two memory barriers and a flush after every 256-row tile. A fragment pass writes its render
            // target, which the GL orders with every later read of that texture; no post shader writes images or buffers.
            glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
            glMemoryBarrier(GL_ALL_SHADER_BITS);
            glFlush();
        }
    }

    public void drawBlocks(GLTexture glTexture,Point drawsize) {
        glTexture.BufferLoad();
        drawBlocks(drawsize.x, drawsize.y);
    }
    public void drawBlocks(GLTexture glTexture) {
        glTexture.BufferLoad();
        drawBlocks(glTexture.mSize.x, glTexture.mSize.y);
        // W1.1: no glFinish after every pass (it also ran at the end of every node): the GPU idled while the CPU set up the
        // next pass. Read-backs are the sync points; post_sync 1 puts a glFinish at each node end for the node timers.
        if (PostGlMode.legacy()) glFinish();
    }

    public void drawBlocks(int w, int h) {
        GLBlockDivider divider = new GLBlockDivider(h, GLDrawParams.TileSize);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            glViewport(0, row[0], w, row[1]);
            draw();
        }
        // W1.1: one submission per pass (the tiles stay 256 rows, so no single draw gets long).
        if (!PostGlMode.legacy()) glFlush();
    }

    public void drawBlocks(GLTexture texture, int bh) {
        drawBlocks(texture, bh, false);
    }

    public void drawBlocks(GLTexture texture, boolean forceFlush) {
        if(!forceFlush) {
            drawBlocks(texture);
            return;
        }
        texture.BufferLoad();
        drawBlocks(texture.mSize.x, texture.mSize.y, GLDrawParams.TileSize, -1, texture.mFormat.getGLType());
    }
    public void drawBlocks(GLTexture texture, int bh, boolean forceFlush) {
        texture.BufferLoad();
        drawBlocks(texture.mSize.x, texture.mSize.y, bh, -1, forceFlush ? texture.mFormat.mFormat.mID : -1);
    }

    public void drawBlocks(int w, int h, int bh, int flushFormat, int flushType) {
        mFlushBuffer.reset();
        if (flushFormat == -1) {
            flushFormat = flushType == GL_FLOAT ? GL_RGBA : GL_RGBA_INTEGER;
        }

        GLBlockDivider divider = new GLBlockDivider(h, bh);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            glViewport(0, row[0], w, row[1]);
            mSquare.draw(vPosition());

            // Force flush.
            glFlush();
            if (flushType != -1) {
                glReadPixels(0, row[0], 1, 1, flushFormat, flushType, mFlushBuffer);
                int glError = glGetError();
                if (glError != 0) {
                    Log.d("GLPrograms", "GLError: " + glError);
                }
            }
        }
    }


    public void setTexture(String var,GLTexture tex) {
        if(tex == null) {
            new Exception("Wrong Texture:" + var).printStackTrace();
            return;
        }
        int textureId;
        if (mTextureBinds.containsKey(var)) {
            textureId = mTextureBinds.get(var);
        } else {
            textureId = mNewTextureId;
            mTextureBinds.put(var, textureId);
            mNewTextureId += 2;
        }
        setVar(var, textureId);
        tex.bind(GL_TEXTURE0 + textureId);
    }
    /** P134: a 3D texture (sampler3D) by its GL name, on a unit of its own like {@link #setTexture}. */
    public void setTexture3D(String var,int textureName) {
        int textureId;
        if (mTextureBinds.containsKey(var)) {
            textureId = mTextureBinds.get(var);
        } else {
            textureId = mNewTextureId;
            mTextureBinds.put(var, textureId);
            mNewTextureId += 2;
        }
        setVar(var, textureId);
        android.opengl.GLES30.glActiveTexture(GL_TEXTURE0 + textureId);
        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_3D, textureName);
    }
    public void setBufferCompute(String var,GLBuffer buff){
        setBufferCompute(var,buff,GL_SHADER_STORAGE_BUFFER);
    }
    public void setBufferCompute(String var,GLBuffer buff,int type){
        GLComputeLayout computeLayout = mComputeLayouts == null ? null : mComputeLayouts.get(var);
        if(computeLayout == null){
            // P317: an unbound block reads and writes nothing, silently (the Mali AE histograms 0 / 0); a printStackTrace
            // reached only logcat. The callers catch it, log it and fall back to the CPU.
            throw missingLayout("buffer block", var);
        }
        buff.BindBase(computeLayout.binding,type);
    }
    private IllegalStateException missingLayout(String what, String var) {
        final String message = "no " + what + " " + var + " in the program's layouts "
                + (mComputeLayouts == null ? "(none)" : mComputeLayouts.keySet());
        Log.e(TAG, message);
        return new IllegalStateException(message);
    }
    public void setTextureCompute(String var,GLTexture tex,boolean write){
        int access = GL_READ_ONLY;
        if(write) access = GL_WRITE_ONLY;
        setTextureCompute(var,tex,access);
    }
    public void setTextureCompute(String var,GLTexture tex,int access){
        GLComputeLayout computeLayout = mComputeLayouts == null ? null : mComputeLayouts.get(var);
        if(computeLayout == null){
            throw missingLayout("image", var); // P317: as setBufferCompute
        }
        glBindImageTexture(computeLayout.binding,
                tex.mTextureID, 0, false, 0, access, tex.mFormat.getGLFormatInternal());
        checkEglError("glBindImageTexture tex.mTextureID:"+tex.mTextureID);
    }

    public void setVar(String name, int... vars) {
        int addr = glGetUniformLocation(mCurrentProgramActive, name);
        switch (vars.length) {
            case 1:
                glUniform1i(addr, vars[0]);
                break;
            case 2:
                glUniform2i(addr, vars[0], vars[1]);
                break;
            case 3:
                glUniform3i(addr, vars[0], vars[1], vars[2]);
                break;
            case 4:
                glUniform4i(addr, vars[0], vars[1], vars[2], vars[3]);
                break;
            case 8:
                glUniform1iv(addr, 8, vars, 0);
            default:
                throw new RuntimeException("Wrong var size " + name);
        }
        checkEglError("setVar:" + name);
    }

    public void setVar(String name, Point in) {
        setVar(name, in.x, in.y);
    }

    public void setVar(String name, boolean transpose, float... vars) {
        int address = glGetUniformLocation(mCurrentProgramActive, name);
        switch (vars.length) {
            case 1:
                glUniform1f(address, vars[0]);
                break;
            case 2:
                glUniform2f(address, vars[0], vars[1]);
                break;
            case 3:
                glUniform3f(address, vars[0], vars[1], vars[2]);
                break;
            case 4:
                glUniform4f(address, vars[0], vars[1], vars[2], vars[3]);
                break;
            case 9:
                glUniformMatrix3fv(address,1, transpose, vars,0);
                break;
            default:
                throw new RuntimeException("Wrong var size " + name);
        }
        checkEglError("setVar:" + name);
    }
    public void setVar(String name, float... vars) {
        setVar(name,true,vars);
    }
    /** Sets a float array uniform (uniform float name[len]). */
    public void setVarFloats(String name, float... vars) {
        int address = glGetUniformLocation(mCurrentProgramActive, name);
        glUniform1fv(address, vars.length, vars, 0);
        checkEglError("setVarFloats:" + name);
    }

    /** Sets a vec4 array uniform (uniform vec4 name[len]) from 4 * len floats (P144: the own tone curve's table). */
    public void setVarVec4s(String name, float... vars) {
        int address = glGetUniformLocation(mCurrentProgramActive, name);
        glUniform4fv(address, vars.length / 4, vars, 0);
        checkEglError("setVarVec4s:" + name);
    }

    public void setVarU(String name, Point var) {
        setVarU(name,var.x,var.y);
    }

    public void setVarU(String name, int... vars) {
        int address = glGetUniformLocation(mCurrentProgramActive, name);
        switch (vars.length) {
            case 1:
                glUniform1ui(address, vars[0]);
                break;
            case 2:
                glUniform2ui(address, vars[0], vars[1]);
                break;
            case 3:
                glUniform3ui(address, vars[0], vars[1], vars[2]);
                break;
            case 4:
                glUniform4ui(address, vars[0], vars[1], vars[2], vars[3]);
                break;
            default:
                throw new RuntimeException("Wrong var size " + name);
        }
        checkEglError("setVarU:" + name);
    }

    @Override
    public void close() {
        closed = true;
        //for (int program : mPrograms) {
        //    glDeleteProgram(program);
        //}
    }
}
