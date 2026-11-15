package com.inpulync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.function.IntSupplier;

/** Popup of one-tap shortcuts: built-in ones plus the user's own. */
final class MacroPanel {
    private static final int COLUMNS = 4;

    private final Activity activity;
    private final NeoUi ui;
    private final ShortcutRunner runner;
    private final IntSupplier layout;
    private PopupWindow popup;

    MacroPanel(Activity activity, NeoUi ui, ShortcutRunner runner, IntSupplier layout) {
        this.activity = activity;
        this.ui = ui;
        this.runner = runner;
        this.layout = layout;
    }

    void dismiss() {
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
    }

    void show(View anchor) {
        if (popup != null) popup.dismiss();
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        card.setBackground(ui.card(Theme.current.green));

        card.addView(heading("SHORTCUTS"));
        addGrid(card, MacroStore.PRESETS, false);

        List<MacroStore.Macro> mine = MacroStore.load(activity);
        card.addView(heading("MY MACROS \\u00B7 HOLD TO DELETE"));
        if (!mine.isEmpty()) addGrid(card, mine.toArray(new MacroStore.Macro[0]), true);

        Button add = ui.button("+ NEW MACRO", Theme.current.yellow);
        add.setOnClickListener(v -> {
            if (MacroStore.load(activity).size() >= MacroStore.MAX_CUSTOM) {
                Toast.makeText(activity, "You can keep up to " + MacroStore.MAX_CUSTOM +
                        " macros. Hold one to delete it.", Toast.LENGTH_LONG).show();
            } else {
                editor(anchor);
            }
        });
        card.addView(add, new LinearLayout.LayoutParams(-1, ui.dp(46)));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(card, new ScrollView.LayoutParams(-1, ScrollView.LayoutParams.WRAP_CONTENT));
        popup = new PopupWindow(scroll, ui.dp(392), ui.dp(300), true);
        popup.setBackgroundDrawable(ui.rounded(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(ui.dp(12));
        popup.showAsDropDown(anchor, 0, ui.dp(4));
    }

    private TextView heading(String label) {
        TextView heading = ui.text(label, 11);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(2));
        return heading;
    }

    private void addGrid(LinearLayout card, MacroStore.Macro[] macros, boolean deletable) {
        LinearLayout row = null;
        for (int i = 0; i < macros.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row, new LinearLayout.LayoutParams(-1, ui.dp(46)));
            }
            MacroStore.Macro macro = macros[i];
            Button button = ui.button(macro.name, Theme.current.paper);
            button.setTextSize(10);
            button.setOnClickListener(v -> runner.runShortcut(macro.spec));
            if (deletable) button.setOnLongClickListener(v -> {
                confirmDelete(macro, v);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            params.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
            row.addView(button, params);
        }
        // Pad the last row so buttons keep a consistent width.
        int rest = macros.length % COLUMNS;
        if (rest != 0 && row != null) {
            for (int i = rest; i < COLUMNS; i++) {
                row.addView(new View(activity), new LinearLayout.LayoutParams(0, -1, 1));
            }
        }
    }

    private void confirmDelete(MacroStore.Macro macro, View anchor) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete \"" + macro.name + "\"?")
                .setPositiveButton("DELETE", (d, w) -> {
                    List<MacroStore.Macro> all = MacroStore.load(activity);
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).name.equals(macro.name) && all.get(i).spec.equals(macro.spec)) {
                            all.remove(i);
                            break;
                        }
                    }
                    MacroStore.save(activity, all);
                    show(anchor);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void editor(View anchor) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);

        EditText name = new EditText(activity);
        name.setHint("Name (up to " + MacroStore.MAX_NAME + " letters)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        name.setSingleLine(true);
        form.addView(name);

        EditText keys = new EditText(activity);
        keys.setHint("ctrl+shift+t   alt+tab   f5   text:Hello");
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setSingleLine(true);
        form.addView(keys);

        TextView help = new TextView(activity);
        help.setText("Keys: ctrl, shift, alt, win + a key such as a-z, 0-9, f1-f12, tab, enter, esc, " +
                "space, delete, home, end, up, down, left, right. Start with text: to type words.");
        help.setTextSize(12);
        help.setGravity(Gravity.START);
        help.setPadding(0, ui.dp(8), 0, 0);
        form.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("New macro")
                .setView(form)
                .setPositiveButton("SAVE", null)
                .setNegativeButton("CANCEL", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = MacroStore.clean(name.getText().toString());
            String spec = MacroStore.clean(keys.getText().toString());
            if (label.isEmpty()) {
                Toast.makeText(activity, "Give the macro a name", Toast.LENGTH_SHORT).show();
                return;
            }
            if (label.length() > MacroStore.MAX_NAME) label = label.substring(0, MacroStore.MAX_NAME);
            if (spec.length() > MacroStore.MAX_SPEC || ShortcutParser.parse(spec, layout.getAsInt()) == null) {
                Toast.makeText(activity, "Those keys were not understood", Toast.LENGTH_LONG).show();
                return;
            }
            List<MacroStore.Macro> all = MacroStore.load(activity);
            all.add(new MacroStore.Macro(label, spec));
            MacroStore.save(activity, all);
            dialog.dismiss();
            show(anchor);
        });
    }
}
