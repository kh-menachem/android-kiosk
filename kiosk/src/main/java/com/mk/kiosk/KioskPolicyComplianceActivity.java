package com.mk.kiosk;

import android.app.Activity;
import android.os.Bundle;

/**
 * The last step of provisioning: the system hands control to the app so it can
 * check the device meets whatever its policy requires, and waits to be told the
 * device is good before finishing setup.
 *
 * <p>Like {@link KioskProvisioningActivity}, this is required from Android 11
 * onwards. Without it the device installs the app and then refuses to complete
 * setup.
 *
 * <p>Nothing has to be checked here. The kiosk applies itself when the app first
 * runs, and there is no server to enrol against and no account to verify, so the
 * honest answer is always yes.
 */
public class KioskPolicyComplianceActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setResult(RESULT_OK);
        finish();
    }
}
