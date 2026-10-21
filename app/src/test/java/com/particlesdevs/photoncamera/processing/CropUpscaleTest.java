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
        int block = s.indexOf("mergedDngBlock = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastDngMosaic;");
        int flag = s.indexOf("mosaicDngOnly = mergedDngBlock > 1;", block);
        int dng = s.indexOf("if (mosaicDngOnly || ((saveRAW >= 1) && alignAlgorithm != 2)) {", flag);
        int check = s.indexOf("final boolean mosaicDng = mosaicDngOnly;", dng);
        int merged = s.indexOf("if (scamMergedDng != null) {", check);
        int stacked = s.indexOf("imageSaved = mosaicDng ? ImageSaver.Util.saveStackedRaw(dngFile, scamMergedDng, processingParameters, true)", merged);
        int reference = s.indexOf("imageSaved = mosaicDng ? ImageSaver.Util.saveSingleRaw(dngFile, output, processingParameters, true)", stacked);
        int end = s.indexOf("if (saveRAW == 2 || mosaicDngOnly) {", reference);
        int pipeline = s.indexOf("PostPipeline pipeline = new PostPipeline();", end);
        assertTrue(block > 0 && flag > block && dng > flag && check > dng && merged > check && stacked > merged && reference > stacked
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

    /**
     * P318 (owner, 2026-10-21: «у нас 2x2 4x4 оптимизированы под все цветовые фильтры?»): the merged mosaic DNG and a mosaic
     * stream's reference frame declare their colour blocks (CFARepeatPatternDim 2b x 2b, black level and GainMap by block;
     * tools/check_dng_mosaic_cfa.cpp checks the native writer): the block reaches the writer before its GainMap, the merged DNG
     * gets the merge's layout, the reference frame the stream's, and the next DNG is Bayer again.
     */
    @Test public void theMosaicDngDeclaresItsColourBlocks() throws Exception {
        String d = src("src/main/java/com/particlesdevs/photoncamera/processing/DngCreator.java");
        int params = d.indexOf("public void setParameters(Parameters parameters) {");
        int block = d.indexOf("setMosaicBlock(parameters.dngMosaicBlock);", params);
        int gain = d.indexOf("setGainMap(parameters.gainMap,", params);
        assertTrue("the block before the GainMap", params > 0 && block > params && gain > block);
        String s = src("src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java");
        int ref = s.indexOf("referenceDngBlock = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastStreamBlock;");
        int merged = s.indexOf("processingParameters.dngMosaicBlock = mergedDngBlock;", ref);
        int mergedSave = s.indexOf("ImageSaver.Util.saveStackedRaw(dngFile, scamMergedDng, processingParameters, true)", merged);
        int mergedReset = s.indexOf("processingParameters.dngMosaicBlock = 1;", mergedSave);
        int reference = s.indexOf("processingParameters.dngMosaicBlock = referenceDngBlock;", mergedReset);
        int referenceSave = s.indexOf("ImageSaver.Util.saveSingleRaw(dngFile, output, processingParameters, true)", reference);
        int referenceReset = s.indexOf("processingParameters.dngMosaicBlock = 1;", referenceSave);
        assertTrue(ref > 0 && merged > ref && mergedSave > merged && mergedReset > mergedSave && reference > mergedReset
                && referenceSave > reference && referenceReset > referenceSave);
        String h = src("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamHybridBurst.java");
        int reset = h.indexOf("lastStreamBlock = 1;");
        int set = h.indexOf("if (made[0] != null) lastStreamBlock = dngOnlyBlock(false, made[0].mosaicBlock, made[0].width, made[0].height);", reset);
        assertTrue("the stream's block, measured or stored, whole colour periods only", reset > 0 && set > reset);
        String c = src("src/main/cpp/dngCreator.cpp");
        assertTrue("no 4x4 expansion into the 4-byte cfa_pattern", !c.contains("metadata.cfa_pattern[y * 4 + x]"));
    }

    @Test public void theDownscaleStaysDownscaleOnly() throws Exception {
        String h = src("src/main/cpp/lanczos-downscale.h");
        assertTrue(h.contains("resample(source, sw, sh, srcStride, destination, dw, dh, dstStride, lobes, false);"));
        assertTrue(h.contains("(!up && (dw > sw || dh > sh))"));
        String j = src("src/main/java/com/particlesdevs/photoncamera/processing/ml/ScamPostDownscale.java");
        assertTrue(j.contains("if (width >= source.getWidth() && height >= source.getHeight()) return source;"));
    }
}
