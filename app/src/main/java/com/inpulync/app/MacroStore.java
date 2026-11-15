package com.inpulync.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Built-in shortcut buttons plus up to a dozen the user defines. */
final class MacroStore {
    static final int MAX_CUSTOM = 12;
    static final int MAX_NAME = 10;
    static final int MAX_SPEC = 200;

    static final class Macro {
        final String name;
        final String spec;

        Macro(String name, String spec) {
            this.name = name;
            this.spec = spec;
        }
    }

    static final Macro[] PRESETS = {
            new Macro("COPY", "ctrl+c"),
            new Macro("PASTE", "ctrl+v"),
            new Macro("CUT", "ctrl+x"),
            new Macro("UNDO", "ctrl+z"),
            new Macro("REDO", "ctrl+y"),
            new Macro("SELECT ALL", "ctrl+a"),
            new Macro("SAVE", "ctrl+s"),
            new Macro("FIND", "ctrl+f"),
            new Macro("ALT+TAB", "alt+tab"),
            new Macro("DESKTOP", "win+d"),
            new Macro("LOCK PC", "win+l"),
            new Macro("TASK MGR", "ctrl+shift+esc"),
            new Macro("SNIP", "win+shift+s"),
            new Macro("NEW TAB", "ctrl+t"),
            new Macro("CLOSE TAB", "ctrl+w"),
            new Macro("REOPEN TAB", "ctrl+shift+t"),
            new Macro("REFRESH", "f5"),
            new Macro("CLOSE WIN", "alt+f4"),
            new Macro("RUN", "win+r"),
            new Macro("EXPLORER", "win+e"),
    };

    private static final String KEY = "custom_macros";
    private static final char SEPARATOR = '\u0001';

    private MacroStore() {
    }

    /** Removes the characters the storage format uses as separators. */
    static String clean(String value) {
        if (value == null) return "";
        return value.replace(SEPARATOR, ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String encode(List<Macro> macros) {
        StringBuilder out = new StringBuilder();
        for (Macro macro : macros) {
            out.append(clean(macro.name)).append(SEPARATOR).append(clean(macro.spec)).append('\n');
        }
        return out.toString();
    }

    static List<Macro> decode(String stored) {
        List<Macro> macros = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return macros;
        for (String line : stored.split("\n")) {
            int cut = line.indexOf(SEPARATOR);
            if (cut <= 0 || cut == line.length() - 1) continue;
            String name = line.substring(0, cut).trim();
            String spec = line.substring(cut + 1).trim();
            if (name.isEmpty() || spec.isEmpty()) continue;
            macros.add(new Macro(truncate(name, MAX_NAME), truncate(spec, MAX_SPEC)));
            if (macros.size() >= MAX_CUSTOM) break;
        }
        return macros;
    }

    static List<Macro> load(Context context) {
        return decode(context.getSharedPreferences("controls", Context.MODE_PRIVATE)
                .getString(KEY, ""));
    }

    static void save(Context context, List<Macro> macros) {
        context.getSharedPreferences("controls", Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(macros)).apply();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
