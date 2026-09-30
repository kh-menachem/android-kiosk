package com.mk.kiosk;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shuts off the voice assistant, which is otherwise a way straight out of a
 * kiosk.
 *
 * <p>Bixby, Google Assistant and the like are not ordinary apps. They answer a
 * long press on a hardware key, a wake word, or a corner swipe - none of which
 * lock task governs - and once open they will launch apps, search the web and
 * open settings on request. A device can be locked into a single app and still
 * be wide open through the assistant.
 *
 * <p>Hiding them with the rest of the apps does not work either: an assistant
 * usually has no launcher icon, so a sweep over launchable packages never sees
 * it. That is exactly how a locked tablet was found still answering Bixby.
 *
 * <p>Found by capability rather than by name, so this covers assistants nobody
 * thought to list - including whatever a particular OEM ships.
 */
final class KioskAssistants {

    private KioskAssistants() { }

    private static final String TAG = "Kiosk";

    /** Never touched, whatever they happen to answer. */
    private static final Set<String> NEVER = new LinkedHashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.tts",            // speech engine, not an assistant
            "com.google.android.as"              // system intelligence
    ));

    /**
     * The intents an assistant answers.
     *
     * <p>VoiceInteractionService is the one that matters most: it is what makes
     * a package *the* assistant, reachable by hardware key and wake word rather
     * than by anything on screen.
     */
    private static final String[] ACTIVITY_ACTIONS = {
            Intent.ACTION_ASSIST,
            "android.intent.action.VOICE_ASSIST",
            "android.speech.action.VOICE_SEARCH_HANDS_FREE",
            "android.intent.action.VOICE_COMMAND"
    };

    /**
     * Only the service that makes a package *the* assistant.
     *
     * <p>Not RecognitionService: that is the speech engine, which ordinary apps
     * use for voice input and which an accessibility user depends on. Matching
     * it swept up the text-to-speech engine and Android System Intelligence on
     * a real device - a lot of collateral for no gain, since an assistant that
     * answers here answers the actions above as well.
     */
    private static final String[] SERVICE_ACTIONS = {
            "android.service.voice.VoiceInteractionService"
    };

    /** Every package that can act as a voice assistant on this device. */
    static List<String> find(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> out = new LinkedHashSet<>();
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS;

        for (String action : ACTIVITY_ACTIONS) {
            try {
                for (ResolveInfo r : pm.queryIntentActivities(new Intent(action), flags)) {
                    if (r.activityInfo != null) out.add(r.activityInfo.packageName);
                }
            } catch (Exception ignored) { }
        }
        for (String action : SERVICE_ACTIONS) {
            try {
                for (ResolveInfo r : pm.queryIntentServices(new Intent(action), flags)) {
                    if (r.serviceInfo != null) out.add(r.serviceInfo.packageName);
                }
            } catch (Exception ignored) { }
        }

        out.removeAll(NEVER);
        out.remove(ctx.getPackageName());
        return new ArrayList<>(out);
    }

    /**
     * Hides or restores the assistants.
     *
     * <p>Hiding is tried first and suspension used as a fallback, because an OEM
     * will refuse to hide its own assistant on some builds while still allowing
     * it to be suspended.
     */
    static void block(Context ctx, boolean block) {
        DevicePolicyManager d = Kiosk.dpm(ctx);
        if (d == null || !Kiosk.isDeviceOwner(ctx)) return;

        List<String> assistants = find(ctx);
        if (assistants.isEmpty()) return;

        List<String> done = new ArrayList<>();
        for (String pkg : assistants) {
            boolean ok = false;
            try {
                ok = d.setApplicationHidden(KioskAdminReceiver.componentName(ctx), pkg, block);
            } catch (Exception ignored) { }

            if (!ok && Build.VERSION.SDK_INT >= 24) {
                try {
                    d.setPackagesSuspended(KioskAdminReceiver.componentName(ctx),
                            new String[]{pkg}, block);
                    ok = true;
                } catch (Exception ignored) { }
            }
            if (ok) done.add(pkg);
        }
        Log.i(TAG, (block ? "assistants blocked: " : "assistants restored: ") + done);
    }
}
