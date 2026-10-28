package com.particlesdevs.photoncamera.processing.color;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA): the app's own film code for research/filmsim/pair380.py, run on
 * the host against the classes Gradle compiled (not built into the app):
 * <pre>
 *   CP=app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:&lt;androidx annotation jar&gt;
 *   javac -cp $CP -d /tmp/scenedump research/filmsim/SceneDump.java
 *   java -cp $CP:/tmp/scenedump com.particlesdevs.photoncamera.processing.color.SceneDump table app/src/main/assets/film \
 *       kodak_portra_160 kodak_endura_premier 0 out.f32
 *   java ... SceneDump diffuse in.f32 W H pearl 2.0 imax70 frameLongPx out.f32
 * </pre>
 * {@code table}: FilmSim.lutScene of a stock / print / DIR couplers' amount (%) - the photo's scene table (41^3, linear sRGB
 * scene light on the log grid around 0.184, encoded sRGB out, float32 little-endian, red fastest). {@code diffuse}: the film's
 * diffusion as the photo's FilmDiffusion node draws it (DiffusionGauss.plan of FilmSim.Settings.diffusionP on the format's frame,
 * the CPU mirror of the GPU passes) on a W x H x 3 float32 scene image whose frame is {@code frameLongPx} long.
 */
public final class SceneDump {
    private SceneDump() {}

    public static void main(String[] a) throws IOException {
        if (a.length >= 6 && a[0].equals("table")) {
            final String dir = a[1];
            FilmSpectral.source(n -> new FileInputStream(dir + "/" + n));
            final FilmSim.Stock st = FilmSim.stock(a[2]);
            final FilmSim.Settings set = new FilmSim.Settings(st, FilmSim.print(a[3], st), FilmSim.format(FilmSim.DEFAULT_FORMAT), 1, 1, 1,
                    0, 0, 0, 0, 0, 1, "", FilmSim.DIFFUSION_DEFAULT, FilmSim.couplers(a[4]));
            final FilmLut lut = FilmSim.lutScene(set, FilmSim.SCENE_SIZE);
            if (lut == null) throw new IOException("film data unreadable");
            final ByteBuffer bb = ByteBuffer.allocate(lut.rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : lut.rgb) bb.putFloat(v);
            try (FileOutputStream f = new FileOutputStream(a[5])) { f.write(bb.array()); }
            System.out.println("table " + set.lutKey() + " " + lut.size + "^3 -> " + a[5]);
            return;
        }
        if (a.length >= 9 && a[0].equals("diffuse")) {
            final int w = Integer.parseInt(a[2]), h = Integer.parseInt(a[3]);
            final float grade = Float.parseFloat(a[5]);
            final FilmSim.Settings set = new FilmSim.Settings(FilmSim.STOCKS[0], FilmSim.print("", FilmSim.STOCKS[0]), FilmSim.format(a[6]),
                    1, 0, 0, 0, 0, 0, 0, 0, 1, a[4], grade, 1);
            final byte[] in = Files.readAllBytes(Paths.get(a[1]));
            final float[] rgb = new float[w * h * 3];
            ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rgb);
            final DiffusionGauss.Plan plan = DiffusionGauss.plan(set.diffusion, set.diffusionP(), LensFilters.Tune.defaults(set.diffusion), w, h,
                    Double.parseDouble(a[7]), set.diffusionFrameUm());
            if (plan == null) throw new IOException("no diffusion: " + a[4]);
            final long t0 = System.currentTimeMillis();
            DiffusionGauss.apply(rgb, w, h, plan);
            final ByteBuffer bb = ByteBuffer.allocate(rgb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : rgb) bb.putFloat(v);
            try (FileOutputStream f = new FileOutputStream(a[8])) { f.write(bb.array()); }
            System.out.println(String.format(java.util.Locale.ROOT, "diffuse %s grade %.2f %s frame %.0f um p %.4f gaussians %d levels %d (%d ms)",
                    set.diffusion, grade, set.format.id, set.diffusionFrameUm(), plan.p, plan.blurs.size(), plan.top + 1,
                    System.currentTimeMillis() - t0));
            return;
        }
        System.err.println("usage: table <assets/film> <stock> <print> <dir %> <out.f32> | diffuse <in.f32> <w> <h> <id> <grade> <format> <frameLongPx> <out.f32>");
        System.exit(2);
    }
}
