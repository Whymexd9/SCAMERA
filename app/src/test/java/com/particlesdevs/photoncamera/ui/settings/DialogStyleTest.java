package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Owner, 2026-10-28: «исправь стиль всех таких меню которые выбиваются из нашего стиля приложения» - the reset dialog was the
 * framework's grey card with ALL-CAPS buttons. Every alert dialog is now the app's (DialogStyle, AlertDialogTheme): the
 * settings card with a faint outline and 24dp corners, the white 20sp title, muted body text, sentence-case buttons (the
 * confirm an accent pill), the settings' radio in lists; the preference library's dialogs get the same theme.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class DialogStyleTest {
    private static final int LAVENDER = 0xFFC7B5FF, BLUE = 0xFF90C7FF;
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;

    @Before public void setUp() {
        accent("lavender");
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_Scamera_SettingsActivity);
        activity = controller.setup().get();
    }

    @After public void tearDown() {
        controller.close();
    }

    private static void accent(String value) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()).edit()
                .putString(AccentPalette.KEY, value).commit();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static int resource(Context c, int attr) {
        TypedValue v = new TypedValue();
        assertTrue("attribute " + attr, c.getTheme().resolveAttribute(attr, v, true));
        return v.resourceId;
    }

    private static float dimension(Context c, int attr) {
        TypedValue v = new TypedValue();
        assertTrue("attribute " + attr, c.getTheme().resolveAttribute(attr, v, true));
        return v.getDimension(c.getResources().getDisplayMetrics());
    }

    private static boolean allCaps(Context c, int style) {
        TypedArray a = c.obtainStyledAttributes(style, new int[]{android.R.attr.textAllCaps});
        try {
            return a.getBoolean(0, true);
        } finally {
            a.recycle();
        }
    }

    private float dp(float v) {
        return v * activity.getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, activity.getResources().getDisplayMetrics());
    }

    /** The card behind a shown dialog: the window background (inset) around the surface drawable. */
    private static GradientDrawable card(AlertDialog d) {
        Drawable bg = d.getWindow().getDecorView().getBackground();
        assertTrue(String.valueOf(bg), bg instanceof InsetDrawable);
        Drawable inner = ((InsetDrawable) bg).getDrawable();
        assertTrue(String.valueOf(inner), inner instanceof GradientDrawable);
        return (GradientDrawable) inner;
    }

    @Test public void theDialogThemeHas24dpCornersAndNoAllCapsButtons() {
        Context base = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_BaseApp);
        // every androidx / Material dialog takes the app's theme; the framework's only a fallback in the same look
        assertEquals(R.style.AlertDialogTheme, resource(base, androidx.appcompat.R.attr.alertDialogTheme));
        assertEquals(R.style.AlertDialogTheme, resource(base, com.google.android.material.R.attr.materialAlertDialogTheme));
        assertEquals(R.style.AlertDialogTheme_Framework, resource(base, android.R.attr.alertDialogTheme));

        Context dialog = new ContextThemeWrapper(base, R.style.AlertDialogTheme);
        assertEquals(dp(24), dimension(dialog, androidx.appcompat.R.attr.dialogCornerRadius), .5f);
        assertEquals(dp(24), dimension(dialog, android.R.attr.dialogCornerRadius), .5f);
        for (int attr : new int[]{androidx.appcompat.R.attr.buttonBarPositiveButtonStyle, androidx.appcompat.R.attr.buttonBarNegativeButtonStyle,
                androidx.appcompat.R.attr.buttonBarNeutralButtonStyle, androidx.appcompat.R.attr.buttonBarButtonStyle})
            assertFalse("no ALL-CAPS buttons", allCaps(dialog, resource(dialog, attr)));
        assertFalse("no ALL-CAPS title", allCaps(dialog, resource(dialog, com.google.android.material.R.attr.materialAlertDialogTitleTextStyle)));

        Context framework = new ContextThemeWrapper(base, R.style.AlertDialogTheme_Framework);
        assertEquals(dp(24), dimension(framework, android.R.attr.dialogCornerRadius), .5f);
        assertFalse(allCaps(framework, resource(framework, android.R.attr.buttonBarButtonStyle)));

        // the card: the settings card colour with a faint outline, 24dp corners, the same in both window backgrounds
        GradientDrawable surface = (GradientDrawable) dialog.getDrawable(R.drawable.dialog_surface);
        assertEquals(dp(24), surface.getCornerRadius(), .5f);
        assertEquals(SettingsStyle.CARD, surface.getColor().getDefaultColor());
        for (Context c : new Context[]{dialog, framework}) {
            Drawable window = c.getDrawable(resource(c, android.R.attr.windowBackground));
            assertTrue(String.valueOf(window), window instanceof InsetDrawable);
            assertEquals(dp(24), ((GradientDrawable) ((InsetDrawable) window).getDrawable()).getCornerRadius(), .5f);
        }
    }

    @Test public void aConfirmDialogHasTheAppTitleBodyAndButtons() {
        final List<String> clicks = new ArrayList<>();
        AlertDialog d = DialogStyle.builder(activity).setTitle("Reset this page?").setMessage("Every setting goes back to its default.")
                .setNeutralButton("Auto", (x, w) -> clicks.add("auto"))
                .setNegativeButton("Cancel", (x, w) -> clicks.add("cancel"))
                .setPositiveButton("Reset", (x, w) -> clicks.add("reset")).show();
        idle();
        assertEquals(dp(24), card(d).getCornerRadius(), .5f);
        assertEquals(SettingsStyle.CARD, card(d).getColor().getDefaultColor());
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        assertEquals("Reset this page?", title.getText().toString());
        assertEquals(sp(20), title.getTextSize(), .5f);
        assertEquals(SettingsStyle.TEXT, title.getCurrentTextColor());
        assertFalse(title.isAllCaps());
        TextView body = d.findViewById(android.R.id.message);
        assertEquals("Every setting goes back to its default.", body.getText().toString());
        assertEquals(sp(15), body.getTextSize(), .5f);
        assertEquals(SettingsStyle.MUTED, body.getCurrentTextColor());

        MaterialButton reset = (MaterialButton) d.getButton(DialogInterface.BUTTON_POSITIVE);
        MaterialButton cancel = (MaterialButton) d.getButton(DialogInterface.BUTTON_NEGATIVE);
        MaterialButton auto = (MaterialButton) d.getButton(DialogInterface.BUTTON_NEUTRAL);
        for (MaterialButton b : new MaterialButton[]{reset, cancel, auto}) assertFalse(b.getText() + " in sentence case", b.isAllCaps());
        assertEquals("Reset", reset.getText().toString());
        assertEquals("the confirm: an accent pill", LAVENDER, reset.getBackgroundTintList().getDefaultColor());
        assertEquals(0xFF17141F, reset.getCurrentTextColor());
        assertEquals("a pill: half the height round", 22f, reset.getShapeAppearanceModel().getTopLeftCornerSize().getCornerSize(
                new android.graphics.RectF(0, 0, 120, 44)), .01f);
        assertEquals("«Cancel»: a quiet text button", SettingsStyle.TEXT, cancel.getCurrentTextColor());
        assertEquals(LAVENDER, auto.getCurrentTextColor());

        cancel.performClick();
        idle();
        assertFalse(d.isShowing());
        assertEquals(java.util.Arrays.asList("cancel"), clicks);
    }

    /** LensDiscoveryActivity does not apply the accent theme: its dialogs still take the user's accent. */
    @Test public void theAccentComesFromTheSettingNotTheActivity() {
        accent("blue");
        Context plain = new ContextThemeWrapper(activity, R.style.Theme_Scamera_SettingsActivity);
        AlertDialog d = DialogStyle.builder(plain).setMessage("m").setPositiveButton("OK", null).show();
        idle();
        assertEquals(BLUE, ((MaterialButton) d.getButton(DialogInterface.BUTTON_POSITIVE)).getBackgroundTintList().getDefaultColor());
    }

    @Test public void listsUseTheSettingsRowsAndRadio() {
        AlertDialog single = DialogStyle.builder(activity).setTitle("Lens mask")
                .setSingleChoiceItems(new String[]{"None", "round.png"}, 1, null).show();
        idle();
        ListView list = single.getListView();
        layout(single);
        CheckedTextView row = (CheckedTextView) list.getChildAt(1);
        assertTrue(row.isChecked());
        assertEquals(SettingsStyle.TEXT, row.getCurrentTextColor());
        assertEquals(sp(16), row.getTextSize(), .5f);
        assertNotNull("the radio", row.getCompoundDrawablesRelative()[0]);
        assertNull("no second check mark", row.getCheckMarkDrawable());
        single.dismiss();

        AlertDialog plain = DialogStyle.builder(activity).setTitle("Modules").setItems(new String[]{"1×", "3×"}, null).show();
        idle();
        layout(plain);
        TextView item = (TextView) plain.getListView().getChildAt(0);
        assertEquals("1×", item.getText().toString());
        assertEquals(SettingsStyle.TEXT, item.getCurrentTextColor());
        plain.dismiss();
    }

    private static void layout(AlertDialog d) {
        View decor = d.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(880, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }

    /** The preference library builds its dialogs with the plain androidx builder: the base theme gives them the same look. */
    @Test public void aPlainAndroidxDialogLooksTheSame() {
        // the settings activity's accent (BaseActivity: PreferenceKeys.setActivityTheme)
        activity.getTheme().applyStyle(com.particlesdevs.photoncamera.settings.PreferenceKeys.accentStyle("lavender"), true);
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("Attention!").setMessage("Reset?")
                .setPositiveButton("Yes", null).setNegativeButton("Cancel", null).show();
        idle();
        assertEquals(dp(24), card(d).getCornerRadius(), .5f);
        MaterialButton yes = (MaterialButton) d.getButton(DialogInterface.BUTTON_POSITIVE);
        assertFalse(yes.isAllCaps());
        assertEquals(LAVENDER, yes.getBackgroundTintList().getDefaultColor());
        assertEquals(SettingsStyle.TEXT, ((TextView) d.findViewById(androidx.appcompat.R.id.alertTitle)).getCurrentTextColor());
    }

    @Test public void fieldsAndBoxesInADialogAreTheSettingsOnes() {
        android.widget.EditText field = DialogStyle.field(new android.widget.EditText(activity));
        assertEquals(SettingsStyle.TEXT, field.getCurrentTextColor());
        assertEquals(SettingsStyle.MUTED, field.getCurrentHintTextColor());
        assertTrue(field.getBackground() instanceof GradientDrawable);
        android.widget.CheckBox box = DialogStyle.check(new android.widget.CheckBox(activity));
        assertEquals(LAVENDER, box.getButtonTintList().getColorForState(new int[]{android.R.attr.state_checked}, 0));
        assertEquals(SettingsStyle.MUTED, box.getButtonTintList().getDefaultColor());
        android.widget.LinearLayout body = DialogStyle.body(activity, field, box);
        assertEquals(2, body.getChildCount());
        assertEquals("aligned with the title", Math.round(dp(24)), body.getPaddingLeft());
    }

    /**
     * No dialog of the app is built on the framework's AlertDialog (restyled by OEM ROMs) or around DialogStyle: one look.
     */
    @Test public void everyAlertDialogOfTheAppIsBuiltByDialogStyle() throws IOException {
        File src = new File("src/main/java");
        assertTrue(src.getAbsolutePath(), src.isDirectory());
        Pattern framework = Pattern.compile("import\\s+android\\.app\\.(AlertDialog|ProgressDialog|DatePickerDialog|TimePickerDialog)\\s*;"
                + "|new\\s+android\\.app\\.(AlertDialog|ProgressDialog|DatePickerDialog|TimePickerDialog)\\b"
                + "|new\\s+(ProgressDialog|DatePickerDialog|TimePickerDialog)\\s*\\(");
        Pattern builder = Pattern.compile("new\\s+[\\w.]*(AlertDialog\\.Builder|MaterialAlertDialogBuilder)\\s*\\(");
        List<String> found = new ArrayList<>();
        int built = 0;
        try (Stream<java.nio.file.Path> files = Files.walk(src.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = framework.matcher(text);
                while (m.find()) found.add(p + ": " + m.group());
                if (!p.endsWith("DialogStyle.java")) {
                    m = builder.matcher(text);
                    while (m.find()) found.add(p + ": " + m.group());
                }
                Matcher uses = Pattern.compile("DialogStyle\\.builder\\(").matcher(text);
                while (uses.find()) built++;
            }
        }
        assertTrue(String.join("\n", found), found.isEmpty());
        assertTrue("the converted dialogs (" + built + ")", built >= 22);
    }
}
