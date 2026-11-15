package com.inpulync.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Theme swatches plus, for the Custom theme, a hue slider and a dark/light switch. */
final class ThemePicker {
    interface Host {
        /** A different theme was chosen. */
        void onThemeSelected(int index);

        /** The custom theme's colours changed; rebuild the screen. */
        void onCustomChanged();
    }

    private final Context context;
    private final NeoUi ui;
    private final Host host;

    ThemePicker(Context context, NeoUi ui, Host host) {
        this.context = context;
        this.ui = ui;
        this.host = host;
    }

    /** Restores the saved custom colour; call before {@link Theme#apply}. */
    static void loadCustom(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        Theme.setCustom(prefs.getInt("custom_hue", 210), prefs.getBoolean("custom_dark", true));
    }

    View build() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView label = ui.text("THEME \u00B7 " + Theme.current.name.toUpperCase(), 12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Theme.current.ink);
        column.addView(label, new LinearLayout.LayoutParams(-1, ui.dp(22)));

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout swatches = new LinearLayout(context);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < Theme.ALL.length; i++) swatches.addView(swatch(i));
        scroller.addView(swatches, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, -1));
        column.addView(scroller, new LinearLayout.LayoutParams(-1, ui.dp(44)));

        if (Theme.index == Theme.CUSTOM_INDEX) column.addView(customControls());
        return column;
    }

    private View swatch(int index) {
        Theme option = Theme.ALL[index];
        boolean selected = index == Theme.index;
        View swatch = new View(context);
        GradientDrawable dot = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{option.bgTop, option.blob1, option.blob2});
        dot.setShape(GradientDrawable.OVAL);
        dot.setStroke(ui.dp(selected ? 3 : 1),
                selected ? Theme.current.ink : Color.argb(0x88, 128, 128, 128));
        swatch.setBackground(dot);
        swatch.setOnClickListener(v -> host.onThemeSelected(index));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
        params.setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2));
        swatch.setLayoutParams(params);
        return swatch;
    }

    private View customControls() {
        SharedPreferences prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        int hue = prefs.getInt("custom_hue", 210);
        boolean dark = prefs.getBoolean("custom_dark", true);

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = ui.text("HUE " + hue + "\u00B0", 12);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setTextColor(Theme.current.ink);
        row.addView(value, new LinearLayout.LayoutParams(ui.dp(70), -2));

        SeekBar slider = new SeekBar(context);
        slider.setMax(359);
        slider.setProgress(hue);
        ColorStateList tint = ColorStateList.valueOf(Theme.current.ink);
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText("HUE " + progress + "\u00B0");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt("custom_hue", bar.getProgress()).apply();
                Theme.setCustom(bar.getProgress(), prefs.getBoolean("custom_dark", true));
                host.onCustomChanged();
            }
        });
        row.addView(slider, new LinearLayout.LayoutParams(0, ui.dp(40), 1));

        Button mode = ui.button(dark ? "DARK" : "LIGHT", Theme.current.paper);
        mode.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("custom_dark", true);
            prefs.edit().putBoolean("custom_dark", next).apply();
            Theme.setCustom(prefs.getInt("custom_hue", 210), next);
            host.onCustomChanged();
        });
        row.addView(mode, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(38)));
        return row;
    }
}
