package com.mk.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Answers the one question Android asks during QR and NFC provisioning:
 * what kind of managed device is this meant to be?
 *
 * <p>From Android 11 onwards the system will not provision an app it cannot ask.
 * It launches {@code GET_PROVISIONING_MODE} and waits for a reply; an app that
 * does not handle it gets as far as downloading and installing, then fails with
 * "Can't set up device" and no explanation of what was missing.
 *
 * <p>The answer here is always a fully managed device, because that is the only
 * thing this library is for. A work profile would leave the personal side of the
 * device untouched, which is the opposite of a kiosk.
 *
 * <p>There is no UI. It answers and closes.
 */
public class KioskProvisioningActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        Intent reply = new Intent();
        if (Build.VERSION.SDK_INT >= 30) {
            reply.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE);
        }
        setResult(RESULT_OK, reply);
        finish();
    }
}
