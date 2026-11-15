package com.firestarter.voicesend;

import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * A long press on the paperclip opens the app's own attach sheet (the same call as a tap) and ticks
 * the newest screenshot in it, then the one before it for as long as the finger stays down.
 *
 * Since 1.6.0 nothing here draws anything: the sheet, the ticks, the counter, the caption field and
 * the send button are the app's own, so a hold looks and sends exactly like picking the same
 * screenshots by hand. Each tick runs the steps of the sheet's own tick circle handler
 * (cell.setChecked, addToSelectedPhotos, updateCountButton). The screenshots come from the gallery
 * list the sheet already holds, so there is no query of its own.
 *
 * Event driven: Android's own long press detection, then one posted repeat while held, removed
 * when the finger lifts. Every step is wrapped; any surprise in the app's classes ends the hold
 * and leaves the plain attach sheet open.
 */
public final class ClipHold implements View.OnLongClickListener, View.OnTouchListener, Runnable {

    public static volatile boolean enabled = true;
    private static final String TAG = "VoiceSend";
    private static final long REPEAT_MS = 700;
    private static final long FIRST_MS = 260;   // the sheet is created by the click; give it a frame or two
    private static final long RETRY_MS = 120;   // sheet or gallery list not ready yet
    private static final int MAX_WAITS = 40;    // about 5 s of waiting in total, then give up
    private static final int MAX_PICKS = 10;    // one album
    private static final int SCAN = 600;        // newest gallery rows looked at for a screenshot
    private static final int DONE = 0, WAIT = 1, STOP = 2;
    private static final WeakHashMap<View, ClipHold> HOLDS = new WeakHashMap<>();

    private final View button;
    private final View enter;
    private boolean holding;
    private int picked;
    private int waits;

    private ClipHold(View button, View enter) {
        this.button = button;
        this.enter = enter;
    }

    /** UI thread. Once per paperclip. (field, content and bubble are unused since 1.6.0.) */
    public static void arm(View button, View enter, EditText field, ViewGroup content, Object bubble) {
        if (button == null || enter == null || HOLDS.containsKey(button)) {
            return;
        }
        ClipHold h = new ClipHold(button, enter);
        HOLDS.put(button, h);
        button.setOnLongClickListener(h);
        button.setOnTouchListener(h);
    }

    /** UI thread. Takes every listener away again. */
    public static void disarmAll() {
        for (View b : new ArrayList<>(HOLDS.keySet())) {
            ClipHold h = HOLDS.get(b);
            if (b != null && h != null) {
                h.holding = false;
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
        try {
            if (!enabled || !usable()) {
                return false;
            }
            v.removeCallbacks(this);
            if (!v.performClick()) {      // the paperclip's own click: the app opens its attach sheet
                return false;
            }
            holding = true;
            picked = 0;
            waits = 0;
            v.postDelayed(this, FIRST_MS);
            return true;
        } catch (Throwable e) {
            holding = false;
            Log.i(TAG, "clip hold: " + e);
            return false;
        }
    }

    @Override
    public void run() {
        try {
            // a finger that lifted early still gets its first screenshot once the sheet is ready
            if (!enabled || !button.isAttachedToWindow() || (!holding && picked > 0)) {
                holding = false;
                return;
            }
            int r = step();
            if (r == DONE) {
                picked++;
                if (haptics()) {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                if (holding && picked < MAX_PICKS) {
                    button.postDelayed(this, REPEAT_MS);
                } else {
                    holding = false;
                }
            } else if (r == WAIT && ++waits <= MAX_WAITS) {
                button.postDelayed(this, RETRY_MS);
            } else {
                holding = false;
            }
        } catch (Throwable e) {
            holding = false;
            Log.i(TAG, "clip hold step: " + e);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            holding = false;
            if (picked > 0) {
                v.removeCallbacks(this);
            }
        }
        return false;
    }

    /** Tick the newest screenshot not ticked yet, the way the sheet's own tick circle does. */
    private int step() throws Throwable {
        Object chat = field(enter, "parentFragment");
        Object alert = chat == null ? null : field(chat, "chatAttachAlert");
        if (alert == null || !Boolean.TRUE.equals(alert.getClass().getMethod("isShowing").invoke(alert))) {
            return picked > 0 ? STOP : WAIT;   // closed after a tick: the user went back, stop
        }
        Object layout = alert.getClass().getMethod("getPhotoLayout").invoke(alert);
        if (layout == null) {
            return WAIT;
        }
        Object album = field(layout, "galleryAlbumEntry");
        if (album == null || field(layout, "selectedAlbumEntry") != album) {
            return WAIT;   // the gallery list is still loading
        }
        Object photosObj = field(album, "photos");
        Object selObj = layout.getClass().getMethod("getSelectedPhotos").invoke(layout);
        Object ordObj = layout.getClass().getMethod("getSelectedPhotosOrder").invoke(layout);
        if (!(photosObj instanceof ArrayList) || !(selObj instanceof HashMap) || !(ordObj instanceof ArrayList)) {
            return STOP;
        }
        ArrayList<?> photos = (ArrayList<?>) photosObj;
        HashMap<?, ?> sel = (HashMap<?, ?>) selObj;
        Object maxObj = field(alert, "maxSelectedPhotos");
        int max = maxObj instanceof Integer ? (Integer) maxObj : -1;
        if (max >= 0 && sel.size() >= max) {
            return STOP;
        }
        Object entry = null;
        Field idF = null, pathF = null, videoF = null;
        int n = Math.min(photos.size(), SCAN);
        for (int i = 0; i < n; i++) {
            Object p = photos.get(i);
            if (p == null) {
                continue;
            }
            if (idF == null) {
                idF = p.getClass().getField("imageId");
                pathF = p.getClass().getField("path");
                videoF = p.getClass().getField("isVideo");
            }
            Object path = pathF.get(p);
            if (videoF.getBoolean(p) || !(path instanceof String) || !((String) path).contains("/Screenshots/")) {
                continue;
            }
            if (!sel.containsKey(idF.get(p))) {
                entry = p;
                break;
            }
        }
        if (entry == null) {
            return photos.isEmpty() ? WAIT : STOP;   // no (more) screenshots
        }
        int num = ((ArrayList<?>) ordObj).size();
        View cell = cellOf(layout, entry);
        if (cell != null) {
            cell.getClass().getMethod("setChecked", int.class, boolean.class, boolean.class).invoke(cell, num, true, true);
        }
        Method add = find(layout.getClass(), "addToSelectedPhotos", entry.getClass(), int.class);
        add.invoke(layout, entry, -1);
        alert.getClass().getMethod("updateCountButton", int.class).invoke(alert, 1);
        return DONE;
    }

    /** The grid cell showing this photo right now, if it is on screen. */
    private static View cellOf(Object layout, Object entry) {
        try {
            Object grid = field(layout, "gridView");
            if (!(grid instanceof ViewGroup)) {
                return null;
            }
            ViewGroup g = (ViewGroup) grid;
            for (int i = 0, c = g.getChildCount(); i < c; i++) {
                View v = g.getChildAt(i);
                if (v != null && v.getClass().getName().endsWith("PhotoAttachPhotoCell")
                        && v.getClass().getMethod("getPhotoEntry").invoke(v) == entry) {
                    return v;
                }
            }
        } catch (Throwable e) {
            Log.i(TAG, "clip hold cell: " + e);
        }
        return null;
    }

    private static Object field(Object o, String name) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method find(Class<?> c, String name, Class<?>... args) throws Throwable {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
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
