package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * P369 (tone-colour-3, viewfinder-vibhl: the RAW viewfinder never set vibHlU, the photo's light-tone vibrance, so the film was
 * shown on less saturated pale tones than the photo gets; P301: the viewfinder's tone is the photo's): every uniform and texture
 * WhyCombine.Run sets on why/combine is set by LiveWhyTone's combine passes too, but those a single viewfinder frame cannot have
 * (the Bento headroom, the highlight landing and its mask, the sun bloom, the full-size image, P378's share of the pixels «Inpaint
 * opposed» rebuilt: the merge's clip flags) - and every one is declared by the shader. P378 review: the sky term is the
 * viewfinder's too (LiveWhyTone decides it on the meter's cells: WhySky, skyU, skyMU, skyTopU are not photo-only).
 */
public class WhyCombineUniformParityTest {
    /** What the viewfinder's frame has not (LiveWhyTone's class comment): headroom, landing, sun bloom, the full-size merge, clip flags. */
    static final Set<String> PHOTO_ONLY = new HashSet<>(Arrays.asList("hrU", "hrKneeU", "hrRoomU", "hrEaseU", "hrDetailU", "hlSoftU", "sunBloomU",
            "sunGridU", "fullInputU", "WhyHl", "SunBloom", "oppGridU", "OppShare",
            // P394: reads the full-size merge (fullInputU), which the viewfinder's frame is not
            "edgeHoldU"));

    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SRC = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";

