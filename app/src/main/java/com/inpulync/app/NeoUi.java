package com.inpulync.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

/** View factory and interaction styling: liquid-glass themes plus the classic neo look. */
final class NeoUi {
    private final Activity activity;
    private final FeedbackController feedback;
    private final int ink;

    NeoUi(Activity activity, FeedbackController feedback, int ink) {
        this.activity = activity;
        this.feedback = feedback;
        this.ink = ink;
    }

    Button button(String label, int color) {
        Button result = new Button(activity);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(12);
        result.setTextColor(ink);
        result.setTypeface(Typeface.DEFAULT_BOLD);
        result.setGravity(Gravity.CENTER);
        result.setSingleLine(!label.contains("\n"));
        result.setLineSpacing(0, 0.82f);
        result.setPadding(dp(5), 0, dp(5), dp(4));
        result.setMinHeight(0);
        result.setMinWidth(0);
        if (Theme.current.glass) result.setStateListAnimator(null);
        result.setBackground(interactiveBackground(color));
        result.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                feedback.perform(view, HapticFeedbackConstants.KEYBOARD_TAP);
                view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(55).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(75).start();
            }
            return false;
        });
        result.setOnHoverListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER)
                view.animate().scaleX(1.035f).scaleY(1.035f).setDuration(80).start();
            else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT)
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            return false;
        });
        return result;
    }

    RadioButton radio(String label, int id) {
        RadioButton result = new RadioButton(activity);
        result.setText(label);
        result.setId(id);
        result.setTextSize(12);
        result.setTextColor(ink);
        result.setTypeface(Typeface.DEFAULT_BOLD);
        result.setButtonTintList(ColorStateList.valueOf(ink));
        result.setPadding(0, 0, dp(3), 0);
        return result;
    }

    TextView text(String value, int sp) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(sp);
        result.setTextColor(ink);
        return result;
    }

    GradientDrawable rounded(int color) {
        if (Theme.current.glass) {
            int fill = glassFill(color);
            GradientDrawable shape = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{lighten(fill, 0.12f), fill});
            shape.setCornerRadius(dp(18));
            if (Color.alpha(color) != 0)
                shape.setStroke(dp(1), Color.argb(0x70, 255, 255, 255));
            return shape;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        if (color != Color.TRANSPARENT) shape.setStroke(dp(2), Theme.current.rim);
        return shape;
    }

    /** Large translucent panel (gamepad body and similar). */
    LayerDrawable background(int color) {
        return layered(color, false, false);
    }

    /** Popup card: nearly opaque so text stays readable over the keys underneath. */
    Drawable card(int tint) {
        Theme theme = Theme.current;
        if (!theme.glass) return background(tint);
        int base = Theme.mix(theme.bgBottom, tint, theme.dark ? 0.20f : 0.32f);
        int fill = Theme.withAlpha(base, 0xF2);
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.withAlpha(Theme.mix(base, Color.WHITE, 0.10f), 0xF2), fill});
        shape.setCornerRadius(dp(22));
        shape.setStroke(dp(1), Color.argb(0x70, 255, 255, 255));
        return shape;
    }

    StateListDrawable interactiveBackground(int color) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, layered(color, true, false));
        states.addState(new int[]{android.R.attr.state_hovered}, layered(color, false, true));
        states.addState(new int[]{android.R.attr.state_focused}, layered(color, false, true));
        states.addState(new int[]{}, background(color));
        return states;
    }

    int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private LayerDrawable layered(int color, boolean pressed, boolean raised) {
        if (Theme.current.glass) return glassLayers(color, pressed, raised);
        int faceLeft = pressed ? 4 : 0;
        int faceTop = pressed ? 4 : 0;
        int faceRight = pressed ? 1 : raised ? 7 : 5;
        int faceBottom = pressed ? 1 : raised ? 7 : 5;
        int stroke = raised ? 3 : 2;
        GradientDrawable shadow = rounded(Theme.current.rim);
        GradientDrawable face = rounded(color);
        face.setStroke(dp(stroke), Theme.current.rim);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{shadow, face});
        layers.setLayerInset(0, dp(5), dp(5), 0, 0);
        layers.setLayerInset(1, dp(faceLeft), dp(faceTop), dp(faceRight), dp(faceBottom));
        return layers;
    }

    private LayerDrawable glassLayers(int color, boolean pressed, boolean raised) {
        Theme theme = Theme.current;
        int fill = glassFill(color);
        if (pressed) fill = Theme.withAlpha(lighten(fill, 0.18f),
                Math.min(255, Color.alpha(fill) + 0x38));
        else if (raised) fill = Theme.withAlpha(fill, Math.min(255, Color.alpha(fill) + 0x18));
        int top = lighten(fill, pressed ? 0.30f : 0.20f);

        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(Color.argb(theme.dark ? 0x58 : 0x28, 0, 0, 0));
        shadow.setCornerRadius(dp(16));

        GradientDrawable face = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, fill});
        face.setCornerRadius(dp(16));
        face.setStroke(dp(1), Color.argb(pressed ? 0xA0 : 0x70, 255, 255, 255));

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(theme.dark ? 0x46 : 0x90, 255, 255, 255), Color.TRANSPARENT});
        sheen.setCornerRadius(dp(10));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{shadow, face, sheen});
        int lift = pressed ? 1 : 3;
        layers.setLayerInset(0, dp(1), dp(lift), dp(1), 0);
        layers.setLayerInset(1, 0, pressed ? dp(2) : 0, 0, pressed ? dp(1) : dp(3));
        layers.setLayerInset(2, dp(4), (pressed ? dp(2) : 0) + dp(2), dp(4), 0);
        layers.setLayerGravity(2, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        layers.setLayerHeight(2, dp(12));
        return layers;
    }

    /** Glass tint: translucent version of an accent; the neutral key stays frosted white. */
    private static int glassFill(int color) {
        int alpha = Color.alpha(color);
        if (alpha == 0 || alpha < 255) return color;
        Theme theme = Theme.current;
        float strength = theme.dark ? 0.36f : 0.66f;
        if (color == theme.paper) strength = theme.dark ? 0.15f : 0.58f;
        return Theme.withAlpha(color, Math.round(255 * strength));
    }

    private static int lighten(int color, float amount) {
        int alpha = Color.alpha(color);
        return Theme.withAlpha(Theme.mix(color, Color.WHITE, amount), alpha);
    }
}
