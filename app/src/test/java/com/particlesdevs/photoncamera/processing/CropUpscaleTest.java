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
     * P258 (owner, 2026-10-19): a mosaic shot without our remosaic keeps its mosaic in the DNG (the reference frame as captured)
     * so Scam Remosaic's neural remosaic can develop it; the binned merge is dropped before the merged branch.
     */
    @Test public void aMosaicShotWithoutRemosaicKeepsItsMosaicInTheDng() throws Exception {
        String s = src("src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java");
        int check = s.indexOf("if (scamMergedDng != null && mosaicAsCaptured(processingParameters.rawSize, mergedSize,");
        int drop = s.indexOf("scamMergedDng = null;", check);
        int merged = s.indexOf("if (scamMergedDng != null) {", check);
        int reference = s.indexOf("imageSaved = ImageSaver.Util.saveStackedRaw(dngFile, output, processingParameters);", merged);
        assertTrue(check > 0 && drop > check && merged > drop && reference > merged);
        assertTrue(s.contains("return on && sensor != null && merged != null && merged.x > 0 && merged.y > 0 && (merged.x < sensor.x || merged.y < sensor.y);"));
    }

    @Test public void theDownscaleStaysDownscaleOnly() throws Exception {
        String h = src("src/main/cpp/lanczos-downscale.h");
        assertTrue(h.contains("resample(source, sw, sh, srcStride, destination, dw, dh, dstStride, lobes, false);"));
        assertTrue(h.contains("(!up && (dw > sw || dh > sh))"));
        String j = src("src/main/java/com/particlesdevs/photoncamera/processing/ml/ScamPostDownscale.java");
        assertTrue(j.contains("if (width >= source.getWidth() && height >= source.getHeight()) return source;"));
    }
}
