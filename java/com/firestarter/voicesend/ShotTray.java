package com.firestarter.voicesend;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The screenshots and camera pictures waiting to go out with the message being written in one chat.
 *
 * One tray per message box. A spoken "screenshot" (or "picture") asks for the newest screenshot
 * (or camera photo) that is not in the tray yet: the lookup (one MediaStore query for the newest rows) and the thumbnail run on a
 * background thread, the UI thread only adds a small picture to a strip above the message box.
 * The tray empties when the message is sent, when its X is tapped, when the box is emptied by hand
 * and when the chat is left. Nothing runs while the tray is empty: the listeners exist only while
 * the strip is showing.
 */
public final class ShotTray implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener,
        TextWatcher, View.OnClickListener {

    public static final int MAX = 10;
    private static final String TAG = "VoiceSend";
    private static final WeakHashMap<View, ShotTray> TRAYS = new WeakHashMap<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static ThreadPoolExecutor io;
    /** MediaStore.QUERY_ARG_MATCH_PENDING (API 30); 1 = MediaStore.MATCH_INCLUDE. */
    private static final String MATCH_PENDING = "android:query-arg-match-pending";

    private final View enter;
    private final EditText field;
    private final ViewGroup content;
    private final Object bubble;
    private Method bubbleTop;
    private final ArrayList<String> paths = new ArrayList<>();
    private final HashSet<String> claimed = new HashSet<>();
    private ArrayList<Runnable> waiters;
    private LinearLayout strip;
    private int jobs;
    private int gen;
    private long quietUntil;
    private boolean hadText;
    private boolean showing;
    private boolean prevForce;
    private float lastY = Float.NaN;
    private final int[] a = new int[2];
    private final int[] b = new int[2];

    private ShotTray(View enter, EditText field, ViewGroup content, Object bubble) {
        this.enter = enter;
        this.field = field;
        this.content = content;
        this.bubble = bubble;
        try {
            if (bubble != null) {
                bubbleTop = bubble.getClass().getMethod("getInputBubbleTop");
            }
        } catch (Throwable ignore) {
        }
    }

    // ---- called from the plugin, UI thread ----

    public static final int SCREENSHOT = 0;
    public static final int CAMERA = 1;
    /** The newest image in any folder the gallery shows (MediaStore Images, by date added). */
    public static final int ANY = 2;

    /** Ask for the next n newest screenshots (kind 0), camera photos (kind 1) or images of any folder (kind 2) not in this box's tray yet. */
    public static void add(View enter, EditText field, ViewGroup content, Object bubble, int n, final int kind) {
        ShotTray t = TRAYS.get(enter);
        if (t == null) {
            t = new ShotTray(enter, field, content, bubble);
            TRAYS.put(enter, t);
        }
        t.quietUntil = SystemClock.uptimeMillis() + 600;
        final Context ctx = enter.getContext().getApplicationContext();
        final float density = enter.getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            if (t.paths.size() + t.jobs >= MAX) {
                break;
            }
            t.jobs++;
            final ShotTray tray = t;
            final int g = t.gen;
            executor().execute(new Runnable() {
                @Override
                public void run() {
                    String path = null;
                    Bitmap bmp = null;
                    try {
                        long t0 = SystemClock.uptimeMillis();
                        String[] row = tray.pick(ctx, g, kind);
                        if (row != null) {
                            path = row[0];
                            Log.i(TAG, "attach: picked " + new File(path).getName() + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
                            bmp = thumb(ctx, row, density);
                        }
                    } catch (Throwable e) {
                        Log.i(TAG, "screenshot lookup: " + e);
                    }
                    final String p = path;
                    final Bitmap bm = bmp;
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            tray.done(g, p, bm);
                        }
                    });
                }
            });
        }
    }

    /** Is there room for one more in this box's tray? */
    public static boolean room(View enter) {
        ShotTray t = TRAYS.get(enter);
        return t == null || t.paths.size() + t.jobs < MAX;
    }

    public static int count(View enter) {
        ShotTray t = TRAYS.get(enter);
        return t == null ? 0 : t.paths.size();
    }

    /** A lookup is still running for this box. */
    public static boolean busy(View enter) {
        ShotTray t = TRAYS.get(enter);
        return t != null && t.jobs > 0;
    }

    /** Run r once no lookup is running for this box (at once when none is). */
    public static void whenReady(View enter, Runnable r) {
        ShotTray t = TRAYS.get(enter);
        if (t == null || t.jobs <= 0) {
            r.run();
            return;
        }
        if (t.waiters == null) {
            t.waiters = new ArrayList<>();
        }
        t.waiters.add(r);
    }

    /** The waiting screenshots in the order they were added; the tray is emptied. */
    public static String[] take(View enter) {
        ShotTray t = TRAYS.get(enter);
        if (t == null) {
            return new String[0];
        }
        String[] out = t.paths.toArray(new String[0]);
        t.reset();
        return out;
    }

    public static void clear(View enter) {
        ShotTray t = TRAYS.get(enter);
        if (t != null) {
            t.reset();
        }
    }

    public static void clearAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            UI.post(new Runnable() {
                @Override
                public void run() {
                    clearAll();
                }
            });
            return;
        }
        for (ShotTray t : new ArrayList<>(TRAYS.values())) {
            if (t != null) {
                t.reset();
            }
        }
        TRAYS.clear();
    }

    /** The plugin is about to change the text itself: an emptied box in the next moment is not a reset. */
    public static void quiet(View enter) {
        ShotTray t = TRAYS.get(enter);
        if (t != null) {
            t.quietUntil = SystemClock.uptimeMillis() + 600;
        }
    }

    // ---- background thread ----

    private static synchronized ThreadPoolExecutor executor() {
        if (io == null) {
            io = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    return new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                            r.run();
                        }
                    }, "VoiceSendShots");
                }
            });
            io.allowCoreThreadTimeOut(true);
        }
        return io;
    }

    /** A row still being written counts only when it was added this recently (seconds): an abandoned one never makes a pick wait. */
    private static final long FRESH_S = 60;
    /** How long a pick waits for a picture that is still being written, and how often it looks again. */
    private static final long WAIT_MS = 8000;
    private static final long STEP_MS = 100;

    /** The newest screenshot or camera photo not yet claimed by this tray: {path, MediaStore id or null}. */
    private String[] pick(Context ctx, int g, int kind) {
        ArrayList<String[]> rows = newest(ctx, MAX + 4, kind);
        if (rows.isEmpty() && kind == ANY) {
            kind = SCREENSHOT; // any folder gave nothing: behave as the screenshot lookup always has
            rows = newest(ctx, MAX + 4, kind);
        }
        if (rows.isEmpty()) {
            rows = listed(MAX + 4, kind);
        }
        if (kind == ANY) {
            // never a picture from a hidden folder (a dot folder or one holding .nomedia)
            ArrayList<String[]> seen = new ArrayList<>();
            for (String[] r : rows) {
                if (r[0] != null && !hidden(r[0])) {
                    seen.add(r);
                }
            }
            rows = seen;
        }
        // A picture taken a moment ago is still being written (its row is pending, its file empty or
        // half done). When the newest row not in the tray is such a picture, wait for it here, on this
        // background thread, instead of taking the one before it. Nothing pending: no wait at all.
        try {
            String[] first = null;
            synchronized (claimed) {
                if (g != gen) {
                    return null;
                }
                long now = System.currentTimeMillis() / 1000;
                for (String[] r : rows) {
                    boolean pending = r.length > 3 && "1".equals(r[2]);
                    if (pending && now - parse(r[3]) > FRESH_S) {
                        continue; // left behind by an app long ago: never waited for, never attached
                    }
                    if (r[0] != null && !claimed.contains(r[0])) {
                        first = r;
                        break;
                    }
                }
            }
            if (first != null && first.length > 3 && "1".equals(first[2]) && first[1] != null) {
                long t0 = SystemClock.uptimeMillis();
                String[] fin = await(ctx, Long.parseLong(first[1]), g);
                Log.i(TAG, "attach: newest picture was still being saved, waited " + (SystemClock.uptimeMillis() - t0)
                        + " ms, " + (fin != null ? "took it" : !enter.isAttachedToWindow() ? "chat left" : "gave up, took the one before"));
                if (fin == null && !enter.isAttachedToWindow()) {
                    return null;
                }
                if (fin != null) {
                    synchronized (claimed) {
                        if (g != gen) {
                            return null;
                        }
                        if (!claimed.contains(fin[0])) {
                            claimed.add(fin[0]);
                            return fin;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.i(TAG, "screenshot wait: " + e);
        }
        synchronized (claimed) {
            if (g != gen) {
                return null;
            }
            for (String[] r : rows) {
                if (r[0] != null && !(r.length > 2 && "1".equals(r[2])) && !claimed.contains(r[0])
                        && !new File(r[0]).getName().startsWith(".") && new File(r[0]).length() > 0) {
                    claimed.add(r[0]);
                    return r;
                }
            }
        }
        return null;
    }

    /** Is this file inside a dot folder or a folder (at any level) that holds a .nomedia file? Background thread. */
    private static boolean hidden(String path) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File d = new File(path).getParentFile();
            for (int i = 0; d != null && i < 12; i++) {
                String p = d.getAbsolutePath();
                if (p.length() <= root.length() || !p.startsWith(root)) {
                    break;
                }
                if (d.getName().startsWith(".") || new File(d, ".nomedia").exists()) {
                    return true;
                }
                d = d.getParentFile();
            }
            return false;
        } catch (Throwable e) {
            return true;
        }
    }

    private static long parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Background thread only. Wait for the row id to be written out: {path, id} once it is, null when it went away, the tray was emptied, the chat was left or time ran out. */
    private String[] await(Context ctx, long id, int g) {
        Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(STEP_MS);
            synchronized (claimed) {
                if (g != gen) {
                    return null;
                }
            }
            if (!enter.isAttachedToWindow()) {
                return null; // the chat was left: nothing to wait for
            }
            Cursor c = null;
            try {
                Bundle q = new Bundle();
                q.putInt(MATCH_PENDING, 1);
                c = ctx.getContentResolver().query(u, new String[]{"_data", "is_pending"}, q, null);
                if (c == null || !c.moveToFirst()) {
                    return null;
                }
                String path = c.getString(0);
                if (c.getInt(1) == 0 && path != null && new File(path).length() > 0) {
                    return new String[]{path, String.valueOf(id), "0", "0"};
                }
            } catch (Throwable e) {
                Log.i(TAG, "screenshot wait query: " + e);
                return null;
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return null;
    }

    private static ArrayList<String[]> newest(Context ctx, int limit, int kind) {
        ArrayList<String[]> out = new ArrayList<>();
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri u = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = Build.VERSION.SDK_INT >= 30 ? new String[]{"_id", "_data", "is_pending", "date_added"} : new String[]{"_id", "_data"};
            String sel = kind == ANY ? null : "(bucket_display_name = ? OR _data LIKE ?)";
            String[] args = kind == ANY ? null : kind == CAMERA ? new String[]{"Camera", "%/DCIM/Camera/%"} : new String[]{"Screenshots", "%/Screenshots/%"};
            String order = "date_added DESC, _id DESC";
            if (Build.VERSION.SDK_INT >= 30) {
                Bundle q = new Bundle();
                if (sel != null) {
                    q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
                    q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
                }
                q.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
                q.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
                q.putInt(MATCH_PENDING, 1); // include pictures still being written, so a pick can wait for them
                c = cr.query(u, proj, q, null);
            } else {
                c = cr.query(u, proj, sel, args, order + " LIMIT " + limit);
            }
            while (c != null && c.moveToNext() && out.size() < limit) {
                if (c.getColumnCount() > 3) {
                    out.add(new String[]{c.getString(1), String.valueOf(c.getLong(0)), String.valueOf(c.getInt(2)), String.valueOf(c.getLong(3))});
                } else {
                    out.add(new String[]{c.getString(1), String.valueOf(c.getLong(0))});
                }
            }
        } catch (Throwable e) {
            Log.i(TAG, "screenshot query: " + e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /** Only when MediaStore gave nothing: the two usual folders, newest file first. */
    private static ArrayList<String[]> listed(int limit, int kind) {
        ArrayList<File> all = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        for (String d : kind == CAMERA ? new String[]{"DCIM/Camera"} : new String[]{"Pictures/Screenshots", "DCIM/Screenshots"}) {
            File[] fs = new File(root, d).listFiles();
            if (fs != null) {
                all.addAll(Arrays.asList(fs));
            }
        }
        final java.util.HashMap<File, Long> when = new java.util.HashMap<>();
        ArrayList<File> pics = new ArrayList<>();
        for (File f : all) {
            String n = f.getName().toLowerCase();
            if (n.startsWith(".")) {
                continue;
            }
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".heic")) {
                when.put(f, f.lastModified());
                pics.add(f);
            }
        }
        java.util.Collections.sort(pics, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                return Long.compare(when.get(y), when.get(x));
            }
        });
        ArrayList<String[]> out = new ArrayList<>();
        for (File f : pics) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new String[]{f.getAbsolutePath(), null});
        }
        return out;
    }

    private static Bitmap thumb(Context ctx, String[] row, float density) {
        int w = (int) (64 * density);
        int h = (int) (150 * density);
        try {
            if (Build.VERSION.SDK_INT >= 29 && row[1] != null) {
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(row[1]));
                return ctx.getContentResolver().loadThumbnail(u, new Size(w, h), null);
            }
        } catch (Throwable ignore) {
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(row[0], o);
            int s = 1;
            while (o.outHeight / (s * 2) >= h && o.outWidth / (s * 2) >= w / 2) {
                s *= 2;
            }
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(row[0], o);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---- UI thread ----

    private void done(int g, String path, Bitmap bmp) {
        jobs--;
        if (g == gen && path != null && enter.isAttachedToWindow()) {
            paths.add(path);
            show(bmp);
        }
        if (jobs <= 0) {
            jobs = 0;
            ArrayList<Runnable> w = waiters;
            waiters = null;
            if (w != null) {
                for (Runnable r : w) {
                    try {
                        r.run();
                    } catch (Throwable e) {
                        Log.i(TAG, "screenshot waiter: " + e);
                    }
                }
            }
        }
    }

    private int dp(float v) {
        return (int) Math.ceil(v * enter.getResources().getDisplayMetrics().density);
    }

    /** The app's theme colour for a Theme key name, or the fallback. */
    private int themeColor(String key, int fallback) {
        try {
            Class<?> th = Class.forName("org.telegram.ui.ActionBar.Theme", false, enter.getClass().getClassLoader());
            int k = th.getField(key).getInt(null);
            return (Integer) th.getMethod("getColor", int.class).invoke(null, k);
        } catch (Throwable e) {
            return fallback;
        }
    }

    /**
     * 1.6.1: the tray is drawn like the app's own photo picker: square pictures with the picker's own
     * numbered blue tick (CheckBox2, the same size, arc and colours PhotoAttachPhotoCell uses), the
     * message panel's colour behind them and the app's own close icon. Every step falls back to a
     * plain look if a class or field is missing, so a new XF build can never break the tray.
     */
    private View tick(Context ctx, int num) {
        try {
            ClassLoader cl = enter.getClass().getClassLoader();
            Class<?> cb = Class.forName("org.telegram.ui.Components.CheckBox2", false, cl);
            Class<?> th = Class.forName("org.telegram.ui.ActionBar.Theme", false, cl);
            View v = (View) cb.getConstructor(Context.class, int.class).newInstance(ctx, 24);
            cb.getMethod("setDrawBackgroundAsArc", int.class).invoke(v, 7);
            cb.getMethod("setColor", int.class, int.class, int.class).invoke(v,
                    th.getField("key_chat_attachCheckBoxBackground").getInt(null),
                    th.getField("key_chat_attachPhotoBackground").getInt(null),
                    th.getField("key_chat_attachCheckBoxCheck").getInt(null));
            cb.getMethod("setChecked", int.class, boolean.class, boolean.class).invoke(v, num, true, false);
            return v;
        } catch (Throwable e) {
            Log.i(TAG, "screenshot tick: " + e);
            return null;
        }
    }

    /** Square pictures 64 dp wide, smaller when that many would not fit across the chat (never under 24 dp). */
    private void fit() {
        LinearLayout s = strip;
        if (s == null) {
            return;
        }
        int n = s.getChildCount() - 1;
        if (n <= 0) {
            return;
        }
        int avail = content.getWidth();
        if (avail <= 0) {
            avail = enter.getResources().getDisplayMetrics().widthPixels;
        }
        avail -= dp(16) + dp(42) + dp(6);
        int side = Math.max(dp(24), Math.min(dp(64), avail / n - dp(4)));
        for (int i = 0; i < n; i++) {
            View c = s.getChildAt(i);
            if (!(c instanceof FrameLayout)) {
                continue;
            }
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && lp.width != side) {
                lp.width = side;
                lp.height = side;
                c.setLayoutParams(lp);
                View iv = ((FrameLayout) c).getChildAt(0);
                if (iv != null && iv.getLayoutParams() != null) {
                    iv.getLayoutParams().width = side;
                    iv.getLayoutParams().height = side;
                    iv.setLayoutParams(iv.getLayoutParams());
                }
                View tk = ((FrameLayout) c).getChildCount() > 1 ? ((FrameLayout) c).getChildAt(1) : null;
                if (tk != null) {
                    tk.setVisibility(side >= dp(40) ? View.VISIBLE : View.GONE);
                }
            }
        }
        View x = s.getChildAt(n);
        if (x != null && x.getLayoutParams() != null && x.getLayoutParams().height != side) {
            x.getLayoutParams().height = side;
            x.setLayoutParams(x.getLayoutParams());
        }
    }

    private void show(Bitmap bmp) {
        Context ctx = enter.getContext();
        int side = dp(64);
        if (strip == null) {
            strip = new LinearLayout(ctx);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(themeColor("key_chat_messagePanelBackground", 0xE61C1C1E));
            bg.setCornerRadius(dp(14));
            strip.setBackground(bg);
            strip.setElevation(dp(2));
            strip.setPadding(dp(5), dp(5), dp(1), dp(5));
            View x = null;
            int icon = ctx.getResources().getIdentifier("msg_close", "drawable", ctx.getPackageName());
            if (icon != 0) {
                try {
                    ImageView ix = new ImageView(ctx);
                    ix.setImageResource(icon);
                    ix.setScaleType(ImageView.ScaleType.CENTER);
                    ix.setColorFilter(themeColor("key_chat_messagePanelIcons", 0xFFFFFFFF));
                    x = ix;
                } catch (Throwable e) {
                    x = null;
                }
            }
            if (x == null) {
                TextView tx = new TextView(ctx);
                tx.setText("\u2715");
                tx.setTextColor(themeColor("key_chat_messagePanelIcons", 0xFFFFFFFF));
                tx.setTextSize(16);
                tx.setGravity(Gravity.CENTER);
                x = tx;
            }
            x.setContentDescription("Remove attachments");
            x.setOnClickListener(this);
            strip.addView(x, new LinearLayout.LayoutParams(dp(36), side));
        }
        FrameLayout cell = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(themeColor("key_chat_attachPhotoBackground", 0xFF3A3A3C));
        if (bmp != null) {
            iv.setImageBitmap(bmp);
        }
        final int rad = dp(8);
        cell.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), rad);
            }
        });
        cell.setClipToOutline(true);
        cell.addView(iv, new FrameLayout.LayoutParams(side, side));
        View t = tick(ctx, Math.max(0, paths.size() - 1));   // the picker counts from 0 and draws the number plus one
        if (t != null) {
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
            tl.topMargin = dp(3);
            tl.rightMargin = dp(3);
            cell.addView(t, tl);
        }
        cell.setContentDescription("Attachment " + paths.size());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.rightMargin = dp(4);
        strip.addView(cell, strip.getChildCount() - 1, lp);
        fit();
        if (!showing) {
            showing = true;
            lastY = Float.NaN;
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            flp.leftMargin = dp(8);
            strip.setAlpha(0f);
            content.addView(strip, flp);
            content.getViewTreeObserver().addOnPreDrawListener(this);
            enter.addOnAttachStateChangeListener(this);
            hadText = field.length() > 0;
            field.addTextChangedListener(this);
            force(true);
        }
    }

    /** Empty the tray and take the strip and every listener away. */
    private void reset() {
        synchronized (claimed) {
            gen++;
            claimed.clear();
        }
        paths.clear();
        if (!showing) {
            return;
        }
        showing = false;
        try {
            content.getViewTreeObserver().removeOnPreDrawListener(this);
            enter.removeOnAttachStateChangeListener(this);
            field.removeTextChangedListener(this);
            content.removeView(strip);
        } catch (Throwable e) {
            Log.i(TAG, "screenshot strip: " + e);
        }
        strip = null;
        force(false);
    }

    /** With screenshots waiting the send button shows even when the box is empty. */
    private void force(boolean on) {
        try {
            if (on) {
                prevForce = enter.getClass().getField("forceShowSendButton").getBoolean(enter);
                if (prevForce) {
                    return;
                }
            } else if (prevForce) {
                return;
            }
            enter.getClass().getMethod("setForceShowSendButton", boolean.class, boolean.class).invoke(enter, on, true);
        } catch (Throwable e) {
            Log.i(TAG, "screenshot send button: " + e);
        }
    }

    @Override
    public boolean onPreDraw() {
        LinearLayout s = strip;
        if (s == null || s.getHeight() == 0) {
            return true;
        }
        float top;
        try {
            if (bubbleTop != null) {
                top = ((Number) bubbleTop.invoke(bubble)).floatValue() + ((View) bubble).getY();
            } else {
                field.getLocationInWindow(a);
                content.getLocationInWindow(b);
                top = a[1] - b[1] - dp(8);
            }
        } catch (Throwable e) {
            bubbleTop = null;
            return true;
        }
        float y = top - s.getTop() - s.getHeight() - dp(6);
        if (y != lastY) {
            lastY = y;
            s.setTranslationY(y);
            s.setAlpha(1f);
        }
        return true;
    }

    @Override
    public void onClick(View v) {
        reset();
    }

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        reset();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        boolean has = s.length() > 0;
        if (hadText && !has && SystemClock.uptimeMillis() > quietUntil && !MicWatch.recent(3000)) {
            hadText = false;
            UI.post(new Runnable() {
                @Override
                public void run() {
                    if (field.length() == 0) {
                        reset();
                    }
                }
            });
            return;
        }
        hadText = has;
    }
}
