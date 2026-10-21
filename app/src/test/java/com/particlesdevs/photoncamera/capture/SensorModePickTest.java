package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureRequest;

import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

/** P276: the module's sensor mode from the camera's SensorModeTable (ArkCam's per-lens list). */
public class SensorModePickTest {
    /** count 4, stride 3: 8192x6144@30 (Quad), 4096x3072@30 (binned), 4096x2304@60, 2048x1536@120. */
    private static final int[] TABLE = {4, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 2304, 60, 2048, 1536, 120};

    @Test public void rowsAndLabelsAsArkCamListsThem() {
        List<int[]> rows = SensorModePick.rows(TABLE);
        assertEquals(4, rows.size());
        assertEquals("Mode #0: 8192x6144 @ 30fps", SensorModePick.label(rows.get(0)));
        assertEquals("Mode #3: 2048x1536 @ 120fps", SensorModePick.label(rows.get(3)));
        assertTrue(SensorModePick.rows((int[]) null).isEmpty());
    }

    @Test public void aPickIsARowOfTheTable() {
        SensorModePick.Pick p = SensorModePick.of(0, TABLE);
        assertNotNull(p);
        assertEquals(8192, p.width);
        assertEquals(6144, p.height);
        assertNull("Auto", SensorModePick.of(-1, TABLE));
        assertNull("a row the firmware does not have", SensorModePick.of(9, TABLE));
        assertNull("no table", SensorModePick.of(0, null));
    }

    @Test public void theFullSizeRowIsTheSensorsMosaic() {
        assertEquals("8192x6144 with a binned 4096x3072 row: Quad", 2, SensorModePick.declaredBlock(TABLE, 0));
        assertEquals("a binned row: measured", 0, SensorModePick.declaredBlock(TABLE, 1));
        assertEquals(0, SensorModePick.declaredBlock(TABLE, 2));
        final int[] tetra = {2, 3, 16320, 12288, 10, 4080, 3072, 30};
        assertEquals("200 MP with a quarter row: Tetra", 4, SensorModePick.declaredBlock(tetra, 0));
    }

    @Test public void theBrandsModeKey() {
        assertEquals(SensorModePick.Brand.VIVO, SensorModePick.brand("vivo"));
        assertEquals(SensorModePick.Brand.VIVO, SensorModePick.brand("iQOO"));
        assertEquals(SensorModePick.Brand.OPLUS, SensorModePick.brand("OPPO"));
        assertEquals(SensorModePick.Brand.OPLUS, SensorModePick.brand("OnePlus"));
        assertEquals(SensorModePick.Brand.OPLUS, SensorModePick.brand("realme"));
        assertEquals(SensorModePick.Brand.QCOM, SensorModePick.brand("Xiaomi"));
        assertEquals(SensorModePick.Brand.QCOM, SensorModePick.brand(null));
        CaptureRequest.Builder b = Mockito.mock(CaptureRequest.Builder.class);
        assertEquals("vivo.control.forceSensorMode = [4]", SensorModePick.apply(b, 4, SensorModePick.Brand.VIVO));
        Mockito.verify(b).set(ArgumentMatchers.any(), ArgumentMatchers.any(int[].class));
        CaptureRequest.Builder q = Mockito.mock(CaptureRequest.Builder.class);
        assertEquals("org.codeaurora.qcamera3.sensor_meta_data.current_mode = 9", SensorModePick.apply(q, 9, SensorModePick.Brand.QCOM));
        Mockito.verify(q).set(ArgumentMatchers.any(), ArgumentMatchers.eq(9));
    }

    @Test public void noSessionNoKey() {
        SensorModePick.session(null, null);
        CaptureRequest.Builder b = Mockito.mock(CaptureRequest.Builder.class);
        assertEquals("", SensorModePick.apply(b));
        Mockito.verifyNoInteractions(b);
    }

    /**
     * P315: a picked sensor mode on an OPPO / OnePlus / realme camera shoots from the ring alone (the X8U's HAL failed every request
     * after the shutter in mode 8 and died); other brands and sessions without a pick keep the series; the dev switch forces it.
     */
    @Test public void anOplusPickShootsFromTheRingAlone() {
        assertFalse(SensorModePick.seriesAllowed(true, SensorModePick.Brand.OPLUS, false));
        assertTrue(SensorModePick.seriesAllowed(true, SensorModePick.Brand.OPLUS, true));
        assertTrue(SensorModePick.seriesAllowed(false, SensorModePick.Brand.OPLUS, false));
        assertTrue(SensorModePick.seriesAllowed(true, SensorModePick.Brand.VIVO, false));
        assertTrue(SensorModePick.seriesAllowed(true, SensorModePick.Brand.QCOM, false));
        assertTrue(HybridPlan.zslOnly(10_000_000L, 200, SensorModePick.SERIES_REASON).requests.isEmpty());
    }

    /**
     * P316 (settings audit): a shot whose ring drain gave no usable frame is refused in an OPPO-family pick (the normal-back plan
     * would send the series after the shutter); everywhere else it falls back to the frames after the shutter, as before.
     */
    @Test public void anEmptyRingIsRefusedWhereTheSeriesIsNot() {
        assertTrue(SensorModePick.emptyRingRefused(true, SensorModePick.seriesAllowed(true, SensorModePick.Brand.OPLUS, false)));
        assertFalse(SensorModePick.emptyRingRefused(false, SensorModePick.seriesAllowed(true, SensorModePick.Brand.OPLUS, false)));
        assertFalse("sensor_mode_series 1", SensorModePick.emptyRingRefused(true, SensorModePick.seriesAllowed(true, SensorModePick.Brand.OPLUS, true)));
        assertFalse(SensorModePick.emptyRingRefused(true, SensorModePick.seriesAllowed(true, SensorModePick.Brand.VIVO, false)));
        assertFalse(SensorModePick.emptyRingRefused(true, SensorModePick.seriesAllowed(true, SensorModePick.Brand.QCOM, false)));
        assertFalse("no pick", SensorModePick.emptyRingRefused(true, SensorModePick.seriesAllowed(false, SensorModePick.Brand.OPLUS, false)));
    }
}
