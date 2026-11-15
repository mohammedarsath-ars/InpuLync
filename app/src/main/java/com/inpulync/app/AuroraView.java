package com.inpulync.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Slowly drifting colour blobs behind the translucent glass controls. */
final class AuroraView extends View {
    private static final long FRAME_MS = 50;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long startedAt = SystemClock.uptimeMillis();
    private boolean animated = true;
    private boolean running;
    private Shader background;
    private Shader[] blobs;
    private float blobRadius;
    private int builtWidth;
    private int builtHeight;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || !animated || !Theme.current.glass) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    AuroraView(Context context) {
        super(context);
    }

    void setAnimated(boolean animated) {
        this.animated = animated;
        refreshLoop();
        invalidate();
    }

    void setRunning(boolean running) {
        this.running = running;
        refreshLoop();
    }

    private void refreshLoop() {
        removeCallbacks(tick);
        if (running && animated && Theme.current.glass) postDelayed(tick, FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;
        if (background == null || width != builtWidth || height != builtHeight) rebuild(width, height);

        Theme theme = Theme.current;
        paint.setShader(background);
        canvas.drawRect(0, 0, width, height, paint);
        if (!theme.glass) return;

        float t = animated ? (SystemClock.uptimeMillis() - startedAt) / 1000f : 0f;
        drawBlob(canvas, 0, width * (0.20f + 0.10f * (float) Math.sin(t * 0.21f)),
                height * (0.30f + 0.18f * (float) Math.cos(t * 0.17f)));
        drawBlob(canvas, 1, width * (0.78f + 0.10f * (float) Math.cos(t * 0.19f)),
                height * (0.25f + 0.20f * (float) Math.sin(t * 0.23f)));
        drawBlob(canvas, 2, width * (0.50f + 0.18f * (float) Math.sin(t * 0.13f)),
                height * (0.85f + 0.12f * (float) Math.cos(t * 0.27f)));
    }

    private void drawBlob(Canvas canvas, int index, float x, float y) {
        paint.setShader(blobs[index]);
        canvas.save();
        canvas.translate(x, y);
        canvas.drawCircle(0, 0, blobRadius, paint);
        canvas.restore();
    }

    private void rebuild(int width, int height) {
        Theme theme = Theme.current;
        builtWidth = width;
        builtHeight = height;
        background = new LinearGradient(0, 0, width * 0.35f, height,
                theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP);
        blobRadius = Math.max(width, height) * 0.5f;
        int strength = theme.dark ? 0x70 : 0x99;
        blobs = new Shader[]{
                blobShader(theme.blob1, strength),
                blobShader(theme.blob2, strength),
                blobShader(theme.blob3, strength)
        };
    }

    private Shader blobShader(int color, int alpha) {
        return new RadialGradient(0, 0, blobRadius, Theme.withAlpha(color, alpha),
                Color.TRANSPARENT, Shader.TileMode.CLAMP);
    }

    /** Called after the theme changes so cached shaders are rebuilt. */
    void themeChanged() {
        background = null;
        refreshLoop();
        invalidate();
    }
}
