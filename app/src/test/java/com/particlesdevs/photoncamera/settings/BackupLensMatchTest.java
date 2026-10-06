package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Map;

/** Old JSON backups: the lenses they recorded decide whether the backup was written on this phone. */
public class BackupLensMatchTest {
    private static JsonObject backup(String lensList) {
        return JsonParser.parseString("{\"cameras_preferences\":{\"all_camera_lens\":" + lensList + "}}").getAsJsonObject();
    }

    @Test
    public void recordedLensesAreReadFromTheStringSet() {
        // putJsonValueToEditor stores the scan as a string set of CameraLensData JSON.
        Map<String, float[]> lenses = BackupRestoreUtil.recordedLenses(backup(
                "[\"{\\\"id\\\":\\\"0\\\",\\\"face\\\":1,\\\"fl\\\":6.9}\",\"{\\\"id\\\":\\\"5\\\",\\\"face\\\":1,\\\"fl\\\":14.0}\"]"));
        assertEquals(2, lenses.size());
        assertEquals(6.9f, lenses.get("0")[1], 1e-6f);
        assertEquals(1f, lenses.get("5")[0], 0f);
    }

    @Test
    public void missingOrBrokenListsSayNothing() {
        assertTrue(BackupRestoreUtil.recordedLenses(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
        assertTrue(BackupRestoreUtil.recordedLenses(backup("[\"not json\", 3]")).isEmpty());
    }

    @Test
    public void focalLengthAndFacingMustMatch() {
        float[] tele = {1, 14.0f};
        assertTrue(BackupRestoreUtil.lensMatches(tele, 1, new float[]{14.2f}));   // 1.4 %: same lens
        assertFalse(BackupRestoreUtil.lensMatches(tele, 1, new float[]{8.67f}));  // X100 Ultra main under the X200 Ultra tele id
        assertFalse(BackupRestoreUtil.lensMatches(tele, 0, new float[]{14.0f}));  // other facing
        assertTrue(BackupRestoreUtil.lensMatches(tele, 1, new float[]{6.9f, 14.0f})); // any of the listed focal lengths
        assertTrue(BackupRestoreUtil.lensMatches(new float[]{-1, 0}, 1, new float[]{8.67f})); // nothing recorded
    }
}
