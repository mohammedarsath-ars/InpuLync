package com.inpulync.app;

import android.graphics.Color;

/** App-wide palette. Translucent glass themes with customizable dynamic hue. */
final class Theme {
    final String name;
    final boolean glass;
    final boolean dark;
    final int bgTop, bgBottom, blob1, blob2, blob3;
    final int ink, paper, green, yellow, coral, blue, bluePressed;
    final int surface, surfacePressed, surfaceDrag, onSurface;
    final int dpadBody, dpadMark, dpadMarkActive, rim;

    static final Theme[] ALL = {
        glassDark("Aurora", "#090D1F", "#1A1038", "#22D3C5", "#8B5CF6", "#F472B6",
                "#F4F6FF", "#5EEAD4", "#FDE68A", "#FDA4AF", "#93C5FD"),
        glassLight("Frost", "#E6EEFF", "#F6ECFF", "#7DD3FC", "#C4B5FD", "#FBCFE8",
                "#1B2340", "#86EFAC", "#FDE047", "#FDA4AF", "#93C5FD"),
        glassDark("Midnight", "#04060C", "#0B1220", "#1D4ED8", "#0EA5E9", "#6366F1",
                "#EAF0FF", "#67E8F9", "#FCD34D", "#FB7185", "#818CF8"),
        glassDark("Sunset", "#1F0A1E", "#3B0F2A", "#FB7185", "#F59E0B", "#C026D3",
                "#FFF3EC", "#FCD34D", "#FDBA74", "#FB7185", "#F0ABFC"),
        glassDark("Ocean", "#03181F", "#06303A", "#06B6D4", "#22D3EE", "#34D399",
                "#E8FBFF", "#6EE7B7", "#FDE68A", "#FDA4AF", "#7DD3FC"),
        glassDark("Forest", "#08170F", "#10291B", "#22C55E", "#A3E635", "#14B8A6",
                "#EFFFF3", "#86EFAC", "#FDE047", "#FCA5A5", "#5EEAD4"),
        glassLight("Sakura", "#FFF1F5", "#FDE7F3", "#F9A8D4", "#FDBA74", "#C4B5FD",
                "#3B1D2E", "#BBF7D0", "#FDE68A", "#FDA4AF", "#C4B5FD"),
        glassDark("AMOLED", "#000000", "#000000", "#FFFFFF", "#9CA3AF", "#6B7280",
                "#FFFFFF", "#86EFAC", "#FDE68A", "#FCA5A5", "#93C5FD"),
        custom(210, true)
    };

    /** The last entry is the user's own colour; earlier indexes stay stable for saved settings. */
    static final int CUSTOM_INDEX = ALL.length - 1;

    static Theme current = ALL[0];
    static int index = 0;

    /** Rebuilds the custom theme from a hue (0-359) and a dark/light choice. */
    static void setCustom(int hue, boolean dark) {
        ALL[CUSTOM_INDEX] = custom(hue, dark);
        if (index == CUSTOM_INDEX) current = ALL[CUSTOM_INDEX];
    }

    static void apply(int requested) {
        index = Math.max(0, Math.min(ALL.length - 1, requested));
        current = ALL[index];
    }

    // Convenience statics so views can read the active palette directly.
    static int ink() { return current.ink; }
    static int paper() { return current.paper; }
    static int green() { return current.green; }
    static int yellow() { return current.yellow; }
    static int coral() { return current.coral; }
    static int blue() { return current.blue; }

    private Theme(String name, boolean glass, boolean dark,
                  int bgTop, int bgBottom, int blob1, int blob2, int blob3,
                  int ink, int paper, int green, int yellow, int coral, int blue,
                  int bluePressed, int surface, int surfacePressed, int surfaceDrag,
                  int onSurface, int dpadBody, int dpadMark, int dpadMarkActive, int rim) {
        this.name = name;
        this.glass = glass;
        this.dark = dark;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.blob1 = blob1;
        this.blob2 = blob2;
        this.blob3 = blob3;
        this.ink = ink;
        this.paper = paper;
        this.green = green;
        this.yellow = yellow;
        this.coral = coral;
        this.blue = blue;
        this.bluePressed = bluePressed;
        this.surface = surface;
        this.surfacePressed = surfacePressed;
        this.surfaceDrag = surfaceDrag;
        this.onSurface = onSurface;
        this.dpadBody = dpadBody;
        this.dpadMark = dpadMark;
        this.dpadMarkActive = dpadMarkActive;
        this.rim = rim;
    }

