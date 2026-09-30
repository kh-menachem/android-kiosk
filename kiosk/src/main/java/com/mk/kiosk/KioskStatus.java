package com.mk.kiosk;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Battery and Wi-Fi, shown by the library rather than by every app.
 *
 * <p>Locking a device into an app hides the system status bar, which takes the
 * battery and signal indicators with it. On a wall-mounted or handed-around
 * tablet those are the two things people actually need to see, so the library
 * puts them back instead of leaving each app to build its own.
 *
 * <pre>
 *   Kiosk.showStatusOverlay(this, true);
 * </pre>
 *
 * <p>The strip floats above the app in a corner. It is not clickable and does
 * not take part in layout, so it cannot push an app's own views around or
 * swallow a touch meant for them.
 *
 * <p>Signal strength only, never the network name: reading the SSID needs
 * location permission, and a status strip is not worth asking a kiosk user for
 * that.
 */
public final class KioskStatus {

    private KioskStatus() { }

    /** Which corner the strip sits in. */
    public enum Position { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    /**
     * How the strip is coloured.
     *
     * <p>{@link #AUTO} is the default and takes the host app's own theme, so
     * the strip looks like part of the app rather than something bolted on.
     */
    public enum Style {
        /** Follow the app's theme: its background behind its text colour. */
        AUTO,
        /** No chip at all; icons tinted from the theme, drawn straight on the app. */
        TRANSPARENT,
        /** Force a dark chip with light icons. */
        DARK,
        /** Force a light chip with dark icons. */
        LIGHT
    }

    private static Position position = Position.TOP_RIGHT;
    private static Style style = Style.AUTO;
    private static Integer customFg;
    private static Integer customChip;

    /**
     * What tapping the Wi-Fi icon does, when the host app wants something other
     * than Android's Wi-Fi screen.
     *
     * <p>An app that deliberately keeps Settings off the lock-task allow-list -
     * a launcher, say, where Settings is a way out - can point this at its own
     * Wi-Fi screen instead. Null means the default.
     */
    private static Runnable wifiAction;

    private static KioskStatusView strip;
    private static BroadcastReceiver watcher;
    private static Activity host;

    /** Replaces what the Wi-Fi icon opens. Null restores Android's own screen. */
    public static void wifiAction(Runnable action) {
        wifiAction = action;
    }

    /** Moves the strip. Call before showing it. */
    public static void position(Position p) {
        position = (p == null) ? Position.TOP_RIGHT : p;
    }

    /** Chooses how it is coloured. Default is {@link Style#AUTO}. */
    public static void style(Style s) {
        style = (s == null) ? Style.AUTO : s;
        customFg = null;
        customChip = null;
    }

    /**
     * Exact colours, when the app's theme is not the whole story - a WebView
     * with its own palette, say, or a branded header the strip sits on.
     *
     * @param chip pass a fully transparent colour for no chip at all
     */
    public static void colours(int foreground, int chip) {
        customFg = foreground;
        customChip = chip;
    }

