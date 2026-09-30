package com.mk.kiosk;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Recovers a Wi-Fi link that has gone stale.
 *
 * A device was found showing full signal and a valid address, with Android
 * reporting the network as validated, while resolving no hostname at all and
 * unable to ping its own gateway. Private DNS was off. The association had gone
 * stale overnight and nothing on the device noticed — the app simply sat there
 * "offline" until somebody turned Wi-Fi off and on by hand.
 *
 * Turning the radio off and on clears it. A device owner is still permitted to
 * do that; ordinary apps targeting Android 10 or newer are not, so this is only
 * useful in exactly the situation this library creates.
 */
public final class KioskNetwork {

    private KioskNetwork() { }

    /** At most one attempt in this window, so a real outage is not hammered. */
    private static final long COOLDOWN_MS = 5 * 60 * 1000L;
    private static final int MAX_TRIES = 3;

    private static long lastTry = 0;
    private static int tries = 0;

    public interface Result {
        void onDone(boolean ok, String message);
    }

    /** Call after a successful request, so a later failure starts fresh. */
    public static void noteHealthy() {
        tries = 0;
    }

    /**
     * True when a failure message looks like the link is stale rather than the
     * network being genuinely down. Name resolution is the tell: a real outage
     * usually fails to connect, this fails to even look the name up.
     */
    public static boolean looksStale(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("resolve host")
                || m.contains("no address associated")
                || m.contains("unable to resolve")
                || m.contains("name not resolved")
                || m.contains("err_name");
    }

    /**
     * Bounces Wi-Fi. Backs off on its own, so this can be called from an error
     * handler without any bookkeeping in the app.
     *
     * @return false when the attempt was skipped by the back-off
     */
    public static boolean reconnectIfStale(Context ctx, String failureMessage, Result then) {
        if (!looksStale(failureMessage)) return false;

        long now = System.currentTimeMillis();
        if (now - lastTry < COOLDOWN_MS) return false;
        if (tries >= MAX_TRIES) return false;
        tries++;
        lastTry = now;
        reconnect(ctx, then);
        return true;
    }

    /** Bounces Wi-Fi now, ignoring the back-off. For a button the owner pressed. */
    public static void reconnect(final Context context, final Result then) {
        final Context ctx = context.getApplicationContext();
        final WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);

        if (wifi == null) {
            done(then, false, "No Wi-Fi on this device");
            return;
        }
        if (!Kiosk.isDeviceOwner(ctx)) {
            done(then, false, "Needs device owner — use Wi-Fi settings instead");
            return;
        }

        boolean off;
        try {
            off = wifi.setWifiEnabled(false);
        } catch (Exception e) {
            off = false;
        }
        if (!off) {
            done(then, false, "Android would not let the app switch Wi-Fi");
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean on;
            try {
                on = wifi.setWifiEnabled(true);
            } catch (Exception e) {
                on = false;
            }
            done(then, on, on ? "Wi-Fi reconnected"
                              : "Wi-Fi switched off but would not come back on");
        }, 3000);
    }

    private static void done(Result then, boolean ok, String message) {
        if (then != null) new Handler(Looper.getMainLooper()).post(() -> then.onDone(ok, message));
    }
}
