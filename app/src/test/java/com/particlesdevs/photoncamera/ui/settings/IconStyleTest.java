package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * P136: the icons of the settings, the shade and the viewfinder in one style (filled glyphs like the launcher icon: a light
 * body, cut-out inner parts, details in the accent). Renders contact sheets to build/reports/icons (the settings rows as
 * drawn, untinted with the coral accent theme; the shade / top bar tinted as their views tint them).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconStyleTest {
    static List<Field> drawables(String... prefixes) {
        List<Field> out = new ArrayList<>();
        for (Field f : R.drawable.class.getFields())
            for (String p : prefixes) if (f.getName().startsWith(p)) { out.add(f); break; }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** One sheet: each icon at 48 dp on the settings card colour; tint = null draws the icon as it is. */
    static File sheet(String name, List<Field> icons, Integer tint) throws Exception {
        ContextThemeWrapper c = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        c.getTheme().applyStyle(R.style.RedTheme, true);   // the coral accent («Коралловый»)
        int cell = 112, cols = 8, rows = (icons.size() + cols - 1) / cols;
        Bitmap bmp = Bitmap.createBitmap(cols * cell, Math.max(1, rows) * cell, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFF1B2023);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFF8A93A0);
        text.setTextSize(11);
        for (int i = 0; i < icons.size(); i++) {
            int x = (i % cols) * cell, y = (i / cols) * cell;
            Drawable d = c.getDrawable(icons.get(i).getInt(null)).mutate();
            if (tint != null) d.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
            d.setBounds(x + 16, y + 8, x + 16 + 80, y + 8 + 80);
            d.draw(canvas);
            String n = icons.get(i).getName().replaceFirst("^(settings_ic_|ic_sheet_|ic_shade_|topbar_ic_|ic_)", "");
            canvas.drawText(n, x + 6, y + cell - 8, text);
        }
        File dir = new File("build/reports/icons");
        dir.mkdirs();
        File out = new File(dir, name + ".png");
        try (FileOutputStream f = new FileOutputStream(out)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, f); }
        return out;
    }

    /**
     * The settings rows draw the generated glyphs as they are (light body, accent details from the theme): no tint, but the
     * reset rows in the warning colour; every row icon of the settings XML is one of the generated settings_ic_*.
     */
    @Test public void settingsRowsDrawTheGlyphsAsTheyAre() throws Exception {
        android.content.Context c = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        c.getTheme().applyStyle(R.style.RedTheme, true);
        androidx.preference.PreferenceManager pm = new androidx.preference.PreferenceManager(c);
        for (String key : new String[]{"pref_scam_hybrid_colour_warmth", "pref_reset_preferences_key"}) {
            androidx.preference.Preference p = new androidx.preference.Preference(c);
            p.setKey(key);
            p.setIcon(R.drawable.settings_ic_gear);
            p.setLayoutResource(R.layout.preference_card);
            android.widget.FrameLayout parent = new android.widget.FrameLayout(c);
            android.view.View row = android.view.LayoutInflater.from(c).inflate(R.layout.preference_card, parent, false);
            androidx.preference.PreferenceViewHolder holder = androidx.preference.PreferenceViewHolder.createInstanceForTests(row);
            p.onBindViewHolder(holder);
            android.widget.ImageView icon = (android.widget.ImageView) holder.findViewById(android.R.id.icon);
            icon.setColorFilter(0xFF00FF00); // a recycled row that was tinted before
            SettingsStyle.bind(holder, p);
            if (key.startsWith("pref_reset")) org.junit.Assert.assertNotNull(key, icon.getColorFilter());
            else org.junit.Assert.assertNull(key + " is tinted", icon.getColorFilter());
        }
        String xml = new String(java.nio.file.Files.readAllBytes(new File("src/main/res/xml/preferences.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("android:icon=\"@drawable/([a-z0-9_]+)\"").matcher(xml);
        int n = 0;
        while (m.find()) { n++; assertTrue(m.group(1) + " is not a generated row glyph", m.group(1).startsWith("settings_ic_")); }
        assertTrue(n > 50);
        // the glyphs load in the accent theme (settings) and in the application context (the shade's catalog)
        for (Field f : drawables("settings_ic_", "ic_shade_", "ic_sheet_", "topbar_ic_", "shortcut_")) {
            assertTrue(f.getName(), c.getDrawable(f.getInt(null)) != null);
            assertTrue(f.getName(), RuntimeEnvironment.getApplication().getDrawable(f.getInt(null)) != null);
        }
    }

    @Test public void renderSheets() throws Exception {
        assertTrue(sheet("settings", drawables("settings_ic_"), null).length() > 0);
        assertTrue(sheet("settings_tinted", drawables("settings_ic_"), Color.WHITE).length() > 0);
        assertTrue(sheet("shade", drawables("ic_sheet_", "ic_shade_", "ic_flash_", "ic_torch", "ic_timer", "ic_grid_"), Color.WHITE).length() > 0);
        assertTrue(sheet("topbar", drawables("topbar_ic_"), Color.WHITE).length() > 0);
    }
}
