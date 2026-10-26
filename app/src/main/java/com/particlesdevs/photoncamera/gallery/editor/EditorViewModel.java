package com.particlesdevs.photoncamera.gallery.editor;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.util.Log;

/**
 * P351: what outlives a configuration change of the editor: the decoded source, the undo history and the running export
 * (its progress and result). The state itself also goes into the saved instance state as JSON (process death).
 */
public final class EditorViewModel extends AndroidViewModel {
    private static final String TAG = "EditorViewModel";

    /** null while decoding; the error text when it failed. */
    final MutableLiveData<EditSource> source = new MutableLiveData<>();
    final MutableLiveData<String> error = new MutableLiveData<>();
    final MutableLiveData<Float> exportProgress = new MutableLiveData<>();
    final MutableLiveData<EditExporter.Result> exported = new MutableLiveData<>();
    final MutableLiveData<String> exportError = new MutableLiveData<>();
    @Nullable EditHistory history;
    EditState state = new EditState();
    /** The tone before a style was picked (the style's 0 %). */
    @Nullable EditState styleBase;
    private boolean loading, exporting;

    public EditorViewModel(@NonNull Application app) {
        super(app);
    }

    void load(Uri uri, String name, @Nullable String mime, @Nullable String path) {
        if (loading || source.getValue() != null) return;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                EditSource s = EditSource.open(getApplication(), uri, name, mime);
                s.absolutePath = path;
                source.postValue(s);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "open " + name + ": " + e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                loading = false;
            }
        }, "EditorDecode");
        t.start();
    }

    boolean exporting() {
        return exporting;
    }

    void export(EditState s) {
        EditSource src = source.getValue();
        if (src == null || exporting) return;
        exporting = true;
        exportProgress.setValue(0f);
        final EditState snapshot = s.copy();
        Thread t = new Thread(() -> {
            try {
                EditExporter.Result r = EditExporter.export(getApplication(), src, snapshot, exportProgress::postValue);
                exported.postValue(r);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "export: " + e);
                exportError.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                exporting = false;
            }
        }, "EditorExport");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    @Override
    protected void onCleared() {
        EditSource s = source.getValue();
        if (s != null && !exporting) s.close();
        super.onCleared();
    }
}
