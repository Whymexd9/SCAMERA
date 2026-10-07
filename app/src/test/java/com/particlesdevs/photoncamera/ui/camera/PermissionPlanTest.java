package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.ui.camera.PermissionPlan.Group;
import com.particlesdevs.photoncamera.ui.camera.PermissionPlan.Step;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P31: the startup permissions are asked like ArkCam does, in one batch and never in an endless loop. */
public class PermissionPlanTest {
    private static final String CAMERA = "android.permission.CAMERA";
    private static final String MIC = "android.permission.RECORD_AUDIO";
    private static final String IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String VIDEO = "android.permission.READ_MEDIA_VIDEO";
    private static final String READ = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String WRITE = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final Predicate<String> NONE = p -> false;
    private static final Predicate<String> ALL = p -> true;

    private static Predicate<String> grantedOnly(String... permissions) {
        Set<String> set = new HashSet<>(Arrays.asList(permissions));
        return set::contains;
    }

    @Test
    public void oneBatchHoldsEveryRuntimePermissionOfTheSdk() {
        assertArrayEquals(new String[]{CAMERA, MIC, IMAGES, VIDEO}, PermissionPlan.runtimePermissions(35));
        assertArrayEquals(new String[]{CAMERA, MIC, IMAGES, VIDEO}, PermissionPlan.runtimePermissions(33));
        assertArrayEquals(new String[]{CAMERA, MIC, READ}, PermissionPlan.runtimePermissions(32));
        assertArrayEquals(new String[]{CAMERA, MIC, READ}, PermissionPlan.runtimePermissions(30));
        assertArrayEquals(new String[]{CAMERA, MIC, WRITE, READ}, PermissionPlan.runtimePermissions(29));
        assertArrayEquals(new String[]{CAMERA, MIC, WRITE, READ}, PermissionPlan.runtimePermissions(26));
        for (int sdk = 26; sdk <= 36; sdk++) {
            assertFalse("INTERNET is a normal permission, sdk " + sdk,
                    Arrays.asList(PermissionPlan.runtimePermissions(sdk)).contains("android.permission.INTERNET"));
        }
    }

    @Test
    public void missingKeepsTheRequestOrder() {
        PermissionPlan plan = new PermissionPlan(34);
        assertArrayEquals(new String[]{CAMERA, MIC, IMAGES, VIDEO}, plan.missing(NONE));
        assertArrayEquals(new String[]{MIC, VIDEO}, plan.missing(grantedOnly(CAMERA, IMAGES)));
        assertArrayEquals(new String[0], plan.missing(ALL));
    }

    @Test
    public void theFirstRequestGoesOutWithoutAskingTheRationale() {
        PermissionPlan plan = new PermissionPlan(34);
        Predicate<String> mustNotBeAsked = p -> {
            throw new AssertionError("rationale asked before any request");
        };
        assertEquals(Step.REQUEST, plan.next(plan.missing(NONE), mustNotBeAsked, false));
    }

    @Test
    public void aRefusalIsAskedAgainOnlyWhileTheSystemCanAsk() {
        PermissionPlan plan = new PermissionPlan(34);
        plan.onRequest();
        plan.onResult(true);
        String[] missing = plan.missing(grantedOnly(CAMERA, MIC));
        assertEquals(Step.REQUEST, plan.next(missing, grantedOnly(VIDEO), true));
        assertEquals(Step.SETTINGS, plan.next(missing, NONE, true));
    }

    @Test
    public void automaticReAsksStopAtTheCap() {
        PermissionPlan plan = new PermissionPlan(29);
        String[] missing = plan.missing(NONE);
        int requests = 0;
        // A user who always refuses on a system that always offers the prompt again (Android 8-10 without
        // "Don't ask again"): the plan has to give up and offer the settings.
        while (plan.next(missing, ALL, true) == Step.REQUEST) {
            plan.onRequest();
            plan.onResult(true);
            assertTrue("endless request loop", ++requests <= PermissionPlan.MAX_REQUESTS);
        }
        assertEquals(PermissionPlan.MAX_REQUESTS, requests);
        assertEquals(Step.SETTINGS, plan.next(missing, ALL, true));
    }

    @Test
    public void anInterruptedRequestIsRepeatedButBounded() {
        PermissionPlan plan = new PermissionPlan(34);
        String[] missing = plan.missing(NONE);
        int requests = 0;
        while (plan.next(missing, NONE, true) == Step.REQUEST) {
            plan.onRequest();
            plan.onResult(false);
            assertTrue("endless request loop", ++requests <= PermissionPlan.MAX_REQUESTS);
        }
        assertEquals(PermissionPlan.MAX_REQUESTS, requests);
        assertEquals(Step.SETTINGS, plan.next(missing, NONE, true));
    }

    @Test
    public void comingBackFromTheSettingsStartsANewRound() {
        PermissionPlan plan = new PermissionPlan(34);
        for (int i = 0; i < PermissionPlan.MAX_REQUESTS; i++) {
            plan.onRequest();
            plan.onResult(true);
        }
        assertEquals(Step.SETTINGS, plan.next(plan.missing(NONE), NONE, true));
        plan.restart();
        assertEquals(Step.REQUEST, plan.next(plan.missing(NONE), NONE, true));
        assertEquals(Step.START, plan.next(plan.missing(ALL), NONE, true));
    }

