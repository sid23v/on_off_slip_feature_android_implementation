package com.example.uvcviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Lightweight UI overlay for drawing trial_32 markers/arrow and reset overlay tint
 * on top of the live camera preview.
 */
public final class TrackingOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final Path arrowHeadPath = new Path();

    private int[] ref_center = null;
    private int[] live_pt = null;
    private int distance = 0;
    private int overlay_counter = 0;

    public TrackingOverlayView(Context context) {
        super(context);
        init();
    }

    public TrackingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrackingOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(Color.argb((int) (0.4f * 255f), 255, 0, 0)); // match Python addWeighted(red, 0.4, ...)
        setWillNotDraw(false);
    }

    public void setFrameState(@Nullable Trial32Tracker.FrameState state) {
        if (state == null) {
            ref_center = null;
            live_pt = null;
            distance = 0;
            overlay_counter = 0;
        } else {
            ref_center = (state.ref_center != null) ? new int[] { state.ref_center[0], state.ref_center[1] } : null;
            live_pt = (state.live_pt != null) ? new int[] { state.live_pt[0], state.live_pt[1] } : null;
            distance = state.distance;
            overlay_counter = state.overlay_counter;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (ref_center == null) {
            return;
        }

        float sx = (float) getWidth() / (float) Trial32Tracker.FRAME_WIDTH;
        float sy = (float) getHeight() / (float) Trial32Tracker.FRAME_HEIGHT;

        float refX = ref_center[0] * sx;
        float refY = ref_center[1] * sy;

        // ref marker: blue circle
        paint.setColor(Color.rgb(0, 0, 255));
        canvas.drawCircle(refX, refY, Trial32Tracker.MARKER_SIZE * Math.min(sx, sy), paint);

        if (live_pt != null) {
            float liveX = live_pt[0] * sx;
            float liveY = live_pt[1] * sy;

            // live marker: green circle
            paint.setColor(Color.rgb(0, 255, 0));
            canvas.drawCircle(liveX, liveY, Trial32Tracker.MARKER_SIZE * Math.min(sx, sy), paint);

            // arrow (live -> ref center)
            int arrowColor = getDistanceColor(distance);
            paint.setColor(arrowColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * Math.min(sx, sy));
            canvas.drawLine(liveX, liveY, refX, refY, paint);

            drawArrowHead(canvas, liveX, liveY, refX, refY, paint, 0.3f);
            paint.setStyle(Paint.Style.FILL);
        }

        if (overlay_counter > 0) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), overlayPaint);
        }
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

    private void drawArrowHead(Canvas canvas, float x0, float y0, float x1, float y1, Paint strokePaint, float tipLengthFraction) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;

        float ux = dx / len;
        float uy = dy / len;

        float tipLen = tipLengthFraction * len;
        float angle = (float) (Math.PI / 6.0); // 30 degrees

        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);

        // Rotate unit vector by +angle and -angle, then scale to tipLen (pointing backwards from tip).
        float rx1 = (ux * cos - uy * sin);
        float ry1 = (ux * sin + uy * cos);
        float rx2 = (ux * cos + uy * sin);
        float ry2 = (-ux * sin + uy * cos);

        float hx1 = x1 - rx1 * tipLen;
        float hy1 = y1 - ry1 * tipLen;
        float hx2 = x1 - rx2 * tipLen;
        float hy2 = y1 - ry2 * tipLen;

        arrowHeadPath.reset();
        arrowHeadPath.moveTo(x1, y1);
        arrowHeadPath.lineTo(hx1, hy1);
        arrowHeadPath.moveTo(x1, y1);
        arrowHeadPath.lineTo(hx2, hy2);

        canvas.drawPath(arrowHeadPath, strokePaint);
    }
}

