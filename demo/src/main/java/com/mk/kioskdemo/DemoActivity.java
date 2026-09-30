package com.mk.kioskdemo;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mk.kiosk.Kiosk;
import com.mk.kiosk.KioskGesture;
import com.mk.kiosk.KioskPanel;

/**
 * An app that does nothing but lock the device into itself.
 *
 * The whole integration is the one Kiosk.attach call below. Everything else
 * here is just something to look at while checking it works.
 */
public class DemoActivity extends Activity {

    private TextView state;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());

        // ---- this is the entire integration ----
        Kiosk.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.applyKeepScreenOn(this);
        refresh();
    }

    private View buildUi() {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Kiosk Demo");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        state = new TextView(this);
        state.setTextSize(16);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, pad / 2, 0, pad);

        TextView hint = new TextView(this);
        hint.setText(KioskGesture.describe() + " opens the controls."
                + "\n"
                + "That works in any app using this library. The button\n"
                + "below is only here to make it obvious while testing.");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, pad / 2);

        Button panel = new Button(this);
        panel.setText("Kiosk settings");
        panel.setAllCaps(false);
        panel.setOnClickListener(v -> KioskPanel.open(this));

        Button check = new Button(this);
        check.setText("Refresh status");
        check.setAllCaps(false);
        check.setOnClickListener(v -> refresh());

        root.addView(title);
        root.addView(state);
        root.addView(hint);
        root.addView(panel);
        root.addView(check);
        return root;
    }

    private void refresh() {
        // lock task settles a moment after onResume; look once it has
        new Handler().postDelayed(() -> {
            Kiosk.Status s = Kiosk.status(this);
            state.setText("Device owner: " + (s.isDeviceOwner ? "granted" : "NOT granted")
                    + "\nLocked to this app: " + (s.isLocked ? "yes" : "no")
                    + (s.isDeviceOwner ? "" : "\n\nRun:\n" + s.provisionCommand));
        }, 400);
    }
}
