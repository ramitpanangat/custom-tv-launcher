package com.tvtiles.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/** A Windows Phone style start screen for Android TV: every app as a coloured tile, plus a live clock tile. */
public class MainActivity extends Activity {

    static final class AppEntry {
        String pkg;
        ComponentName component;
        boolean leanback;
        String label;
        Drawable icon;
        int color;
        /** Activity and APK path: when both are unchanged, nothing about the tile can have changed. */
        String stamp;
        /** The icon drawn once at each size it is shown at, so a frame draws a plain bitmap. */
        final Map<Integer, Bitmap> iconAt = new HashMap<>();
    }

    private LayoutStore store;
    private ScrollView scroll;
    private TileGrid grid;
    private TextView btnWifi;
    private TextView greeting;
    private AlertDialog menu;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long flipMs;
    /** The clock tile's turning face and its two sides; replaced whenever the tiles are rebuilt. */
    private View clockFace, clockFront, clockBack;
    private boolean showingBack;
    /** The full-screen clock while it is open, else null. */
    private FrameLayout bigClock;
    private View bigClockBody;
    private final Random random = new Random();

    /** Every flip interval while Home is in front: turn the clock tile, refresh the greeting. Nothing else runs. */
    private final Runnable flipTick = new Runnable() {
        @Override public void run() {
            flipClock();
            updateGreeting();
            handler.postDelayed(this, flipMs);
        }
    };

