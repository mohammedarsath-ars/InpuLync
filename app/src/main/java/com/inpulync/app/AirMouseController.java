package com.inpulync.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Manages phone gyroscope and accelerometer sensors to compute smooth,
 * ultra-low latency relative mouse movement (Air Mouse / Gyro Mode).
 * Uses world-gravity projection so horizontal wrist movements map to cursor X
 * and vertical pitch movements map to cursor Y regardless of phone tilt.
 */
final class AirMouseController implements SensorEventListener {
    private static final String TAG = "AirMouseController";
    private static final String PREFS_NAME = "air_mouse_prefs";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_ALWAYS_ON = "always_on";
    private static final String KEY_INVERT_X = "invert_x";
    private static final String KEY_INVERT_Y = "invert_y";

    interface Callback {
        void onMotion(int dx, int dy);
    }

    interface AngleListener {
        void onAngleUpdate(float pitchRate, float yawRate, boolean clutchActive);
    }

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor gyroSensor;
    private final Sensor accelSensor;
    private Callback callback;
    private AngleListener angleListener;

    private boolean active = false;
    private boolean clutchEngaged = false;
    private boolean alwaysOn = false;
    private boolean invertX = false;
    private boolean invertY = false;

    private float sensitivity = 1.6f;
    private float deadzone = 0.018f; // ~1 deg/sec threshold to cancel natural hand tremor

    // Normalized gravity vector in phone coordinates
    private float gravX = 0f;
    private float gravY = 9.8f;
    private float gravZ = 0f;

    // Smoothed angular velocity
    private float smoothedYaw = 0f;
    private float smoothedPitch = 0f;

    // Sub-pixel fractional accumulators
    private float accumX = 0f;
    private float accumY = 0f;

    private long lastSensorTimestamp = 0;

