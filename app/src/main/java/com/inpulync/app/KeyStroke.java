package com.inpulync.app;

/** Converts printable characters into USB HID keyboard usages for the PC's layout. */
final class KeyStroke {
    static final int LAYOUT_US = 0;
    static final int LAYOUT_UK = 1;
    static final int LAYOUT_COUNT = 2;

    final int modifier;
    final int usage;

    private KeyStroke(int modifier, int usage) {
        this.modifier = modifier;
        this.usage = usage;
    }

    static String layoutName(int layout) {
        return layout == LAYOUT_UK ? "UK" : "US";
    }

    /**
     * The key presses that make the PC type {@code c} when the PC uses {@code layout}.
     * Only the keys that differ from US are listed for UK; everything else falls through.
     */
    static KeyStroke forChar(char c, int layout) {
        if (layout == LAYOUT_UK) {
            switch (c) {
                case '"': return new KeyStroke(0x02, 0x1F);
                case '@': return new KeyStroke(0x02, 0x34);
                case '\u00A3': return new KeyStroke(0x02, 0x20);   // pound sign
                case '#': return new KeyStroke(0, 0x32);
                case '~': return new KeyStroke(0x02, 0x32);
                case '\\': return new KeyStroke(0, 0x64);
                case '|': return new KeyStroke(0x02, 0x64);
                case '\u00AC': return new KeyStroke(0x02, 0x35);   // not sign
                case '\u20AC': return new KeyStroke(0x40, 0x21);   // euro sign (AltGr+4)
                default: break;
            }
        }
        return forChar(c);
    }

    /** US layout. */
    static KeyStroke forChar(char c) {
        if (c >= 'a' && c <= 'z') return new KeyStroke(0, 0x04 + c - 'a');
        if (c >= 'A' && c <= 'Z') return new KeyStroke(0x02, 0x04 + c - 'A');
        if (c >= '1' && c <= '9') return new KeyStroke(0, 0x1E + c - '1');
        if (c == '0') return new KeyStroke(0, 0x27);
        switch (c) {
            case '\n': return new KeyStroke(0, 0x28);
            case '\t': return new KeyStroke(0, 0x2B);
            case ' ': return new KeyStroke(0, 0x2C);
            case '-': return new KeyStroke(0, 0x2D);
            case '_': return new KeyStroke(0x02, 0x2D);
            case '=': return new KeyStroke(0, 0x2E);
            case '+': return new KeyStroke(0x02, 0x2E);
            case '[': return new KeyStroke(0, 0x2F);
            case '{': return new KeyStroke(0x02, 0x2F);
            case ']': return new KeyStroke(0, 0x30);
            case '}': return new KeyStroke(0x02, 0x30);
            case '\\': return new KeyStroke(0, 0x31);
            case '|': return new KeyStroke(0x02, 0x31);
            case ';': return new KeyStroke(0, 0x33);
            case ':': return new KeyStroke(0x02, 0x33);
            case '\'': return new KeyStroke(0, 0x34);
            case '"': return new KeyStroke(0x02, 0x34);
            case '`': return new KeyStroke(0, 0x35);
            case '~': return new KeyStroke(0x02, 0x35);
            case ',': return new KeyStroke(0, 0x36);
            case '<': return new KeyStroke(0x02, 0x36);
            case '.': return new KeyStroke(0, 0x37);
            case '>': return new KeyStroke(0x02, 0x37);
            case '/': return new KeyStroke(0, 0x38);
            case '?': return new KeyStroke(0x02, 0x38);
            case '!': return new KeyStroke(0x02, 0x1E);
            case '@': return new KeyStroke(0x02, 0x1F);
            case '#': return new KeyStroke(0x02, 0x20);
            case '$': return new KeyStroke(0x02, 0x21);
            case '%': return new KeyStroke(0x02, 0x22);
            case '^': return new KeyStroke(0x02, 0x23);
            case '&': return new KeyStroke(0x02, 0x24);
            case '*': return new KeyStroke(0x02, 0x25);
            case '(': return new KeyStroke(0x02, 0x26);
            case ')': return new KeyStroke(0x02, 0x27);
            default: return null;
        }
    }
}
