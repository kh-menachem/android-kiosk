package com.mk.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the kiosk back as soon as the device finishes starting, so a device on
 * a counter returns to the app by itself after a power cut or a restart.
 *
 * Several manufacturers send their own boot broadcast instead of the standard
 * one, so the common variants are all listened for.
 */
public class KioskBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        // The keep-alive service matters more than the activity: if the activity
        // start is refused, the service still watches for the screen coming on
        // and puts the app back then.
        KioskService.start(context);
        KioskWatchdogJob.schedule(context);

        Intent launch = homeIntent(context);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
            // some Android versions refuse a background activity start; being
            // the Home app covers that case
        }
    }

    /**
     * An intent for whichever activity called {@link Kiosk#attach}, remembered
     * across restarts so this receiver works before the app has run.
     */
    static Intent homeIntent(Context context) {
        String name = KioskPrefs.get(context).homeActivity();
        if (name != null && !name.isEmpty()) {
            try {
                return new Intent(context, Class.forName(name));
            } catch (ClassNotFoundException ignored) {
                // the app was rebuilt and the activity renamed; fall through
            }
        }
        return context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
    }
}
