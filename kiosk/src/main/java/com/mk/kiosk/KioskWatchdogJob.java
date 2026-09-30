package com.mk.kiosk;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Puts the kiosk back if the system has taken it down.
 *
 * The rest of the kiosk hangs off the activity: it is onResume that starts the
 * keep-alive service and re-enters lock task. That leaves one hole, and a device
 * left on charge overnight falls straight through it — the OEM kills the service
 * while the screen is off, and with no activity and no service there is nothing
 * left to notice or recover.
 *
 * This job is owned by the system rather than by the app, so it survives the
 * process being killed and keeps running across reboots. Every quarter of an
 * hour it makes sure the keep-alive service is up; that service then brings the
 * app forward the next time the screen wakes.
 */
public class KioskWatchdogJob extends JobService {

    private static final int JOB_ID = 0x4B49;      // "KI"
    /** The shortest period the framework will honour. */
    private static final long EVERY = 15 * 60 * 1000L;

    /** Idempotent: re-scheduling an identical job just replaces it. */
    public static void schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, KioskWatchdogJob.class))
                    .setPeriodic(EVERY)
                    .setPersisted(true)            // outlives a reboot
                    .build());
        } catch (Exception ignored) { }
    }

    public static void cancel(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // A device owner may start a foreground service from the background,
        // which is the whole reason this can work at all.
        KioskService.start(this);
        return false;                              // nothing left running
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;                               // please run again
    }
}
