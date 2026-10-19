package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P226 / owner, 2026-10-19 («Увеличивать кадр с кропом» - «настройка со скриншота не работает»): the digital zoom's crop was
 * taken after the resize to the photo size and never brought back, so the switch changed nothing. On, the crop now comes
 * first and the cropped image is scaled to the photo size (up if need be); off, the crop keeps its pixels.
 */
public class CropUpscaleTest {
    private static String src(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void theSwitchBringsTheCropToThePhotoSize() throws Exception {
        String s = src("src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java");
        int target = s.indexOf("final Point cropTarget = zoomCrop > 1.005f && PreferenceKeys.cropUpscale() ? photoSize(img, hybridFinal) : null;");
        int resize = s.indexOf("if (cropTarget == null && hybridFinal != null");
        int crop = s.indexOf("img = com.particlesdevs.photoncamera.control.ZoomController.crop(img, zoomCrop);");
        int scale = s.indexOf("ScamPostDownscale.scaleTo(img, cropTarget.x, cropTarget.y)");
        assertTrue("the target before the resize, the resize skipped when it is set, the crop, then the scale to the photo size",
                target > 0 && resize > target && crop > resize && scale > crop);
        assertTrue("the gain map follows", s.indexOf("gm = gm.resizedFor(img.getWidth(), img.getHeight());", scale) > scale);
    }

    /**
     * P258 / P267 (owner, 2026-10-19: «При выкл сохраняй только днг без ремозаика»; «Склейка должны быть, просто без
     * ремозаика»): a mosaic shot without our remosaic is merged and its merged mosaic DNG is the shot - saved whatever the RAW
     * setting says, uncompressed (Scam Remosaic reads uncompressed 16-bit CFA only), the reference frame as captured when the merge
     * gave none, and the shot ends there (no photo).
     */
    @Test public void aMosaicShotWithoutRemosaicIsItsMergedMosaicDngAlone() throws Exception {
        String s = src("src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java");
        int flag = s.indexOf("mosaicDngOnly = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastDngMosaic > 1;");
        int dng = s.indexOf("if (mosaicDngOnly || ((saveRAW >= 1) && alignAlgorithm != 2)) {", flag);
        int check = s.indexOf("final boolean mosaicDng = mosaicDngOnly;", dng);
        int merged = s.indexOf("if (scamMergedDng != null) {", check);
        int stacked = s.indexOf("imageSaved = mosaicDng ? ImageSaver.Util.saveStackedRaw(dngFile, scamMergedDng, processingParameters, true)", merged);
        int reference = s.indexOf("imageSaved = mosaicDng ? ImageSaver.Util.saveSingleRaw(dngFile, output, processingParameters, true)", stacked);
        int end = s.indexOf("if (saveRAW == 2 || mosaicDngOnly) {", reference);
        int pipeline = s.indexOf("PostPipeline pipeline = new PostPipeline();", end);
        assertTrue(flag > 0 && dng > flag && check > dng && merged > check && stacked > merged && reference > stacked
                && end > reference && pipeline > end);
        String h = src("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamHybridBurst.java");
        assertTrue(h.contains("burst.mergedDng = mergedDng || burst.dngMosaic > 1;"));
        assertTrue(h.contains("safe.mergedDng = safe.dngMosaic > 1;"));
        String w = src("src/main/cpp/scam-hybrid.h");
        assertTrue(w.contains("||set(\"dngMosaic\",nullptr,&t.dngMosaic)"));
        int at = w.indexOf("const int dngBlock=tune.dngMosaic!=0&&(block==2||block==4)?block:1;");
        assertTrue("the native merge and the sub-frame split write the mosaic layout",
                at > 0 && w.indexOf("const int dngBlock=tune.dngMosaic!=0&&(block==2||block==4)?block:1;", at + 1) > at);
    }

    @Test public void theDownscaleStaysDownscaleOnly() throws Exception {
        String h = src("src/main/cpp/lanczos-downscale.h");
        assertTrue(h.contains("resample(source, sw, sh, srcStride, destination, dw, dh, dstStride, lobes, false);"));
        assertTrue(h.contains("(!up && (dw > sw || dh > sh))"));
        String j = src("src/main/java/com/particlesdevs/photoncamera/processing/ml/ScamPostDownscale.java");
        assertTrue(j.contains("if (width >= source.getWidth() && height >= source.getHeight()) return source;"));
    }
}
