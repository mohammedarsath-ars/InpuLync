package com.inpulync.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Builds the complete Air Mouse & Presentation / Media surface layout.
 * Embeds the reactive gyro reticle, scroll strip, dedicated click buttons,
 * and presentation/media control decks.
 * Dynamically adapts between Portrait and Landscape layouts for optimal ergonomics.
 */
final class AirMousePanel {

    interface Host {
        void move(int dx, int dy);
        void clickLeft();
        void clickRight();
        void setMouseButton(String which, boolean down);
        void scroll(int amount);
        void sendKey(int modifier, int usage);
        void sendConsumer(int usage);
    }

    private final Activity activity;
    private final NeoUi neoUi;
    private final FeedbackController feedback;
    private final AirMouseController controller;
    private final Host host;

    private boolean showSlidesDeck = true;
    private LinearLayout deckContainer;
    private Button slidesTabBtn;
    private Button mediaTabBtn;
    private Button lockBtn;
    private Button sensBtn;

    AirMousePanel(Activity activity, NeoUi neoUi, FeedbackController feedback,
                  AirMouseController controller, Host host) {
        this.activity = activity;
        this.neoUi = neoUi;
        this.feedback = feedback;
        this.controller = controller;
        this.host = host;

        controller.setCallback(host::move);
    }

    View build() {
        int pad = dp(8);
        boolean isLandscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        if (isLandscape) {
            return buildLandscapeLayout(pad);
        } else {
            return buildPortraitLayout(pad);
        }
    }