    @Test
    public void dcimFollowsTheRuntimePermissionsOnAndroid11AndLater() {
        PermissionPlan plan = new PermissionPlan(34);
        assertTrue(plan.needsDcimAccess());
        assertEquals(Step.REQUEST, plan.next(plan.missing(NONE), NONE, false));
        assertEquals(Step.PICK_DCIM, plan.next(plan.missing(ALL), NONE, false));
        assertEquals(Step.START, plan.next(plan.missing(ALL), NONE, true));

        PermissionPlan legacy = new PermissionPlan(29);
        assertFalse(legacy.needsDcimAccess());
        assertEquals(Step.START, legacy.next(legacy.missing(ALL), NONE, false));
    }

    @Test
    public void aWrongDcimFolderIsRetriedButNotForever() {
        PermissionPlan plan = new PermissionPlan(31);
        String[] none = plan.missing(ALL);
        int picks = 0;
        while (plan.next(none, NONE, false) == Step.PICK_DCIM) {
            assertEquals(picks, plan.dcimPicks());
            plan.onDcimPick();
            assertTrue("endless picker loop", ++picks <= PermissionPlan.MAX_DCIM_PICKS);
        }
        assertEquals(PermissionPlan.MAX_DCIM_PICKS, picks);
        assertEquals(Step.DCIM_FAILED, plan.next(none, NONE, false));
        plan.restart();
        assertEquals(Step.PICK_DCIM, plan.next(none, NONE, false));
        assertEquals(0, plan.dcimPicks());
    }

    @Test
    public void aCancelledDcimPickWaitsForTheUser() {
        PermissionPlan plan = new PermissionPlan(34);
        String[] none = plan.missing(ALL);
        plan.onDcimPick();
        plan.onDcimCancelled();
        assertEquals(Step.DCIM_FAILED, plan.next(none, NONE, false));
        plan.restart();
        assertEquals(Step.PICK_DCIM, plan.next(none, NONE, false));
    }

    @Test
    public void aGrantedDcimPickIsTrustedEvenIfThePathCheckDisagrees() {
        PermissionPlan plan = new PermissionPlan(34);
        plan.onDcimPick();
        plan.onDcimGranted();
        assertEquals(Step.START, plan.next(plan.missing(ALL), NONE, false));
    }

    @Test
    public void settingsDialogNamesTheMissingGroups() {
        PermissionPlan p34 = new PermissionPlan(34);
        assertEquals(Arrays.asList(Group.CAMERA, Group.MICROPHONE, Group.PHOTOS), p34.groups(p34.missing(NONE)));
        assertEquals(Collections.singletonList(Group.PHOTOS), p34.groups(new String[]{VIDEO}));
        PermissionPlan p31 = new PermissionPlan(31);
        assertEquals(Collections.singletonList(Group.FILES), p31.groups(p31.missing(grantedOnly(CAMERA, MIC))));
        PermissionPlan p29 = new PermissionPlan(29);
        assertEquals(Arrays.asList(Group.MICROPHONE, Group.STORAGE), p29.groups(p29.missing(grantedOnly(CAMERA))));
    }

    @Test
    public void onlyTheMediaReadKeepsTheFullAccessText() {
        assertTrue(PermissionPlan.mediaOnly(Collections.singletonList(Group.PHOTOS)));
        assertTrue(PermissionPlan.mediaOnly(Collections.singletonList(Group.FILES)));
        assertFalse(PermissionPlan.mediaOnly(Collections.singletonList(Group.STORAGE)));
        assertFalse(PermissionPlan.mediaOnly(Arrays.asList(Group.CAMERA, Group.PHOTOS)));
        assertFalse(PermissionPlan.mediaOnly(Collections.emptyList()));
    }

    @Test
    public void theRoundSurvivesARecreation() {
        PermissionPlan plan = new PermissionPlan(34);
        plan.onRequest();
        plan.onRequest();
        plan.onResult(false);
        plan.onDcimPick();
        plan.onDcimCancelled();
        PermissionPlan back = PermissionPlan.restore(34, plan.save());
        assertArrayEquals(plan.save(), back.save());
        assertArrayEquals(new PermissionPlan(34).save(), PermissionPlan.restore(34, null).save());
        assertArrayEquals(new PermissionPlan(34).save(), PermissionPlan.restore(34, new int[]{2}).save());
    }

    /** Text of string {@code name} in {@code file}, or null. */
    private static String string(String file, String name) throws Exception {
        String xml = new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(name) + "\"[^>]*>([^<]*)</string>").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    @Test
    public void permissionTextsExistInBothLanguagesAndDoNotNameTheOldApp() throws Exception {
        String[] names = {"perm_open_settings", "perm_settings_title", "perm_settings_message", "perm_group_camera",
                "perm_group_microphone", "perm_group_photos", "perm_group_files", "perm_group_storage",
                "perm_rationale_media_title", "perm_rationale_media_settings", "perm_dcim_hint",
                "perm_dcim_wrong_folder", "perm_rationale_dcim_title", "perm_dcim_failed", "perm_retry"};
        for (String file : new String[]{"src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml"}) {
            for (String name : names) {
                String text = string(file, name);
                assertTrue(file + " lacks " + name, text != null && !text.isEmpty());
                assertFalse(file + " " + name, text.contains("PhotonCamera"));
            }
            String xml = new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
            for (String gone : new String[]{"perm_rationale_camera_message", "perm_rationale_storage_message",
                    "perm_rationale_media_message", "perm_rationale_dcim_message"}) {
                assertFalse(file + " still has the pre-request rationale " + gone,
                        xml.contains("name=\"" + gone + "\""));
            }
        }
    }
}
