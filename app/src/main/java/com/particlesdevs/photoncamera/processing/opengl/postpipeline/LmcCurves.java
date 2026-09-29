package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * LMC/GCam tone and gamma curve presets (assets/curves) on the display-encoded
 * image: tone curve (17 points) first, then gamma curve (33 points), per RGB
 * channel as in GCam, each blended with the identity by its strength.
 */
public final class LmcCurves extends Node {
    private static final int LUT_SIZE = 1024;

    public LmcCurves() {
        super("", "LmcCurves");
    }

    @Override public void Compile() {}

    static float[] load(String asset, int expected) throws Exception {
        List<Float> values = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                PhotonCamera.getAppContext().getAssets().open("curves/" + asset), StandardCharsets.UTF_8))) {
            String line; boolean header = true;
            while ((line = in.readLine()) != null) {
                line = line.replace("﻿", "").trim();
                if (line.isEmpty()) continue;
                if (header) {
                    header = false;
                    if (!line.startsWith("TONE_CURVE") && !line.startsWith("GAMMA_CURVE"))
                        throw new IllegalArgumentException("unknown curve header: " + line);
                    continue;
                }
                values.add(Float.parseFloat(line));
            }
        }
        if (values.size() != expected) throw new IllegalArgumentException(asset + ": " + values.size() + " points");
        float[] out = new float[expected];
        for (int i = 0; i < expected; i++) out[i] = values.get(i);
        return out;
    }

    /** Monotone cubic (Fritsch-Carlson) through uniformly spaced points on [0,1]. */
    static float sample(float[] p, float x) {
        int n = p.length - 1;
        float t = Math.max(0f, Math.min(1f, x)) * n;
        int i = Math.min(n - 1, (int) t);
        float u = t - i;
        float d0 = p[i + 1] - p[i];
        float m0 = tangent(p, i), m1 = tangent(p, i + 1);
        if (d0 == 0f) { m0 = 0f; m1 = 0f; }
        else {
            float a = m0 / d0, b = m1 / d0, s = a * a + b * b;
            if (s > 9f) { float k = 3f / (float) Math.sqrt(s); m0 = k * a * d0; m1 = k * b * d0; }
        }
        float u2 = u * u, u3 = u2 * u;
        return (2 * u3 - 3 * u2 + 1) * p[i] + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p[i + 1] + (u3 - u2) * m1;
    }

    private static float tangent(float[] p, int i) {
        int n = p.length - 1;
        if (i == 0) return p[1] - p[0];
        if (i == n) return p[n] - p[n - 1];
        float a = p[i] - p[i - 1], b = p[i + 1] - p[i];
        return a * b <= 0f ? 0f : (a + b) * 0.5f;
    }

    @Override public void Run() {
        String toneName = PreferenceKeys.getLmcToneCurve(), gammaName = PreferenceKeys.getLmcGammaCurve();
        float toneStrength = PreferenceKeys.getLmcToneCurveStrength(), gammaStrength = PreferenceKeys.getLmcGammaCurveStrength();
        float[] tone = null, gamma = null;
        try {
            if (!"off".equals(toneName) && toneStrength > 0f) tone = load(toneName, 17);
            if (!"off".equals(gammaName) && gammaStrength > 0f) gamma = load(gammaName, 33);
        } catch (Exception e) {
            Log.e(Name, "LMC curve unavailable: " + e);
            tone = null; gamma = null;
        }
        if (tone == null && gamma == null) {
            WorkingTexture = previousNode.WorkingTexture;
            glProg.closed = true;
            return;
        }
        float[] lut = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            float x = i / (float) (LUT_SIZE - 1), y = x;
            if (tone != null) y += (sample(tone, y) - y) * toneStrength;
            if (gamma != null) y += (sample(gamma, y) - y) * gammaStrength;
            lut[i] = Math.max(0f, Math.min(1f, y));
        }
        GLTexture lutTexture = new GLTexture(LUT_SIZE, 1, new GLFormat(GLFormat.DataType.FLOAT_16, 1),
                BufferUtils.getFrom(lut), GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.setDefine("LUTSIZE", LUT_SIZE);
        glProg.useAssetProgram("lmc/curves");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setTexture("CurveLut", lutTexture);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        lutTexture.close();
        Log.i(Name, "LMC curves: tone=" + toneName + "@" + toneStrength + " gamma=" + gammaName + "@" + gammaStrength
                + " mid=" + lut[LUT_SIZE / 2] + " q1=" + lut[LUT_SIZE / 4] + " q3=" + lut[3 * LUT_SIZE / 4]);
    }
}
