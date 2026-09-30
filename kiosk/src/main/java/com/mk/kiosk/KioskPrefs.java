package com.mk.kiosk;

import android.content.Context;
import android.content.SharedPreferences;

/** The library's own small store, kept apart from the host app's preferences. */
final class KioskPrefs {

    private static final String FILE = "com.mk.kiosk";
    private static final String KEY_HOME = "home_activity";
    private static final String KEY_AWAKE = "keep_awake";
    private static final String KEY_PROVISIONED = "provisioned";
    private static final String KEY_EXTRAS = "provisioning_extras";
    private static final String KEY_PW_HASH = "master_hash";
    private static final String KEY_PW_SALT = "master_salt";
    private static final String KEY_ENABLED = "kiosk_enabled";
    private static final String KEY_STATUS = "status_overlay";
    private static final String KEY_HOME_APP = "become_home";
    private static final String KEY_ASSIST = "block_assistant";
    private static final String KEY_WIFI_TAP = "wifi_tap";

    private final SharedPreferences sp;

    private KioskPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static KioskPrefs get(Context ctx) {
        return new KioskPrefs(ctx);
    }

    String homeActivity() { return sp.getString(KEY_HOME, ""); }
    void setHomeActivity(String name) { sp.edit().putString(KEY_HOME, name).apply(); }

    boolean keepAwake() { return sp.getBoolean(KEY_AWAKE, false); }
    void setKeepAwake(boolean on) { sp.edit().putBoolean(KEY_AWAKE, on).apply(); }

    /** Whether the kiosk is switched on. Survives restarts, unlike a maintenance unlock. */
    boolean enabled() { return sp.getBoolean(KEY_ENABLED, true); }
    void setEnabled(boolean on) { sp.edit().putBoolean(KEY_ENABLED, on).apply(); }

    /** On by default: locking the device hides the system status bar, so
     *  without this there is no battery or signal reading at all. */
    boolean statusOverlay() { return sp.getBoolean(KEY_STATUS, true); }
    void setStatusOverlay(boolean on) { sp.edit().putBoolean(KEY_STATUS, on).apply(); }

    /** Whether the app takes over the Home button. On by default. */
    boolean becomeHome() { return sp.getBoolean(KEY_HOME_APP, true); }
    void setBecomeHome(boolean on) { sp.edit().putBoolean(KEY_HOME_APP, on).apply(); }

    /** Tapping the Wi-Fi icon opens Android's Wi-Fi settings. On by default. */
    boolean wifiTap() { return sp.getBoolean(KEY_WIFI_TAP, true); }
    void setWifiTap(boolean on) { sp.edit().putBoolean(KEY_WIFI_TAP, on).apply(); }

    /** The QR's configuration bundle, as JSON. Empty when none was sent. */
    String provisioningExtras() { return sp.getString(KEY_EXTRAS, ""); }
    void setProvisioningExtras(String json) {
        sp.edit().putString(KEY_EXTRAS, json).apply();
    }

    /** Whether the voice assistant is shut off. On by default: it is a way out. */
    boolean blockAssistant() { return sp.getBoolean(KEY_ASSIST, true); }
    void setBlockAssistant(boolean on) { sp.edit().putBoolean(KEY_ASSIST, on).apply(); }

    boolean provisioned() { return sp.getBoolean(KEY_PROVISIONED, false); }
    void setProvisioned(boolean v) { sp.edit().putBoolean(KEY_PROVISIONED, v).apply(); }

    boolean hasPassword() { return !sp.getString(KEY_PW_HASH, "").isEmpty(); }
    String passwordHash() { return sp.getString(KEY_PW_HASH, ""); }
    String passwordSalt() { return sp.getString(KEY_PW_SALT, ""); }

    void setPassword(String hash, String salt) {
        sp.edit().putString(KEY_PW_HASH, hash).putString(KEY_PW_SALT, salt).apply();
    }
}
