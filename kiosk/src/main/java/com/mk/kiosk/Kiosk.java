package com.mk.kiosk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;

import java.util.Collections;

/**
 * Locks an Android device into a single app.
 *
 * One line in the activity that should own the device:
 *
 * <pre>
 *   protected void onCreate(Bundle b) {
 *       super.onCreate(b);
 *       setContentView(R.layout.main);
 *       Kiosk.attach(this);
 *   }
 * </pre>
 *
 * From then on the device stays in that app: Home and Recents stop working, the
 * status bar is hidden, the app becomes Home so a reboot lands back in it, and
 * the system is told not to force-stop it. Nothing else is required — no
 * buttons, no settings screen, no code in onResume.
 *
 * <p>The way back into the controls is seven taps in the top-left corner, which
 * asks for the master password. That works in every app using this library, so
 * there is nothing to wire up and nothing app-specific to remember.
 *
 * <h3>What this cannot do</h3>
 * Android does not let an app make itself device owner. That is granted once per
 * device, on a factory-fresh device, by adb / QR / NFC provisioning. Until it is
 * granted, {@link #attach} is harmless: the app runs normally and
 * {@link Status#isDeviceOwner} is false. Everything in this class is written to
 * degrade quietly rather than crash on a device that was never provisioned.
 */
public final class Kiosk {

    private Kiosk() { }

    /** Minutes a maintenance unlock survives if the app is never reopened. */
    private static final long MAINTENANCE_WINDOW_MS = 15 * 60 * 1000L;

    private static long suspendedUntil = 0L;
    private static boolean wentAwayWhileUnlocked = false;
    private static Class<? extends Activity> homeActivity;
    private static boolean lifecycleHooked = false;
    private static boolean askedForPassword = false;

    /* ------------------------------------------------------------------ */
    /*  the one call a host app needs                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Makes this activity the kiosk. Call once, from its {@code onCreate}.
     *
     * Registers the lifecycle hooks that re-apply the lock, so the host app
     * never has to touch onResume or onStop itself.
     */
    public static void attach(final Activity activity) {
        homeActivity = activity.getClass();
        KioskPrefs.get(activity).setHomeActivity(activity.getClass().getName());

        if (!lifecycleHooked) {
            lifecycleHooked = true;
            hookLifecycle(activity.getApplication());
        }

        // The way back in, identical in every app that uses this: seven taps in
        // the top-left corner. Without it an app would have to build its own
        // trigger, and a device could be locked with no route to the controls.
        KioskGesture.install(activity);

        // A kiosk switched off stays off across restarts. Only the maintenance
        // unlock is temporary; this is the deliberate "leave it alone" state.
        if (KioskPrefs.get(activity).enabled()) apply(activity);

        if (KioskPrefs.get(activity).statusOverlay()) {
            KioskStatus.attach(activity, true);
        }

        // First run on this device: choose the master password before anyone
        // has a chance to need it. The dialog cannot be dismissed, so a device
        // can never end up locked into an app whose controls have no key.
        if (!KioskPassword.isSet(activity) && !askedForPassword) {
            askedForPassword = true;
            activity.getWindow().getDecorView().post(
                    () -> KioskPassword.choose(activity, null));
        }
    }

    private static void hookLifecycle(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }

            @Override
            public void onActivityResumed(Activity a) {
                if (!isOurs(a)) return;
                if (!KioskPrefs.get(a).enabled()) return;
                // A maintenance unlock is for stepping out to Android and
                // nothing more, so coming back ends it. Staying in the app just
                // after unlocking does not: that resume is lock-task letting go.
                if (isSuspended() && !wentAwayWhileUnlocked) return;
                wentAwayWhileUnlocked = false;
                apply(a);
            }

            @Override
            public void onActivityStopped(Activity a) {
                if (!isOurs(a)) return;
                // genuinely left the app; the next return re-locks it
                if (isSuspended()) wentAwayWhileUnlocked = true;
            }

            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override
            public void onActivityDestroyed(Activity a) {
                // the strip holds a receiver; it must not outlive the activity
                if (isOurs(a)) KioskStatus.detach(a);
            }

