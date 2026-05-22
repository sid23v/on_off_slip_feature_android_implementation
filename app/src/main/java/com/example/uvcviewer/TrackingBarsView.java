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
    private boolean autoResetActive = false;

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

    public void setAutoResetActive(boolean v) {
        autoResetActive = v;
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

        // Draw a single status circle that reflects radial+axial combined status.
        // Compute axial display value (keeps existing animation code)
        long now = SystemClock.uptimeMillis();
        display_scale_est = getAnimatedScale(now);

        // Determine radial severity: 0=green,1=amber,2=red
        int radialSeverity;
        {
            int radialColor = getDistanceColor(distance);
            if (radialColor == Color.rgb(0, 255, 0)) radialSeverity = 0;
            else if (radialColor == Color.rgb(255, 0, 0)) radialSeverity = 2;
            else radialSeverity = 1; // amber
        }

        // Determine axial severity similarly
        int axialSeverity;
        {
            double ratio;
            if (display_scale_est >= 1.0) {
                double max_dev = Math.max(Trial32Tracker.SCALE_MAX - 1.0, 1e-6);
                double dev = Math.min(display_scale_est - 1.0, Trial32Tracker.SCALE_MAX - 1.0);
                ratio = clamp01(dev / max_dev);
            } else {
                double max_dev = Math.max(1.0 - Trial32Tracker.SCALE_MIN, 1e-6);
                double dev = Math.min(1.0 - display_scale_est, 1.0 - Trial32Tracker.SCALE_MIN);
                ratio = clamp01(dev / max_dev);
            }
            if (ratio < 0.5) axialSeverity = 0;
            else if (ratio < 0.8) axialSeverity = 1;
            else axialSeverity = 2;
        }

        int combinedSeverity = Math.max(radialSeverity, axialSeverity);

        // Colors: green, amber, red, grey(transparent)
        int green = Color.rgb(0, 255, 0);
        int amber = Color.rgb(255, 191, 0);
        int red = Color.rgb(255, 0, 0);
        int greyTrans = Color.argb(140, 128, 128, 128);

        // Circle layout
        float cx = padL + w / 2f;
        float cy = padT + h / 2f;
        float radius = Math.min(w, h) * 0.25f;

        int fillColor;
        // If auto-reset active, force red
        if (autoResetActive) {
            fillColor = red;
        } else {
            // If tracking inactive (distance==0 and scale==1 and not set), show grey transparent
            boolean trackingActive = !(distance == 0 && Math.abs(display_scale_est - 1.0) < 1e-9);
            if (!trackingActive) {
                fillColor = greyTrans;
            } else {
                if (combinedSeverity == 0) fillColor = green;
                else if (combinedSeverity == 1) fillColor = amber;
                else fillColor = red;
            }
        }

        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, fillPaint);

        // Draw border
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * density);
        canvas.drawCircle(cx, cy, radius, borderPaint);

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