    /** The body of the method {@code signature} (from its declaration to its closing brace). */
    static String method(String src, String signature) {
        final int at = src.indexOf(signature);
        assertTrue("no " + signature, at >= 0);
        int i = src.indexOf('{', at), depth = 0;
        for (int j = i; j < src.length(); j++) {
            final char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(i, j + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    static Set<String> names(String text, String... patterns) {
        final Set<String> out = new TreeSet<>();
        for (String p : patterns) {
            final Matcher m = Pattern.compile(p).matcher(text);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    @Test
    public void theViewfinderSetsEveryUniformOfThePhotosCombine() throws IOException {
        final String why = read(SRC + "WhyCombine.java");
        // WhyCombine.Run: from the why/combine program to its draw
        final int from = why.indexOf("glProg.useAssetProgram(\"why/combine\"");
        final int to = why.indexOf("glProg.drawBlocks(WorkingTexture);", from);
        assertTrue(from > 0 && to > from);
        final Set<String> photo = names(why.substring(from, to), "glProg\\.setVar(?:Floats|Vec4s)?\\(\"(\\w+)\"",
                "glProg\\.setTexture(?:3D)?\\(\"(\\w+)\"");
        final String live = read(SRC + "LiveWhyTone.java");
        final String passes = method(live, "private void combineUniforms(") + method(live, "private void combine(")
                + method(live, "public boolean drawFused(") + method(live, "private void colour(");
        final Set<String> view = names(passes, "set\\(p, \"(\\w+)\"", "loc\\(p, \"(\\w+)\"\\)", "texture\\(p, \"(\\w+)\"");
        final Set<String> missing = new TreeSet<>(photo);
        missing.removeAll(view);
        missing.removeAll(PHOTO_ONLY);
        assertTrue("WhyCombine sets these on why/combine, LiveWhyTone does not: " + missing, missing.isEmpty());
        assertTrue("the light-tone vibrance", photo.contains("vibHlU") && view.contains("vibHlU"));
        // P378 review (P301): the sky term on both
        assertTrue("the sky term on both", photo.containsAll(Arrays.asList("WhySky", "skyU", "skyMU", "skyTopU"))
                && view.containsAll(Arrays.asList("WhySky", "skyU", "skyMU", "skyTopU")));
        assertTrue("the film's scene feed on both", photo.containsAll(Arrays.asList("filmExpU", "filmLogU", "lutU", "FilmLut"))
                && view.containsAll(Arrays.asList("filmExpU", "filmLogU", "lutU", "FilmLut")));
        // every name is the shader's
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        final Set<String> undeclared = new TreeSet<>();
        for (String n : photo) if (!Pattern.compile("uniform [\\w ]+ " + n + "(\\[\\d+\\])?;").matcher(shader).find()) undeclared.add(n);
        assertTrue("not declared by why/combine.glsl: " + undeclared, undeclared.isEmpty());
    }

    @Test
    public void theFilmFeedIsNeverWithTheDisplayLut() throws IOException {
        // WhyCombine: FILM_SIM only with the scene table, FILM_LUT only without it (the display chain is null whenever the scene
        // table is there; a film whose table is missing falls back to the chain); LiveWhyTone: one of the two
        final String why = read(SRC + "WhyCombine.java");
        assertTrue(why.contains("if (scene != null) glProg.setDefine(\"FILM_SIM\", 1);"));
        assertTrue(why.contains("scene != null ? null\n                : com.particlesdevs.photoncamera.processing.color.FilmChain.current(null,"));
        final String live = read(SRC + "LiveWhyTone.java");
        assertTrue(live.contains("if (lutOn) defs.add(new String[]{scene ? \"FILM_SIM\" : \"FILM_LUT\", \"1\"});"));
        assertTrue("the draws take the kind of the table shown", live.contains("combineDefines(lutOn, lutShownScene, true, true)")
                && live.contains("combineDefines(lutOn, lutShownScene, point, false)"));
    }

    @Test
    public void theLightChromaIsTheAgxPhotosAndTheViewfindersAlike() throws IOException {
        // P393: LIGHT_CHROMA (a saturated colour's light tones keep its mid-tone chroma) on the photo and the viewfinder with AgX only
        // (the block sits under TONE_OP 0), its uniform beside the define; the dev key's default on, clamped to 0 .. 1
        final String why = read(SRC + "WhyCombine.java");
        assertTrue(why.contains("final float lightChroma = toneOp == com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX ? WhyTone.lightChroma() : 0f;"));
        assertTrue(why.contains("if (lightChroma > 0f) glProg.setDefine(\"LIGHT_CHROMA\", 1);"));
        assertTrue(why.contains("if (lightChroma > 0f) glProg.setVar(\"lightChromaU\", lightChroma);"));
        final String live = read(SRC + "LiveWhyTone.java");
        // P393 review: the viewfinder's define and its uniform from the same cached setting (refreshSettings), as colourOn / toneOp
        assertTrue(live.contains("if (toneOp == ToneCurve.AGX && lightChroma > 0f) defs.add(new String[]{\"LIGHT_CHROMA\", \"1\"});"));
        assertTrue(method(live, "private void refreshSettings(").contains("lightChroma = WhyTone.lightChroma();"));
        assertTrue(method(live, "private void combineUniforms(").contains("set(p, \"lightChromaU\", lightChroma);"));
        // P393 review: Ultra HDR's sky ratio draws both without the term (the sky term holds it back under the mask)
        assertTrue(why.contains("skyHdrRatio(pipeline, st, r, skyWith, skyWithout, lightChroma > 0f);"));
        assertTrue(method(why, "private void skyHdrRatio(").contains("if (lightChroma) glProg.setVar(\"lightChromaU\", 0f);"));
        final String tone = read(SRC + "WhyTone.java");
        assertTrue(tone.contains("return Math.max(0f, Math.min(1f, value(\"light_chroma\", 1f)));"));
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue("0: the shader before P393", shader.contains("#define LIGHT_CHROMA 0\n"));
        final int block = shader.indexOf("#if LIGHT_CHROMA == 1\n#if TONE_OP == 0\n");
        assertTrue("the term is AgX's (TONE_OP 0)", block > 0 && shader.indexOf("float keepK", block) > block);
        // P393 review: the orange-yellow scene hues only, the chroma raised from AgX's own continuously, the hue turned by the share of that
        // chroma the locked hue loses to the gamut (no gate where the target first passes the chroma: the hue snapped there)
        final int end = shader.indexOf("    if (look != 3 && inChroma > 0.000001 && hueLockU != 2) {", block);
        assertTrue("the hue lock follows the term", end > block);
        final String term = shader.substring(block, end);
        assertTrue(term.contains("float bandK = smoothstep(LC_HUE_IN.x, LC_HUE_IN.y, hueK) * (1.0 - smoothstep(LC_HUE_OUT.x, LC_HUE_OUT.y, hueK));"));
        assertTrue(term.contains("float cOutK = cNowK * mix(1.0, max(targetK / cNowK, 1.0), keepK);"));
        assertTrue(term.contains("turnK *= clamp(LC_TURN * (1.0 - holdK / cOutK), 0.0, 1.0);"));
        assertTrue("no step where the target meets the chroma", !term.contains("targetK > cNowK"));
        // P393 review (M3 / M4): saturated from the leaves' C / L (skin of their hue, a lit lampshade, a yellow wallpaper under it)
        assertTrue(shader.contains("const vec2 LC_SAT = vec2(0.14, 0.17);"));
        assertTrue(term.contains("float saturatedK = smoothstep(LC_SAT.x, LC_SAT.y, cMidK / max(midK.x, 0.0001));"));
    }
}
