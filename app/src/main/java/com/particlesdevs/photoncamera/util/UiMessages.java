package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P214f (speed-ux.md C3): the camera screen's one message channel. CaptureController.showToast / showError, the camera
 * screen's card (CameraFragment.showCardToast / showToast / showSnackBar, the shade, the manual panel) all post here; the
 * camera screen's card ({@code ui/camera/MessageCard}) is the {@link Sink} that shows them: an information message for
 * {@link MessageLog#durationMs} (1.5 s + 50 ms per character, at most 7 s), an error until it is tapped, a repeat as one
 * message with a counter. Without the card (the camera screen is gone: a shot still processing behind another screen) a
 * system toast stands in, and an error stays pending for the card of the next camera screen.
 * <p>
 * Every message is recorded in {@link MessageLog} (the last 20, «Последние сообщения» of Система → Диагностика and a long
 * press on the card) and written to the log ({@code SCAM_MESSAGE}), so a log from the owner shows what the screen said.
 */
public final class UiMessages {
    private UiMessages() {}

    private static final String TAG = "SCAM_MESSAGE";
    private static final String FILE = "scam_messages.txt";

    /** Shows a message; called on the main thread. */
    public interface Sink {
        void show(MessageLog.Entry entry);
    }

    private static final MessageLog LOG = new MessageLog();
    private static final Object FILE_LOCK = new Object();
    private static volatile Sink sink;
    /** The last error nobody tapped away yet (this run only; the history keeps it anyway). */
    private static volatile MessageLog.Entry unread;
    private static volatile File file;
    private static boolean loaded;
    private static ExecutorService writer;

    /** An information message. */
    public static void info(@Nullable CharSequence text) {
        post(text, false);
    }

    /** An error: the card keeps it until it is tapped. */
    public static void error(@Nullable CharSequence text) {
        post(text, true);
    }

    public static void post(@Nullable CharSequence text, boolean error) {
        final MessageLog.Entry entry = LOG.add(text, error, System.currentTimeMillis());
        if (entry == null) return;
        if (error) unread = entry;
        try {
            Log.i(TAG, (error ? "error" : "info") + (entry.count > 1 ? " x" + entry.count : "") + ": " + entry.text);
        } catch (RuntimeException ignored) {
            // the log writer is optional here (a unit test without it)
        }
        persist();
        mainHandler().post(() -> {
            Sink s = sink;
            if (s != null) s.show(entry);
            else fallback(entry);
        });
    }

    /**
     * The camera screen's card attaches itself ({@code context}: where the history file lives). A pending error of an
     * earlier screen is shown on it at once.
     */
    public static void attach(Sink card, @Nullable Context context) {
        sink = card;
        useFile(context);
        preload(); // the saved history is read early, off the main thread
        final MessageLog.Entry pending = unread;
        if (pending != null) mainHandler().post(() -> {
            if (sink == card && unread == pending) card.show(pending);
        });
    }

    /** Detaches {@code card} (only if it is still the attached one: a new screen may have attached already). */
    public static void detach(Sink card) {
        if (sink == card) sink = null;
    }

    /** The error the card shows until it is tapped (null: none). */
    @Nullable
    public static MessageLog.Entry unreadError() {
        return unread;
    }

    /** A tap on the card: the pending error is read. */
    public static void acknowledge() {
        unread = null;
    }

    /** The last {@link MessageLog#KEEP} messages, newest first (with the ones saved before an app restart). */
    public static List<MessageLog.Entry> history(@Nullable Context context) {
        useFile(context);
        ensureLoaded();
        return LOG.newestFirst();
    }

    /** «Очистить» of the history sheet. */
    public static void clearHistory(@Nullable Context context) {
        useFile(context);
        ensureLoaded();
        LOG.clear();
        unread = null;
        persist();
    }

    // ───── the history file (app-private files dir): read once, written after each message on one background thread

    private static void useFile(@Nullable Context context) {
        if (file != null) return;
        Context c = context != null && context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (c == null) c = ScameraApp.getAppContext();
        if (c == null) return;
        try {
            File dir = c.getFilesDir();
            if (dir != null) file = new File(dir, FILE);
        } catch (RuntimeException ignored) {
            // no files dir (a stubbed context): the history lives in memory only
        }
    }

    /** Puts the saved history (an earlier run's) before this run's messages, once. */
    private static void ensureLoaded() {
        synchronized (FILE_LOCK) {
            if (loaded) return;
            useFile(null);
            if (file == null) return;
            LOG.loadOlder(file);
            loaded = true;
        }
    }

    private static void preload() {
        useFile(null);
        if (file != null) writer().execute(UiMessages::ensureLoaded);
    }

    /** Writes the history; the saved one is read first, so a message before the read does not drop the earlier run's. */
    private static void persist() {
        useFile(null);
        if (file == null) return;
        writer().execute(() -> {
            synchronized (FILE_LOCK) {
                ensureLoaded();
                if (!loaded) return;
                try {
                    LOG.save(file);
                } catch (java.io.IOException | RuntimeException e) {
                    try {
                        Log.w(TAG, "history not saved: " + e);
                    } catch (RuntimeException ignored) {
                        // no log writer
                    }
                }
            }
        });
    }

    /** No card: a system toast (long for a message the card would show longer than 2.5 s, and for errors). */
    private static void fallback(MessageLog.Entry entry) {
        Context c = ScameraApp.getAppContext();
        if (c == null) return;
        try {
            boolean longer = entry.error || MessageLog.durationMs(entry.text) > 2500;
            Toast.makeText(c, entry.text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // no toast possible (process going down)
        }
    }

    /** A handler of the main looper (not cached: messages are rare, and a test run's main looper is not kept). */
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static synchronized ExecutorService writer() {
        if (writer == null) writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scam-messages");
            t.setDaemon(true);
            return t;
        });
        return writer;
    }

    /** Tests: waits until the history file is written. */
    @androidx.annotation.VisibleForTesting
    public static void flushForTest() throws Exception {
        writer().submit(() -> { }).get();
    }

    /** Tests: forget everything (history, pending error, card, file). */
    @androidx.annotation.VisibleForTesting
    public static void resetForTest(@Nullable File historyFile) {
        synchronized (FILE_LOCK) {
            LOG.clear();
            unread = null;
            sink = null;
            file = historyFile;
            loaded = false;
        }
    }
}
