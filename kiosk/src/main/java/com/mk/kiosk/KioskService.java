package com.mk.kiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the kiosk alive.
 *
 * Two things go wrong without it. Android kills a backgrounded app to save
 * power, and when the locked app dies the lock dies with it — a device left with
 * its screen off overnight wakes up on the normal launcher. And nothing brings
 * it back, because waking a screen simply resumes whatever was last on top.
 *
 * Running as a foreground service keeps the process resident, and watching for
 * the screen coming on puts the app back in front.
 */
public class KioskService extends Service {

    private static final String CHANNEL_ID = "kiosk";
    private static final int NOTE_ID = 42;

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, KioskService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, KioskService.class)); } catch (Exception ignored) { }
    }

    private final BroadcastReceiver screenWatcher = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : String.valueOf(intent.getAction());
            if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                bringAppForward();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTE_ID, buildNotification());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenWatcher, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenWatcher, filter);
        }
    }

    private void bringAppForward() {
        Intent open = KioskBootReceiver.homeIntent(this);
        if (open == null) return;
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(open);
        } catch (Exception ignored) {
            // a device owner is allowed this; anything else is not worth crashing over
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Kiosk", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        return b.setContentTitle(label)
                .setContentText("Kiosk mode is running")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .build();
    }

    /** Restarted by Android if it is ever killed. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenWatcher); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
