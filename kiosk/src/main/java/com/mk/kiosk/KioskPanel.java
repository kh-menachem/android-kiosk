package com.mk.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A ready-made settings panel, so an app can have working kiosk controls
 * without building any UI of its own:
 *
 * <pre>
 *   KioskPanel.open(this);     // asks for the master password first
 * </pre>
 *
 * It is plain Android dialogs on purpose - the library should not care whether
 * the host app is views, Compose or a WebView. Anything fancier is the app's
 * own job, using {@link Kiosk#status} and the methods on {@link Kiosk}.
 *
 * <p>Changing a setting redraws the panel in place. Only the two things that
 * genuinely leave - opening Android's Wi-Fi screen, and handing back device
 * owner - close it.
 */
public final class KioskPanel {

    private KioskPanel() { }

    /** Asks for the master password, then shows the panel. */
    public static void open(final Activity activity) {
        KioskPassword.require(activity, () -> show(activity));
    }

    /** Shows the panel with no password check. Use only behind your own gate. */
    public static void show(final Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Kiosk")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        populate(activity, root, dialog);
        dialog.show();
    }

    /**
     * Draws the current state and the buttons that act on it.
     *
     * Called again after every change, so the panel always shows what is
     * actually true rather than what was true when it opened.
     */
    private static void populate(final Activity activity, final LinearLayout root,
                                 final AlertDialog dialog) {
        root.removeAllViews();

        final Kiosk.Status s = Kiosk.status(activity);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(pad, pad / 2, pad, 0);

        info.addView(line(activity, "Device owner", s.isDeviceOwner ? "Granted" : "Not granted"));
        info.addView(line(activity, "Kiosk", s.isEnabled ? "On" : "Off"));
        info.addView(line(activity, "Locked to this app", s.isLocked ? "Yes" : "No"));
        info.addView(line(activity, "Screen stays on", s.keepAwake ? "Yes" : "No"));
        info.addView(line(activity, "Battery & Wi-Fi shown", s.statusOverlay ? "Yes" : "No"));
        info.addView(line(activity, "Home opens this app", s.becomeHome ? "Yes" : "No"));
        info.addView(line(activity, "Voice assistant", s.blockAssistant ? "Blocked" : "Allowed"));
        info.addView(line(activity, "Wi-Fi icon tappable", s.wifiTap ? "Yes" : "No"));

        if (!s.isDeviceOwner) {
            TextView warn = new TextView(activity);
            warn.setPadding(0, pad / 2, 0, 0);
            warn.setText("Home and Recents cannot be blocked yet. Android only allows "
                    + "that for a device-owner app. Factory reset the device, skip "
                    + "adding any account, install this app, then run:\n\n"
                    + s.provisionCommand
                    + "\n\nOr provision by QR or NFC - see the library's tools folder.");
            info.addView(warn);
        }
        root.addView(info);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.VERTICAL);
        int bpad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        buttons.setPadding(bpad, bpad, bpad, 0);

        if (!s.isEnabled) {
            // Switched off, but still device owner: one press brings it back.
            buttons.addView(button(activity, "Turn the kiosk back on", v -> {
                Kiosk.enable(activity);
                toast(activity, "Kiosk on");
                populate(activity, root, dialog);
            }));
        } else {
            if (s.isLocked) {
                buttons.addView(button(activity, "Unlock for maintenance", v ->
                        confirmHold(activity, "Unlock for maintenance?",
                                "Home and Recents start working again. The kiosk comes back "
                                        + "the moment you return to this app, and in any case "
                                        + "after fifteen minutes.",
                                () -> {
                                    Kiosk.suspend(activity);
                                    toast(activity, "Unlocked - returns to kiosk when you come back");
                                    populate(activity, root, dialog);
                                })));
            } else {
                buttons.addView(button(activity, "Lock the device again", v -> {
                    Kiosk.resume(activity);
                    toast(activity, "Locked");
                    populate(activity, root, dialog);
                }));
            }

            buttons.addView(button(activity, "Turn the kiosk off", v ->
                    confirmHold(activity, "Turn the kiosk off?",
                            "The device goes back to behaving normally and stays that way, "
                                    + "including after a reboot.\n\nDevice owner is kept, so "
                                    + "you can turn the kiosk back on from this panel at any "
                                    + "time. Nothing needs reprovisioning.",
                            () -> {
                                Kiosk.disable(activity);
                                toast(activity, "Kiosk off - turn it back on from this panel");
                                populate(activity, root, dialog);
                            })));
        }

        buttons.addView(button(activity,
                s.blockAssistant ? "Allow the voice assistant" : "Block the voice assistant",
                v -> {
                    Kiosk.blockAssistant(activity, !s.blockAssistant);
                    toast(activity, s.blockAssistant
                            ? "Assistant allowed"
                            : "Assistant blocked");
                    populate(activity, root, dialog);
                }));

        buttons.addView(button(activity,
                s.becomeHome ? "Stop Home opening this app" : "Make Home open this app",
                v -> {
                    Kiosk.setBecomeHome(activity, !s.becomeHome);
                    toast(activity, s.becomeHome
                            ? "Home goes to the launcher again"
                            : "Home returns to this app");
                    populate(activity, root, dialog);
                }));

        buttons.addView(button(activity,
                s.statusOverlay ? "Hide battery & Wi-Fi" : "Show battery & Wi-Fi",
                v -> {
                    Kiosk.showStatusOverlay(activity, !s.statusOverlay);
                    populate(activity, root, dialog);
                }));

        buttons.addView(button(activity, s.keepAwake ? "Let the screen sleep" : "Keep the screen on",
                v -> {
                    Kiosk.keepScreenOn(activity, !s.keepAwake);
                    populate(activity, root, dialog);
                }));

        buttons.addView(button(activity,
                s.wifiTap ? "Wi-Fi icon opens settings: on" : "Wi-Fi icon opens settings: off",
                v -> {
                    Kiosk.setWifiIconOpensSettings(activity, !s.wifiTap);
                    populate(activity, root, dialog);
                }));

        // leaves the app, so this one has to close
        buttons.addView(button(activity, "Wi-Fi settings", v -> {
            Kiosk.openWifiSettings(activity);
            close(dialog);
        }));

        buttons.addView(button(activity, "Reconnect Wi-Fi", v ->
                KioskNetwork.reconnect(activity, (ok, message) -> toast(activity, message))));

        buttons.addView(button(activity, "Change master password", v -> {
            close(dialog);
            KioskPassword.change(activity);
        }));

        if (s.isDeviceOwner) {
            Button release = button(activity, "Release device owner", v ->
                    confirmHold(activity, "Release device owner?",
                            "This is the one that cannot be undone from the device.\n\n"
                                    + "Android will not grant device owner again without a "
                                    + "factory reset and provisioning from scratch. If you "
                                    + "only want the kiosk out of the way, use \"Turn the "
                                    + "kiosk off\" instead - that keeps device owner and is "
                                    + "reversible.",
                            () -> {
                                Kiosk.release(activity);
                                close(dialog);
                                toast(activity, "Device owner released");
                            }));
            release.setTextColor(0xFFD03B3B);
            buttons.addView(release);
        }

        root.addView(buttons);
    }

    /**
     * A confirmation that has to be held rather than tapped.
     *
     * Leaving the kiosk is not something to do by brushing the screen, and the
     * ways out are exactly where a stray tap costs the most.
     */
    private static void confirmHold(final Activity activity, String title, String message,
                                    final Runnable then) {
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message + "\n\nPress and hold to confirm.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Hold", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final long[] downAt = {0};
            yes.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downAt[0] = System.currentTimeMillis();
                        v.postDelayed(() -> {
                            if (downAt[0] > 0 && System.currentTimeMillis() - downAt[0] >= 1400) {
                                dialog.dismiss();
                                then.run();
                            }
                        }, 1450);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        downAt[0] = 0;
                        return true;
                    default:
                        return false;
                }
            });
        });
        dialog.show();
    }

    private static View line(Activity a, String label, String value) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (4 * a.getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView k = new TextView(a);
        k.setText(label);
        k.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(a);
        v.setText(value);
        v.setGravity(Gravity.END);

        row.addView(k);
        row.addView(v);
        return row;
    }

    private static Button button(Activity a, String label, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private static void close(AlertDialog d) {
        if (d != null) d.dismiss();
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }
}