    AirMouseController(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            this.gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            this.accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        } else {
            this.gyroSensor = null;
            this.accelSensor = null;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.sensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.6f);
        this.alwaysOn = prefs.getBoolean(KEY_ALWAYS_ON, false);
        this.invertX = prefs.getBoolean(KEY_INVERT_X, false);
        this.invertY = prefs.getBoolean(KEY_INVERT_Y, false);
    }

    boolean hasGyroscope() {
        return gyroSensor != null;
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    void setAngleListener(AngleListener listener) {
        this.angleListener = listener;
    }

    void setClutch(boolean engaged) {
        this.clutchEngaged = engaged;
        if (!engaged && !alwaysOn) {
            // Reset accumulators when releasing trigger so cursor stops cleanly
            smoothedYaw = 0f;
            smoothedPitch = 0f;
            accumX = 0f;
            accumY = 0f;
            lastSensorTimestamp = 0;
            if (angleListener != null) {
                angleListener.onAngleUpdate(0f, 0f, false);
            }
        }
    }

    boolean isClutchEngaged() {
        return clutchEngaged;
    }

    boolean isAlwaysOn() {
        return alwaysOn;
    }

    void setAlwaysOn(boolean alwaysOn) {
        this.alwaysOn = alwaysOn;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALWAYS_ON, alwaysOn).apply();
        if (!alwaysOn && !clutchEngaged) {
            setClutch(false);
        }
    }

    float getSensitivity() {
        return sensitivity;
    }

    void setSensitivity(float sensitivity) {
        this.sensitivity = Math.max(0.4f, Math.min(3.5f, sensitivity));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_SENSITIVITY, this.sensitivity).apply();
    }

    void calibrate() {
        // Zero all running state and recenter
        smoothedYaw = 0f;
        smoothedPitch = 0f;
        accumX = 0f;
        accumY = 0f;
        lastSensorTimestamp = 0;
        if (angleListener != null) {
            angleListener.onAngleUpdate(0f, 0f, clutchEngaged || alwaysOn);
        }
    }

    void start() {
        if (active || sensorManager == null || gyroSensor == null) return;
        active = true;
        lastSensorTimestamp = 0;
        accumX = 0f;
        accumY = 0f;
        smoothedYaw = 0f;
        smoothedPitch = 0f;
        // SENSOR_DELAY_GAME delivers ~50-100Hz with optimal battery consumption
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        Log.d(TAG, "Air mouse sensors started");
    }

    void stop() {
        if (!active || sensorManager == null) return;
        active = false;
        sensorManager.unregisterListener(this);
        smoothedYaw = 0f;
        smoothedPitch = 0f;
        accumX = 0f;
        accumY = 0f;
        lastSensorTimestamp = 0;
        if (angleListener != null) {
            angleListener.onAngleUpdate(0f, 0f, false);
        }
        Log.d(TAG, "Air mouse sensors stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Low-pass filter the gravity vector (alpha = 0.12)
            gravX += 0.12f * (event.values[0] - gravX);
            gravY += 0.12f * (event.values[1] - gravY);
            gravZ += 0.12f * (event.values[2] - gravZ);
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            boolean isAiming = clutchEngaged || alwaysOn;
            if (!isAiming) {
                lastSensorTimestamp = event.timestamp;
                if (angleListener != null) {
                    angleListener.onAngleUpdate(0f, 0f, false);
                }
                return;
            }

            long nowNs = event.timestamp;
            if (lastSensorTimestamp == 0) {
                lastSensorTimestamp = nowNs;
                return;
            }
            float dt = (nowNs - lastSensorTimestamp) * 1e-9f;
            lastSensorTimestamp = nowNs;
            if (dt <= 0f || dt > 0.08f) dt = 0.016f; // Clamp anomalous deltas

            float wx = event.values[0]; // Pitch rate (rad/s)
            float wy = event.values[1]; // Roll rate (rad/s)
            float wz = event.values[2]; // Yaw rate (rad/s)

            // Normalized gravity unit vector
            float gLen = (float) Math.hypot(gravX, Math.hypot(gravY, gravZ));
            float gx = 0f, gy = 1f, gz = 0f;
            if (gLen > 0.001f) {
                gx = gravX / gLen;
                gy = gravY / gLen;
                gz = gravZ / gLen;
            }

            // World Yaw: projection of 3D angular velocity onto earth-vertical gravity vector.
            // Turning wrist left/right rotates around earth gravity regardless of phone incline.
            float worldYaw = (wx * gx + wy * gy + wz * gz);

            // World Pitch: rotation around horizontal axis orthogonal to gravity.
            // In standard handheld orientation, this is predominantly wx.
            float worldPitch = wx;

            // Hand tremor deadzone
            if (Math.abs(worldYaw) < deadzone) worldYaw = 0f;
            if (Math.abs(worldPitch) < deadzone) worldPitch = 0f;

            // Velocity-adaptive exponential smoothing
            float speed = (float) Math.hypot(worldYaw, worldPitch);
            float alpha = Math.min(0.92f, Math.max(0.42f, speed * 2.2f));

            smoothedYaw += alpha * (worldYaw - smoothedYaw);
            smoothedPitch += alpha * (worldPitch - smoothedPitch);

            if (angleListener != null) {
                angleListener.onAngleUpdate(smoothedPitch, smoothedYaw, true);
            }

            // Scale angular displacement to mouse screen pixels (800 px per radian base)
            float scale = sensitivity * 800.0f;
            float dx = -smoothedYaw * dt * scale;
            float dy = -smoothedPitch * dt * scale;

            if (invertX) dx = -dx;
            if (invertY) dy = -dy;

            accumX += dx;
            accumY += dy;

            int stepX = (int) accumX;
            int stepY = (int) accumY;

            accumX -= stepX;
            accumY -= stepY;

            if ((stepX != 0 || stepY != 0) && callback != null) {
                callback.onMotion(stepX, stepY);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }
}
