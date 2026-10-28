package com.particlesdevs.photoncamera.gallery.editor;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.util.Log;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * P351: what outlives a configuration change of the editor: the decoded source, the undo history and the running export
 * (its progress and result). The state itself also goes into the saved instance state as JSON (process death).
 */
public final class EditorViewModel extends AndroidViewModel {
    private static final String TAG = "EditorViewModel";
    /** P367: how long the export waits for the zoom's reader to close (a RAW open in progress finishes first). */
    static final long READY_WAIT_S = 30;

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
    private volatile boolean loading;
    /**
     * The decoded source this ViewModel owns, set before it is posted to {@link #source}; {@code cleared} after onCleared;
     * {@code exporting} while an export reads the source. Guarded by {@link #lock}: the source is closed exactly once - by
     * onCleared, by the export that outlived it, or by the decode that finished after it (the full-size DNG copy in the cache
     * and LibRaw's native image stayed until the process died).
     */
    private final Object lock = new Object();
    @Nullable private EditSource opened;
    private boolean cleared, exporting;

    public EditorViewModel(@NonNull Application app) {
        super(app);
    }

    void load(Uri uri, String name, @Nullable String mime, @Nullable String path) {
        synchronized (lock) {
            // opened is set before loading is cleared: a recreated editor's load while the first source is still on its way to
            // the main thread (source still null) decoded a second copy, and the first was never closed
            if (loading || opened != null || cleared) return;
            loading = true;
        }
        Thread t = new Thread(() -> {
            try {
                EditSource s = EditSource.open(getApplication(), uri, name, mime);
                s.absolutePath = path;
                boolean drop;
                synchronized (lock) {
                    drop = cleared;
                    if (!drop) opened = s;
                }
                if (drop) s.close(); // the editor was left while decoding
                else source.postValue(s);
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
        synchronized (lock) {
            return exporting;
        }
    }

    /**
     * The export on its own thread; P367: after {@code ready} (the editor zoom's reader closing, EditorView.closeFullRes) is
     * done - its memory is the export's - or {@link #READY_WAIT_S} passed.
     */
    void export(EditState s, @Nullable Future<?> ready) {
        EditSource src = source.getValue();
        synchronized (lock) {
            if (src == null || exporting || cleared) return;
            exporting = true;
        }
        exportProgress.setValue(0f);
        final EditState snapshot = s.copy();
        Thread t = new Thread(() -> {
            try {
                if (ready != null) {
                    try {
                        ready.get(READY_WAIT_S, TimeUnit.SECONDS);
                    } catch (TimeoutException | ExecutionException e) {
                        Log.w(TAG, "the zoom's reader did not close in time: " + e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                EditExporter.Result r = EditExporter.export(getApplication(), src, snapshot, exportProgress::postValue);
                exported.postValue(r);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "export: " + e);
                exportError.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                boolean close;
                synchronized (lock) {
                    exporting = false;
                    close = cleared;
                }
                if (close) src.close(); // the editor was cleared while this export read the source
            }
        }, "EditorExport");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    @Override
    protected void onCleared() {
        EditSource s;
        synchronized (lock) {
            cleared = true;
            s = exporting ? null : opened; // a running export closes it when it ends
        }
        if (s != null) s.close();
        super.onCleared();
    }
}