    /** Once a minute the full-screen clock moves a little, so nothing stays in one place on the panel. */
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (bigClockBody == null) return;
            bigClockBody.animate()
                    .translationX((random.nextFloat() * 2 - 1) * dp(90))
                    .translationY((random.nextFloat() * 2 - 1) * dp(60))
                    .setDuration(2000).start();
            handler.postDelayed(this, 60_000);
        }
    };

    private final Map<String, AppEntry> apps = new HashMap<>();
    /** Entries kept between refreshes: labels, icons and sampled colours are only loaded again for changed apps. */
    private final Map<String, AppEntry> cache = new HashMap<>();
    private List<LayoutStore.Item> items = new ArrayList<>();
    private Set<String> hidden;
    private String appsSignature = "";
    private String lastFocusedKey;
    /** The tile being moved, or null outside move mode. */
    private String movingKey;

    private float density;
    private Typeface light, regular, medium;
    private int clockColor;
    private final Map<String, Integer> colorOverrides = new HashMap<>();
    private final Map<String, String> labelOverrides = new HashMap<>();
    /** config.xml tile_icons: package to a white glyph drawn in the tile style. */
    private final Map<String, Integer> glyphs = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh(true);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        light = getResources().getFont(R.font.outfit_light);
        regular = getResources().getFont(R.font.outfit_regular);
        medium = getResources().getFont(R.font.outfit_medium);
        clockColor = Color.parseColor(getString(R.string.clock_color));
        excluded.addAll(Arrays.asList(getResources().getStringArray(R.array.excluded_packages)));
        for (String entry : getResources().getStringArray(R.array.tile_colors)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                colorOverrides.put(entry.substring(0, eq).trim(), Color.parseColor(entry.substring(eq + 1).trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String entry : getResources().getStringArray(R.array.tile_labels)) {
            int eq = entry.indexOf('=');
            if (eq > 0) labelOverrides.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        for (String entry : getResources().getStringArray(R.array.tile_icons)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            int id = getResources().getIdentifier(entry.substring(eq + 1).trim(), "drawable", getPackageName());
            if (id != 0) glyphs.put(entry.substring(0, eq).trim(), id);
        }
        flipMs = getResources().getInteger(R.integer.clock_flip_seconds) * 1000L;
        store = new LayoutStore(this);
        hidden = store.hidden();
        String[] bg = getResources().getStringArray(R.array.background_colors);
        int[] colors = new int[bg.length];
        for (int i = 0; i < bg.length; i++) colors[i] = Color.parseColor(bg[i]);
        // The window's own background, the only full-screen fill. A gradient computed per pixel cost this TV about
        // 5 ms a frame, so it is drawn once into a 160x90 bitmap that the GPU stretches with filtering instead.
        if (colors.length > 1) {
            Bitmap small = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
            g.setBounds(0, 0, 160, 90);
            g.draw(new Canvas(small));
            android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), small);
            d.setFilterBitmap(true);
            getWindow().setBackgroundDrawable(d);
        } else {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    colors.length == 1 ? colors[0] : Color.BLACK));
        }
        buildViews();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);

        refresh(true);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        btnWifi.setText(networkName());
        updateGreeting();
        refresh(false);
        if (getCurrentFocus() == null || getCurrentFocus() == scroll) {
            focusKey(lastFocusedKey != null ? lastFocusedKey : firstAppKey());
        }
        handler.removeCallbacks(flipTick);
        if (flipMs > 0) handler.postDelayed(flipTick, flipMs);
    }

    /** Nothing ticks while an app or the screen is in front of Home. */
    @Override
    protected void onPause() {
        handler.removeCallbacks(flipTick);
        hideBigClock(false);
        super.onPause();
    }

    /** Home pressed while already home: back to the top, like the stock launcher. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideBigClock(false);
        if (movingKey != null) endMove();
        if (menu != null && menu.isShowing()) menu.dismiss();
        scroll.smoothScrollTo(0, 0);
        focusKey(firstAppKey());
    }

    @Override
    public void onBackPressed() {
        if (movingKey != null) endMove();
        // Otherwise nothing: Back on the home screen has nowhere to go.
    }

    // ---------------------------------------------------------------- views

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // No background here: the window's own black is the only full-screen fill (no overdraw).
        root.setPadding(dp(42), dp(22), dp(42), 0);
        root.setClipChildren(false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        greeting = new TextView(this);
        greeting.setTypeface(light);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        greeting.setTextColor(0xF2FFFFFF);
        greeting.setSingleLine(true);
        greeting.setEllipsize(TextUtils.TruncateAt.END);
        greeting.setIncludeFontPadding(false);
        greeting.setPadding(dp(12), 0, dp(16), 0);   // lines up with the tiles' left edge
        bar.addView(greeting, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // No Settings button: TV Settings and Android Settings are tiles.
        bar.addView(button(getString(R.string.inputs), R.drawable.ic_input, this::openInputs));
        btnWifi = button("Wi-Fi", R.drawable.ic_wifi, () -> start(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        bar.addView(btnWifi);
        // The ends of the top bar stop there instead of falling through to a tile.
        View first = bar.getChildAt(1), last = bar.getChildAt(bar.getChildCount() - 1);
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setPadding(0, dp(18), 0, dp(40));
        grid = new TileGrid(this);
        // Room for the focus lift on every side: a 4% zoom of the widest tile (about 470 px) reaches 9-10 px past
        // its edge, and with less margin than that the outline was clipped on the left and right.
        grid.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    /**
     * A small top-bar button: an icon and text, until focused, when it sits on a white pill and the icon and text
     * turn black. No glass.
     */
    private TextView button(String text, int iconRes, Runnable action) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(100));   // clamped to half the height: fully rounded ends
        TextView b = new TextView(this);
        b.setId(View.generateViewId());
        b.setText(text);
        b.setTypeface(medium);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMaxWidth(dp(220));
        b.setPadding(dp(12), dp(7), dp(16), dp(7));
        b.setGravity(Gravity.CENTER_VERTICAL);
        Drawable icon = getDrawable(iconRes).mutate();
        icon.setBounds(0, 0, dp(18), dp(18));
        b.setCompoundDrawablesRelative(icon, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        b.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        b.setBackground(null);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);   // see tileFrame
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, focused) -> {
            int ink = focused ? Color.BLACK : Color.WHITE;
            b.setBackground(focused ? pill : null);
            b.setTextColor(ink);
            b.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private View appTile(AppEntry e, TileSize size) {
        FrameLayout t = tileFrame(e.color);
        boolean labelled = size != TileSize.SMALL;
        int iconSize = dp(size == TileSize.SMALL ? 40 : size == TileSize.LARGE ? 112 : 72);
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(iconBitmap(e, iconSize));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        if (labelled) ilp.bottomMargin = dp(14);
        t.addView(icon, ilp);
        if (labelled) {
            TextView label = new TextView(this);
            label.setText(e.label);
            label.setTypeface(regular);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size == TileSize.LARGE ? 17 : 14);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            llp.leftMargin = dp(10);
            llp.rightMargin = dp(10);
            llp.bottomMargin = dp(8);
            t.addView(label, llp);
        }
        t.setOnClickListener(v -> launch(e));
        return t;
    }

    /**
     * The live clock tile. Its face turns over every flip interval: the time on the front, the day and full date
     * on the back. Only the face rotates, so the focus lift and outline on the tile itself are not disturbed.
     * OK opens the full-screen clock.
     */
    private View clockTile(TileSize size) {
        FrameLayout t = tileFrame(clockColor);
        t.setBackground(null);   // the face carries the colour, so the whole square turns over
        FrameLayout face = new FrameLayout(this);
        face.setBackgroundColor(clockColor);
        face.setCameraDistance(8000 * density);
        View front = clockFront(size), back = clockBack(size);
        back.setVisibility(View.INVISIBLE);
        face.addView(front, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        face.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        t.addView(face, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        clockFace = face;
        clockFront = front;
        clockBack = back;
        showingBack = false;
        t.setOnClickListener(v -> showBigClock());
        return t;
    }

    /** The back of the clock tile: the day's name large, the full date under it. */
    private View clockBack(TileSize size) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM | Gravity.START);
        col.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextClock day = new TextClock(this);
        day.setFormat12Hour("EEEE");
        day.setFormat24Hour("EEEE");
        day.setTypeface(light);
        day.setTextColor(Color.WHITE);
        day.setSingleLine(true);
        day.setIncludeFontPadding(false);
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                size == TileSize.LARGE ? 64 : size == TileSize.WIDE ? 48 : size == TileSize.MEDIUM ? 30 : 14);
        col.addView(day);
        if (size != TileSize.SMALL) {
            TextClock date = new TextClock(this);
            String format = size == TileSize.MEDIUM ? "d MMM yyyy" : "d MMMM yyyy";
            date.setFormat12Hour(format);
            date.setFormat24Hour(format);
            date.setTypeface(regular);
            date.setTextColor(0xE6FFFFFF);
            date.setSingleLine(true);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, size == TileSize.LARGE ? 22 : size == TileSize.WIDE ? 18 : 14);
            col.addView(date);
        }
        return col;
    }

    /** The front of the clock tile: the time, and the date on medium and larger tiles. TextClock updates itself. */
    private View clockFront(TileSize size) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM | Gravity.START);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        col.addView(timeRow(size == TileSize.SMALL ? 20 : size == TileSize.MEDIUM ? 48 : size == TileSize.WIDE ? 72 : 110,
                Gravity.START));

        if (size != TileSize.SMALL) {
            TextClock date = new TextClock(this);
            date.setFormat12Hour(size == TileSize.MEDIUM ? "EEE d MMM" : "EEEE, d MMMM");
            date.setFormat24Hour(size == TileSize.MEDIUM ? "EEE d MMM" : "EEEE, d MMMM");
            date.setTypeface(regular);
            date.setTextColor(0xE6FFFFFF);
            date.setSingleLine(true);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, size == TileSize.LARGE ? 22 : size == TileSize.WIDE ? 18 : 14);
            col.addView(date);
        }
        return col;
    }

    /**
     * The time in 12-hour form, "9:52" with a smaller "PM" beside it, whatever the TV's 24-hour setting. Both
     * TextClocks get their text before they are attached, so the row is never measured with an empty time.
     */
    private LinearLayout timeRow(float timeSp, int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity | Gravity.BOTTOM);
        row.setBaselineAligned(true);
        Calendar now = Calendar.getInstance();

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("h:mm");
        time.setText(android.text.format.DateFormat.format("h:mm", now));
        time.setTypeface(light);
        time.setTextColor(Color.WHITE);
        time.setIncludeFontPadding(false);
        time.setSingleLine(true);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSp);
        row.addView(time);

        TextClock ampm = new TextClock(this);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("a");
        ampm.setText(android.text.format.DateFormat.format("a", now));
        ampm.setAllCaps(true);
        ampm.setTypeface(regular);
        ampm.setTextColor(0xE6FFFFFF);
        ampm.setIncludeFontPadding(false);
        ampm.setSingleLine(true);
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11f, timeSp * 0.3f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(timeSp * 0.12f * density);
        row.addView(ampm, lp);
        return row;
    }

    /** Turns the clock tile over: a quarter turn away, swap sides, a quarter turn back (about 0.3 s in all). */
    private void flipClock() {
        View face = clockFace;
        if (face == null || !face.isAttachedToWindow() || bigClock != null || movingKey != null
                || (menu != null && menu.isShowing())) return;
        face.animate().rotationX(90f).setDuration(150).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (face != clockFace) return;   // the tiles were rebuilt meanwhile
                    showingBack = !showingBack;
                    clockFront.setVisibility(showingBack ? View.INVISIBLE : View.VISIBLE);
                    clockBack.setVisibility(showingBack ? View.VISIBLE : View.INVISIBLE);
                    face.setRotationX(-90f);
                    face.animate().rotationX(0f).setDuration(150).setInterpolator(new DecelerateInterpolator())
                            .start();
                }).start();
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int part = hour >= 5 && hour < 12 ? R.string.good_morning
                : hour >= 12 && hour < 17 ? R.string.good_afternoon
                : hour >= 17 && hour < 22 ? R.string.good_evening : R.string.good_night;
        String name = getString(R.string.greeting_name).trim();
        String text = name.isEmpty() ? getString(part) : getString(R.string.greeting_with_name, getString(part), name);
        if (!text.contentEquals(greeting.getText())) greeting.setText(text);
    }

    /** The full-screen clock: huge time, the date and the greeting on black. Any button closes it. */
    private void showBigClock() {
        if (bigClock != null) return;
        FrameLayout content = findViewById(android.R.id.content);
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        // Full-width lines with centred contents: TextClock fills in its text only once attached, so a line sized to
        // its (still empty) text would cut the time off.
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        body.addView(timeRow(150, Gravity.CENTER_HORIZONTAL), line);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTypeface(regular);
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        date.setSingleLine(true);
        body.addView(date, new LinearLayout.LayoutParams(line));

        TextView hello = new TextView(this);
        hello.setText(greeting.getText());
        hello.setTypeface(light);
        hello.setTextColor(0x99FFFFFF);
        hello.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        hello.setGravity(Gravity.CENTER_HORIZONTAL);
        hello.setSingleLine(true);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(line);
        hlp.topMargin = dp(18);
        body.addView(hello, hlp);

        overlay.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).start();
        bigClock = overlay;
        bigClockBody = body;
        handler.postDelayed(drift, 60_000);
    }

    private void hideBigClock(boolean animate) {
        if (bigClock == null) return;
        handler.removeCallbacks(drift);
        FrameLayout overlay = bigClock;
        bigClock = null;
        bigClockBody = null;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent == null) return;
        if (animate) overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> parent.removeView(overlay)).start();
        else parent.removeView(overlay);
        focusKey(LayoutStore.CLOCK);
    }

    /** A flat tile of one colour, with a white outline and a slight lift while focused. */
    private FrameLayout tileFrame(int color) {
        FrameLayout t = new FrameLayout(this);
        t.setBackgroundColor(color);
        t.setOutlineProvider(null);   // no shadow under the lifted tile
        t.setFocusable(true);
        // This firmware's mouse mode leaves Android in touch mode, where plain focusable views lose focus and the
        // outline disappears; a TV launcher keeps its focus whatever the mode.
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setLongClickable(true);
        t.setOnLongClickListener(v -> {
            showMenu((String) v.getTag());
            return true;
        });
        t.setOnFocusChangeListener((v, focused) -> {
            if (movingKey == null) v.setForeground(focused ? outline(3) : null);
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(110).start();
            v.setTranslationZ(focused ? dp(4) : 0);
            if (focused) lastFocusedKey = (String) v.getTag();
        });
        return t;
    }

    private Drawable outline(int widthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setStroke(dp(widthDp), Color.WHITE);
        return d;
    }

    // ---------------------------------------------------------------- apps and layout

    /** Reloads the app list; rebuilds the tiles when it changed (or when forced). */
    private void refresh(boolean force) {
        loadApps();
        String signature = new TreeSet<>(apps.keySet()).toString();
        if (!force && signature.equals(appsSignature) && grid.getChildCount() > 0) return;
        appsSignature = signature;
        reconcile();
        rebuildTiles(lastFocusedKey != null ? lastFocusedKey : firstAppKey());
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || !ai.exported) continue;
                String pkg = ai.packageName;
                if (pkg.equals(getPackageName()) || excluded.contains(pkg) || apps.containsKey(pkg)) continue;
                boolean leanback = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category);
                String stamp = ai.name + '|' + ai.applicationInfo.sourceDir;
                AppEntry cached = cache.get(pkg);
                if (cached != null && cached.leanback == leanback && stamp.equals(cached.stamp)) {
                    apps.put(pkg, cached);
                    continue;
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.stamp = stamp;
                e.component = new ComponentName(pkg, ai.name);
                e.leanback = leanback;
                String override = labelOverrides.get(pkg);
                e.label = override != null ? override : String.valueOf(ri.loadLabel(pm));
                Drawable own = ri.loadIcon(pm);
                Integer glyph = glyphs.get(pkg);
                e.icon = glyph != null ? getDrawable(glyph) : own;
                Integer color = colorOverrides.get(pkg);
                if (color != null) {
                    e.color = color;
                } else {
                    // The colour always comes from the app's own artwork, never from a white glyph.
                    Drawable art = ai.loadBanner(pm);
                    if (art == null) art = ai.applicationInfo.loadBanner(pm);
                    e.color = AppColors.sample(art != null ? art : own);
                }
                apps.put(pkg, e);
                cache.put(pkg, e);
            }
        }
        cache.keySet().retainAll(apps.keySet());
    }

    /** The app's icon rendered once into a bitmap of exactly this size (adaptive icons are masked only here). */
    private Bitmap iconBitmap(AppEntry e, int size) {
        Bitmap b = e.iconAt.get(size);
        if (b == null && e.icon != null) {
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Drawable d = e.icon.getConstantState() != null ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(b));
            e.iconAt.put(size, b);
        }
        return b;
    }

    /** The saved layout (or the default), minus apps that are gone or hidden, plus new apps at the end. */
    private void reconcile() {
        List<LayoutStore.Item> saved = store.load();
        if (saved == null) saved = LayoutStore.parse(getResources().getStringArray(R.array.default_layout));
        List<LayoutStore.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LayoutStore.Item it : saved) {
            boolean keep = LayoutStore.CLOCK.equals(it.key) || (apps.containsKey(it.key) && !hidden.contains(it.key));
            if (keep && seen.add(it.key)) out.add(it);
        }
        if (!seen.contains(LayoutStore.CLOCK)) out.add(0, new LayoutStore.Item(LayoutStore.CLOCK, TileSize.WIDE));
        List<AppEntry> fresh = new ArrayList<>();
        for (AppEntry e : apps.values()) if (!seen.contains(e.pkg) && !hidden.contains(e.pkg)) fresh.add(e);
        fresh.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        for (AppEntry e : fresh) out.add(new LayoutStore.Item(e.pkg, TileSize.MEDIUM));
        items = out;
        store.save(items);
    }

    private void rebuildTiles(String focus) {
        grid.removeAllViews();
        for (LayoutStore.Item it : items) {
            View tile = LayoutStore.CLOCK.equals(it.key) ? clockTile(it.size) : appTile(apps.get(it.key), it.size);
            tile.setTag(it.key);
            grid.addView(tile, new TileGrid.LayoutParams(it.size));
        }
        if (movingKey != null) markMoving();
        focusKey(focus);
    }

    private String firstAppKey() {
        for (LayoutStore.Item it : items) if (!LayoutStore.CLOCK.equals(it.key)) return it.key;
        return items.isEmpty() ? null : items.get(0).key;
    }

    private void focusKey(String key) {
        grid.post(() -> {
            View target = key == null ? null : grid.findViewWithTag(key);
            String first = firstAppKey();
            if (target == null && first != null) target = grid.findViewWithTag(first);
            if (target == null && grid.getChildCount() > 0) target = grid.getChildAt(0);
            if (target != null) target.requestFocus();
        });
    }

    private int indexOf(String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- actions

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    private void openInputs() {
        Intent i = new Intent();
        ComponentName c = ComponentName.unflattenFromString(getString(R.string.inputs_component));
        if (c != null) {
            i.setComponent(c);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        start(new Intent("com.android.tv.action.VIEW_INPUTS"));
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    private String networkName() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        if (caps == null) return getString(R.string.offline);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) return ssid.replace("\"", "");
            return "Wi-Fi";
        }
        return getString(R.string.online);
    }

    // ---------------------------------------------------------------- the tile menu

    private void showMenu(String key) {
        if (key == null || movingKey != null) return;
        int index = indexOf(key);
        if (index < 0) return;
        LayoutStore.Item item = items.get(index);
        boolean clock = LayoutStore.CLOCK.equals(key);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.move));
        actions.add(() -> startMove(key));
        for (TileSize s : TileSize.values()) {
            if (s == item.size || (clock && s == TileSize.SMALL)) continue;
            labels.add(getString(R.string.size_as, s.title));
            actions.add(() -> resize(key, s));
        }
        if (!clock) {
            labels.add(getString(R.string.hide));
            actions.add(() -> hide(key));
            labels.add(getString(R.string.app_info));
            actions.add(() -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + key))));
        }
        if (!hiddenInstalled().isEmpty()) {
            labels.add(getString(R.string.show_hidden, hiddenInstalled().size()));
            actions.add(this::showHidden);
        }
        labels.add(getString(R.string.reset_layout));
        actions.add(this::confirmReset);
        String title = clock ? getString(R.string.clock) : apps.get(key).label;
        menu = new AlertDialog.Builder(this, R.style.TilesDialog)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void resize(String key, TileSize size) {
        int i = indexOf(key);
        if (i < 0) return;
        items.get(i).size = size;
        store.save(items);
        rebuildTiles(key);
    }

    private void hide(String key) {
        int i = indexOf(key);
        if (i < 0) return;
        hidden.add(key);
        store.saveHidden(hidden);
        items.remove(i);
        store.save(items);
        rebuildTiles(items.isEmpty() ? null : items.get(Math.min(i, items.size() - 1)).key);
    }

    /** Hidden apps that are still installed, sorted by name. */
    private List<AppEntry> hiddenInstalled() {
        List<AppEntry> list = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String pkg : hidden) {
            Intent probe = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (probe == null) probe = pm.getLaunchIntentForPackage(pkg);
            if (probe == null || probe.getComponent() == null) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            String override = labelOverrides.get(pkg);
            try {
                e.label = override != null ? override : String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ex) {
                continue;
            }
            list.add(e);
        }
        list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private void showHidden() {
        List<AppEntry> list = hiddenInstalled();
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).label;
        menu = new AlertDialog.Builder(this, R.style.TilesDialog)
                .setTitle(R.string.show_hidden_title)
                .setItems(names, (d, which) -> {
                    String pkg = list.get(which).pkg;
                    hidden.remove(pkg);
                    store.saveHidden(hidden);
                    refresh(true);
                    focusKey(pkg);
                })
                .show();
    }

    private void confirmReset() {
        menu = new AlertDialog.Builder(this, R.style.TilesDialog)
                .setTitle(R.string.reset_layout)
                .setMessage(R.string.reset_question)
                .setPositiveButton(R.string.reset, (d, w) -> {
                    store.reset();
                    hidden.clear();
                    lastFocusedKey = null;
                    refresh(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- move mode

    private void startMove(String key) {
        movingKey = key;
        markMoving();
        focusKey(key);
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
    }

    /** The moving tile keeps a thick outline; the others dim, as on Windows Phone. */
    private void markMoving() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            boolean moving = movingKey != null && movingKey.equals(v.getTag());
            v.setAlpha(movingKey == null || moving ? 1f : 0.55f);
            v.setForeground(moving ? outline(5) : (v.isFocused() && movingKey == null ? outline(3) : null));
        }
    }

    private void endMove() {
        String key = movingKey;
        movingKey = null;
        store.save(items);
        markMoving();
        focusKey(key);
    }

    /** Left/Right: one place earlier/later. Up/Down: to the place of the tile above/below. */
    private void moveBy(int keyCode) {
        int from = indexOf(movingKey);
        if (from < 0) return;
        int to = from;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else to = verticalNeighbour(from, keyCode == KeyEvent.KEYCODE_DPAD_UP);
        if (to < 0 || to >= items.size() || to == from) return;
        LayoutStore.Item item = items.remove(from);
        items.add(to, item);
        rebuildTiles(movingKey);
    }

    /** The index of the nearest tile directly above (or below) tile i on the grid, or i when there is none. */
    private int verticalNeighbour(int i, boolean up) {
        Rect r = grid.cellAt(i);
        if (r == null) return i;
        int best = i, bestRow = up ? Integer.MIN_VALUE : Integer.MAX_VALUE, bestDx = Integer.MAX_VALUE;
        for (int j = 0; j < items.size(); j++) {
            Rect c = grid.cellAt(j);
            if (c == null || j == i) continue;
            boolean candidate = up ? c.bottom <= r.top : c.top >= r.bottom;
            if (!candidate) continue;
            int row = up ? c.bottom : c.top;
            int dx = Math.abs((c.left + c.right) - (r.left + r.right));
            boolean closerRow = up ? row > bestRow : row < bestRow;
            if (closerRow || (row == bestRow && dx < bestDx)) {
                best = j;
                bestRow = row;
                bestDx = dx;
            }
        }
        return best;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (bigClock != null) {
            // Any button closes the full-screen clock (on release, so the press doesn't reach a tile).
            if (event.getAction() == KeyEvent.ACTION_UP) hideBigClock(true);
            return true;
        }
        if (movingKey != null) {
            boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean done = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
            if (dpad || done) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (dpad) moveBy(code);
                    else endMove();
                }
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (f != null && f.getParent() == grid) {
                showMenu((String) f.getTag());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
