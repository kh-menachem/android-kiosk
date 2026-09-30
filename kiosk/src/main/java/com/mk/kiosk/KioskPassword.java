package com.mk.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The master password that guards the kiosk controls.
 *
 * Set once, the first time the app runs on a device, and asked for every time
 * anyone opens the kiosk panel — there is deliberately no "remember for a few
 * minutes", because that turns closing the panel and reopening it into a way
 * straight back in.
 *
 * Stored as a PBKDF2 hash with a random salt, never as the password itself. It
 * guards a settings screen on a device somebody is holding, not a bank; what it
 * has to survive is a curious person, not an attacker with the flash chip.
 */
public final class KioskPassword {

    private KioskPassword() { }

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int MIN_LENGTH = 4;

    public interface Result {
        void onPassed();
    }

    public static boolean isSet(Context ctx) {
        return KioskPrefs.get(ctx).hasPassword();
    }

    /**
     * Asks for a new master password, twice, and will not take no for an answer
     * — this runs on first install and the device should not end up locked with
     * no way into its own settings.
     */
    public static void choose(final Activity activity, final Result then) {
        final EditText first = field(activity, "New password");
        final EditText again = field(activity, "Type it again");

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView why = new TextView(activity);
        why.setText("This device locks into this app. The password is the only way "
                + "back into the kiosk controls, so keep it somewhere safe — it "
                + "cannot be recovered from the device.");
        why.setPadding(0, 0, 0, pad / 2);
        box.addView(why);
        box.addView(first);
        box.addView(again);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Set a master password")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Save", null)     // wired below so it can refuse to close
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String a = first.getText().toString();
                    String b = again.getText().toString();
                    if (a.length() < MIN_LENGTH) {
                        toast(activity, "At least " + MIN_LENGTH + " characters.");
                        return;
                    }
                    if (!a.equals(b)) {
                        toast(activity, "The two entries do not match.");
                        again.setText("");
                        return;
                    }
                    set(activity, a);
                    dialog.dismiss();
                    if (then != null) then.onPassed();
                }));
        dialog.show();
    }

    /** Asks for the existing password, and calls back only if it is right. */
    public static void require(final Activity activity, final Result then) {
        require(activity, then, null);
    }

    /**
     * As above, but also tells the caller when the prompt was dismissed without
     * a correct password - a caller that guards against opening the prompt twice
     * needs to know, or cancelling once locks it out for good.
     */
    public static void require(final Activity activity, final Result then,
                               final Runnable onCancel) {
        if (!isSet(activity)) {          // nothing set yet: set one now
            choose(activity, then);
            return;
        }
        final EditText entry = field(activity, "Master password");
        LinearLayout box = new LinearLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(entry);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Kiosk controls")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unlock", null)
                .create();

        // Covers Cancel, the back button and a tap outside alike. A successful
        // unlock dismisses the dialog too, so it has to be told apart or every
        // correct password would also report a cancellation.
        final boolean[] passed = {false};
        dialog.setOnDismissListener(d -> {
            if (!passed[0] && onCancel != null) onCancel.run();
        });

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (verify(activity, entry.getText().toString())) {
                        passed[0] = true;
                        dialog.dismiss();
                        if (then != null) then.onPassed();
                    } else {
                        entry.setText("");
                        toast(activity, "Wrong password.");
                    }
                }));
        dialog.show();
    }

    /** Replaces the password. The caller is expected to have checked the old one. */
    public static void change(final Activity activity) {
        choose(activity, () -> toast(activity, "Master password changed."));
    }

    /* ------------------------------------------------------------------ */

    /**
     * Sets the master password directly, with no dialog.
     *
     * <p>For a device configured at provisioning time, where there is nobody
     * standing in front of it to type one. Overwrites whatever was there, so a
     * caller that means "only if unset" checks {@link #isSet} first.
     */
    public static void set(Context ctx, String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = hex(salt);
        KioskPrefs.get(ctx).setPassword(hash(password, salt), saltHex);
    }

    static boolean verify(Context ctx, String attempt) {
        KioskPrefs prefs = KioskPrefs.get(ctx);
        String saltHex = prefs.passwordSalt();
        String expected = prefs.passwordHash();
        if (saltHex.isEmpty() || expected.isEmpty()) return false;
        String actual = hash(attempt, unhex(saltHex));
        // length-independent compare, cheap to do properly
        return constantTimeEquals(expected, actual);
    }

    private static String hash(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return hex(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(), y = b.getBytes();
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        Arrays.fill(y, (byte) 0);
        return diff == 0;
    }

    private static EditText field(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
