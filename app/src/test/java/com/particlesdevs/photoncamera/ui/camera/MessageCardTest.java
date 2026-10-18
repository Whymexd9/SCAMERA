package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.settings.MessageHistorySheet;
import com.particlesdevs.photoncamera.util.MessageLog;
import com.particlesdevs.photoncamera.util.UiMessages;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * P214f (speed-ux.md C3): one message channel on the camera screen. CaptureController's 2 s system toasts, the 1.5 s card
 * toast and the snackbars are one card now: an information message for 1.5 s + 50 ms per character (at most 7 s), an error
 * until it is tapped, a repeat as one message with a counter, the last 20 kept and shown in «Последние сообщения».
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h800dp-xhdpi")
public class MessageCardTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private TextView view;
    private MessageCard card;
    private int historyOpened;

    /** The card's fades off: under Robolectric a running animator's frames move the clock past an idle's end. */
    private static void animatorScale(float scale) {
        try {
            android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, scale);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Before
    public void setUp() {
        animatorScale(0f);
        UiMessages.resetForTest(null);
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
        FrameLayout root = new FrameLayout(activity);
        view = new TextView(activity);
        view.setVisibility(View.GONE);
        view.setTextColor(ContextCompat.getColor(activity, R.color.settings_muted));
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        card = new MessageCard(view, () -> historyOpened++);
        UiMessages.attach(card, null);
        idle(0);
    }

    @After
    public void tearDown() {
        card.release();
        UiMessages.resetForTest(null);
        controller.pause().stop().destroy();
        animatorScale(1f);
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static String chars(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append((char) ('a' + i % 26));
        return s.toString();
    }

    @Test public void anInformationMessageShowsForItsLength() {
        UiMessages.info("HDR: on"); // 7 characters: 1.545 s (SIMPLE_UI: 1.3 s + 35 ms per character)
        idle(10);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals("HDR: on", view.getText().toString());
        idle(1400);
        assertEquals(View.VISIBLE, view.getVisibility());
        idle(300);
        assertNull(card.showing());
        idle(400);
        assertEquals(View.GONE, view.getVisibility());

        String longText = chars(120); // a long text: 5.5 s
        UiMessages.info(longText);
        idle(5300);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(longText, card.showing().text);
        idle(400);
        assertNull(card.showing());
    }

    @Test public void anErrorStaysUntilTapped() {
        UiMessages.error("FULLRES: mode 11 does not work on this camera; the next time mode 31 is tried");
        idle(60_000);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertTrue(card.showing().error);
        assertTrue(view.getText().toString().contains("FULLRES: mode 11"));
        assertFalse("SIMPLE_UI: one line", view.getText().toString().contains("\n"));
        assertTrue("the «tap to close» hint for TalkBack", view.getContentDescription().toString().contains("закрыть")
                || view.getContentDescription().toString().contains("close"));
        assertEquals(ContextCompat.getColor(activity, R.color.settings_warn), view.getCurrentTextColor());
        assertNotNull(UiMessages.unreadError());

        // an information message over it: shown for its time, then the error is back
        UiMessages.info("Previous shot still processing");
        idle(10);
        assertFalse(card.showing().error);
        assertEquals(ContextCompat.getColor(activity, R.color.settings_muted), view.getCurrentTextColor());
        idle(MessageLog.durationMs("Previous shot still processing") + 50);
        assertTrue(card.showing().error);
        assertEquals(View.VISIBLE, view.getVisibility());

        view.performClick();
        idle(400);
        assertNull(card.showing());
        assertNull(UiMessages.unreadError());
        assertEquals(View.GONE, view.getVisibility());
        // read: an information message later does not bring it back
        UiMessages.info("ISO: auto");
        idle(MessageLog.durationMs("ISO: auto") + 400);
        assertEquals(View.GONE, view.getVisibility());
    }

    @Test public void repeatsAreMergedWithACounter() {
        for (int i = 0; i < 3; i++) UiMessages.error("Camera failure (code 4), restarting");
        idle(10);
        assertEquals(3, card.showing().count);
        assertTrue(view.getText().toString().startsWith("Camera failure (code 4), restarting  ×3"));
        List<MessageLog.Entry> history = UiMessages.history(null);
        assertEquals(1, history.size());
        assertEquals(3, history.get(0).count);
    }

    @Test public void aPendingErrorShowsOnTheNextCameraScreen() {
        card.release(); // the camera screen goes
        UiMessages.error("The camera is not responding");
        idle(10);
        assertNull(card.showing());
        TextView next = new TextView(activity);
        MessageCard nextCard = new MessageCard(next, null);
        UiMessages.attach(nextCard, null);
        idle(10);
        assertEquals("The camera is not responding", nextCard.showing().text);
        assertEquals(View.VISIBLE, next.getVisibility());
        nextCard.release();
    }

    @Test public void theHistoryKeepsTheLastTwentyAndALongPressOpensIt() {
        for (int i = 0; i < 25; i++) UiMessages.info("message " + i);
        idle(10);
        List<MessageLog.Entry> history = UiMessages.history(null);
        assertEquals(20, history.size());
        assertEquals("message 24", history.get(0).text);
        assertTrue(view.performLongClick());
        assertEquals(1, historyOpened);
    }

    @Test public void theHistorySheetListsTheMessagesAndClears() {
        UiMessages.info("HDR: on");
        UiMessages.error("Sensor mode refused; using a plain module");
        idle(10);
        BottomSheetDialog sheet = MessageHistorySheet.show(activity);
        idle(10);
        List<String> texts = new ArrayList<>();
        collect(sheet.getWindow().getDecorView(), texts);
        int hdr = texts.indexOf("HDR: on"), refused = texts.indexOf("Sensor mode refused; using a plain module");
        assertTrue(texts.toString(), hdr > 0 && refused > 0 && refused < hdr); // newest first
        View clear = sheet.getWindow().getDecorView().findViewWithTag("messages_clear");
        assertNotNull(clear);
        assertNotNull(sheet.getWindow().getDecorView().findViewWithTag("messages_copy"));
        clear.performClick();
        idle(10);
        assertTrue(UiMessages.history(null).isEmpty());
        assertNull(UiMessages.unreadError());
        BottomSheetDialog empty = MessageHistorySheet.show(activity);
        idle(10);
        assertNull(empty.getWindow().getDecorView().findViewWithTag("messages_clear"));
        empty.dismiss();
    }

    @Test public void theHistorySurvivesARestartThroughItsFile() throws Exception {
        File file = new File(tmp.getRoot(), "scam_messages.txt");
        UiMessages.resetForTest(file);
        UiMessages.attach(card, null);
        UiMessages.error("Camera 4 does not start (error 4), back to camera 2");
        UiMessages.info("HDR: on");
        UiMessages.flushForTest();
        String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(saved, saved.contains("Camera 4 does not start"));
        UiMessages.resetForTest(file); // a new process
        UiMessages.info("after the restart");
        List<MessageLog.Entry> history = UiMessages.history(null);
        assertEquals(3, history.size());
        assertEquals("after the restart", history.get(0).text);
        assertTrue(history.get(2).error);
        assertNull(UiMessages.unreadError()); // a pending error is of this run only
        UiMessages.flushForTest();
    }

    /** Every message of the camera screen goes through the channel: no system toast or snackbar of its own left. */
    @Test public void theCameraScreenHasNoOtherMessageMechanism() throws Exception {
        for (String path : new String[]{"src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java",
                "src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraFragment.java"}) {
            String src = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
            assertFalse(path, src.contains("Toast.makeText"));
            assertFalse(path, src.contains("Snackbar.make"));
            assertFalse(path, src.contains("postDelayed(hideCardToast"));
        }
        String capture = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(capture.contains("UiMessages.info(msg)"));
        assertTrue(capture.contains("UiMessages.error(msg)"));
    }

    private static void collect(View v, List<String> out) {
        if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }
}
