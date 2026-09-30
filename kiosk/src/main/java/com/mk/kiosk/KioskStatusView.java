package com.mk.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Draws the Wi-Fi and battery indicators.
 *
 * <p>The usual fan of Wi-Fi arcs, and a battery that fills in proportion with
 * the percentage printed inside it and the charging bolt alongside the number,
 * also inside. Nothing sits beside the battery, so the strip stays compact.
 *
 * <p>Everything scales from the text size, so the strip stays proportioned at
 * any density and there are no bitmaps to ship.
 */
final class KioskStatusView extends View {

    /** 0..4, or -1 for no network at all. */
    int wifiLevel = -1;
    /** 0..100, or -1 when unknown. */
    int percent = -1;
    boolean charging;
    /** Associated but nothing reachable: the stale-link case, worth showing. */
    boolean noInternet;
    /** Not Wi-Fi - mobile or wired - so the arcs would be a lie. */
    boolean otherTransport;
    /** Drawn while a finger is down on the Wi-Fi icon. */
    boolean wifiPressed;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();

    private final float iconH;
    private final float wifiW;
    private final float battW;
    private final float gap;

    /** Foreground, taken from the host app's theme. */
    private int LIT = Color.WHITE;
    /** The same colour at low alpha, for arcs above the signal level. */
    private int DIM = 0x55FFFFFF;
    /** The chip colour, so knocked-out shapes read as cut out of it. */
    private int BG = 0xFF2A2A2A;

    private static final int WARN_ON_DARK = 0xFFFFB300;
    private static final int WARN_ON_LIGHT = 0xFFC77700;
    private int WARN = WARN_ON_DARK;

    /**
     * Adopts the host app's colours.
     *
     * @param foreground text and icon colour
     * @param chip       what sits behind them, used for the knockout too
     */
    void colours(int foreground, int chip) {
        LIT = foreground;
        BG = chip;
        DIM = (foreground & 0x00FFFFFF) | 0x55000000;
        // amber is the warning either way, but the bright one disappears on a
        // pale background, so pick by what it will actually sit on
        WARN = luminance(chip) > 0.55f ? WARN_ON_LIGHT : WARN_ON_DARK;
    }

