package com.particlesdevs.photoncamera.ui.camera;

import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.MessageLog;
import com.particlesdevs.photoncamera.util.UiMessages;

/**
 * P214f (speed-ux.md C3): the camera screen's message card (the card toast of P25, {@code shade_toast} above the bottom bar)
 * as the {@link UiMessages.Sink} of the one message channel. An information message shows for
 * {@link MessageLog#durationMs} of its text (1.5 s + 50 ms per character, at most 7 s; the card was 1.5 s for any text, the
 * system toasts ~2 s); an error stays, in the warning colour with a «tap to close» line, until it is tapped; a repeat updates
 * the counter («×3») and restarts the time. An information message over a pending error shows for its time, then the error
 * comes back. A tap closes the card (and the pending error); a long press opens the history of the last 20 messages.
 * Main thread only.
 */
public final class MessageCard implements UiMessages.Sink {
    private final TextView view;
    private final ColorStateList infoColor;
    private final int errorColor, hintColor;
    private final Runnable timeout = this::onTimeout;
    /** The entry on the card now (null: hidden). */
    private MessageLog.Entry showing;

    public MessageCard(TextView view, Runnable openHistory) {
        this.view = view;
        this.infoColor = view.getTextColors();
        this.errorColor = ContextCompat.getColor(view.getContext(), R.color.settings_warn);
        this.hintColor = ContextCompat.getColor(view.getContext(), R.color.settings_summary);
        view.setOnClickListener(v -> dismiss());
        view.setOnLongClickListener(v -> {
            if (openHistory != null) openHistory.run();
            return true;
        });
    }

    @Override
    public void show(MessageLog.Entry entry) {
        if (entry == null) return;
        display(entry);
        view.removeCallbacks(timeout);
        if (!entry.error) view.postDelayed(timeout, MessageLog.durationMs(entry.text));
    }

    /** The entry on the card now (tests). */
    MessageLog.Entry showing() {
        return showing;
    }

    /** A tap: the card closes and the pending error is read. */
    void dismiss() {
        UiMessages.acknowledge();
        view.removeCallbacks(timeout);
        hide();
    }

    /** The camera screen goes: no callbacks on its views any more. */
    public void release() {
        UiMessages.detach(this);
        view.removeCallbacks(timeout);
        view.animate().cancel();
        view.setOnClickListener(null);
        view.setOnLongClickListener(null);
        view.setClickable(false);
        view.setLongClickable(false);
    }

    private void onTimeout() {
        MessageLog.Entry pending = UiMessages.unreadError();
        if (pending != null) display(pending);
        else hide();
    }

    /** The card's text: the message, «×N» for a repeat; an error in the warning colour with the «tap to close» line. */
    static CharSequence text(android.content.Context c, MessageLog.Entry e, int hintColor) {
        SpannableStringBuilder out = new SpannableStringBuilder(e.text);
        if (e.count > 1) out.append("  ×").append(String.valueOf(e.count));
        if (e.error) {
            int start = out.length();
            out.append('\n').append(Lang.t(c, "Нажмите, чтобы закрыть · удерживайте — последние сообщения",
                    "Tap to close · hold for the recent messages"));
            out.setSpan(new ForegroundColorSpan(hintColor), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new AbsoluteSizeSpan(12, true), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private void display(MessageLog.Entry e) {
        showing = e;
        view.animate().cancel();
        view.setText(text(view.getContext(), e, hintColor));
        if (e.error) view.setTextColor(errorColor);
        else view.setTextColor(infoColor);
        view.setAccessibilityLiveRegion(e.error ? View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (view.getVisibility() != View.VISIBLE) {
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);
        }
        view.animate().alpha(1f).setDuration(200).start();
    }

    private void hide() {
        showing = null;
        view.animate().cancel();
        if (view.getVisibility() != View.VISIBLE) return;
        view.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (showing == null) view.setVisibility(View.GONE);
        }).start();
    }
}
