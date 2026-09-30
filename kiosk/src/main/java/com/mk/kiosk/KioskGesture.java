package com.mk.kiosk;

import android.app.Activity;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * The way back into the kiosk controls, the same in every app.
 *
 * By default, seven taps in the top-left corner within three seconds opens the
 * master password prompt. {@link Kiosk#attach} installs it, so an app gets this
 * for free and nobody has to remember to build a trigger.
 *
 * <p>Change it before calling {@code attach}:
 *
 * <pre>
 *   KioskGesture.configure(new KioskGesture.Config()
 *           .taps(5)
 *           .corner(KioskGesture.Corner.BOTTOM_RIGHT)
 *           .within(5000));
 *   Kiosk.attach(this);
 * </pre>
 *
 * <p>It watches touches through the window callback rather than putting a view
 * on top of the app. Nothing is consumed and nothing is covered, so it works the
 * same whether the app is views, Compose or a WebView, and it cannot interfere
 * with whatever is actually on screen.
 */
public final class KioskGesture {

    private KioskGesture() { }

    /** Which corner the taps have to land in. */
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, ANYWHERE }

    private static Config config = new Config();
    private static boolean panelOpen = false;

    /**
     * How the gesture behaves. Set it before {@link Kiosk#attach}; changing it
     * afterwards takes effect on the next touch, since the watcher reads it
     * fresh each time.
     */
    public static final class Config {
        int taps = 7;
        long withinMs = 3000L;
        float cornerFraction = 0.15f;
        Corner corner = Corner.TOP_LEFT;
        boolean enabled = true;

        /** How many taps. Fewer than three is asking to be found by accident. */
        public Config taps(int n) { this.taps = Math.max(2, n); return this; }

        /** How long the whole run may take, in milliseconds. */
        public Config within(long ms) { this.withinMs = Math.max(500L, ms); return this; }

        /** Which corner, or {@link Corner#ANYWHERE} to accept taps on any part. */
        public Config corner(Corner c) { this.corner = c; return this; }

        /**
         * Corner size as a fraction of the screen, 0.05 to 0.5. Bigger is easier
         * to hit and easier to trip over by accident.
         */
        public Config size(float fraction) {
            this.cornerFraction = Math.min(0.5f, Math.max(0.05f, fraction));
            return this;
        }

        /** Turn the built-in gesture off, for an app providing its own trigger. */
        public Config enabled(boolean on) { this.enabled = on; return this; }
    }

    /** Replaces the gesture settings. Pass null to go back to the defaults. */
    public static void configure(Config c) {
        config = (c == null) ? new Config() : c;
    }

    /** The settings in force, for a settings screen that wants to show them. */
    public static Config settings() {
        return config;
    }

    /** A plain-words description, e.g. "7 taps in the top-left corner". */
    public static String describe() {
        if (!config.enabled) return "disabled";
        String where;
        switch (config.corner) {
            case TOP_RIGHT:    where = "in the top-right corner";    break;
            case BOTTOM_LEFT:  where = "in the bottom-left corner";  break;
            case BOTTOM_RIGHT: where = "in the bottom-right corner"; break;
            case ANYWHERE:     where = "anywhere on the screen";     break;
            default:           where = "in the top-left corner";     break;
        }
        return config.taps + " taps " + where;
    }

    /**
     * Starts watching for the gesture on this activity.
     *
     * Wraps whatever callback the window already has, so an app that sets its
     * own keeps working. Installing twice is harmless.
     */
    public static void install(final Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;

        Window.Callback existing = window.getCallback();
        if (existing instanceof Watcher) return;      // already watching

        window.setCallback(new Watcher(existing, activity));
    }

    /** Opens the panel as the gesture would, for an app that wants its own trigger. */
    public static void openPanel(final Activity activity) {
        if (panelOpen) return;
        panelOpen = true;
        KioskPassword.require(activity, () -> {
            panelOpen = false;
            KioskPanel.show(activity);
        }, () -> panelOpen = false);
    }

    /* ------------------------------------------------------------------ */

    private static final class Watcher implements Window.Callback {

        private final Window.Callback inner;
        private final Activity activity;

        private int count = 0;
        private long firstTapAt = 0;

        Watcher(Window.Callback inner, Activity activity) {
            this.inner = inner;
            this.activity = activity;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (config.enabled && e.getActionMasked() == MotionEvent.ACTION_DOWN) look(e);
            // always hand it on: this listens, it never takes
            return inner != null && inner.dispatchTouchEvent(e);
        }

        private void look(MotionEvent e) {
            View decor = activity.getWindow() == null ? null
                    : activity.getWindow().getDecorView();
            if (decor == null || decor.getWidth() == 0) return;

            long now = System.currentTimeMillis();
            if (!inZone(e, decor)) {
                count = 0;                       // a tap elsewhere breaks the run
                return;
            }
            if (count == 0 || now - firstTapAt > config.withinMs) {
                count = 1;
                firstTapAt = now;
                return;
            }
            if (++count >= config.taps) {
                count = 0;
                openPanel(activity);
            }
        }

        private boolean inZone(MotionEvent e, View decor) {
            if (config.corner == Corner.ANYWHERE) return true;

            float w = decor.getWidth(), h = decor.getHeight();
            float edgeX = w * config.cornerFraction;
            float edgeY = h * config.cornerFraction;

            switch (config.corner) {
                case TOP_RIGHT:    return e.getX() > w - edgeX && e.getY() < edgeY;
                case BOTTOM_LEFT:  return e.getX() < edgeX     && e.getY() > h - edgeY;
                case BOTTOM_RIGHT: return e.getX() > w - edgeX && e.getY() > h - edgeY;
                default:           return e.getX() < edgeX     && e.getY() < edgeY;
            }
        }

        /* ---- everything below is plain delegation ---- */

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            return inner != null && inner.dispatchKeyEvent(e); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) {
            return inner != null && inner.dispatchKeyShortcutEvent(e); }
        @Override public boolean dispatchTrackballEvent(MotionEvent e) {
            return inner != null && inner.dispatchTrackballEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
            return inner != null && inner.dispatchGenericMotionEvent(e); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) {
            return inner != null && inner.dispatchPopulateAccessibilityEvent(e); }
        @Override public View onCreatePanelView(int id) {
            return inner == null ? null : inner.onCreatePanelView(id); }
        @Override public boolean onCreatePanelMenu(int id, Menu menu) {
            return inner != null && inner.onCreatePanelMenu(id, menu); }
        @Override public boolean onPreparePanel(int id, View view, Menu menu) {
            return inner != null && inner.onPreparePanel(id, view, menu); }
        @Override public boolean onMenuOpened(int id, Menu menu) {
            return inner != null && inner.onMenuOpened(id, menu); }
        @Override public boolean onMenuItemSelected(int id, MenuItem item) {
            return inner != null && inner.onMenuItemSelected(id, item); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams p) {
            if (inner != null) inner.onWindowAttributesChanged(p); }
        @Override public void onContentChanged() {
            if (inner != null) inner.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean focus) {
            if (inner != null) inner.onWindowFocusChanged(focus); }
        @Override public void onAttachedToWindow() {
            if (inner != null) inner.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() {
            if (inner != null) inner.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int id, Menu menu) {
            if (inner != null) inner.onPanelClosed(id, menu); }
        @Override public boolean onSearchRequested() {
            return inner != null && inner.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent e) {
            return inner != null && inner.onSearchRequested(e); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) {
            return inner == null ? null : inner.onWindowStartingActionMode(c); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int type) {
            return inner == null ? null : inner.onWindowStartingActionMode(c, type); }
        @Override public void onActionModeStarted(ActionMode mode) {
            if (inner != null) inner.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(ActionMode mode) {
            if (inner != null) inner.onActionModeFinished(mode); }
        @Override public void onProvideKeyboardShortcuts(
                List<android.view.KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            if (inner != null) inner.onProvideKeyboardShortcuts(data, menu, deviceId); }
        @Override public void onPointerCaptureChanged(boolean hasCapture) {
            if (inner != null) inner.onPointerCaptureChanged(hasCapture); }
    }
}
