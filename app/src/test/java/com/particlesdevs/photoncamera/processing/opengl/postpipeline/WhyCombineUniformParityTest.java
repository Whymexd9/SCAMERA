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
 * (the Bento headroom, the highlight landing and its mask, the sun bloom, the full-size image) - and every one is declared by the
 * shader.
 */
public class WhyCombineUniformParityTest {
    /** What the viewfinder's frame has not (LiveWhyTone's class comment): headroom, landing, sun bloom, the full-size merge. */
    static final Set<String> PHOTO_ONLY = new HashSet<>(Arrays.asList("hrU", "hrKneeU", "hrRoomU", "hrDetailU", "hlSoftU", "sunBloomU",
            "sunGridU", "fullInputU", "WhyHl", "SunBloom"));

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
}