    private static Theme glassDark(String name, String bgTop, String bgBottom,
                                   String b1, String b2, String b3, String ink,
                                   String green, String yellow, String coral, String blue) {
        int inkColor = Color.parseColor(ink);
        int blueColor = Color.parseColor(blue);
        return new Theme(name, true, true,
                Color.parseColor(bgTop), Color.parseColor(bgBottom),
                Color.parseColor(b1), Color.parseColor(b2), Color.parseColor(b3),
                inkColor, Color.WHITE, Color.parseColor(green), Color.parseColor(yellow),
                Color.parseColor(coral), blueColor, mix(blueColor, Color.WHITE, 0.35f),
                Color.argb(0x24, 255, 255, 255), Color.argb(0x3A, 255, 255, 255),
                Color.argb(0x52, 255, 255, 255), inkColor,
                Color.argb(0x45, 255, 255, 255), inkColor, Color.rgb(20, 20, 28),
                Color.argb(0x99, 255, 255, 255));
    }

    private static Theme glassLight(String name, String bgTop, String bgBottom,
                                    String b1, String b2, String b3, String ink,
                                    String green, String yellow, String coral, String blue) {
        int inkColor = Color.parseColor(ink);
        int blueColor = Color.parseColor(blue);
        return new Theme(name, true, false,
                Color.parseColor(bgTop), Color.parseColor(bgBottom),
                Color.parseColor(b1), Color.parseColor(b2), Color.parseColor(b3),
                inkColor, Color.WHITE, Color.parseColor(green), Color.parseColor(yellow),
                Color.parseColor(coral), blueColor, mix(blueColor, Color.BLACK, 0.18f),
                Color.argb(0x2E, 30, 40, 90), Color.argb(0x48, 30, 40, 90),
                Color.argb(0x62, 30, 40, 90), inkColor,
                Color.argb(0xA6, 255, 255, 255), inkColor, inkColor,
                Color.argb(0x88, 40, 60, 120));
    }

    private static Theme custom(int hueDegrees, boolean dark) {
        float h = ((hueDegrees % 360) + 360) % 360;
        if (dark) {
            return glassDark("Custom",
                    hex(h, 0.55f, 0.09f), hex(h + 25, 0.50f, 0.20f),
                    hex(h, 0.75f, 1f), hex(h + 55, 0.70f, 1f), hex(h - 55, 0.65f, 1f),
                    hex(h, 0.05f, 0.98f),
                    hex(h, 0.35f, 1f), hex(h + 50, 0.35f, 1f),
                    hex(h - 50, 0.35f, 1f), hex(h + 100, 0.35f, 1f));
        }
        return glassLight("Custom",
                hex(h, 0.10f, 0.97f), hex(h + 20, 0.16f, 0.94f),
                hex(h, 0.45f, 1f), hex(h + 55, 0.40f, 1f), hex(h - 55, 0.38f, 1f),
                hex(h, 0.60f, 0.22f),
                hex(h, 0.30f, 1f), hex(h + 50, 0.30f, 1f),
                hex(h - 50, 0.30f, 1f), hex(h + 100, 0.30f, 1f));
    }

    private static String hex(float hue, float saturation, float value) {
        float wrapped = ((hue % 360f) + 360f) % 360f;
        int color = Color.HSVToColor(new float[]{wrapped, saturation, value});
        return String.format("#%06X", color & 0xFFFFFF);
    }

    static int mix(int from, int to, float amount) {
        float inverse = 1f - amount;
        return Color.argb(
                Math.round(Color.alpha(from) * inverse + Color.alpha(to) * amount),
                Math.round(Color.red(from) * inverse + Color.red(to) * amount),
                Math.round(Color.green(from) * inverse + Color.green(to) * amount),
                Math.round(Color.blue(from) * inverse + Color.blue(to) * amount));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