    /** Adds the strip to this activity, or takes it away again. */
    public static void attach(final Activity activity, boolean on) {
        if (!on) {
            detach(activity);
            return;
        }
        if (strip != null && host == activity) {
            paint(activity);
            return;
        }
        detach(host);

        host = activity;
        strip = new KioskStatusView(activity, 13f);
        applyColours(activity, strip);
        int pad = (int) (7 * activity.getResources().getDisplayMetrics().density);
        strip.setPadding(pad + pad / 2, pad, pad + pad / 2, pad);
        // A status read-out should never eat a touch aimed at the app, so the
        // strip stays unclickable and the listener below consumes only the
        // touches that land on the Wi-Fi icon. Everything else returns false
        // and falls through to whatever the app has underneath.
        strip.setClickable(false);
        strip.setFocusable(false);

        strip.setOnTouchListener((v, e) -> {
            if (!KioskPrefs.get(activity).wifiTap()) return false;
            if (!strip.isInWifiIcon(e.getX(), e.getY())) {
                if (strip.wifiPressed) {
                    strip.wifiPressed = false;
                    strip.invalidate();
                }
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    strip.wifiPressed = true;
                    strip.invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean was = strip.wifiPressed;
                    strip.wifiPressed = false;
                    strip.invalidate();
                    // Deliberately no password: a user who cannot get the
                    // device back online cannot do anything at all, and the
                    // kiosk allows only Settings through while they are there.
                    if (was) {
                        if (wifiAction != null) wifiAction.run();
                        else Kiosk.openWifiSettings(activity);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    strip.wifiPressed = false;
                    strip.invalidate();
                    return true;
                default:
                    return true;
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravityFor(position);
        lp.setMargins(pad, pad, pad, pad);

        View content = activity.findViewById(android.R.id.content);
        if (content instanceof FrameLayout) {
            ((FrameLayout) content).addView(strip, lp);
        } else {
            strip = null;                               // nothing sensible to add to
            host = null;
            return;
        }

        watcher = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { paint(activity); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(WifiManager.RSSI_CHANGED_ACTION);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        activity.registerReceiver(watcher, f);

        paint(activity);
    }

    /** Removes the strip and stops listening. Safe to call when nothing is showing. */
    public static void detach(Activity activity) {
        if (watcher != null && activity != null) {
            try {
                activity.unregisterReceiver(watcher);
            } catch (Exception ignored) {
                // already gone; nothing to undo
            }
        }
        watcher = null;

        if (strip != null && strip.getParent() instanceof ViewGroup) {
            ((ViewGroup) strip.getParent()).removeView(strip);
        }
        strip = null;
        host = null;
    }

    /* ------------------------------------------------------------------ */

    /**
     * Works out what the strip should look like in this app.
     *
     * Reads the activity's own theme rather than guessing: colorBackground for
     * the chip and textColorPrimary for the icons. An app with a light theme
     * gets dark icons on a light chip and a dark app gets the reverse, without
     * anyone configuring anything.
     */
    private static void applyColours(Activity activity, KioskStatusView v) {
        int fg, chip;

        if (customFg != null && customChip != null) {
            fg = customFg;
            chip = customChip;
        } else {
            switch (style) {
                case DARK:
                    chip = 0xEE202020; fg = 0xFFFFFFFF;
                    break;
                case LIGHT:
                    chip = 0xEEF2F2F2; fg = 0xFF1A1A1A;
                    break;
                case TRANSPARENT:
                    chip = themeColour(activity, android.R.attr.colorBackground, 0xFF000000);
                    fg = themeColour(activity, android.R.attr.textColorPrimary,
                            KioskStatusView.luminance(chip) > 0.55f ? 0xFF1A1A1A : 0xFFFFFFFF);
                    chip = chip & 0x00FFFFFF;        // keep the hue, drop the chip
                    break;
                default:
                    chip = themeColour(activity, android.R.attr.colorBackground, 0xFF202020);
                    fg = themeColour(activity, android.R.attr.textColorPrimary,
                            KioskStatusView.luminance(chip) > 0.55f ? 0xFF1A1A1A : 0xFFFFFFFF);

                    // A chip the same colour as the app behind it would vanish;
                    // nudge it away from the background so the strip still reads
                    // as a distinct thing without fighting the app's palette.
                    chip = shade(chip, KioskStatusView.luminance(chip) > 0.55f ? -0.07f : 0.10f);
                    chip = (chip & 0x00FFFFFF) | 0xF0000000;
                    break;
            }
        }

        // If the two ended up too close to tell apart - an odd theme, or a
        // custom pair chosen carelessly - fall back to something that works
        // rather than drawing an unreadable strip.
        if (Math.abs(KioskStatusView.luminance(fg) - KioskStatusView.luminance(chip)) < 0.25f
                && (chip >>> 24) != 0) {
            fg = KioskStatusView.luminance(chip) > 0.55f ? 0xFF1A1A1A : 0xFFFFFFFF;
        }

        v.colours(fg, (chip >>> 24) == 0 ? pickKnockout(fg) : chip);
        v.setBackgroundColor(chip);
    }

    /**
     * With no chip there is nothing to knock the bolt and digits out of, so use
     * the opposite of the foreground - it is what the app behind is most likely
     * to be.
     */
    private static int pickKnockout(int fg) {
        return KioskStatusView.luminance(fg) > 0.55f ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Lightens (positive) or darkens (negative) by a fraction. */
    private static int shade(int c, float amount) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        if (amount >= 0) {
            r += (255 - r) * amount; g += (255 - g) * amount; b += (255 - b) * amount;
        } else {
            r *= (1 + amount); g *= (1 + amount); b *= (1 + amount);
        }
        return Color.argb(Color.alpha(c),
                Math.max(0, Math.min(255, (int) r)),
                Math.max(0, Math.min(255, (int) g)),
                Math.max(0, Math.min(255, (int) b)));
    }

    /**
     * One colour out of the theme. The attribute may be a plain colour or a
     * reference to a ColorStateList - textColorPrimary usually is - so both
     * have to be handled or the common case falls back to the default.
     */
    private static int themeColour(Activity activity, int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (!activity.getTheme().resolveAttribute(attr, tv, true)) return fallback;

            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                ColorStateList csl = activity.getResources()
                        .getColorStateList(tv.resourceId, activity.getTheme());
                if (csl != null) return csl.getDefaultColor();
            }
        } catch (Exception ignored) {
            // an app with an unusual theme; the fallback is still readable
        }
        return fallback;
    }


    private static void paint(Activity activity) {
        if (strip == null) return;
        readBattery(activity, strip);
        readNetwork(activity, strip);
        strip.requestLayout();     // the percentage changes the measured width
        strip.invalidate();
    }

    private static void readBattery(Context ctx, KioskStatusView v) {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) {
            v.percent = -1;
            return;
        }
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        v.percent = (level < 0 || scale <= 0) ? -1 : Math.round(level * 100f / scale);

        int state = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        v.charging = state == BatteryManager.BATTERY_STATUS_CHARGING
                || state == BatteryManager.BATTERY_STATUS_FULL;
    }

    private static void readNetwork(Context ctx, KioskStatusView v) {
        v.otherTransport = false;
        v.noInternet = false;
        v.wifiLevel = -1;

        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkCapabilities caps = null;
        try {
            caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        } catch (Exception ignored) { }
        if (caps == null) return;

        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        v.noInternet = !validated;

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            v.wifiLevel = wifiLevel(ctx);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            v.otherTransport = true;
        }
    }

    /** 0..4, or -1 when the signal cannot be read. */
    private static int wifiLevel(Context ctx) {
        try {
            WifiManager w = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (w == null) return -1;
            int rssi = w.getConnectionInfo().getRssi();
            if (Build.VERSION.SDK_INT >= 30) {
                int max = Math.max(1, w.getMaxSignalLevel());
                return Math.round(w.calculateSignalLevel(rssi) * 4f / max);
            }
            return WifiManager.calculateSignalLevel(rssi, 5);   // 0..4
        } catch (Exception e) {
            return -1;
        }
    }

    private static int gravityFor(Position p) {
        switch (p) {
            case TOP_LEFT:     return Gravity.TOP | Gravity.START;
            case BOTTOM_RIGHT: return Gravity.BOTTOM | Gravity.END;
            case BOTTOM_LEFT:  return Gravity.BOTTOM | Gravity.START;
            default:           return Gravity.TOP | Gravity.END;
        }
    }
}
