package com.mk.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

import org.json.JSONObject;

/**
 * The device-admin component Android requires before an app can be made device
 * owner. Being device owner is what makes a real kiosk possible: locking the
 * device into one app with no pinning prompt, hiding the status bar, and making
 * the app the permanent Home app.
 *
 * Granted once per device, by one of:
 *   adb shell dpm set-device-owner <package>/com.mk.kiosk.KioskAdminReceiver
 * or by QR / NFC provisioning during the setup wizard — see tools/.
 *
 * It can be handed back from inside the app, so a device never needs a factory
 * reset to undo this.
 */
public class KioskAdminReceiver extends DeviceAdminReceiver {

    public static ComponentName componentName(Context context) {
        return new ComponentName(context.getApplicationContext(), KioskAdminReceiver.class);
    }

    /**
     * Fired when QR or NFC provisioning finishes. The app is device owner by
     * this point but has never been launched, so the kiosk is armed here and
     * applies itself the moment the app first opens.
     *
     * <p>This is also the only moment the provisioning configuration is offered.
     * Android hands over whatever the QR put in its
     * {@code PROVISIONING_ADMIN_EXTRAS_BUNDLE} and never offers it again - there
     * is no getter to ask for it later. So it is saved here or it is lost, and a
     * device meant to arrive configured comes up blank instead.
     */
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        KioskPrefs.get(context).setProvisioned(true);
        saveExtras(context, intent);
    }

    private void saveExtras(Context context, Intent intent) {
        if (intent == null) return;
        try {
            PersistableBundle extras = intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
            if (extras == null || extras.isEmpty()) return;

            JSONObject out = new JSONObject();
            for (String key : extras.keySet()) {
                Object v = extras.get(key);
                if (v != null) out.put(key, v);
            }
            KioskPrefs.get(context).setProvisioningExtras(out.toString());
        } catch (Exception ignored) {
            // a malformed bundle must not stop the device being provisioned
        }
    }
}