            private boolean isOurs(Activity a) {
                return homeActivity != null && homeActivity.isInstance(a);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  locking                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Applies every part of the kiosk. Safe to call repeatedly; each piece is
     * attempted on its own so an OEM refusing one does not lose the rest.
     */
    public static void apply(Activity activity) {
        suspendedUntil = 0L;
        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);

        if (isDeviceOwner(ctx) && d != null) {
            ComponentName admin = KioskAdminReceiver.componentName(ctx);
            try {
                d.setLockTaskPackages(admin, new String[]{ctx.getPackageName()});
                // Home, Recents and notifications stay blocked, but the power
                // menu is deliberately allowed — without it the device cannot be
                // shut down or restarted at all.
                d.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);

                // A voice assistant answers a hardware key and a wake word,
                // neither of which lock task governs, and will then open apps
                // and search the web on request. Hiding apps does not catch it:
                // it has no launcher icon, so a sweep over launchable packages
                // never sees it.
                if (KioskPrefs.get(ctx).blockAssistant()) KioskAssistants.block(ctx, true);

                // The app becomes Home, so a reboot lands straight back in it and
                // the Home button cannot be used to leave. Switch it off when the
                // device should still have a normal launcher.
                if (homeActivity != null && KioskPrefs.get(ctx).becomeHome()) {
                    IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addCategory(Intent.CATEGORY_DEFAULT);
                    d.addPersistentPreferredActivity(admin, home, homeComponent(ctx));
                } else if (homeActivity != null) {
                    try {
                        d.clearPackagePersistentPreferredActivities(admin, ctx.getPackageName());
                    } catch (Exception ignored) { }
                }
                setHomeAliasEnabled(ctx, KioskPrefs.get(ctx).becomeHome());

                d.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        String.valueOf(BatteryManager.BATTERY_PLUGGED_AC
                                | BatteryManager.BATTERY_PLUGGED_USB
                                | BatteryManager.BATTERY_PLUGGED_WIRELESS));
                d.setStatusBarDisabled(admin, true);
                d.setKeyguardDisabled(admin, true);

                // Samsung force-stops apps overnight, which kills the keep-alive
                // service with them. A device owner can mark its own package as
                // one the system must leave running.
                if (Build.VERSION.SDK_INT >= 30) {
                    d.setUserControlDisabledPackages(admin,
                            Collections.singletonList(ctx.getPackageName()));
                }
            } catch (Exception ignored) {
                // an OEM may refuse one of these; the lock below still applies
            }
        }

        KioskService.start(ctx);
        KioskWatchdogJob.schedule(ctx);

        try {
            if (!isLocked(ctx)) activity.startLockTask();
        } catch (Exception ignored) {
            // not device owner: Android would only offer screen pinning, which
            // this library deliberately does not pretend to be
        }
    }

    /**
     * Unlocks for maintenance: Home and Recents work again so you can reach
     * Android itself.
     *
     * The unlock lasts only until you come back to the app — returning to it
     * re-locks, as does {@link #resume}. It also expires on its own after
     * fifteen minutes, so a device can never be left open by forgetting.
     */
    public static void suspend(Activity activity) {
        suspendedUntil = System.currentTimeMillis() + MAINTENANCE_WINDOW_MS;
        wentAwayWhileUnlocked = false;
        relax(activity);
    }

    /**
     * Switches the kiosk off and leaves it off, while keeping device owner.
     *
     * This is the reversible way out, and the one to reach for. The device
     * behaves normally until {@link #enable} turns the kiosk back on, and it
     * stays off across reboots. Because device owner is untouched, turning it
     * back on is a button press rather than a factory reset.
     */
    public static void disable(Activity activity) {
        KioskPrefs.get(activity).setEnabled(false);
        suspendedUntil = 0L;
        wentAwayWhileUnlocked = false;
        relax(activity);
    }

    /** Switches the kiosk back on after {@link #disable}. */
    public static void enable(Activity activity) {
        KioskPrefs.get(activity).setEnabled(true);
        suspendedUntil = 0L;
        wentAwayWhileUnlocked = false;
        apply(activity);
    }

    /** True when the kiosk is switched on, whatever it is doing right now. */
    public static boolean isEnabled(Context ctx) {
        return KioskPrefs.get(ctx).enabled();
    }

    /** Undoes the policies and lets go of the lock, without deciding for how long. */
    private static void relax(Activity activity) {
        Context ctx = activity.getApplicationContext();

        // the assistant comes back with everything else
        KioskAssistants.block(ctx, false);

        KioskWatchdogJob.cancel(ctx);
        KioskService.stop(ctx);

        DevicePolicyManager d = dpm(ctx);
        if (isDeviceOwner(ctx) && d != null) {
            ComponentName admin = KioskAdminReceiver.componentName(ctx);
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    d.setUserControlDisabledPackages(admin, Collections.<String>emptyList());
                } catch (Exception ignored) { }
            }
            try {
                d.setStatusBarDisabled(admin, false);
                d.setKeyguardDisabled(admin, false);
                d.clearPackagePersistentPreferredActivities(admin, ctx.getPackageName());
            } catch (Exception ignored) { }
        }
        try {
            activity.stopLockTask();
        } catch (Exception ignored) { }
    }

    /** Ends a maintenance unlock immediately, and switches the kiosk on if it was off. */
    public static void resume(Activity activity) {
        KioskPrefs.get(activity).setEnabled(true);
        suspendedUntil = 0L;
        wentAwayWhileUnlocked = false;
        apply(activity);
    }

    /**
     * Hands device-owner status back and leaves the device completely normal.
     *
     * This is the way out without a factory reset. It cannot be undone from the
     * app: granting device owner again needs provisioning on a reset device.
     */
    public static void release(Activity activity) {
        KioskPrefs.get(activity).setEnabled(false);
        relax(activity);
        suspendedUntil = 0L;
        Context ctx = activity.getApplicationContext();
        try {
            DevicePolicyManager d = dpm(ctx);
            if (d != null) d.clearDeviceOwnerApp(ctx.getPackageName());
        } catch (Exception ignored) { }
    }

    /* ------------------------------------------------------------------ */
    /*  odds and ends worth having on a dedicated device                   */
    /* ------------------------------------------------------------------ */

    /**
     * Opens Android's Wi-Fi settings from inside a locked app, by letting
     * Settings through the lock-task allowlist just long enough. Returning to
     * the app closes the door again, because {@link #apply} resets the list.
     */
    public static void openWifiSettings(Activity activity) {
        Context ctx = activity.getApplicationContext();
        if (isDeviceOwner(ctx)) {
            try {
                DevicePolicyManager d = dpm(ctx);
                if (d != null) {
                    d.setLockTaskPackages(KioskAdminReceiver.componentName(ctx),
                            new String[]{ctx.getPackageName(), "com.android.settings"});
                }
            } catch (Exception ignored) { }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    /**
     * Shuts off the voice assistant, or lets it back.
     *
     * <p>On by default. Bixby, Assistant and the like are reachable by hardware
     * key and wake word regardless of lock task, and will open other apps on
     * request - so a device can be locked into one app and still be wide open.
     */
    public static void blockAssistant(Activity activity, boolean on) {
        KioskPrefs.get(activity).setBlockAssistant(on);
        KioskAssistants.block(activity.getApplicationContext(), on);
    }

    /**
     * Whether pressing Home returns to this app.
     *
     * On by default, and it is what makes a reboot land back in the kiosk. Turn
     * it off and the device keeps its normal launcher: Home leaves the app,
     * and a reboot goes to the launcher rather than here. Lock task still stops
     * Home while the device is actually locked, so this mainly decides what
     * happens when the kiosk is unlocked or switched off.
     */
    public static void setBecomeHome(Activity activity, boolean on) {
        KioskPrefs.get(activity).setBecomeHome(on);
        Context ctx = activity.getApplicationContext();
        DevicePolicyManager d = dpm(ctx);
        if (!on && isDeviceOwner(ctx) && d != null) {
            try {
                d.clearPackagePersistentPreferredActivities(
                        KioskAdminReceiver.componentName(ctx), ctx.getPackageName());
            } catch (Exception ignored) { }
        }
        setHomeAliasEnabled(ctx, on);
        if (on && KioskPrefs.get(ctx).enabled()) apply(activity);
    }

    /** The alias a host declares to make itself switchable as Home. */
    public static final String HOME_ALIAS = ".KioskHomeAlias";

    /**
     * Whichever component actually answers CATEGORY_HOME: the alias when the
     * app declares one, otherwise the activity itself.
     *
     * Pointing the preference at a component that does not handle HOME would
     * leave the device with no working Home at all, so this has to follow
     * wherever the filter really lives.
     */
    private static ComponentName homeComponent(Context ctx) {
        ComponentName alias = new ComponentName(ctx, ctx.getPackageName() + HOME_ALIAS);
        try {
            ctx.getPackageManager().getActivityInfo(alias,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            return alias;
        } catch (Exception notDeclared) {
            return new ComponentName(ctx, homeActivity);
        }
    }

    /**
     * Turns the host's Home alias on or off.
     *
     * Clearing the device-owner preference is not enough on its own: an
     * intent filter in the manifest cannot be revoked at runtime, so the app
     * stays a registered Home candidate and the system can carry on choosing
     * it. Observed on a Samsung running Android 13, where Home kept resolving
     * to the kiosk after the policy had been cleared.
     *
     * A component, unlike a filter, can be switched off. So an app that wants
     * Home to be a runtime choice puts CATEGORY_HOME on an activity-alias
     * named {@code <package>.KioskHomeAlias} rather than on the activity
     * itself, and this disables it. Apps that declare no such alias are
     * unaffected - nothing here fails if it is absent.
     */
    private static void setHomeAliasEnabled(Context ctx, boolean on) {
        ComponentName alias = new ComponentName(ctx, ctx.getPackageName() + HOME_ALIAS);

        // setUserControlDisabledPackages marks this package protected, and the
        // package manager then refuses to enable or disable any component in it
        // - "Cannot disable a protected package". The two features collide, so
        // lift the protection for the moment it takes to flip the alias and put
        // it straight back.
        DevicePolicyManager d = dpm(ctx);
        boolean protectedPackage = Build.VERSION.SDK_INT >= 30
                && isDeviceOwner(ctx) && d != null && KioskPrefs.get(ctx).enabled();
        ComponentName admin = protectedPackage ? KioskAdminReceiver.componentName(ctx) : null;

        if (protectedPackage) {
            try {
                d.setUserControlDisabledPackages(admin, Collections.<String>emptyList());
            } catch (Exception e) {
                protectedPackage = false;      // nothing was lifted, nothing to restore
            }
        }
        try {
            ctx.getPackageManager().setComponentEnabledSetting(alias,
                    on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
            // no alias declared, which is fine - the app simply is not switchable
        } finally {
            if (protectedPackage) {
                try {
                    d.setUserControlDisabledPackages(admin,
                            Collections.singletonList(ctx.getPackageName()));
                } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Shows battery and Wi-Fi in a corner, since locking the device hides the
     * system status bar. Remembered across restarts.
     */
    public static void showStatusOverlay(Activity activity, boolean on) {
        KioskPrefs.get(activity).setStatusOverlay(on);
        KioskStatus.attach(activity, on);
    }

    /** Where the status strip sits. Call before {@link #showStatusOverlay}. */
    public static void statusOverlayPosition(KioskStatus.Position p) {
        KioskStatus.position(p);
    }

    /**
     * How the status strip is coloured. The default follows the host app's
     * theme, so it looks like part of the app rather than something bolted on.
     */
    public static void statusOverlayStyle(KioskStatus.Style s) {
        KioskStatus.style(s);
    }

    /**
     * Whether tapping the Wi-Fi icon opens Android's Wi-Fi settings.
     *
     * On by default, and deliberately not behind the master password: a device
     * that has fallen off the network is useless until somebody can put it
     * back on, and that somebody is rarely the person holding the password.
     * Lock task still confines them to Settings and this app.
     */
    public static void setWifiIconOpensSettings(Activity activity, boolean on) {
        KioskPrefs.get(activity).setWifiTap(on);
    }

    /**
     * Replaces what tapping the Wi-Fi icon opens.
     *
     * <p>For an app that keeps Settings off the allow-list on purpose and has a
     * Wi-Fi screen of its own. Null restores Android's.
     */
    public static void statusOverlayWifiAction(Runnable action) {
        KioskStatus.wifiAction(action);
    }

    /** Exact strip colours, when the theme is not the whole story. */
    public static void statusOverlayColours(int foreground, int chip) {
        KioskStatus.colours(foreground, chip);
    }

    /** Keeps the screen on while the app is in front. */
    public static void keepScreenOn(Activity activity, boolean on) {
        KioskPrefs.get(activity).setKeepAwake(on);
        if (on) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Re-applies the saved keep-awake choice; call from onResume if you use it. */
    public static void applyKeepScreenOn(Activity activity) {
        keepScreenOn(activity, KioskPrefs.get(activity).keepAwake());
    }

    /* ------------------------------------------------------------------ */
    /*  state                                                              */
    /* ------------------------------------------------------------------ */

    public static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager d = dpm(ctx);
        return d != null && d.isDeviceOwnerApp(ctx.getPackageName());
    }

    /** True while the device is actually locked into this app. */
    public static boolean isLocked(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    public static boolean isSuspended() {
        return System.currentTimeMillis() < suspendedUntil;
    }

    /** Everything a settings screen needs, in one object. */
    public static Status status(Context ctx) {
        Status s = new Status();
        s.isEnabled = isEnabled(ctx);
        s.statusOverlay = KioskPrefs.get(ctx).statusOverlay();
        s.becomeHome = KioskPrefs.get(ctx).becomeHome();
        s.blockAssistant = KioskPrefs.get(ctx).blockAssistant();
        s.wifiTap = KioskPrefs.get(ctx).wifiTap();
        s.isDeviceOwner = isDeviceOwner(ctx);
        s.isLocked = isLocked(ctx);
        s.isSuspended = isSuspended();
        s.keepAwake = KioskPrefs.get(ctx).keepAwake();
        s.provisionCommand = provisionCommand(ctx);
        return s;
    }

    /**
     * The configuration the QR carried, as JSON, or empty when there was none.
     *
     * <p>Whatever was put in the provisioning payload's
     * {@code PROVISIONING_ADMIN_EXTRAS_BUNDLE} - an app list, settings, a
     * password. An app reads this on first run to arrive configured rather than
     * asking somebody to set it up by hand.
     *
     * <p>The library gives it back verbatim and ascribes no meaning to it; what
     * the keys mean is between whoever built the QR and the app reading it.
     */
    public static String provisioningExtras(Context ctx) {
        return KioskPrefs.get(ctx).provisioningExtras();
    }

    /** The adb line that grants device owner, ready to paste. */
    public static String provisionCommand(Context ctx) {
        return "adb shell dpm set-device-owner "
                + ctx.getPackageName() + "/com.mk.kiosk.KioskAdminReceiver";
    }

    public static final class Status {
        /** Kiosk switched on, as opposed to temporarily unlocked. */
        public boolean isEnabled;
        public boolean statusOverlay;
        public boolean becomeHome;
        public boolean blockAssistant;
        public boolean wifiTap;
        public boolean isDeviceOwner;
        public boolean isLocked;
        public boolean isSuspended;
        public boolean keepAwake;
        public String provisionCommand;
    }

    static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }
}
