package com.inpulync.app;

/** Speed-based pointer gain: slow swipes stay precise, fast swipes cover more screen. */
final class PointerAcceleration {
    static final int OFF = 0;
    static final int LOW = 1;
    static final int MEDIUM = 2;
    static final int HIGH = 3;
    static final int LEVELS = 4;

    /** Finger speed (dp per millisecond) at which the full boost is reached. */
    private static final float FULL_BOOST_SPEED = 2.0f;

    private PointerAcceleration() {
    }

    static String name(int level) {
        switch (level) {
            case LOW: return "LOW";
            case MEDIUM: return "MED";
            case HIGH: return "HIGH";
            default: return "OFF";
        }
    }

    /** Extra multiplier on top of 1.0 at full speed. */
    private static float maxBoost(int level) {
        switch (level) {
            case LOW: return 0.8f;
            case MEDIUM: return 1.6f;
            case HIGH: return 2.6f;
            default: return 0f;
        }
    }

    /**
     * @param speedDpPerMs finger speed in dp per millisecond; negative or NaN counts as zero
     * @return multiplier of at least 1.0; exactly 1.0 when off or when the finger is slow
     */
    static float gain(int level, float speedDpPerMs) {
        float boost = maxBoost(level);
        if (boost == 0f || !(speedDpPerMs > 0f)) return 1f;
        float x = Math.min(1f, speedDpPerMs / FULL_BOOST_SPEED);
        float smooth = x * x * (3f - 2f * x);
        return 1f + boost * smooth;
    }
}
