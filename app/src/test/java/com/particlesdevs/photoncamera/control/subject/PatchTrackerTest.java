package com.particlesdevs.photoncamera.control.subject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** P42: the ZNCC patch tracker follows a moving textured subject and gives up when it is covered. */
public class PatchTrackerTest {
    private static final int W = 320, H = 420, OBJ = 64;

    /** Smooth random texture (box-blurred noise) in 0..255. */
    private static int[] texture(int w, int h, long seed) {
        Random random = new Random(seed);
        int[] a = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = random.nextInt(256);
        for (int pass = 0; pass < 2; pass++) {
            int[] b = new int[a.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sum = 0, n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx, yy = y + dy;
                            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                            sum += a[yy * w + xx];
                            n++;
                        }
                    }
                    b[y * w + x] = sum / n;
                }
            }
            a = b;
        }
        // Stretch the contrast back after blurring.
        for (int i = 0; i < a.length; i++) a[i] = Math.max(0, Math.min(255, (a[i] - 128) * 3 + 128));
        return a;
    }

    private static final int[] BACKGROUND = texture(W, H, 1);
    private static final int[] OBJECT = texture(OBJ, OBJ, 2);

    /** Background with the object's top-left corner at (ox, oy). */
    private static byte[] frame(int ox, int oy) {
        byte[] f = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = BACKGROUND[y * W + x];
                int u = x - ox, w = y - oy;
                if (u >= 0 && w >= 0 && u < OBJ && w < OBJ) v = OBJECT[w * OBJ + u];
                f[y * W + x] = (byte) v;
            }
        }
        return f;
    }

    private static byte[] flat() {
        byte[] f = new byte[W * H];
        java.util.Arrays.fill(f, (byte) 128);
        return f;
    }

    @Test
    public void followsAMovingSubject() {
        PatchTracker tracker = new PatchTracker(64, 6);
        int ox = 60, oy = 80;
        tracker.start((ox + OBJ / 2f) / (W - 1), (oy + OBJ / 2f) / (H - 1));
        assertEquals(PatchTracker.TRACKING, tracker.update(frame(ox, oy), W, H));
        // Diagonal motion, 7 px right and 5 px down per frame (about 2 view widths a second at 15 fps), then back.
        for (int i = 0; i < 25; i++) {
            ox += 7;
            oy += 5;
            assertEquals("frame " + i, PatchTracker.TRACKING, tracker.update(frame(ox, oy), W, H));
        }
        for (int i = 0; i < 10; i++) {
            ox -= 9;
            assertEquals(PatchTracker.TRACKING, tracker.update(frame(ox, oy), W, H));
        }
        float cx = tracker.u() * (W - 1), cy = tracker.v() * (H - 1);
        assertEquals(ox + OBJ / 2f, cx, 2f);
        assertEquals(oy + OBJ / 2f, cy, 2f);
        assertTrue(tracker.score() > 0.6f);
    }

    @Test
    public void lostWhenCovered() {
        PatchTracker tracker = new PatchTracker(64, 6);
        tracker.start((100 + OBJ / 2f) / (W - 1), (150 + OBJ / 2f) / (H - 1));
        tracker.update(frame(100, 150), W, H);
        for (int i = 0; i < 3; i++) assertEquals(PatchTracker.TRACKING, tracker.update(frame(100 + 3 * i, 150), W, H));
        byte[] covered = flat();
        for (int i = 0; i < 5; i++) assertEquals("miss " + i + " holds", PatchTracker.TRACKING, tracker.update(covered, W, H));
        assertEquals(PatchTracker.LOST, tracker.update(covered, W, H));
        assertEquals(PatchTracker.LOST, tracker.update(frame(100, 150), W, H)); // stays lost until restarted
    }

    @Test
    public void shortOcclusionIsBridged() {
        PatchTracker tracker = new PatchTracker(64, 6);
        tracker.start((100 + OBJ / 2f) / (W - 1), (150 + OBJ / 2f) / (H - 1));
        tracker.update(frame(100, 150), W, H);
        tracker.update(flat(), W, H);
        tracker.update(flat(), W, H);
        assertEquals(PatchTracker.TRACKING, tracker.update(frame(104, 152), W, H));
        assertEquals(104 + OBJ / 2f, tracker.u() * (W - 1), 2f);
    }

    @Test
    public void frameSizeChangeEndsTheTrack() {
        PatchTracker tracker = new PatchTracker(64, 6);
        tracker.start(0.5f, 0.5f);
        tracker.update(frame(128, 178), W, H);
        assertEquals(PatchTracker.LOST, tracker.update(new byte[200 * 300], 200, 300));
    }

    @Test
    public void startNearTheBorderIsClamped() {
        PatchTracker tracker = new PatchTracker(64, 6);
        tracker.start(0f, 1f);
        assertEquals(PatchTracker.TRACKING, tracker.update(frame(0, H - OBJ), W, H));
        assertTrue(tracker.u() > 0f && tracker.v() < 1f);
        assertEquals(32, tracker.patchFor(W, H));
        tracker.stop();
        assertEquals(PatchTracker.IDLE, tracker.state());
    }
}