    /** Perceived brightness, 0..1. */
    static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c)
                + 0.114f * Color.blue(c)) / 255f;
    }

    KioskStatusView(Context ctx, float textSp) {
        super(ctx);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;

        iconH = textSp * sp;
        wifiW = iconH * 1.25f;
        battW = iconH * 2.75f;          // wide enough to hold "100%" and a bolt
        gap = iconH * 0.5f;

        inner.setTextAlign(Paint.Align.LEFT);
        inner.setFakeBoldText(true);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(
                (int) Math.ceil(getPaddingLeft() + wifiW + gap + battW + getPaddingRight()),
                (int) Math.ceil(getPaddingTop() + iconH * 1.35f + getPaddingBottom()));
    }

    /**
     * Whether a touch landed on the Wi-Fi icon.
     *
     * Generous vertically - the strip is short and a fingertip is not - but
     * kept to the icon's own width so the battery stays untouchable.
     */
    boolean isInWifiIcon(float x, float y) {
        float slop = iconH * 0.35f;
        return x >= getPaddingLeft() - slop && x <= getPaddingLeft() + wifiW + slop;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float x = getPaddingLeft();

        if (wifiPressed) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((LIT & 0x00FFFFFF) | 0x33000000);
            float r = iconH * 0.12f;
            box.set(x - iconH * 0.22f, cy - iconH * 0.62f,
                    x + wifiW + iconH * 0.22f, cy + iconH * 0.62f);
            c.drawRoundRect(box, r, r, paint);
        }

        if (otherTransport) {
            drawGlobe(c, x, cy);
        } else {
            drawWifi(c, x, cy);
        }
        drawBattery(c, x + wifiW + gap, cy);
    }

    /* ---------------------------------------------------------------- */

    /**
     * The familiar fan of arcs, drawn from a point at the bottom. Arcs above the
     * signal level stay faintly visible rather than vanishing, so the icon keeps
     * its shape and a weak signal reads as weak rather than as a rendering bug.
     */
    private void drawWifi(Canvas c, float left, float cy) {
        float cx = left + wifiW / 2f;
        float baseY = cy + iconH * 0.42f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(wifiLevel >= 1 ? tint() : DIM);
        c.drawCircle(cx, baseY - iconH * 0.06f, iconH * 0.095f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(iconH * 0.13f);
        for (int i = 0; i < 3; i++) {
            float r = iconH * (0.28f + 0.20f * i);
            paint.setColor(wifiLevel >= i + 2 ? tint() : DIM);
            box.set(cx - r, baseY - r - iconH * 0.06f, cx + r, baseY + r - iconH * 0.06f);
            c.drawArc(box, 220, 100, false, paint);     // 100 degrees, centred up
        }

        if (wifiLevel < 0) {
            // no network: strike it through rather than leave an empty icon
            paint.setColor(WARN);
            paint.setStrokeWidth(iconH * 0.11f);
            c.drawLine(cx - iconH * 0.42f, baseY - iconH * 0.58f,
                       cx + iconH * 0.42f, baseY + iconH * 0.10f, paint);
        }
    }

    /** Mobile or ethernet: a simple globe, since Wi-Fi arcs would misreport it. */
    private void drawGlobe(Canvas c, float left, float cy) {
        float cx = left + wifiW / 2f;
        float r = iconH * 0.36f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(iconH * 0.11f);
        paint.setColor(tint());
        c.drawCircle(cx, cy, r, paint);
        c.drawLine(cx - r, cy, cx + r, cy, paint);
        box.set(cx - r * 0.55f, cy - r, cx + r * 0.55f, cy + r);
        c.drawOval(box, paint);
    }

    /**
     * Battery outline with a terminal nub, filled in proportion, with the
     * reading printed inside it.
     */
    private void drawBattery(Canvas c, float left, float cy) {
        float bodyW = battW * 0.88f;
        float bodyH = iconH * 0.92f;
        float top = cy - bodyH / 2f;
        float stroke = iconH * 0.10f;
        float radius = iconH * 0.13f;

        boolean low = percent >= 0 && percent <= 15 && !charging;
        int shell = low ? WARN : LIT;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(shell);
        box.set(left + stroke / 2f, top, left + bodyW - stroke / 2f, top + bodyH);
        c.drawRoundRect(box, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        float nubH = bodyH * 0.40f;
        box.set(left + bodyW, cy - nubH / 2f, left + battW * 0.99f, cy + nubH / 2f);
        c.drawRoundRect(box, radius * 0.5f, radius * 0.5f, paint);

        if (percent < 0) return;

        float inset = stroke * 1.5f;
        float innerL = left + inset;
        float innerR = left + bodyW - inset;
        float innerT = top + inset;
        float innerB = top + bodyH - inset;

        float fillEdge = innerL + (innerR - innerL) * Math.min(100, Math.max(0, percent)) / 100f;
        if (fillEdge - innerL > 0.5f) {
            box.set(innerL, innerT, fillEdge, innerB);
            c.drawRoundRect(box, radius * 0.45f, radius * 0.45f, paint);
        }

        drawReading(c, innerL, innerR, innerT, innerB, cy, fillEdge, shell);
    }

    /**
     * The percentage, and the bolt when charging, inside the battery.
     *
     * Drawn twice under opposing clips: dark where the fill sits behind it,
     * light where the battery is empty. A single colour would disappear into
     * the fill at one charge level or into the background at another, and the
     * boundary moves as the battery drains.
     */
    private void drawReading(Canvas c, float innerL, float innerR, float innerT, float innerB,
                             float cy, float fillEdge, int shell) {
        inner.setTextSize((innerB - innerT) * 0.78f);
        String label = percent + "%";
        float textW = inner.measureText(label);
        float boltW = charging ? (innerB - innerT) * 0.55f : 0;
        float boltGap = charging ? (innerB - innerT) * 0.12f : 0;

        float contentW = boltW + boltGap + textW;
        float startX = (innerL + innerR) / 2f - contentW / 2f;
        float baseline = cy + inner.getTextSize() * 0.36f;

        for (int pass = 0; pass < 2; pass++) {
            boolean overFill = pass == 0;
            c.save();
            if (overFill) {
                c.clipRect(innerL, innerT, fillEdge, innerB);
                inner.setColor(BG);
            } else {
                c.clipRect(fillEdge, innerT, innerR, innerB);
                inner.setColor(shell);
            }
            if (charging) {
                boltPath(startX + boltW / 2f, cy, innerB - innerT);
                c.drawPath(path, inner);
            }
            c.drawText(label, startX + boltW + boltGap, baseline, inner);
            c.restore();
        }
    }

    private void boltPath(float cx, float cy, float h) {
        float w = h * 0.42f;
        path.reset();
        path.moveTo(cx + w * 0.30f, cy - h * 0.40f);
        path.lineTo(cx - w * 0.52f, cy + h * 0.06f);
        path.lineTo(cx - w * 0.02f, cy + h * 0.06f);
        path.lineTo(cx - w * 0.30f, cy + h * 0.40f);
        path.lineTo(cx + w * 0.52f, cy - h * 0.08f);
        path.lineTo(cx + w * 0.02f, cy - h * 0.08f);
        path.close();
    }

    private int tint() {
        return noInternet ? WARN : LIT;
    }
}
