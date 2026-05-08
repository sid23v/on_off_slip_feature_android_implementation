package com.example.uvcviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * UI-only rendering of the two bars from {@code trial_32.py}:
 * - Radial distance bar (distance from live_pt to ref_center)
 * - Axial (Z) bar (scale estimate)
 *
 * NOTE: The tracker logic stays in {@link Trial32Tracker}. This view only renders values.
 *
 * Change vs Python requested by user:
 * - The axial bar is rendered with a linear animation between updates so it does not "step"
 *   when the underlying estimate updates discretely (e.g., template scale list).
 */
public final class TrackingBarsView extends View {
    private static final long SCALE_ANIM_MS = 180L;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF barRect = new RectF();

    private int distance = 0;

    private double display_scale_est = 1.0;
    private double start_scale_est = 1.0;
    private double target_scale_est = 1.0;
    private long scale_anim_start_ms = 0L;

    public TrackingBarsView(Context context) {
        super(context);
        init();
    }

    public TrackingBarsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrackingBarsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);

        bgPaint.setColor(Color.rgb(60, 60, 60));
        fillPaint.setColor(Color.GREEN);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        borderPaint.setColor(Color.rgb(140, 140, 140));

        centerLinePaint.setStyle(Paint.Style.STROKE);
        centerLinePaint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
        centerLinePaint.setColor(Color.rgb(170, 170, 170));
    }

    public void setFrameState(@Nullable Trial32Tracker.FrameState state) {
        if (state == null) {
            distance = 0;
            setTargetScale(1.0, true);
        } else {
            distance = state.distance;
            setTargetScale(state.last_scale_est, false);
        }
        postInvalidateOnAnimation();
    }

    private void setTargetScale(double newTarget, boolean immediate) {
        if (immediate) {
            start_scale_est = newTarget;
            target_scale_est = newTarget;
            display_scale_est = newTarget;
            scale_anim_start_ms = 0L;
            return;
        }

        if (Math.abs(newTarget - target_scale_est) < 1e-9) {
            return;
        }

        // Start a linear transition from the current displayed value to the new target.
        start_scale_est = getAnimatedScale(SystemClock.uptimeMillis());
        target_scale_est = newTarget;
        display_scale_est = start_scale_est;
        scale_anim_start_ms = SystemClock.uptimeMillis();
    }

    private double getAnimatedScale(long nowMs) {
        if (scale_anim_start_ms <= 0L) {
            return target_scale_est;
        }
        long dt = nowMs - scale_anim_start_ms;
        if (dt <= 0L) {
            return start_scale_est;
        }
        if (dt >= SCALE_ANIM_MS) {
            scale_anim_start_ms = 0L;
            return target_scale_est;
        }
        double t = (double) dt / (double) SCALE_ANIM_MS;
        return start_scale_est + t * (target_scale_est - start_scale_est);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float density = getResources().getDisplayMetrics().density;

        final float padL = getPaddingLeft();
        final float padT = getPaddingTop();
        final float padR = getPaddingRight();
        final float padB = getPaddingBottom();

        final float w = getWidth() - padL - padR;
        final float h = getHeight() - padT - padB;

        if (w <= 0f || h <= 0f) return;

        final float sectionGap = 10f * density;
        final float labelH = textPaint.getTextSize() + (6f * density);
        final float barH = Math.max(10f * density, (h - sectionGap - 2f * labelH) / 2f);
        final float barRadius = 6f * density;

        float y = padT;

        // --- Radial ---
        int radialColor = getDistanceColor(distance);
        String radialLabel = "Radial: " + distance + "px";
        canvas.drawText(radialLabel, padL, y + textPaint.getTextSize(), textPaint);
        y += labelH;

        barRect.set(padL, y, padL + w, y + barH);
        canvas.drawRoundRect(barRect, barRadius, barRadius, bgPaint);
        canvas.drawRoundRect(barRect, barRadius, barRadius, borderPaint);

        int d = Math.min(distance, Trial32Tracker.DISTANCE_THRESHOLD);
        float fillW = (Trial32Tracker.DISTANCE_THRESHOLD > 0)
            ? (w * ((float) d / (float) Trial32Tracker.DISTANCE_THRESHOLD))
            : 0f;

        if (fillW > 0f) {
            fillPaint.setColor(radialColor);
            RectF fill = new RectF(padL, y, padL + fillW, y + barH);
            canvas.drawRoundRect(fill, barRadius, barRadius, fillPaint);
        }

        y += barH + sectionGap;

        // --- Axial (Z) ---
        long now = SystemClock.uptimeMillis();
        display_scale_est = getAnimatedScale(now);
        String axialLabel = String.format("Axial (Z): %.2fx", display_scale_est);
        canvas.drawText(axialLabel, padL, y + textPaint.getTextSize(), textPaint);
        y += labelH;

        barRect.set(padL, y, padL + w, y + barH);
        canvas.drawRoundRect(barRect, barRadius, barRadius, bgPaint);
        canvas.drawRoundRect(barRect, barRadius, barRadius, borderPaint);

        float cx = padL + w / 2f;
        canvas.drawLine(cx, y, cx, y + barH, centerLinePaint);

        // Mirror Python axial fill calculation (but rendered horizontally).
        double ratio;
        boolean right;
        if (display_scale_est >= 1.0) {
            double max_dev = Math.max(Trial32Tracker.SCALE_MAX - 1.0, 1e-6);
            double dev = Math.min(display_scale_est - 1.0, Trial32Tracker.SCALE_MAX - 1.0);
            ratio = clamp01(dev / max_dev);
            right = true;
        } else {
            double max_dev = Math.max(1.0 - Trial32Tracker.SCALE_MIN, 1e-6);
            double dev = Math.min(1.0 - display_scale_est, 1.0 - Trial32Tracker.SCALE_MIN);
            ratio = clamp01(dev / max_dev);
            right = false;
        }

        int axialColor;
        if (ratio < 0.5) {
            axialColor = Color.rgb(0, 255, 0);
        } else if (ratio < 0.8) {
            axialColor = Color.rgb(255, 255, 0);
        } else {
            axialColor = Color.rgb(255, 0, 0);
        }

        float halfW = w / 2f;
        float devW = (float) (ratio * halfW);
        if (devW > 0f) {
            fillPaint.setColor(axialColor);
            RectF fill = right
                ? new RectF(cx, y, cx + devW, y + barH)
                : new RectF(cx - devW, y, cx, y + barH);
            canvas.drawRoundRect(fill, barRadius, barRadius, fillPaint);
        }

        if (scale_anim_start_ms > 0L) {
            postInvalidateOnAnimation();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int getDistanceColor(int distance) {
        double ratio = (double) distance / (double) Trial32Tracker.DISTANCE_THRESHOLD;
        if (ratio < 0.5) {
            return Color.rgb(0, 255, 0);
        } else if (ratio < 0.8) {
            return Color.rgb(255, 255, 0);
        }
        return Color.rgb(255, 0, 0);
    }
}

