package com.particlesdevs.photoncamera.gallery.editor;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import com.particlesdevs.photoncamera.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * P351: small OpenGL ES 3.0 helpers of the photo editor: programs from assets/shaders/editor (each fragment shader gets
 * "#version 300 es", highp and common.glsl in front; the vertex shader is the full-screen triangle of quad.vert), textures,
 * framebuffers. The editor has its own GL context (GLSurfaceView for the preview, an EGL pbuffer for the export), not the
 * camera's post pipeline.
 */
final class EditGl {
    private static final String TAG = "EditGl";
    static final String DIR = "shaders/editor/";

    private EditGl() {}

    static String asset(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(DIR + name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
            return o.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** The fragment source as compiled: version, precision, common.glsl, the pass. */
    static String fragmentSource(AssetManager am, String name) throws IOException {
        return "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp usampler2D;\n"
                + asset(am, "common.glsl") + "\n#line 1\n" + asset(am, name);
    }

    static int program(AssetManager am, String fragment) throws IOException {
        int vs = shader(GLES30.GL_VERTEX_SHADER, "#version 300 es\n" + asset(am, "quad.vert"), "quad.vert");
        int fs = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource(am, fragment), fragment);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs);
        GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new IOException("editor program " + fragment + " link: " + log);
        }
        return p;
    }

    private static int shader(int type, String src, String name) throws IOException {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            Log.e(TAG, "Error compiling shader " + name + ": " + log);
            throw new IOException("editor shader " + name + ": " + log);
        }
        return s;
    }

    /** A 2D texture with immutable storage; {@code levels} > 1 for a mipmapped one. */
    static int texture(int w, int h, int internalFormat, boolean linear, int levels) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, internalFormat, w, h);
        params(linear, levels > 1);
        return t[0];
    }

    static void params(boolean linear, boolean mip) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                linear ? (mip ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR) : GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    static int levelsFor(int w, int h) {
        int l = 1;
        for (int s = Math.max(w, h); s > 1; s >>= 1) l++;
        return l;
    }

    /** An ARGB_8888 bitmap as an RGBA8 texture; an RGBA_F16 bitmap as RGBA16F. */
    static int upload(Bitmap b) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        if (b.getConfig() == Bitmap.Config.RGBA_F16) {
            ByteBuffer buf = ByteBuffer.allocateDirect(b.getByteCount()).order(ByteOrder.nativeOrder());
            b.copyPixelsToBuffer(buf);
            buf.rewind();
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, b.getWidth(), b.getHeight(), 0, GLES30.GL_RGBA,
                    GLES30.GL_HALF_FLOAT, buf);
        } else {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        }
        params(true, false);
        return t[0];
    }

    /**
     * Uploads {@code w x h} samples at (x, y) of a row-major 16-bit buffer {@code fullW} samples wide ({@code channels} 1 or 3)
     * as an R16UI / RGB16UI texture, without copying the rest of the buffer (GL_UNPACK_ROW_LENGTH / SKIP).
     */
    static int uploadU16(Buffer data, int fullW, int channels, int x, int y, int w, int h) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, fullW);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, x);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, y);
        data.rewind();
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, channels == 3 ? GLES30.GL_RGB16UI : GLES30.GL_R16UI, w, h, 0,
                channels == 3 ? GLES30.GL_RGB_INTEGER : GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, data);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
        params(false, false);
        return t[0];
    }

    static int fbo(int texture) {
        int[] f = new int[1];
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.w(TAG, "framebuffer incomplete: 0x" + Integer.toHexString(status));
        return f[0];
    }

    static void deleteTexture(int t) {
        if (t != 0) GLES30.glDeleteTextures(1, new int[]{t}, 0);
    }

    static void deleteFbo(int f) {
        if (f != 0) GLES30.glDeleteFramebuffers(1, new int[]{f}, 0);
    }

    static boolean hasExtension(String name) {
        String all = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        return all != null && (" " + all + " ").contains(" " + name + " ");
    }

    static void check(String where) {
        int e = GLES30.glGetError();
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, where + ": GL error 0x" + Integer.toHexString(e));
    }
}
