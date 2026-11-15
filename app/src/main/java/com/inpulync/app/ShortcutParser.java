package com.inpulync.app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns text such as {@code ctrl+shift+t}, {@code alt+tab} or {@code text:Hello} into
 * something both transports can send: a HID modifier+usage for Bluetooth and a chord
 * string for the Windows helper.
 */
final class ShortcutParser {
    static final int CTRL = 0x01;
    static final int SHIFT = 0x02;
    static final int ALT = 0x04;
    static final int WIN = 0x08;

    private static final Map<String, Integer> USAGES = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        USAGES.put("ENTER", 0x28);
        USAGES.put("ESC", 0x29);
        USAGES.put("BACKSPACE", 0x2A);
        USAGES.put("TAB", 0x2B);
        USAGES.put("SPACE", 0x2C);
        USAGES.put("PRINTSCREEN", 0x46);
        USAGES.put("PAUSE", 0x48);
        USAGES.put("INSERT", 0x49);
        USAGES.put("HOME", 0x4A);
        USAGES.put("PGUP", 0x4B);
        USAGES.put("DELETE", 0x4C);
        USAGES.put("END", 0x4D);
        USAGES.put("PGDN", 0x4E);
        USAGES.put("RIGHT", 0x4F);
        USAGES.put("LEFT", 0x50);
        USAGES.put("DOWN", 0x51);
        USAGES.put("UP", 0x52);
        for (int i = 1; i <= 12; i++) USAGES.put("F" + i, 0x3A + i - 1);
        USAGES.put("NUMLOCK", 0x53);
        USAGES.put("DIVIDE", 0x54);
        USAGES.put("MULTIPLY", 0x55);
        USAGES.put("SUBTRACT", 0x56);
        USAGES.put("ADD", 0x57);
        for (int i = 0; i <= 9; i++) USAGES.put("NUMPAD" + i, (i == 0 ? 0x62 : 0x58 + i));
        USAGES.put("DECIMAL", 0x63);

        ALIASES.put("RETURN", "ENTER");
        ALIASES.put("ESCAPE", "ESC");
        ALIASES.put("BKSP", "BACKSPACE");
        ALIASES.put("DEL", "DELETE");
        ALIASES.put("INS", "INSERT");
        ALIASES.put("PAGEUP", "PGUP");
        ALIASES.put("PAGEDOWN", "PGDN");
        ALIASES.put("PRTSC", "PRINTSCREEN");
        ALIASES.put("PRINT", "PRINTSCREEN");
    }

    static final class Shortcut {
        /** HID modifier bits (ctrl 1, shift 2, alt 4, win 8, AltGr 0x40). */
        final int modifier;
        /** HID keyboard usage, or 0 for a text macro. */
        final int usage;
        /** Chord for the Windows helper's HOTKEY command, e.g. CTRL+SHIFT+ESC; null if unsupported. */
        final String usbChord;
        /** Text to type for a {@code text:} macro, otherwise null. */
        final String text;

        Shortcut(int modifier, int usage, String usbChord, String text) {
            this.modifier = modifier;
            this.usage = usage;
            this.usbChord = usbChord;
            this.text = text;
        }

        boolean isText() {
            return text != null;
        }
    }

    private ShortcutParser() {
    }

    /** @return the parsed shortcut, or null when {@code spec} is empty or not understood. */
    static Shortcut parse(String spec, int layout) {
        if (spec == null) return null;
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.regionMatches(true, 0, "text:", 0, 5)) {
            String body = trimmed.substring(5);
            return body.isEmpty() ? null : new Shortcut(0, 0, null, body);
        }

        String[] parts = trimmed.split("\\+", -1);
        if (parts.length > 5) return null;
        // "ctrl++" style: an empty last token means the key itself is '+'.
        int modifier = 0;
        StringBuilder chord = new StringBuilder();
        int keyIndex = parts.length - 1;
        String keyToken = parts[keyIndex].trim();
        if (keyToken.isEmpty() && keyIndex > 0) {
            keyToken = "+";
            keyIndex--;
        }
        for (int i = 0; i < keyIndex; i++) {
            String token = parts[i].trim().toLowerCase(Locale.ROOT);
            int bit = modifierBit(token);
            if (bit == 0) return null;
            if ((modifier & bit) == 0) chord.append(modifierName(bit)).append('+');
            modifier |= bit;
        }
        if (keyToken.isEmpty()) return null;

        String upper = keyToken.toUpperCase(Locale.ROOT);
        String canonical = ALIASES.containsKey(upper) ? ALIASES.get(upper) : upper;
        Integer usage = USAGES.get(canonical);
        if (usage != null) {
            return new Shortcut(modifier, usage, chord + canonical, null);
        }
        if (keyToken.length() == 1) {
            char c = keyToken.charAt(0);
            if (Character.isLetterOrDigit(c) && c < 128) {
                KeyStroke key = KeyStroke.forChar(Character.toLowerCase(c), layout);
                if (key == null) return null;
                return new Shortcut(modifier, key.usage, chord + upper, null);
            }
            KeyStroke key = KeyStroke.forChar(c, layout);
            if (key == null) return null;
            // Punctuation works over Bluetooth only: the helper's HOTKEY has no such keys.
            return new Shortcut(modifier | key.modifier, key.usage, null, null);
        }
        return null;
    }

    private static int modifierBit(String token) {
        switch (token) {
            case "ctrl":
            case "control": return CTRL;
            case "shift": return SHIFT;
            case "alt": return ALT;
            case "win":
            case "windows":
            case "super":
            case "cmd": return WIN;
            default: return 0;
        }
    }

    private static String modifierName(int bit) {
        switch (bit) {
            case CTRL: return "CTRL";
            case SHIFT: return "SHIFT";
            case ALT: return "ALT";
            default: return "WIN";
        }
    }
}