    private View buildPortraitLayout(int pad) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, pad);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(buildReticleAndScroll(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildClickRow(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        root.addView(buildDeckTabs(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        deckContainer = new LinearLayout(activity);
        deckContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(deckContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        updateDeck();
        return root;
    }

    private View buildLandscapeLayout(int pad) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, 0, pad, pad);

        // Left Column: Top options bar + large Reticle & Scroll strip
        LinearLayout leftCol = new LinearLayout(activity);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.addView(buildTopBar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        leftCol.addView(buildReticleAndScroll(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(leftCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        // Right Column: Clicks + Presentation/Media Decks
        LinearLayout rightCol = new LinearLayout(activity);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setPadding(dp(8), 0, 0, 0);
        rightCol.addView(buildClickRow(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        rightCol.addView(buildDeckTabs(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        deckContainer = new LinearLayout(activity);
        deckContainer.setOrientation(LinearLayout.VERTICAL);
        rightCol.addView(deckContainer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(rightCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f));

        updateDeck();
        return root;
    }

    private View buildTopBar() {
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(4));

        Button calibBtn = neoUi.button("⟲ CENTER", Theme.current.paper);
        calibBtn.setOnClickListener(v -> {
            controller.calibrate();
            feedback.perform(v, HapticFeedbackConstants.KEYBOARD_TAP);
            Toast.makeText(activity, "Air Mouse Centered", Toast.LENGTH_SHORT).show();
        });
        topBar.addView(calibBtn, new LinearLayout.LayoutParams(dp(94), dp(38)));

        lockBtn = neoUi.button(controller.isAlwaysOn() ? "AIM: LOCKED" : "AIM: HOLD",
                controller.isAlwaysOn() ? Theme.current.yellow : Theme.current.blue);
        lockBtn.setOnClickListener(v -> {
            boolean next = !controller.isAlwaysOn();
            controller.setAlwaysOn(next);
            lockBtn.setText(next ? "AIM: LOCKED" : "AIM: HOLD");
            lockBtn.setBackground(neoUi.interactiveBackground(next ? Theme.current.yellow : Theme.current.blue));
            feedback.perform(v, HapticFeedbackConstants.KEYBOARD_TAP);
        });
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        lockParams.setMargins(dp(6), 0, dp(6), 0);
        topBar.addView(lockBtn, lockParams);

        sensBtn = neoUi.button(formatSens(controller.getSensitivity()), Theme.current.paper);
        sensBtn.setOnClickListener(v -> {
            float cur = controller.getSensitivity();
            float next = cur >= 2.5f ? 0.8f : cur + 0.4f;
            controller.setSensitivity(next);
            sensBtn.setText(formatSens(next));
            feedback.perform(v, HapticFeedbackConstants.KEYBOARD_TAP);
            Toast.makeText(activity, "Sensitivity: " + formatSens(next), Toast.LENGTH_SHORT).show();
        });
        topBar.addView(sensBtn, new LinearLayout.LayoutParams(dp(82), dp(38)));

        return topBar;
    }

    private View buildReticleAndScroll() {
        LinearLayout centerSurface = new LinearLayout(activity);
        centerSurface.setOrientation(LinearLayout.HORIZONTAL);

        AirMouseReticleView reticle = new AirMouseReticleView(activity, controller, feedback);
        reticle.setReticleCallback(new AirMouseReticleView.ReticleCallback() {
            @Override
            public void onLeftClick() {
                host.clickLeft();
            }

            @Override
            public void onRightClick() {
                host.clickRight();
            }
        });
        centerSurface.addView(reticle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView scrollStrip = neoUi.text("▲\n\nS\nC\nR\nO\nL\nL\n\n▼", 11);
        scrollStrip.setTypeface(Typeface.DEFAULT_BOLD);
        scrollStrip.setGravity(Gravity.CENTER);
        scrollStrip.setTextColor(Theme.current.ink);
        scrollStrip.setBackground(neoUi.rounded(Theme.current.blue));
        scrollStrip.setOnTouchListener(new ScrollPadListener(new ScrollPadListener.Host() {
            @Override
            public int dp(int value) {
                return AirMousePanel.this.dp(value);
            }

            @Override
            public int scrollPercent() {
                return 120;
            }

            @Override
            public void scroll(int amount) {
                host.scroll(amount);
            }

            @Override
            public void haptic(View view, int kind) {
                feedback.perform(view, kind);
            }

            @Override
            public void scrollVisual(View view, boolean pressed) {
                // Visual touch state handled by view animation
            }
        }));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT);
        scrollParams.setMargins(dp(6), 0, 0, 0);
        centerSurface.addView(scrollStrip, scrollParams);

        return centerSurface;
    }

    private View buildClickRow() {
        LinearLayout clickRow = new LinearLayout(activity);
        clickRow.setOrientation(LinearLayout.HORIZONTAL);
        clickRow.setPadding(0, dp(4), 0, dp(4));

        Button leftClick = neoUi.button("LEFT CLICK", Theme.current.yellow);
        leftClick.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                feedback.perform(view, HapticFeedbackConstants.KEYBOARD_TAP);
                host.setMouseButton("left", true);
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(45).start();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                host.setMouseButton("left", false);
                view.animate().scaleX(1f).scaleY(1f).setDuration(65).start();
                return true;
            }
            return false;
        });

        Button rightClick = neoUi.button("RIGHT CLICK", Theme.current.coral);
        rightClick.setOnClickListener(v -> host.clickRight());

        LinearLayout.LayoutParams clickParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        clickRow.addView(leftClick, clickParams);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        rightParams.setMargins(dp(6), 0, 0, 0);
        clickRow.addView(rightClick, rightParams);

        return clickRow;
    }

    private View buildDeckTabs() {
        LinearLayout deckTabs = new LinearLayout(activity);
        deckTabs.setOrientation(LinearLayout.HORIZONTAL);
        deckTabs.setPadding(0, 0, 0, dp(4));

        slidesTabBtn = neoUi.button("📊 SLIDES", showSlidesDeck ? Theme.current.blue : Theme.current.paper);
        slidesTabBtn.setOnClickListener(v -> {
            showSlidesDeck = true;
            updateDeck();
        });
        mediaTabBtn = neoUi.button("🍿 MEDIA", !showSlidesDeck ? Theme.current.blue : Theme.current.paper);
        mediaTabBtn.setOnClickListener(v -> {
            showSlidesDeck = false;
            updateDeck();
        });

        LinearLayout.LayoutParams tabL = new LinearLayout.LayoutParams(0, dp(36), 1);
        deckTabs.addView(slidesTabBtn, tabL);
        LinearLayout.LayoutParams tabR = new LinearLayout.LayoutParams(0, dp(36), 1);
        tabR.setMargins(dp(6), 0, 0, 0);
        deckTabs.addView(mediaTabBtn, tabR);

        return deckTabs;
    }

    private void updateDeck() {
        if (deckContainer == null) return;
        deckContainer.removeAllViews();

        if (slidesTabBtn != null)
            slidesTabBtn.setBackground(neoUi.interactiveBackground(showSlidesDeck ? Theme.current.blue : Theme.current.paper));
        if (mediaTabBtn != null)
            mediaTabBtn.setBackground(neoUi.interactiveBackground(!showSlidesDeck ? Theme.current.blue : Theme.current.paper));

        if (showSlidesDeck) {
            // Presentation Slides Controls
            LinearLayout row1 = new LinearLayout(activity);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setPadding(0, 0, 0, dp(4));

            Button prevSlide = neoUi.button("◀ PREV SLIDE", Theme.current.paper);
            prevSlide.setOnClickListener(v -> host.sendKey(0, 0x50)); // Left Arrow
            Button nextSlide = neoUi.button("NEXT SLIDE ▶", Theme.current.yellow);
            nextSlide.setOnClickListener(v -> host.sendKey(0, 0x4F)); // Right Arrow

            LinearLayout.LayoutParams r1Params = new LinearLayout.LayoutParams(0, dp(40), 1);
            row1.addView(prevSlide, r1Params);
            LinearLayout.LayoutParams r1Next = new LinearLayout.LayoutParams(0, dp(40), 1);
            r1Next.setMargins(dp(6), 0, 0, 0);
            row1.addView(nextSlide, r1Next);
            deckContainer.addView(row1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout row2 = new LinearLayout(activity);
            row2.setOrientation(LinearLayout.HORIZONTAL);

            Button f5Btn = neoUi.button("▶ F5 START", Theme.current.green);
            f5Btn.setOnClickListener(v -> host.sendKey(0, 0x3E)); // F5

            Button blankBtn = neoUi.button("⬛ BLANK (B)", Theme.current.paper);
            blankBtn.setOnClickListener(v -> host.sendKey(0, 0x05)); // 'b'

            Button escBtn = neoUi.button("ESC EXIT", Theme.current.coral);
            escBtn.setOnClickListener(v -> host.sendKey(0, 0x29)); // Esc

            LinearLayout.LayoutParams r2L = new LinearLayout.LayoutParams(0, dp(40), 1);
            row2.addView(f5Btn, r2L);
            LinearLayout.LayoutParams r2M = new LinearLayout.LayoutParams(0, dp(40), 1);
            r2M.setMargins(dp(6), 0, 0, 0);
            row2.addView(blankBtn, r2M);
            LinearLayout.LayoutParams r2R = new LinearLayout.LayoutParams(0, dp(40), 1);
            r2R.setMargins(dp(6), 0, 0, 0);
            row2.addView(escBtn, r2R);
            deckContainer.addView(row2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            // Media Controls
            LinearLayout row1 = new LinearLayout(activity);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setPadding(0, 0, 0, dp(4));

            Button prevBtn = neoUi.button("⏮ PREV", Theme.current.paper);
            prevBtn.setOnClickListener(v -> host.sendConsumer(0xB6));
            Button playBtn = neoUi.button("⏯ PLAY / PAUSE", Theme.current.yellow);
            playBtn.setOnClickListener(v -> host.sendConsumer(0xCD));
            Button nextBtn = neoUi.button("⏭ NEXT", Theme.current.paper);
            nextBtn.setOnClickListener(v -> host.sendConsumer(0xB5));

            LinearLayout.LayoutParams m1L = new LinearLayout.LayoutParams(0, dp(40), 1);
            row1.addView(prevBtn, m1L);
            LinearLayout.LayoutParams m1M = new LinearLayout.LayoutParams(0, dp(40), 1.3f);
            m1M.setMargins(dp(6), 0, 0, 0);
            row1.addView(playBtn, m1M);
            LinearLayout.LayoutParams m1R = new LinearLayout.LayoutParams(0, dp(40), 1);
            m1R.setMargins(dp(6), 0, 0, 0);
            row1.addView(nextBtn, m1R);
            deckContainer.addView(row1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout row2 = new LinearLayout(activity);
            row2.setOrientation(LinearLayout.HORIZONTAL);

            Button volDown = neoUi.button("VOL −", Theme.current.coral);
            volDown.setOnClickListener(v -> host.sendConsumer(0xEA));
            Button mute = neoUi.button("MUTE", Theme.current.yellow);
            mute.setOnClickListener(v -> host.sendConsumer(0xE2));
            Button volUp = neoUi.button("VOL +", Theme.current.green);
            volUp.setOnClickListener(v -> host.sendConsumer(0xE9));
            Button fullBtn = neoUi.button("⛶ FULL", Theme.current.blue);
            fullBtn.setOnClickListener(v -> host.sendKey(0, 0x09)); // 'f'

            LinearLayout.LayoutParams m2L = new LinearLayout.LayoutParams(0, dp(40), 1);
            row2.addView(volDown, m2L);
            LinearLayout.LayoutParams m2M1 = new LinearLayout.LayoutParams(0, dp(40), 1);
            m2M1.setMargins(dp(6), 0, 0, 0);
            row2.addView(mute, m2M1);
            LinearLayout.LayoutParams m2M2 = new LinearLayout.LayoutParams(0, dp(40), 1);
            m2M2.setMargins(dp(6), 0, 0, 0);
            row2.addView(volUp, m2M2);
            LinearLayout.LayoutParams m2R = new LinearLayout.LayoutParams(0, dp(40), 1);
            m2R.setMargins(dp(6), 0, 0, 0);
            row2.addView(fullBtn, m2R);
            deckContainer.addView(row2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private String formatSens(float s) {
        return String.format(java.util.Locale.US, "%.1fx SENS", s);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
