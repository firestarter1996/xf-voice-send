package com.firestarter.voicesend;

import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * A long press on the paperclip puts the newest screenshot in the message box's tray, and keeps
 * adding the one before it for as long as the finger stays down. A short tap is untouched (the
 * button's own click listener opens the attach menu as before).
 *
 * Event driven: Android's own long press detection, then one posted repeat while held, removed
 * the moment the finger lifts.
 */
public final class ClipHold implements View.OnLongClickListener, View.OnTouchListener, Runnable {

    public static volatile boolean enabled = true;
    private static final long REPEAT_MS = 700;
    private static final WeakHashMap<View, ClipHold> HOLDS = new WeakHashMap<>();

    private final View button;
    private final View enter;
    private final EditText field;
    private final ViewGroup content;
    private final Object bubble;
    private boolean holding;

    private ClipHold(View button, View enter, EditText field, ViewGroup content, Object bubble) {
        this.button = button;
        this.enter = enter;
        this.field = field;
        this.content = content;
        this.bubble = bubble;
    }

    /** UI thread. Once per paperclip. */
    public static void arm(View button, View enter, EditText field, ViewGroup content, Object bubble) {
        if (button == null || HOLDS.containsKey(button)) {
            return;
        }
        ClipHold h = new ClipHold(button, enter, field, content, bubble);
        HOLDS.put(button, h);
        button.setOnLongClickListener(h);
        button.setOnTouchListener(h);
    }

    /** UI thread. Takes every listener away again. */
    public static void disarmAll() {
        for (View b : new ArrayList<>(HOLDS.keySet())) {
            ClipHold h = HOLDS.get(b);
            if (b != null && h != null) {
                b.removeCallbacks(h);
                b.setOnLongClickListener(null);
                b.setLongClickable(false);
                b.setOnTouchListener(null);
            }
        }
        HOLDS.clear();
    }

    @Override
    public boolean onLongClick(View v) {
        if (!enabled || !usable()) {
            return false;
        }
        holding = true;
        step();
        v.postDelayed(this, REPEAT_MS);
        return true;
    }

    @Override
    public void run() {
        if (!holding || !enabled || !button.isAttachedToWindow()) {
            holding = false;
            return;
        }
        step();
        button.postDelayed(this, REPEAT_MS);
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            holding = false;
            v.removeCallbacks(this);
        }
        return false;
    }

    private void step() {
        if (!ShotTray.room(enter)) {
            return;
        }
        ShotTray.add(enter, field, content, bubble, 1, ShotTray.SCREENSHOT);
        if (haptics()) {
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /** Not while a message is being edited. */
    private boolean usable() {
        try {
            Object r = enter.getClass().getMethod("isEditingMessage").invoke(enter);
            return !(r instanceof Boolean && (Boolean) r);
        } catch (Throwable e) {
            return true;
        }
    }

    /** The app's own "disable vibration" setting. */
    private boolean haptics() {
        try {
            Class<?> c = Class.forName("tw.nekomimi.nekogram.NekoConfig", false, enter.getClass().getClassLoader());
            Object item = c.getField("disableVibration").get(null);
            Object off = item.getClass().getMethod("Bool").invoke(item);
            return !(off instanceof Boolean && (Boolean) off);
        } catch (Throwable e) {
            return true;
        }
    }
}
