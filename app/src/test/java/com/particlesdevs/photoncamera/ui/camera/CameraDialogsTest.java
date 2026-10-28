package com.particlesdevs.photoncamera.ui.camera;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.FullResTrials;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * The camera side's alert dialogs after their move to DialogStyle (owner, 2026-10-28: one look for every dialog): the
 * permission dialogs, the camera error, «50 Мп выключен» and the lens editor still show their texts, keep their
 * cancelability and run the same actions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class CameraDialogsTest {
    private MockedStatic<ScameraApp> app;
    private SettingsManager manager;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
    }

    @After public void tearDown() {
        app.close();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static AlertDialog latest() {
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertTrue(String.valueOf(d), d instanceof AlertDialog);
        assertTrue(d.isShowing());
        return (AlertDialog) d;
    }

    private static String title(AlertDialog d) {
        TextView t = d.findViewById(androidx.appcompat.R.id.alertTitle);
        return t == null || !t.isShown() ? null : t.getText().toString();
    }

    private static String message(AlertDialog d) {
        TextView t = d.findViewById(android.R.id.message);
        return t == null || !t.isShown() ? null : t.getText().toString();
    }

    private static String button(AlertDialog d, int which) {
        return d.getButton(which).getText().toString();
    }

    private static void click(AlertDialog d, int which) {
        d.getButton(which).performClick();
        idle();
    }

    private static <T extends View> List<T> views(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) out.add(type.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) views(((ViewGroup) v).getChildAt(i), type, out);
        return out;
    }

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    @Test public void thePermissionDialogsKeepTheirTextsAndActions() throws Exception {
        ActivityController<CameraActivity> controller = Robolectric.buildActivity(CameraActivity.class);
        CameraActivity activity = controller.get();
        PermissionPlan plan = mock(PermissionPlan.class);
        when(plan.groups(any())).thenReturn(Collections.singletonList(PermissionPlan.Group.CAMERA));
        Field field = CameraActivity.class.getDeclaredField("permissionPlan");
        field.setAccessible(true);
        field.set(activity, plan);
        Method redirect = CameraActivity.class.getDeclaredMethod("showSettingsRedirectDialog", String[].class);
        redirect.setAccessible(true);

        redirect.invoke(activity, (Object) new String[]{Manifest.permission.CAMERA});
        idle();
        AlertDialog d = latest();
        assertEquals(activity.getString(R.string.perm_settings_title), title(d));
        assertEquals(activity.getString(R.string.perm_settings_message, activity.getString(R.string.perm_group_camera)), message(d));
        assertEquals(activity.getString(R.string.perm_open_settings), button(d, DialogInterface.BUTTON_POSITIVE));
        assertEquals(activity.getString(R.string.cancel), button(d, DialogInterface.BUTTON_NEGATIVE));
        assertFalse("not cancelable", Shadows.shadowOf(d).isCancelable());
        click(d, DialogInterface.BUTTON_POSITIVE);
        Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(settings);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.getAction());
        assertEquals("package:" + activity.getPackageName(), String.valueOf(settings.getData()));
        assertFalse(activity.isFinishing());

        redirect.invoke(activity, (Object) new String[]{Manifest.permission.CAMERA});
        idle();
        click(latest(), DialogInterface.BUTTON_NEGATIVE);
        assertTrue("«Cancel» closes the camera", activity.isFinishing());
    }

    @Test public void theDcimDialogRetriesOrCloses() throws Exception {
        ActivityController<CameraActivity> controller = Robolectric.buildActivity(CameraActivity.class);
        CameraActivity activity = controller.get();
        PermissionPlan plan = mock(PermissionPlan.class);
        when(plan.next(any(), any(), anyBoolean())).thenReturn(PermissionPlan.Step.DCIM_FAILED);
        Field field = CameraActivity.class.getDeclaredField("permissionPlan");
        field.setAccessible(true);
        field.set(activity, plan);

        call(activity, "showDcimFailedDialog");
        idle();
        AlertDialog d = latest();
        assertEquals(activity.getString(R.string.perm_rationale_dcim_title), title(d));
        assertEquals(activity.getString(R.string.perm_dcim_failed), message(d));
        assertEquals(activity.getString(R.string.perm_retry), button(d, DialogInterface.BUTTON_POSITIVE));
        assertEquals(activity.getString(R.string.cancel), button(d, DialogInterface.BUTTON_NEGATIVE));
        assertFalse("not cancelable", Shadows.shadowOf(d).isCancelable());
        click(d, DialogInterface.BUTTON_POSITIVE);
        verify(plan).restart();
        AlertDialog again = latest();
        assertNotSame("the flow ran again (and failed again here)", d, again);
        click(again, DialogInterface.BUTTON_NEGATIVE);
        assertTrue(activity.isFinishing());
    }

    @Test public void theCameraErrorClosesTheActivity() {
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_Scamera_BaseApp);
        FragmentActivity activity = controller.setup().get();
        CameraFragment.ErrorDialog.newInstance("The camera failed to open").show(activity.getSupportFragmentManager(), "dialog");
        activity.getSupportFragmentManager().executePendingTransactions();
        idle();
        AlertDialog d = latest();
        assertEquals("The camera failed to open", message(d));
        assertEquals(activity.getString(android.R.string.ok), button(d, DialogInterface.BUTTON_POSITIVE));
        assertFalse(d.getButton(DialogInterface.BUTTON_NEGATIVE).isShown());
        click(d, DialogInterface.BUTTON_POSITIVE);
        assertTrue(activity.isFinishing());
        controller.close();
    }

    @Test public void anOffSizeExplainsItselfAndTriesAgainOnlyOnAsk() {
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_Scamera_BaseApp);
        FragmentActivity activity = controller.setup().get();
        CameraFragment fragment = mock(CameraFragment.class);
        when(fragment.getContext()).thenReturn(activity);
        try (MockedStatic<FullResTrials> trials = mockStatic(FullResTrials.class, CALLS_REAL_METHODS)) {
            trials.when(() -> FullResTrials.status("3")).thenReturn("Rows 6 and 19 failed");
            trials.when(() -> FullResTrials.retry(anyString())).thenAnswer(inv -> null);
            trials.clearInvocations();
            PhotoSize.availableForTest(false, "3");
            PhotoSize.explain(fragment, PhotoSize.FULL);
            idle();
            AlertDialog d = latest();
            assertEquals("50 MP is off", title(d));
            assertEquals("Rows 6 and 19 failed\n\n“Try again” forgets these trials: this camera's modes are tried again from the first.", message(d));
            assertEquals("Close", button(d, DialogInterface.BUTTON_NEGATIVE));
            assertEquals("Try again", button(d, DialogInterface.BUTTON_POSITIVE));
            click(d, DialogInterface.BUTTON_NEGATIVE);
            trials.verify(() -> FullResTrials.retry(anyString()), never());
            verify(fragment, never()).refreshTopBar();

            PhotoSize.explain(fragment, PhotoSize.FULL);
            idle();
            click(latest(), DialogInterface.BUTTON_POSITIVE);
            trials.verify(() -> FullResTrials.retry("3"));
            verify(fragment).showSnackBar("50 MP: not on this lens");
            verify(fragment).refreshTopBar();
        } finally {
            PhotoSize.availableForTest(false, "");
            controller.close();
        }
    }

    @Test public void theLensEditorSavesOnlyOnOk() throws Exception {
        try (ActivityController<LensDiscoveryActivity> controller = Robolectric.buildActivity(LensDiscoveryActivity.class)) {
            LensDiscoveryActivity activity = controller.setup().get();
            Class<?> type = Class.forName(LensDiscoveryActivity.class.getName() + "$LensInfo");
            Constructor<?> make = type.getDeclaredConstructor();
            make.setAccessible(true);
            Object info = make.newInstance();
            Field id = type.getDeclaredField("f381id");
            id.setAccessible(true);
            id.set(info, "5");
            Method show = LensDiscoveryActivity.class.getDeclaredMethod("showLensEditDialog", type, int.class);
            show.setAccessible(true);

            show.invoke(activity, info, 2);
            idle();
            AlertDialog d = latest();
            assertEquals(activity.getString(R.string.lens_edit_title), title(d));
            List<EditText> fields = views(d.getWindow().getDecorView(), EditText.class, new ArrayList<>());
            assertEquals(2, fields.size());
            assertEquals(activity.getString(R.string.lens_edit_name_hint), fields.get(0).getHint().toString());
            assertEquals(activity.getString(R.string.lens_edit_order_hint), fields.get(1).getHint().toString());
            assertEquals("2", fields.get(1).getText().toString());
            assertEquals(activity.getString(android.R.string.cancel), button(d, DialogInterface.BUTTON_NEGATIVE));
            assertEquals(activity.getString(android.R.string.ok), button(d, DialogInterface.BUTTON_POSITIVE));
            fields.get(0).setText("Tele");
            click(d, DialogInterface.BUTTON_NEGATIVE);
            assertEquals("none", manager.getString("default_scope", "lens_name_5", "none"));

            show.invoke(activity, info, 2);
            idle();
            d = latest();
            fields = views(d.getWindow().getDecorView(), EditText.class, new ArrayList<>());
            fields.get(0).setText(" Tele ");
            fields.get(1).setText("4");
            click(d, DialogInterface.BUTTON_POSITIVE);
            assertEquals("Tele", manager.getString("default_scope", "lens_name_5", "none"));
            assertEquals("4", manager.getString("default_scope", "lens_order_5", "none"));
            assertEquals(activity.getString(R.string.lens_settings_saved), ShadowToast.getTextOfLatestToast());
        }
    }
}
