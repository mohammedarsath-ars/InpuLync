package com.inpulync.app;

import java.util.ArrayList;
import java.util.List;

/** Converts pasted text into key presses for Bluetooth typing. */
final class TextPlanner {
    static final int MAX_CHARS = 2000;

    static final class Plan {
        /** Each step is {modifier, usage}: press, then release. */
        final List<int[]> steps = new ArrayList<>();
        /** Characters the chosen layout cannot type over Bluetooth (for example Tamil). */
        int skipped;
        /** Characters left over because the text was longer than MAX_CHARS. */
        int truncated;
    }

    private TextPlanner() {
    }

    static Plan plan(String text, int layout) {
        Plan plan = new Plan();
        if (text == null) return plan;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue;
            if (plan.steps.size() >= MAX_CHARS) {
                plan.truncated = text.length() - i;
                break;
            }
            KeyStroke key = KeyStroke.forChar(c, layout);
            if (key == null) {
                plan.skipped++;
                continue;
            }
            plan.steps.add(new int[]{key.modifier, key.usage});
        }
        return plan;
    }
}
