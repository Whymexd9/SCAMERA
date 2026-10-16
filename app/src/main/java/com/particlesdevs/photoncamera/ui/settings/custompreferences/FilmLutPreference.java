package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.ScameraFolder;

/**
 * P134 «Плёночный LUT»: the .cube file of Download/SCAMERA/LUT the photo's look goes through (ArkCombine, FILM_LUT), or none.
 * A tap opens the list sheet: «Нет», our five film looks (P170, FilmLooks), the folder's files, «Импортировать .cube…» (the
 * picker; the file is copied into the folder) and, until the folder is granted, «Открыть папку» (files dropped there by
 * another app are listed then).
 */
public class FilmLutPreference extends Preference {
    public interface Files {
        String[] list(Context context);
    }

    @Nullable private Runnable importer, folderOpener;
    private Files files = ScameraFolder.LUTS::list;

    public FilmLutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
    }

    public FilmLutPreference(Context context) {
        this(context, null);
    }

    public void setImporter(@Nullable Runnable importer) { this.importer = importer; }
    public void setFolderOpener(@Nullable Runnable folderOpener) { this.folderOpener = folderOpener; }
    /** Tests: the folder's files. */
    public void setFiles(Files files) { this.files = files; }

    public String chosen() {
        return getPersistedString("");
    }

    /** Choose a file ("" = none); the summary follows. */
    public void choose(String name) {
        persistString(name == null ? "" : name);
        FilmLut.forget();
        notifyChanged();
    }

    @Override
    public CharSequence getSummary() {
        String name = chosen();
        if (name.isEmpty()) return Lang.t(getContext(), "Нет · свои .cube в Download/SCAMERA/LUT", "None · your .cube files in Download/SCAMERA/LUT");
        String look = FilmLooks.label(getContext(), name);
        return look != null ? look : name;
    }

    /** P170: our looks first, then the folder's files. */
    public static String[] choices(String[] files) {
        String[] looks = FilmLooks.values();
        String[] out = new String[looks.length + files.length];
        System.arraycopy(looks, 0, out, 0, looks.length);
        System.arraycopy(files, 0, out, looks.length, files.length);
        return out;
    }

    @Override
    protected void onClick() {
        Context c = getContext();
        String[] names = choices(files.list(c));
        boolean grant = folderOpener != null && ScameraFolder.LUTS.grantedTree(c) == null;
        int extra = 1 + (importer != null ? 1 : 0) + (grant ? 1 : 0);
        CharSequence[] labels = new CharSequence[names.length + extra], tags = new CharSequence[names.length + extra];
        labels[0] = Lang.t(c, "Нет", "None");
        tags[0] = "lut_none";
        int selected = chosen().isEmpty() ? 0 : -1;
        for (int i = 0; i < names.length; i++) {
            String look = FilmLooks.label(c, names[i]);
            labels[1 + i] = look != null ? look : names[i];
            tags[1 + i] = "lut_" + names[i];
            if (names[i].equals(chosen())) selected = 1 + i;
        }
        int k = 1 + names.length;
        final int importAt = importer != null ? k++ : -1;
        final int folderAt = grant ? k : -1;
        if (importAt >= 0) { labels[importAt] = Lang.t(c, "Импортировать .cube…", "Import .cube…"); tags[importAt] = "lut_import"; }
        if (folderAt >= 0) { labels[folderAt] = Lang.t(c, "Открыть папку Download/SCAMERA/LUT", "Open Download/SCAMERA/LUT"); tags[folderAt] = "lut_open_folder"; }
        SettingsStyle.optionSheet(c, getTitle(), labels, tags, selected, SettingsStyle.accent(c), i -> {
            if (i == 0) choose("");
            else if (i == importAt) importer.run();
            else if (i == folderAt) folderOpener.run();
            else choose(names[i - 1]);
        }).show();
    }
}
