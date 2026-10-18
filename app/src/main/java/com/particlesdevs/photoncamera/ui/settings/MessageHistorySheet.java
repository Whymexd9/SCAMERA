package com.particlesdevs.photoncamera.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.MessageLog;
import com.particlesdevs.photoncamera.util.UiMessages;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * P214f (speed-ux.md C3): «Последние сообщения» - the last 20 messages of the camera screen's message channel
 * ({@link UiMessages}), newest first, with the time, the repeat count and the error mark, to read at leisure and copy.
 * Opened from Система → Диагностика and by a long press on the camera screen's message card.
 */
public final class MessageHistorySheet {
    private MessageHistorySheet() {}

    public static BottomSheetDialog show(Context c) {
        List<MessageLog.Entry> entries = UiMessages.history(c);
        LinearLayout body = SettingsStyle.sheetBody(c, Lang.t(c, "Последние сообщения", "Recent messages"), null);
        if (entries.isEmpty()) {
            TextView none = SettingsStyle.text(c, Lang.t(c, "Сообщений пока нет.", "No messages yet."), 14, SettingsStyle.MUTED);
            none.setPadding(SettingsStyle.dp(c, 4), 0, SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8));
            body.addView(none);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(SettingsStyle.LINE);
                body.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, SettingsStyle.dp(c, 1) / 2)));
            }
            body.addView(row(c, entries.get(i), now));
        }
        BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        if (!entries.isEmpty()) {
            LinearLayout actions = new LinearLayout(c);
            actions.setPadding(0, SettingsStyle.dp(c, 14), 0, 0);
            TextView copy = SettingsStyle.chip(c, Lang.t(c, "Скопировать", "Copy"));
            copy.setTag("messages_copy");
            copy.setOnClickListener(v -> {
                ClipboardManager clip = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("SCAMERA", plain(entries, now)));
            });
            TextView clear = SettingsStyle.chip(c, Lang.t(c, "Очистить", "Clear"));
            clear.setTag("messages_clear");
            clear.setOnClickListener(v -> {
                UiMessages.clearHistory(c);
                if (dialog[0] != null) dialog[0].dismiss();
            });
            actions.addView(copy);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(SettingsStyle.dp(c, 10));
            actions.addView(clear, lp);
            body.addView(actions);
        }
        dialog[0] = SettingsStyle.sheet(c, body);
        dialog[0].show();
        return dialog[0];
    }

    private static View row(Context c, MessageLog.Entry e, long now) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 9), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 9));
        TextView meta = SettingsStyle.text(c, meta(c, e, now), 12, e.error ? SettingsStyle.WARN : SettingsStyle.SUMMARY);
        meta.setPadding(0, 0, 0, SettingsStyle.dp(c, 4));
        TextView text = SettingsStyle.text(c, e.text, 14, SettingsStyle.TEXT);
        text.setTextIsSelectable(true);
        text.setLineSpacing(0, 1.15f);
        row.addView(meta);
        row.addView(text);
        return row;
    }

    /** «12:04:05 · ×3 (с 12:01:40) · ошибка» */
    static String meta(Context c, MessageLog.Entry e, long now) {
        StringBuilder out = new StringBuilder(time(e.lastMs, now));
        if (e.count > 1)
            out.append(" · ×").append(e.count).append(Lang.t(c, " (с ", " (since ")).append(time(e.firstMs, now)).append(')');
        if (e.error) out.append(" · ").append(Lang.t(c, "ошибка", "error"));
        return out.toString();
    }

    /** The copied text: one line per message, newest first. */
    static String plain(List<MessageLog.Entry> entries, long now) {
        StringBuilder out = new StringBuilder();
        for (MessageLog.Entry e : entries) {
            out.append(time(e.lastMs, now));
            if (e.count > 1) out.append(" x").append(e.count);
            if (e.error) out.append(" E");
            out.append("  ").append(e.text.replace('\n', ' ')).append('\n');
        }
        return out.toString();
    }

    /** HH:mm:ss today, dd.MM HH:mm:ss on another day. */
    static String time(long ms, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(ms);
        b.setTimeInMillis(now);
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm:ss" : "dd.MM HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }
}
