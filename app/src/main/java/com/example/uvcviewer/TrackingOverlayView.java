package com.example.uvcviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
// removed unused Paint and Path imports
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Lightweight UI overlay for drawing trial_32 markers/arrow and reset overlay tint
 * on top of the live camera preview.
 */
public final class TrackingOverlayView extends View {
    // overlay drawing disabled; keep only state fields

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
        // No overlay drawing required; keep view ready for invalidation
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
        // Overlay rendering disabled: keep tracker state logic intact but do not draw markers/arrows.
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

    // drawArrowHead removed; overlay drawing disabled
}

