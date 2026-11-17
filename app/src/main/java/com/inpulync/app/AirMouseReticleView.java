package com.inpulync.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Holographic, cyberpunk-styled reticle and touch-clutch surface for Air Mouse mode.
 * Visualizes dynamic gyro tilt angle and provides ergonomic hold-to-aim touch engagement.
 */
final class AirMouseReticleView extends View {

    interface ReticleCallback {
        void onLeftClick();
        void onRightClick();
    }

    private final AirMouseController airMouseController;
    private final FeedbackController feedback;
    private ReticleCallback reticleCallback;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float puckOffsetX = 0f;
    private float puckOffsetY = 0f;
    private boolean isAiming = false;

    private float touchStartX = 0f;
    private float touchStartY = 0f;
    private long touchStartTime = 0;
    private boolean isMultiTouch = false;

    AirMouseReticleView(Context context, AirMouseController controller, FeedbackController feedback) {
        super(context);
        this.airMouseController = controller;
        this.feedback = feedback;

        ringPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.FILL);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        puckPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        subTextPaint.setTextAlign(Paint.Align.CENTER);

        controller.setAngleListener((pitchRate, yawRate, clutchActive) -> {
            this.isAiming = clutchActive;
            // Map gyro rates to visual puck offset (-35dp to +35dp)
            float maxOffset = dp(36);
            this.puckOffsetX = Math.max(-maxOffset, Math.min(maxOffset, -yawRate * dp(24)));
            this.puckOffsetY = Math.max(-maxOffset, Math.min(maxOffset, -pitchRate * dp(24)));
            postInvalidateOnAnimation();
        });
    }

    void setReticleCallback(ReticleCallback callback) {
        this.reticleCallback = callback;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(cx, cy) - dp(12);
        if (radius <= 0) return;

        Theme theme = Theme.current;
        int accent = theme.blue;
        int activeAccent = theme.yellow;

        // 1. Outer Translucent Glass Backing
        glowPaint.setColor(isAiming ? Theme.withAlpha(activeAccent, 0x2A) : Theme.withAlpha(theme.surface, 0x66));
        canvas.drawCircle(cx, cy, radius, glowPaint);

        // 2. Outer Rim Border
        ringPaint.setColor(isAiming ? activeAccent : Theme.withAlpha(accent, 0x88));
        ringPaint.setStrokeWidth(dp(isAiming ? 2.5f : 1.5f));
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // 3. Concentric Targeting Rings
        ringPaint.setColor(Theme.withAlpha(accent, 0x40));
        ringPaint.setStrokeWidth(dp(1f));
        canvas.drawCircle(cx, cy, radius * 0.70f, ringPaint);
        canvas.drawCircle(cx, cy, radius * 0.40f, ringPaint);

        // 4. Crosshairs & Cardinal Ticks
        crosshairPaint.setColor(Theme.withAlpha(accent, 0x60));
        crosshairPaint.setStrokeWidth(dp(1.2f));
        // Horizontal ticks
        canvas.drawLine(cx - radius, cy, cx - radius + dp(14), cy, crosshairPaint);
        canvas.drawLine(cx + radius - dp(14), cy, cx + radius, cy, crosshairPaint);
        // Vertical ticks
        canvas.drawLine(cx, cy - radius, cx, cy - radius + dp(14), crosshairPaint);
        canvas.drawLine(cx, cy + radius - dp(14), cx, cy + radius, crosshairPaint);

        // Small center crosshair
        canvas.drawLine(cx - dp(8), cy, cx + dp(8), cy, crosshairPaint);
        canvas.drawLine(cx, cy - dp(8), cx, cy + dp(8), crosshairPaint);

        // 5. Reactive Gyro Puck / Laser Beacon
        float puckX = cx + puckOffsetX;
        float puckY = cy + puckOffsetY;

        puckPaint.setColor(isAiming ? Theme.withAlpha(activeAccent, 0x55) : Theme.withAlpha(theme.ink, 0x22));
        canvas.drawCircle(puckX, puckY, dp(18), puckPaint);

        puckPaint.setColor(isAiming ? activeAccent : Theme.withAlpha(theme.ink, 0x77));
        canvas.drawCircle(puckX, puckY, dp(6), puckPaint);

        // 6. Informative Center Text
        textPaint.setColor(isAiming ? activeAccent : theme.ink);
        textPaint.setTextSize(dp(15));
        subTextPaint.setColor(Theme.withAlpha(theme.ink, 0xAA));
        subTextPaint.setTextSize(dp(11.5f));

        String title = isAiming ? "AIMING ACTIVE 🎯" : "HOLD TO AIM";
        String subtitle = isAiming ? "Tap to Click · 2 Fingers for Right Click" : "Touch & hold anywhere to move cursor";

        canvas.drawText(title, cx, cy - dp(40), textPaint);
        canvas.drawText(subtitle, cx, cy + dp(55), subTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                touchStartTime = SystemClock.uptimeMillis();
                touchStartX = event.getX();
                touchStartY = event.getY();
                isMultiTouch = false;
                if (!airMouseController.isAlwaysOn()) {
                    airMouseController.setClutch(true);
                }
                feedback.perform(this, HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    isMultiTouch = true;
                    if (reticleCallback != null) {
                        reticleCallback.onRightClick();
                        feedback.perform(this, HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                long elapsed = SystemClock.uptimeMillis() - touchStartTime;
                float dist = (float) Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY);
                if (!airMouseController.isAlwaysOn()) {
                    airMouseController.setClutch(false);
                }
                if (!isMultiTouch && elapsed < 260 && dist < dp(22)) {
                    // Quick tap = Left Click
                    if (reticleCallback != null) {
                        reticleCallback.onLeftClick();
                        feedback.perform(this, HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!airMouseController.isAlwaysOn()) {
                    airMouseController.setClutch(false);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
