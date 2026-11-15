package com.inpulync.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements TrackpadGestureListener.Host,
        ScrollPadListener.Host {
    private static final int REQUEST_BT = 100;
    private static final int MODE_OFF = 0;
    private static final int MODE_BLUETOOTH = 1;
    private static final int MODE_USB = 2;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView systemStatsView;
    private TextView bluetoothStateView;
    private RadioButton bluetoothModeButton;
    private Button layoutSwitch;
    private LinearLayout normalTopBar;
    private LinearLayout controllerTopBar;
    private LinearLayout inputContainer;
    private LinearLayout pairedDevices;
    private AuroraView aurora;
    private RadioGroup modeGroup;
    private ShortcutRunner shortcuts;
    private MacroPanel macroPanel;
    private int hostLayout;
    private int accelLevel;
    private Button settingsMenuButton;
    private boolean auroraAnimated = true;
    private volatile int mode = MODE_OFF;
    private volatile String pcStats = "LAPTOP BAT —";

    private BluetoothHidController bluetooth;
    private UsbBridgeServer usb;
    private FeedbackController feedback;
    private NeoUi neoUi;
    private ControllerPanel controllerPanel;
    private boolean shiftOn;
    private boolean capsOn;
    private boolean ctrlOn;
    private boolean altOn;
    private boolean winOn;
    private int mouseButtons;
    private boolean trackpadOnRight;
    private PopupWindow trackpadPopup;
    private PopupWindow toolsPopup;
    private PopupWindow systemPopup;
    private PopupWindow settingsPopup;
    private PopupWindow functionPopup;
    private PopupWindow numpadPopup;
    private PopupWindow bluetoothPopup;
    private final Map<String, TextView> settingValueViews = new HashMap<>();
    private int dragHoldMs = 500;
    private int pointerPercent = 100;
    private int scrollPercent = 100;
    private int repeatMs = 55;
    private boolean hapticsOn = true;
    private boolean laptopClicksOn = true;
    private boolean controllerLayout;
    private boolean swapGamepadControls;
    private int gamepadLabelStyle;
    private final List<Button> shiftButtons = new ArrayList<>();
    private final List<Button> capsButtons = new ArrayList<>();
    private final List<Button> ctrlButtons = new ArrayList<>();
    private final List<Button> altButtons = new ArrayList<>();
    private final List<Button> winButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemePicker.loadCustom(this);
        Theme.apply(getSharedPreferences("controls", MODE_PRIVATE).getInt("theme", 0));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bluetooth = HidHub.get(this);
        bluetooth.setListener(new BluetoothHidController.Listener() {
            @Override public void onStateChanged() {
                syncModeFromPrefs();
                refreshBondedDevices();
                updateStatus();
            }

            @Override public void onInputConnected(String hostName) {
                Toast.makeText(MainActivity.this,
                        "Bluetooth input connected ✓\n" + hostName,
                        Toast.LENGTH_LONG).show();
            }
        });
        loadSettings();
        usb = new UsbBridgeServer(() -> UsbPairing.code(this), new UsbBridgeServer.Listener() {
            @Override public void onHostLine(String line) {
                handleHostLine(line);
            }

            @Override public void onStateChanged() {
                if (!usb.hasClients()) pcStats = "LAPTOP BAT —";
                updateStatus();
            }
        });
        shortcuts = new ShortcutRunner(this, bluetooth, usb, new ShortcutRunner.Host() {
            @Override public boolean usbMode() { return mode == MODE_USB; }
            @Override public boolean bluetoothMode() { return mode == MODE_BLUETOOTH; }
            @Override public int hostLayout() { return hostLayout; }
        });
        macroPanel = new MacroPanel(this, neoUi, shortcuts, () -> hostLayout);
        feedback = new FeedbackController(this,
                () -> usb.send("SOUND KEY"));
        feedback.setEnabled(hapticsOn);
        feedback.setLaptopClicksEnabled(laptopClicksOn);
        neoUi = new NeoUi(this, feedback, Theme.current.ink);
        View mainUi = buildUi();
        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.addView(mainUi, new FrameLayout.LayoutParams(-1, -1));
        View splash = buildSplashScreen(rootFrame);
        rootFrame.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);
        applySystemBars();
        usb.start();
        requestBluetoothPermission();
    }

    private boolean splashDismissed;

    private View buildSplashScreen(FrameLayout parent) {
        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(Color.parseColor("#05070D"));
        splash.setClickable(true);
        splash.setFocusable(true);

        ImageView logo = new ImageView(this);
        int resId = getResources().getIdentifier("inpulync_logo", "drawable", getPackageName());
        if (resId != 0) {
            logo.setImageResource(resId);
        }
        int logoSize = dp(170);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.bottomMargin = dp(14);
        splash.addView(logo, logoParams);

        TextView brand = text("INPULYNC", 24);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(Color.parseColor("#FDE68A"));
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.2f);
        splash.addView(brand, new LinearLayout.LayoutParams(-2, -2));

        TextView tag = text("YOUR PHONE IS THE PERIPHERAL", 10);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(Color.parseColor("#94A3B8"));
        tag.setGravity(Gravity.CENTER);
        tag.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
        tagParams.topMargin = dp(6);
        splash.addView(tag, tagParams);

        splash.setOnClickListener(v -> dismissSplash(parent, splash));

        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.setAlpha(0f);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).start();

        brand.setAlpha(0f);
        brand.animate().alpha(1f).setDuration(550).start();
        tag.setAlpha(0f);
        tag.animate().alpha(1f).setDuration(600).start();

        uiHandler.postDelayed(() -> dismissSplash(parent, splash), 1350);

        return splash;
    }

    private void dismissSplash(FrameLayout parent, View splash) {
        if (splashDismissed || splash == null) return;
        splashDismissed = true;
        splash.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            try {
                parent.removeView(splash);
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bluetooth.setForeground(true);
        bluetooth.setActive(mode == MODE_BLUETOOTH);
        syncModeFromPrefs();
        syncServiceWithMode();
        if (aurora != null) aurora.setRunning(true);
    }

    @Override
    protected void onPause() {
        if (controllerPanel != null) controllerPanel.releaseAll();
        // With the background service running the link stays alive off-screen.
        if (!HidService.isRunning()) bluetooth.setForeground(false);
        if (aurora != null) aurora.setRunning(false);
        dismissAllPopups();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        dismissAllPopups();
        setContentView(buildUi());
        applySystemBars();
        updateStatus();
    }

    private void dismissAllPopups() {
        if (trackpadPopup != null) { try { trackpadPopup.dismiss(); } catch (Exception ignored) {} trackpadPopup = null; }
        if (toolsPopup != null) { try { toolsPopup.dismiss(); } catch (Exception ignored) {} toolsPopup = null; }
        if (systemPopup != null) { try { systemPopup.dismiss(); } catch (Exception ignored) {} systemPopup = null; }
        if (settingsPopup != null) { try { settingsPopup.dismiss(); } catch (Exception ignored) {} settingsPopup = null; }
        if (functionPopup != null) { try { functionPopup.dismiss(); } catch (Exception ignored) {} functionPopup = null; }
        if (numpadPopup != null) { try { numpadPopup.dismiss(); } catch (Exception ignored) {} numpadPopup = null; }
        if (bluetoothPopup != null) { try { bluetoothPopup.dismiss(); } catch (Exception ignored) {} bluetoothPopup = null; }
        if (macroPanel != null) { try { macroPanel.dismiss(); } catch (Exception ignored) {} }
    }

    private void setMode(int nextMode) {
        if (mode == MODE_BLUETOOTH && nextMode != MODE_BLUETOOTH &&
                controllerPanel != null) controllerPanel.releaseAll();
        mode = nextMode;
        getSharedPreferences("controls", MODE_PRIVATE).edit()
                .putInt("active_mode", mode).apply();
        bluetooth.setActive(mode == MODE_BLUETOOTH);
        syncServiceWithMode();
        updateStatus();
    }

    private View buildPortraitUi() {
        int pad = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button settingsMenu = neoButton("SET", Theme.current.paper);
        settingsMenuButton = settingsMenu;
        settingsMenu.setOnClickListener(this::showSettingsPopup);
        top.addView(settingsMenu, new LinearLayout.LayoutParams(dp(54), dp(48)));

        Button toolsMenu = neoButton("TOOLS ▾", Theme.current.yellow);
        toolsMenu.setOnClickListener(this::showToolsPopup);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(dp(86), dp(48));
        toolsParams.setMargins(dp(6), 0, 0, 0);
        top.addView(toolsMenu, toolsParams);

        TextView title = text("InpuLync", 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.current.ink);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button modeBtn = neoButton(mode == MODE_BLUETOOTH ? "BT ✓" : mode == MODE_USB ? "USB ✓" : "OFF",
                Theme.current.blue);
        modeBtn.setOnClickListener(v -> {
            int nextMode = mode == MODE_BLUETOOTH ? MODE_USB : mode == MODE_USB ? MODE_OFF : MODE_BLUETOOTH;
            setMode(nextMode);
            modeBtn.setText(nextMode == MODE_BLUETOOTH ? "BT ✓" : nextMode == MODE_USB ? "USB ✓" : "OFF");
        });
        top.addView(modeBtn, new LinearLayout.LayoutParams(dp(64), dp(48)));

        root.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        status = text(bluetooth != null ? bluetooth.shortStatus() : "Starting input…", 11);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Theme.current.ink);
        status.setGravity(Gravity.CENTER);
        status.setOnClickListener(v -> quickConnect());
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout surfaces = new LinearLayout(this);
        surfaces.setOrientation(LinearLayout.HORIZONTAL);
        surfaces.setPadding(0, dp(6), 0, dp(6));

        TextView trackpad = text("TRACKPAD\n\nTap to click · Hold / double-tap to drag\n2 fingers to scroll · 3 fingers to swipe", 14);
        trackpad.setTypeface(Typeface.DEFAULT_BOLD);
        trackpad.setGravity(Gravity.CENTER);
        trackpad.setTextColor(Theme.current.onSurface);
        trackpad.setBackground(rounded(Theme.current.surface));
        trackpad.setOnTouchListener(new TrackpadGestureListener(uiHandler, this));
        surfaces.addView(trackpad, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView scrollPad = text("▲\n\nS\nC\nR\nO\nL\nL\n\n▼", 11);
        scrollPad.setTypeface(Typeface.DEFAULT_BOLD);
        scrollPad.setGravity(Gravity.CENTER);
        scrollPad.setTextColor(Theme.current.ink);
        scrollPad.setBackground(rounded(Theme.current.blue));
        scrollPad.setOnTouchListener(new ScrollPadListener(this));
        LinearLayout.LayoutParams scrollPadParams = new LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT);
        scrollPadParams.setMargins(dp(6), 0, 0, 0);
        surfaces.addView(scrollPad, scrollPadParams);

        root.addView(surfaces, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout clicks = new LinearLayout(this);
        clicks.setOrientation(LinearLayout.HORIZONTAL);
        Button left = neoButton("LEFT CLICK", Theme.current.yellow);
        left.setOnClickListener(v -> click("left"));
        Button right = neoButton("RIGHT CLICK", Theme.current.coral);
        right.setOnClickListener(v -> click("right"));
        clicks.addView(left, weighted());
        clicks.addView(right, weighted());
        root.addView(clicks, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout mediaBar = new LinearLayout(this);
        mediaBar.setOrientation(LinearLayout.HORIZONTAL);
        mediaBar.setPadding(0, dp(4), 0, 0);
        addSystemKey(mediaBar, "⏮", "MEDIA_PREV", 0xB6, Theme.current.paper);
        addSystemKey(mediaBar, "⏯", "MEDIA_PLAY_PAUSE", 0xCD, Theme.current.yellow);
        addSystemKey(mediaBar, "⏭", "MEDIA_NEXT", 0xB5, Theme.current.paper);
        addSystemKey(mediaBar, "VOL −", "VOLUME_DOWN", 0xEA, Theme.current.coral);
        addSystemKey(mediaBar, "MUTE", "VOLUME_MUTE", 0xE2, Theme.current.yellow);
        addSystemKey(mediaBar, "VOL +", "VOLUME_UP", 0xE9, Theme.current.green);
        root.addView(mediaBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        TextView hint = text("Rotate landscape for full Keyboard & Gamepad ↷", 11);
        hint.setTextColor(Theme.current.ink);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(4), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        FrameLayout container = new FrameLayout(this);
        aurora = new AuroraView(this);
        aurora.setAnimated(auroraAnimated);
        aurora.setRunning(true);
        container.addView(aurora, new FrameLayout.LayoutParams(-1, -1));
        container.addView(root, new FrameLayout.LayoutParams(-1, -1));
        return container;
    }

    private View buildUi() {
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            return buildPortraitUi();
        }
        int pad = dp(8);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        normalTopBar = top;
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button trackpadMenu = neoButton("TRACKPAD ▾", Theme.current.green);
        trackpadMenu.setOnClickListener(this::showTrackpadPopup);
        top.addView(trackpadMenu, new LinearLayout.LayoutParams(dp(116), dp(54)));

        Button toolsMenu = neoButton("TOOLS ▾", Theme.current.yellow);
        toolsMenu.setOnClickListener(this::showToolsPopup);
        top.addView(toolsMenu, new LinearLayout.LayoutParams(dp(96), dp(54)));

        Button settingsMenu = neoButton("SET", Theme.current.paper);
        settingsMenuButton = settingsMenu;
        settingsMenu.setOnClickListener(this::showSettingsPopup);
        top.addView(settingsMenu, new LinearLayout.LayoutParams(dp(60), dp(54)));

        layoutSwitch = neoButton("GAMEPAD", Theme.current.blue);
        layoutSwitch.setOnClickListener(v -> showInputLayout(true, true));
        top.addView(layoutSwitch, new LinearLayout.LayoutParams(dp(78), dp(54)));

        TextView title = text("InpuLync", 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.current.ink);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(dp(96), dp(54)));

        status = text("Starting local input services…", 11);
        status.setTextSize(10);
        status.setTextColor(Theme.current.ink);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(2);
        status.setOnClickListener(v -> quickConnect());
        top.addView(status, new LinearLayout.LayoutParams(0, dp(54), 1));

        RadioGroup modes = new RadioGroup(this);
        modeGroup = modes;
        modes.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton off = radio("Off", MODE_OFF);
        bluetoothModeButton = radio("BT", MODE_BLUETOOTH);
        RadioButton usb = radio("USB", MODE_USB);
        modes.addView(off);
        modes.addView(bluetoothModeButton);
        modes.addView(usb);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (mode == MODE_BLUETOOTH && checkedId != MODE_BLUETOOTH &&
                    controllerPanel != null) controllerPanel.releaseAll();
            mode = checkedId;
            getSharedPreferences("controls", MODE_PRIVATE).edit()
                    .putInt("active_mode", mode).apply();
            bluetooth.setActive(mode == MODE_BLUETOOTH);
            syncServiceWithMode();
            updateStatus();
        });
        if (mode == MODE_BLUETOOTH) bluetoothModeButton.setChecked(true);
        else if (mode == MODE_USB) usb.setChecked(true);
        else off.setChecked(true);
        top.addView(modes, new LinearLayout.LayoutParams(dp(165), dp(54)));

        Button pair = neoButton("PAIR", Theme.current.blue);
        pair.setOnClickListener(this::showBluetoothPopup);
        top.addView(pair, new LinearLayout.LayoutParams(dp(64), dp(54)));
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        controllerTopBar = new LinearLayout(this);
        controllerTopBar.setGravity(Gravity.CENTER);
        controllerTopBar.setMotionEventSplittingEnabled(true);
        controllerTopBar.addView(controllerShoulder("L2", 7, Theme.current.blue),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        controllerTopBar.addView(controllerShoulder("L1", 5, Theme.current.paper),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        Button keys = neoButton("KEYS", Theme.current.blue);
        keys.setOnClickListener(v -> showInputLayout(false, true));
        controllerTopBar.addView(keys, new LinearLayout.LayoutParams(dp(140), dp(46)));
        controllerTopBar.addView(controllerShoulder("R1", 6, Theme.current.paper),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        controllerTopBar.addView(controllerShoulder("R2", 8, Theme.current.blue),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(controllerTopBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        inputContainer = new LinearLayout(this);
        inputContainer.setOrientation(LinearLayout.VERTICAL);
        inputContainer.setMotionEventSplittingEnabled(true);
        root.addView(inputContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        showInputLayout(controllerLayout, false);

        aurora = new AuroraView(this);
        aurora.setAnimated(auroraAnimated);
        aurora.setRunning(true);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(aurora, new android.widget.FrameLayout.LayoutParams(-1, -1));
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private View buildKeyboard() {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(0, dp(5), 0, 0);

        LinearLayout r1 = keyboardRow();
        addSpecialKey(r1, "Esc", "ESC", 0x29, 1.1f, Theme.current.coral);
        addPrintableKey(r1, "1\n!", "1", "!", 1); addPrintableKey(r1, "2\n@", "2", "@", 1);
        addPrintableKey(r1, "3\n#", "3", "#", 1); addPrintableKey(r1, "4\n$", "4", "$", 1);
        addPrintableKey(r1, "5\n%", "5", "%", 1); addPrintableKey(r1, "6\n^", "6", "^", 1);
        addPrintableKey(r1, "7\n&", "7", "&", 1); addPrintableKey(r1, "8\n*", "8", "*", 1);
        addPrintableKey(r1, "9\n(", "9", "(", 1); addPrintableKey(r1, "0\n)", "0", ")", 1);
        addPrintableKey(r1, "−\n_", "-", "_", 1); addPrintableKey(r1, "=\n+", "=", "+", 1);
        addRepeatingSpecialKey(r1, "Back", "BACKSPACE", 0x2A, 1.65f, Theme.current.coral);

        LinearLayout r2 = keyboardRow();
        addSpecialKey(r2, "Tab", "TAB", 0x2B, 1.4f, Theme.current.blue);
        for (String letter : new String[]{"Q","W","E","R","T","Y","U","I","O","P"}) addLetterKey(r2, letter);
        addPrintableKey(r2, "[\n{", "[", "{", 1); addPrintableKey(r2, "]\n}", "]", "}", 1);
        addPrintableKey(r2, "\\\n|", "\\", "|", 1.3f);

        LinearLayout r3 = keyboardRow();
        addModifierKey(r3, "Caps", "CAPS", 1.65f);
        for (String letter : new String[]{"A","S","D","F","G","H","J","K","L"}) addLetterKey(r3, letter);
        addPrintableKey(r3, ";\n:", ";", ":", 1); addPrintableKey(r3, "'\n\"", "'", "\"", 1);
        addSpecialKey(r3, "Enter", "ENTER", 0x28, 2.05f, Theme.current.yellow);

        LinearLayout r4 = keyboardRow();
        addModifierKey(r4, "Shift", "SHIFT", 1.55f);
        for (String letter : new String[]{"Z","X","C","V","B","N","M"}) addLetterKey(r4, letter);
        addPrintableKey(r4, ",\n<", ",", "<", 1); addPrintableKey(r4, ".\n>", ".", ">", 1);
        addPrintableKey(r4, "/\n?", "/", "?", 1);
        addSpecialKey(r4, "↑", "UP", 0x52, 1, Theme.current.blue);
        addModifierKey(r4, "⇧", "SHIFT", 0.8f);

        LinearLayout r5 = keyboardRow();
        addModifierKey(r5, "Ctrl", "CTRL", 1.4f);
        addModifierKey(r5, "Win\nSuper", "WIN", 1.45f);
        addModifierKey(r5, "Alt", "ALT", 1.25f);
        addSpecialKey(r5, "Home", "HOME", 0x4A, 1.25f, Theme.current.blue);
        addSpecialKey(r5, "Space", "SPACE", 0x2C, 6.2f, Theme.current.yellow);
        addSpecialKey(r5, "End", "END", 0x4D, 1.25f, Theme.current.blue);
        addSpecialKey(r5, "←", "LEFT", 0x50, 1, Theme.current.blue);
        addSpecialKey(r5, "↓", "DOWN", 0x51, 1, Theme.current.blue);
        addSpecialKey(r5, "→", "RIGHT", 0x4F, 1, Theme.current.blue);

        keyboard.addView(r1, rowParams()); keyboard.addView(r2, rowParams());
        keyboard.addView(r3, rowParams()); keyboard.addView(r4, rowParams());
        keyboard.addView(r5, rowParams());
        return keyboard;
    }

    private void showInputLayout(boolean useController, boolean announce) {
        controllerLayout = useController;
        saveSettings();
        if (normalTopBar != null)
            normalTopBar.setVisibility(useController ? View.GONE : View.VISIBLE);
        if (controllerTopBar != null)
            controllerTopBar.setVisibility(useController ? View.VISIBLE : View.GONE);
        if (inputContainer == null) return;

        if (controllerPanel != null) controllerPanel.releaseAll();
        controllerPanel = null;
        inputContainer.removeAllViews();
        if (useController) {
            setMode(MODE_BLUETOOTH);
            if (bluetoothModeButton != null) bluetoothModeButton.setChecked(true);
            controllerPanel = new ControllerPanel(this, neoUi, feedback,
                    this::sendGamepadReport, swapGamepadControls, gamepadLabelStyle);
            inputContainer.addView(controllerPanel.build(),
                    new LinearLayout.LayoutParams(-1, -1));
            if (announce) Toast.makeText(this,
                    "Bluetooth controller layout", Toast.LENGTH_SHORT).show();
        } else {
            inputContainer.addView(buildKeyboard(),
                    new LinearLayout.LayoutParams(-1, -1));
            if (announce) Toast.makeText(this,
                    "Keyboard layout", Toast.LENGTH_SHORT).show();
        }
        updateStatus();
    }

    private Button controllerShoulder(String label, int buttonNumber, int color) {
        Button button = neoButton(label, color);
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                feedback.perform(view, HapticFeedbackConstants.KEYBOARD_TAP);
                if (controllerPanel != null) controllerPanel.setTrigger(buttonNumber, true);
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(45).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (controllerPanel != null) controllerPanel.setTrigger(buttonNumber, false);
                view.animate().scaleX(1f).scaleY(1f).setDuration(65).start();
            }
            return true;
        });
        return button;
    }

    private void showTrackpadPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.green));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView dockLabel = text(trackpadOnRight ? "RIGHT DOCK" : "LEFT DOCK", 12);
        dockLabel.setTypeface(Typeface.DEFAULT_BOLD);
        dockLabel.setTextColor(Theme.current.ink);
        header.addView(dockLabel, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button moveSide = neoButton(trackpadOnRight ? "← LEFT" : "RIGHT →", Theme.current.blue);
        moveSide.setOnClickListener(v -> {
            trackpadOnRight = !trackpadOnRight;
            if (trackpadPopup != null) trackpadPopup.dismiss();
            showTrackpadPopup(anchor);
        });
        header.addView(moveSide, new LinearLayout.LayoutParams(dp(105), dp(42)));
        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout surfaces = new LinearLayout(this);
        surfaces.setOrientation(LinearLayout.HORIZONTAL);

        TextView trackpad = text("TRACKPAD\nTap · hold/double-tap drag\n3 fingers · swipe", 15);
        trackpad.setTypeface(Typeface.DEFAULT_BOLD);
        trackpad.setGravity(Gravity.CENTER);
        trackpad.setTextColor(Theme.current.onSurface);
        trackpad.setBackground(rounded(Theme.current.surface));
        trackpad.setOnTouchListener(new TrackpadGestureListener(uiHandler, this));
        trackpad.setOnHoverListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
                view.animate().scaleX(1.015f).scaleY(1.015f).setDuration(80).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            }
            return false;
        });
        surfaces.addView(trackpad, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView scrollPad = text("SCROLL\n▲\n↕\n▼", 12);
        scrollPad.setTypeface(Typeface.DEFAULT_BOLD);
        scrollPad.setGravity(Gravity.CENTER);
        scrollPad.setTextColor(Theme.current.ink);
        scrollPad.setBackground(rounded(Theme.current.blue));
        scrollPad.setOnTouchListener(new ScrollPadListener(this));
 scrollPad.setOnHoverListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
                view.animate().scaleX(1.025f).scaleY(1.015f).setDuration(80).start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            }
            return false;
        });
        LinearLayout.LayoutParams scrollPadParams = new LinearLayout.LayoutParams(
                dp(62), LinearLayout.LayoutParams.MATCH_PARENT);
        scrollPadParams.setMargins(dp(6), 0, 0, 0);
        surfaces.addView(scrollPad, scrollPadParams);

        card.addView(surfaces, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout clicks = new LinearLayout(this);
        Button left = neoButton("LEFT", Theme.current.yellow);
        left.setOnClickListener(v -> click("left"));
        Button right = neoButton("RIGHT", Theme.current.coral);
        right.setOnClickListener(v -> click("right"));
        clicks.addView(left, weighted()); clicks.addView(right, weighted());
        card.addView(clicks, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        int trackpadWidth = dp(520);
        trackpadPopup = new PopupWindow(card, trackpadWidth, dp(300), true);
        trackpadPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        trackpadPopup.setOutsideTouchable(true);
        trackpadPopup.setElevation(dp(12));
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int xOffset = trackpadOnRight
                ? getResources().getDisplayMetrics().widthPixels - trackpadWidth - location[0]
                : 0;
        trackpadPopup.showAsDropDown(anchor, xOffset, dp(4));
    }

    private void showSystemPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.blue));

        systemStatsView = text(pcStats, 13);
        systemStatsView.setTypeface(Typeface.DEFAULT_BOLD);
        systemStatsView.setTextColor(Theme.current.ink);
        systemStatsView.setGravity(Gravity.CENTER);
        systemStatsView.setMaxLines(2);
        card.addView(systemStatsView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout media = keyboardRow();
        addSystemKey(media, "⏮ PREV", "MEDIA_PREV", 0xB6, Theme.current.paper);
        addSystemKey(media, "⏯ PLAY", "MEDIA_PLAY_PAUSE", 0xCD, Theme.current.yellow);
        addSystemKey(media, "⏭ NEXT", "MEDIA_NEXT", 0xB5, Theme.current.paper);
        card.addView(media, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout volume = keyboardRow();
        addSystemKey(volume, "VOL −", "VOLUME_DOWN", 0xEA, Theme.current.coral);
        addSystemKey(volume, "MUTE", "VOLUME_MUTE", 0xE2, Theme.current.yellow);
        addSystemKey(volume, "VOL +", "VOLUME_UP", 0xE9, Theme.current.green);
        card.addView(volume, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        LinearLayout brightness = keyboardRow();
        addSystemKey(brightness, "BRIGHT −", "BRIGHTNESS_DOWN", 0x70, Theme.current.coral);
        addSystemKey(brightness, "BRIGHT +", "BRIGHTNESS_UP", 0x6F, Theme.current.green);
        card.addView(brightness, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        systemPopup = new PopupWindow(card, dp(310), dp(232), true);
        systemPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        systemPopup.setOutsideTouchable(true);
        systemPopup.setElevation(dp(12));
        systemPopup.setOnDismissListener(() -> systemStatsView = null);
        systemPopup.showAsDropDown(anchor, 0, dp(4));
    }

    private void showToolsPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.yellow));

        Button functions = neoButton("F KEYS + NAV", Theme.current.paper);
        functions.setOnClickListener(v -> {
            toolsPopup.dismiss();
            uiHandler.post(() -> showFunctionPopup(anchor));
        });
        card.addView(functions, new LinearLayout.LayoutParams(dp(190), dp(52)));

        Button numpadBtn = neoButton("NUMPAD", Theme.current.coral);
        numpadBtn.setOnClickListener(v -> {
            toolsPopup.dismiss();
            uiHandler.post(() -> showNumpadPopup(anchor));
        });
        card.addView(numpadBtn, new LinearLayout.LayoutParams(dp(190), dp(52)));

        Button system = neoButton("SYSTEM", Theme.current.blue);
        system.setOnClickListener(v -> {
            toolsPopup.dismiss();
            uiHandler.post(() -> showSystemPopup(anchor));
        });
        card.addView(system, new LinearLayout.LayoutParams(dp(190), dp(52)));

        Button shortcutsButton = neoButton("SHORTCUTS", Theme.current.green);
        shortcutsButton.setOnClickListener(v -> {
            toolsPopup.dismiss();
            uiHandler.post(() -> macroPanel.show(anchor));
        });
        card.addView(shortcutsButton, new LinearLayout.LayoutParams(dp(190), dp(52)));

        Button pasteButton = neoButton("PASTE TEXT", Theme.current.yellow);
        pasteButton.setOnClickListener(v -> {
            toolsPopup.dismiss();
            uiHandler.post(() -> PasteDialog.show(this, shortcuts));
        });
        card.addView(pasteButton, new LinearLayout.LayoutParams(dp(190), dp(52)));

        toolsPopup = new PopupWindow(card, dp(206), dp(388), true);
        toolsPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        toolsPopup.setOutsideTouchable(true);
        toolsPopup.setElevation(dp(12));
        toolsPopup.showAsDropDown(anchor, 0, dp(4));
    }

    private void addSystemKey(LinearLayout row, String label, String name, int usage, int color) {
        Button key = neoButton(label, color);
        key.setOnClickListener(v -> sendSystemControl(name, usage));
        row.addView(key, weighted());
    }

    private void loadSettings() {
        SharedPreferences values = getSharedPreferences("controls", MODE_PRIVATE);
        mode = values.getInt("active_mode", MODE_BLUETOOTH);
        dragHoldMs = values.getInt("drag_hold_ms", 500);
        pointerPercent = values.getInt("pointer_percent", 100);
        scrollPercent = values.getInt("scroll_percent", 100);
        repeatMs = values.getInt("repeat_ms", 55);
        hapticsOn = values.getBoolean("haptics", true);
        laptopClicksOn = values.getBoolean("laptop_clicks", true);
        controllerLayout = values.getBoolean("controller_layout", false);
        swapGamepadControls = values.getBoolean("swap_gamepad_controls", false);
        gamepadLabelStyle = values.getInt("gamepad_label_style", 0);
        auroraAnimated = values.getBoolean("aurora_animated", true);
        hostLayout = values.getInt("host_layout", KeyStroke.LAYOUT_US);
        accelLevel = values.getInt("accel_level", PointerAcceleration.OFF);
    }

    private void saveSettings() {
        getSharedPreferences("controls", MODE_PRIVATE).edit()
                .putInt("drag_hold_ms", dragHoldMs)
                .putInt("pointer_percent", pointerPercent)
                .putInt("scroll_percent", scrollPercent)
                .putInt("repeat_ms", repeatMs)
                .putBoolean("haptics", hapticsOn)
                .putBoolean("laptop_clicks", laptopClicksOn)
                .putBoolean("controller_layout", controllerLayout)
                .putBoolean("swap_gamepad_controls", swapGamepadControls)
                .putInt("gamepad_label_style", gamepadLabelStyle)
                .putBoolean("aurora_animated", auroraAnimated)
                .putInt("host_layout", hostLayout)
                .putInt("accel_level", accelLevel)
                .apply();
    }

    private void showSettingsPopup(View anchor) {
        settingValueViews.clear();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.yellow));

        TextView title = text("INPUT SETTINGS", 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.current.ink);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));

        card.addView(themeRow(), new LinearLayout.LayoutParams(-1, dp(68)));
        card.addView(settingRow("DRAG", "Drag hold"), new LinearLayout.LayoutParams(-1, dp(46)));
        card.addView(settingRow("POINTER", "Pointer speed"), new LinearLayout.LayoutParams(-1, dp(46)));
        card.addView(settingRow("SCROLL", "Scroll speed"), new LinearLayout.LayoutParams(-1, dp(46)));
        card.addView(settingRow("REPEAT", "Key repeat"), new LinearLayout.LayoutParams(-1, dp(46)));

        Button haptics = neoButton("HAPTICS · " + (hapticsOn ? "ON" : "OFF"), Theme.current.green);
        settingValueViews.put("HAPTICS", haptics);
        haptics.setOnClickListener(v -> {
            hapticsOn = !hapticsOn;
            saveSettings();
            feedback.setEnabled(hapticsOn);
            refreshSettingValues();
            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        card.addView(haptics, new LinearLayout.LayoutParams(-1, dp(48)));

        Button laptopClicks = neoButton("LAPTOP CLICKS · " +
                (laptopClicksOn ? "ON" : "OFF"), Theme.current.blue);
        settingValueViews.put("LAPTOP_CLICKS", laptopClicks);
        laptopClicks.setOnClickListener(v -> {
            laptopClicksOn = !laptopClicksOn;
            feedback.setLaptopClicksEnabled(laptopClicksOn);
            saveSettings();
            refreshSettingValues();
        });
        card.addView(laptopClicks, new LinearLayout.LayoutParams(-1, dp(48)));

        Button controllerSwap = neoButton("SWAP PAD / STICKS · " +
                (swapGamepadControls ? "ON" : "OFF"), Theme.current.coral);
        settingValueViews.put("CONTROLLER_SWAP", controllerSwap);
        controllerSwap.setOnClickListener(v -> {
            swapGamepadControls = !swapGamepadControls;
            saveSettings();
            refreshSettingValues();
        });
        card.addView(controllerSwap, new LinearLayout.LayoutParams(-1, dp(48)));

        Button controllerLabels = neoButton("CONTROLLER LABELS · " +
                gamepadLabelStyleName(), Theme.current.blue);
        settingValueViews.put("CONTROLLER_LABELS", controllerLabels);
        controllerLabels.setOnClickListener(v -> {
            gamepadLabelStyle = (gamepadLabelStyle + 1) % 3;
            saveSettings();
            refreshSettingValues();
            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        card.addView(controllerLabels, new LinearLayout.LayoutParams(-1, dp(48)));

        Button motion = neoButton("GLASS MOTION · " + (auroraAnimated ? "ON" : "OFF"), Theme.current.yellow);
        settingValueViews.put("AURORA", motion);
        motion.setOnClickListener(v -> {
            auroraAnimated = !auroraAnimated;
            saveSettings();
            if (aurora != null) aurora.setAnimated(auroraAnimated);
            refreshSettingValues();
            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        card.addView(motion, new LinearLayout.LayoutParams(-1, dp(48)));

        Button layoutButton = neoButton("KEY LAYOUT · " + KeyStroke.layoutName(hostLayout),
                Theme.current.paper);
        settingValueViews.put("LAYOUT", layoutButton);
        layoutButton.setOnClickListener(v -> {
            hostLayout = (hostLayout + 1) % KeyStroke.LAYOUT_COUNT;
            saveSettings();
            refreshSettingValues();
            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        card.addView(layoutButton, new LinearLayout.LayoutParams(-1, dp(48)));

        Button accelButton = neoButton("POINTER ACCEL · " + PointerAcceleration.name(accelLevel),
                Theme.current.blue);
        settingValueViews.put("ACCEL", accelButton);
        accelButton.setOnClickListener(v -> {
            accelLevel = (accelLevel + 1) % PointerAcceleration.LEVELS;
            saveSettings();
            refreshSettingValues();
            haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        card.addView(accelButton, new LinearLayout.LayoutParams(-1, dp(48)));

        Button hostButton = neoButton("SWITCH PC", Theme.current.coral);
        hostButton.setOnClickListener(v -> {
            BluetoothDevice next = bluetooth.nextHost();
            if (next == null) {
                Toast.makeText(this, "Pair a second PC first (Bluetooth popup)",
                        Toast.LENGTH_LONG).show();
                return;
            }
            setMode(MODE_BLUETOOTH);
            if (bluetoothModeButton != null) bluetoothModeButton.setChecked(true);
            Toast.makeText(this, "Switching to " + bluetooth.safeName(next) + "…",
                    Toast.LENGTH_SHORT).show();
            bluetooth.switchTo(next);
            updateStatus();
        });
        card.addView(hostButton, new LinearLayout.LayoutParams(-1, dp(48)));

        Button usbCode = neoButton("USB PAIR CODE", Theme.current.green);
        usbCode.setOnClickListener(v -> showUsbCodeDialog());
        card.addView(usbCode, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(card, new ScrollView.LayoutParams(-1,
                ScrollView.LayoutParams.WRAP_CONTENT));
        settingsPopup = new PopupWindow(scroll, dp(315), dp(440), true);
        settingsPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        settingsPopup.setOutsideTouchable(true);
        settingsPopup.setElevation(dp(12));
        settingsPopup.setOnDismissListener(settingValueViews::clear);
        settingsPopup.showAsDropDown(anchor, 0, dp(4));
        refreshSettingValues();
    }

    /** Background link only while Bluetooth mode is on and the permission is granted. */
    private void syncServiceWithMode() {
        if (mode == MODE_BLUETOOTH && bluetooth.hasPermission()) {
            if (!HidService.isRunning()) HidService.start(this);
        } else if (HidService.isRunning()) {
            HidService.stop(this);
        }
    }

    /** The notification's Stop button turns Bluetooth mode off: reflect that on screen. */
    private void syncModeFromPrefs() {
        if (modeGroup == null) return;
        int saved = getSharedPreferences("controls", MODE_PRIVATE)
                .getInt("active_mode", mode);
        if (saved != mode) modeGroup.check(saved);
    }

    private void showUsbCodeDialog() {
        String code = UsbPairing.pretty(UsbPairing.code(this));
        new android.app.AlertDialog.Builder(this)
                .setTitle("USB pair code")
                .setMessage(code + "\n\nType this once into the Windows helper. " +
                        "The phone ignores any PC that does not know it, and the PC " +
                        "ignores any app that is not this one.")
                .setPositiveButton("Done", null)
                .setNeutralButton("New code", (dialog, which) -> {
                    String fresh = UsbPairing.pretty(UsbPairing.regenerate(this));
                    Toast.makeText(this, "New code: " + fresh +
                            "\nRun the helper with -ResetCode", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private View themeRow() {
        return new ThemePicker(this, neoUi, new ThemePicker.Host() {
            @Override public void onThemeSelected(int index) { applyTheme(index); }
            @Override public void onCustomChanged() { rebuildForTheme(); }
        }).build();
    }

    /** Re-applies the current theme after the custom colours changed, without leaving settings. */
    private void rebuildForTheme() {
        neoUi = new NeoUi(this, feedback, Theme.current.ink);
        if (aurora != null) aurora.themeChanged();
        if (settingsPopup != null) settingsPopup.dismiss();
        applyTheme(Theme.index);
    }

    private View themeSwatch(int index) {
        Theme option = Theme.ALL[index];
        boolean selected = index == Theme.index;
        View swatch = new View(this);
        GradientDrawable dot = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{option.bgTop, option.blob1, option.blob2});
        dot.setShape(GradientDrawable.OVAL);
        dot.setStroke(dp(selected ? 3 : 1),
                selected ? Theme.current.ink : Color.argb(0x88, 128, 128, 128));
        swatch.setBackground(dot);
        swatch.setOnClickListener(v -> applyTheme(index));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        swatch.setLayoutParams(params);
        return swatch;
    }

    private void applyTheme(int index) {
        if (index == Theme.index && index != Theme.CUSTOM_INDEX) return;
        Theme.apply(index);
        getSharedPreferences("controls", MODE_PRIVATE).edit().putInt("theme", index).apply();
        if (settingsPopup != null) settingsPopup.dismiss();
        if (trackpadPopup != null) trackpadPopup.dismiss();
        if (toolsPopup != null) toolsPopup.dismiss();
        if (systemPopup != null) systemPopup.dismiss();
        if (controllerPanel != null) controllerPanel.releaseAll();
        controllerPanel = null;
        shiftButtons.clear();
        capsButtons.clear();
        ctrlButtons.clear();
        altButtons.clear();
        winButtons.clear();
        neoUi = new NeoUi(this, feedback, Theme.current.ink);
        setContentView(buildUi());
        applySystemBars();
        updateStatus();
        uiHandler.post(() -> {
            if (settingsMenuButton != null && (settingsPopup == null || !settingsPopup.isShowing()))
                showSettingsPopup(settingsMenuButton);
        });
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        Theme theme = Theme.current;
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bgTop));
        getWindow().setStatusBarColor(theme.bgTop);
        getWindow().setNavigationBarColor(theme.bgBottom);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (theme.dark) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** One tap on the status text: switch to Bluetooth and retry the saved PC right away. */
    private void quickConnect() {
        if (bluetooth.isTyping()) {
            bluetooth.cancelTyping();
            Toast.makeText(this, "Typing stopped", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mode == MODE_USB) return;
        if (mode != MODE_BLUETOOTH) {
            setMode(MODE_BLUETOOTH);
            if (bluetoothModeButton != null) bluetoothModeButton.setChecked(true);
        }
        if (bluetooth.isInputLive()) {
            Toast.makeText(this, "Already connected ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Reconnecting to your PC…", Toast.LENGTH_SHORT).show();
        bluetooth.reconnectNow();
    }

    private LinearLayout settingRow(String key, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 12);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Theme.current.ink);
        row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));

        Button minus = neoButton("−", Theme.current.coral);
        minus.setOnClickListener(v -> adjustSetting(key, -1));
        row.addView(minus, new LinearLayout.LayoutParams(dp(46), dp(42)));

        TextView value = text("", 12);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(Theme.current.ink);
        settingValueViews.put(key, value);
        row.addView(value, new LinearLayout.LayoutParams(dp(72), -1));

        Button plus = neoButton("+", Theme.current.green);
        plus.setOnClickListener(v -> adjustSetting(key, 1));
        row.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(42)));
        return row;
    }

    private void adjustSetting(String key, int direction) {
        switch (key) {
            case "DRAG":
                dragHoldMs = Math.max(350, Math.min(1000, dragHoldMs + direction * 50));
                break;
            case "POINTER":
                pointerPercent = Math.max(50, Math.min(200, pointerPercent + direction * 10));
                break;
            case "SCROLL":
                scrollPercent = Math.max(50, Math.min(200, scrollPercent + direction * 10));
                break;
            case "REPEAT":
                repeatMs = Math.max(35, Math.min(145, repeatMs + direction * 10));
                break;
        }
        saveSettings();
        refreshSettingValues();
    }

    private void refreshSettingValues() {
        if (settingValueViews.containsKey("DRAG"))
            settingValueViews.get("DRAG").setText(dragHoldMs + " ms");
        if (settingValueViews.containsKey("POINTER"))
            settingValueViews.get("POINTER").setText(pointerPercent + "%");
        if (settingValueViews.containsKey("SCROLL"))
            settingValueViews.get("SCROLL").setText(scrollPercent + "%");
        if (settingValueViews.containsKey("REPEAT"))
            settingValueViews.get("REPEAT").setText(repeatMs + " ms");
        if (settingValueViews.containsKey("HAPTICS"))
            settingValueViews.get("HAPTICS").setText("HAPTICS · " + (hapticsOn ? "ON" : "OFF"));
        if (settingValueViews.containsKey("LAPTOP_CLICKS"))
            settingValueViews.get("LAPTOP_CLICKS").setText("LAPTOP CLICKS · " +
                    (laptopClicksOn ? "ON" : "OFF"));
        if (settingValueViews.containsKey("CONTROLLER_SWAP"))
            settingValueViews.get("CONTROLLER_SWAP").setText("SWAP PAD / STICKS · " +
                    (swapGamepadControls ? "ON" : "OFF"));
        if (settingValueViews.containsKey("AURORA"))
            settingValueViews.get("AURORA").setText("GLASS MOTION · " + (auroraAnimated ? "ON" : "OFF"));
        if (settingValueViews.containsKey("LAYOUT"))
            settingValueViews.get("LAYOUT").setText("KEY LAYOUT · " + KeyStroke.layoutName(hostLayout));
        if (settingValueViews.containsKey("ACCEL"))
            settingValueViews.get("ACCEL").setText("POINTER ACCEL · " + PointerAcceleration.name(accelLevel));
        if (settingValueViews.containsKey("CONTROLLER_LABELS"))
            settingValueViews.get("CONTROLLER_LABELS").setText("CONTROLLER LABELS · " +
                    gamepadLabelStyleName());
    }

    private String gamepadLabelStyleName() {
        return gamepadLabelStyle == 1 ? "ABXY" : gamepadLabelStyle == 2 ? "1–4" : "PS";
    }

    private void showFunctionPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.yellow));

        LinearLayout functions = keyboardRow();
        for (int number = 1; number <= 12; number++) {
            addSpecialKey(functions, "F" + number, "F" + number,
                    0x39 + number, 1, Theme.current.paper);
        }
        card.addView(functions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout navigation = keyboardRow();
        addSpecialKey(navigation, "PrtSc", "PRINTSCREEN", 0x46, 1.35f, Theme.current.blue);
        addSpecialKey(navigation, "ScrLk", "SCROLLLOCK", 0x47, 1.35f, Theme.current.blue);
        addSpecialKey(navigation, "Pause", "PAUSE", 0x48, 1.35f, Theme.current.blue);
        addSpecialKey(navigation, "Ins", "INSERT", 0x49, 1, Theme.current.paper);
        addSpecialKey(navigation, "Del", "DELETE", 0x4C, 1, Theme.current.coral);
        addSpecialKey(navigation, "PgUp", "PGUP", 0x4B, 1.2f, Theme.current.paper);
        addSpecialKey(navigation, "PgDn", "PGDN", 0x4E, 1.2f, Theme.current.paper);
        addSpecialKey(navigation, "Home", "HOME", 0x4A, 1.3f, Theme.current.paper);
        addSpecialKey(navigation, "End", "END", 0x4D, 1.1f, Theme.current.paper);
        card.addView(navigation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        int maxPopupWidth = Math.min(dp(750), getResources().getDisplayMetrics().widthPixels - dp(16));
        functionPopup = new PopupWindow(card, maxPopupWidth, dp(132), true);
        functionPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        functionPopup.setOutsideTouchable(true);
        functionPopup.setElevation(dp(12));
        functionPopup.setOnDismissListener(() -> functionPopup = null);
        functionPopup.showAsDropDown(anchor, 0, dp(4));
    }

    private void showNumpadPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.coral));

        // Row 1: NumLock, /, *, Backspace
        LinearLayout r1 = keyboardRow();
        addSpecialKey(r1, "NumLk", "NUMLOCK", 0x53, 1f, Theme.current.yellow);
        addSpecialKey(r1, "/", "DIVIDE", 0x54, 1f, Theme.current.paper);
        addSpecialKey(r1, "×", "MULTIPLY", 0x55, 1f, Theme.current.paper);
        addRepeatingSpecialKey(r1, "⌫", "BACKSPACE", 0x2A, 1f, Theme.current.coral);
        card.addView(r1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        // Row 2: 7, 8, 9, -
        LinearLayout r2 = keyboardRow();
        addSpecialKey(r2, "7", "NUMPAD7", 0x5F, 1f, Theme.current.paper);
        addSpecialKey(r2, "8", "NUMPAD8", 0x60, 1f, Theme.current.paper);
        addSpecialKey(r2, "9", "NUMPAD9", 0x61, 1f, Theme.current.paper);
        addSpecialKey(r2, "−", "SUBTRACT", 0x56, 1f, Theme.current.blue);
        card.addView(r2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        // Row 3: 4, 5, 6, +
        LinearLayout r3 = keyboardRow();
        addSpecialKey(r3, "4", "NUMPAD4", 0x5C, 1f, Theme.current.paper);
        addSpecialKey(r3, "5", "NUMPAD5", 0x5D, 1f, Theme.current.paper);
        addSpecialKey(r3, "6", "NUMPAD6", 0x5E, 1f, Theme.current.paper);
        addSpecialKey(r3, "+", "ADD", 0x57, 1f, Theme.current.blue);
        card.addView(r3, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        // Row 4: 1, 2, 3, Enter
        LinearLayout r4 = keyboardRow();
        addSpecialKey(r4, "1", "NUMPAD1", 0x59, 1f, Theme.current.paper);
        addSpecialKey(r4, "2", "NUMPAD2", 0x5A, 1f, Theme.current.paper);
        addSpecialKey(r4, "3", "NUMPAD3", 0x5B, 1f, Theme.current.paper);
        addSpecialKey(r4, "Enter", "ENTER", 0x28, 1f, Theme.current.green);
        card.addView(r4, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        // Row 5: 0 (double wide), ., =
        LinearLayout r5 = keyboardRow();
        addSpecialKey(r5, "0", "NUMPAD0", 0x62, 2f, Theme.current.paper);
        addSpecialKey(r5, ".", "DECIMAL", 0x63, 1f, Theme.current.paper);
        addSpecialKey(r5, "=", "ENTER", 0x28, 1f, Theme.current.yellow);
        card.addView(r5, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        numpadPopup = new PopupWindow(card, dp(280), dp(286), true);
        numpadPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        numpadPopup.setOutsideTouchable(true);
        numpadPopup.setElevation(dp(12));
        numpadPopup.setOnDismissListener(() -> numpadPopup = null);
        numpadPopup.showAsDropDown(anchor, 0, dp(4));
    }

    private void showBluetoothPopup(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBg(Theme.current.blue));

        bluetoothStateView = text(bluetoothInputStatus(), 13);
        bluetoothStateView.setTypeface(Typeface.DEFAULT_BOLD);
        bluetoothStateView.setTextColor(Theme.current.ink);
        bluetoothStateView.setGravity(Gravity.CENTER);
        bluetoothStateView.setBackground(rounded(bluetooth.isInputLive() ? Theme.current.green : Theme.current.paper));
        card.addView(bluetoothStateView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        Button visible = neoButton("MAKE PHONE VISIBLE", Theme.current.yellow);
        visible.setOnClickListener(v -> {
            setMode(MODE_BLUETOOTH);
            if (bluetoothModeButton != null) bluetoothModeButton.setChecked(true);
            try {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivity(intent);
            } catch (Exception e) {
                Log.w("InpuLync", "Discoverable request failed", e);
                Toast.makeText(this, "Enable Bluetooth Discoverability in Settings", Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(visible, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button settings = neoButton("BLUETOOTH SETTINGS", Theme.current.paper);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        card.addView(settings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        pairedDevices = new LinearLayout(this);
        pairedDevices.setOrientation(LinearLayout.VERTICAL);
        card.addView(pairedDevices);
        refreshBondedDevices();

        bluetoothPopup = new PopupWindow(card, dp(360),
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        bluetoothPopup.setBackgroundDrawable(rounded(Color.TRANSPARENT));
        bluetoothPopup.setOutsideTouchable(true);
        bluetoothPopup.setElevation(dp(12));
        bluetoothPopup.setOnDismissListener(() -> {
            pairedDevices = null;
            bluetoothStateView = null;
            bluetoothPopup = null;
        });
        bluetoothPopup.showAsDropDown(anchor, -dp(275), dp(4));
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
                !bluetooth.hasPermission()) {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(perms.toArray(new String[0]), REQUEST_BT);
        } else {
            bluetooth.bind();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            bluetooth.bind();
            syncServiceWithMode();
        } else {
            updateStatus();
        }
    }

    private void refreshBondedDevices() {
        runOnUiThread(() -> {
            if (pairedDevices == null) return;
            pairedDevices.removeAllViews();
            if (!bluetooth.hasPermission()) return;
            Set<BluetoothDevice> devices = bluetooth.bondedDevices();
            for (BluetoothDevice device : devices) {
                boolean connected = bluetooth.isConnectedTo(device);
                Button connect = neoButton((connected ? "INPUT CONNECTED ✓ · " :
                        "CONNECT INPUT · ") + bluetooth.safeName(device), connected ? Theme.current.green : Theme.current.paper);
                connect.setOnClickListener(v -> {
                    if (connected) {
                        Toast.makeText(this, "Keyboard and mouse input is live",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Connecting keyboard and mouse…",
                                Toast.LENGTH_SHORT).show();
                        setMode(MODE_BLUETOOTH);
                        if (bluetoothModeButton != null) bluetoothModeButton.setChecked(true);
                        bluetooth.connect(device);
                    }
                    updateStatus();
                });
                pairedDevices.addView(connect, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
            }
            updateBluetoothPanel();
        });
    }

    private String bluetoothInputStatus() {
        return bluetooth.detailedStatus();
    }

    private void updateBluetoothPanel() {
        if (bluetoothStateView == null) return;
        bluetoothStateView.setText(bluetoothInputStatus());
        bluetoothStateView.setBackground(rounded(bluetooth.isInputLive() ? Theme.current.green : Theme.current.paper));
    }

    private void handleHostLine(String line) {
        if (!line.startsWith("BATTERY ")) return;
        String[] values = line.substring(8).trim().split("\\s+");
        if (values.length < 2) return;
        pcStats = "LAPTOP BAT " + metric(values[0], "%") +
                ("1".equals(values[1]) ? " ⚡" : "");
        updateStatus();
    }

    private String metric(String raw, String suffix) {
        try {
            int value = Integer.parseInt(raw);
            return value < 0 ? "—" : value + suffix;
        } catch (NumberFormatException e) {
            return "—";
        }
    }

    private void broadcast(String line) {
        if (mode != MODE_USB) return;
        usb.send(line);
    }

    private void sendText(String value) {
        if (mode == MODE_USB) {
            String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            broadcast("TEXT " + encoded);
        } else if (mode == MODE_BLUETOOTH) {
            for (char c : value.toCharArray()) sendBluetoothChar(c);
        }
    }

    private void sendBluetoothChar(char c) {
        KeyStroke key = KeyStroke.forChar(c, hostLayout);
        if (key == null) return;
        sendBluetoothKey(key.modifier, key.usage);
    }

    private void sendSpecial(String name, int usage) {
        if (mode == MODE_USB) broadcast("KEY " + name);
        else if (mode == MODE_BLUETOOTH) sendBluetoothKey(0, usage);
    }

    private void pressSpecial(String name, int usage) {
        int modifier = activeBluetoothModifier();
        if (modifier == 0) {
            sendSpecial(name, usage);
        } else if (mode == MODE_USB) {
            broadcast("HOTKEY " + activeChordPrefix() + name);
        } else if (mode == MODE_BLUETOOTH) {
            sendBluetoothKey(modifier, usage);
        }
        clearOneShotModifiers();
    }

    private void pressPrintable(String normal, String shifted) {
        boolean letter = normal.length() == 1 && Character.isLetter(normal.charAt(0));
        if (ctrlOn || altOn || winOn) {
            KeyStroke key = KeyStroke.forChar(normal.charAt(0), hostLayout);
            if (key != null) {
                if (mode == MODE_USB) {
                    broadcast("HOTKEY " + activeChordPrefix() + normal.toUpperCase());
                } else if (mode == MODE_BLUETOOTH) {
                    sendBluetoothKey(activeBluetoothModifier(), key.usage);
                }
            }
            clearOneShotModifiers();
            return;
        }

        boolean useShift = shiftOn;
        String value;
        if (letter) value = (capsOn ^ useShift) ? normal.toUpperCase() : normal.toLowerCase();
        else value = useShift ? shifted : normal;
        sendText(value);
        if (shiftOn) {
            shiftOn = false;
            refreshModifierStyles();
        }
    }

    private int activeBluetoothModifier() {
        return (ctrlOn ? 0x01 : 0) | (shiftOn ? 0x02 : 0) |
                (altOn ? 0x04 : 0) | (winOn ? 0x08 : 0);
    }

    private String activeChordPrefix() {
        StringBuilder value = new StringBuilder();
        if (ctrlOn) value.append("CTRL+");
        if (shiftOn) value.append("SHIFT+");
        if (altOn) value.append("ALT+");
        if (winOn) value.append("WIN+");
        return value.toString();
    }

    private void clearOneShotModifiers() {
        shiftOn = false;
        ctrlOn = false;
        altOn = false;
        winOn = false;
        refreshModifierStyles();
    }

    private void sendStandaloneWindowsKey() {
        if (mode == MODE_USB) broadcast("KEY WIN");
        else if (mode == MODE_BLUETOOTH) sendBluetoothKey(0x08, 0);
    }

    private void sendBluetoothKey(int modifier, int usage) {
        bluetooth.sendKey(modifier, usage);
    }

    private void sendSystemControl(String name, int consumerUsage) {
        // Laptop brightness is deliberately cable-only. Windows Bluetooth HID
        // implementations interpret brightness usages inconsistently, and socket
        // writes must never block Android's UI thread.
        if (name.startsWith("BRIGHTNESS") || mode != MODE_BLUETOOTH) {
            usb.send("MEDIA " + name);
        } else {
            sendBluetoothConsumer(consumerUsage);
        }
    }

    private void sendBluetoothConsumer(int usage) {
        bluetooth.sendConsumer(usage);
    }

    @Override
    public void move(int dx, int dy) {
        if (mode == MODE_USB) broadcast("MOVE " + dx + " " + dy);
        else if (mode == MODE_BLUETOOTH) sendBluetoothMouse(mouseButtons, dx, dy, 0);
    }

    @Override
    public void scroll(int amount) {
        if (mode == MODE_USB) broadcast("SCROLL " + amount);
        else if (mode == MODE_BLUETOOTH) sendBluetoothMouse(mouseButtons, 0, 0, amount);
    }

    @Override
    public void scrollHorizontal(int amount) {
        if (mode == MODE_USB) broadcast("HSCROLL " + amount);
        else if (mode == MODE_BLUETOOTH) bluetooth.sendMouse(mouseButtons, 0, 0, 0, amount);
    }

    @Override
    public int accelLevel() {
        return accelLevel;
    }

    private void click(String which) {
        setMouseButton(which, true);
        uiHandler.postDelayed(() -> setMouseButton(which, false), 38);
    }

    @Override
    public void clickLeft() {
        click("left");
    }

    @Override
    public void setLeftButton(boolean down) {
        setMouseButton("left", down);
    }

    @Override
    public void threeFingerSwipe(TrackpadGestureListener.Swipe direction) {
        String usbChord;
        int modifier;
        int usage;
        switch (direction) {
            case LEFT:
                usbChord = "WIN+CTRL+LEFT";
                modifier = 0x09;
                usage = 0x50;
                break;
            case RIGHT:
                usbChord = "WIN+CTRL+RIGHT";
                modifier = 0x09;
                usage = 0x4F;
                break;
            case UP:
                usbChord = "WIN+TAB";
                modifier = 0x08;
                usage = 0x2B;
                break;
            default:
                usbChord = "WIN+D";
                modifier = 0x08;
                usage = 0x07;
                break;
        }
        if (mode == MODE_USB) broadcast("HOTKEY " + usbChord);
        else if (mode == MODE_BLUETOOTH) bluetooth.sendKey(modifier, usage);
        Toast.makeText(this, "3-finger " + direction.name().toLowerCase(),
                Toast.LENGTH_SHORT).show();
    }

    private void setMouseButton(String which, boolean down) {
        int mask = which.equals("right") ? 2 : 1;
        if (down) mouseButtons |= mask;
        else mouseButtons &= ~mask;
        if (mode == MODE_USB) {
            broadcast("BUTTON " + which.toUpperCase() + " " + (down ? "DOWN" : "UP"));
        } else if (mode == MODE_BLUETOOTH) {
            sendBluetoothMouse(mouseButtons, 0, 0, 0);
        }
    }

    private void sendBluetoothMouse(int buttons, int dx, int dy, int wheel) {
        bluetooth.sendMouse(buttons, dx, dy, wheel);
    }

    private void sendGamepadReport(int buttons, int leftX, int leftY,
                                   int rightX, int rightY, int hat,
                                   int leftTrigger, int rightTrigger) {
        if (mode == MODE_BLUETOOTH)
            bluetooth.sendGamepad(buttons, leftX, leftY, rightX, rightY, hat,
                    leftTrigger, rightTrigger);
    }

    private void updateStatus() {
        if (status == null) return;
        runOnUiThread(() -> {
            String selected = mode == MODE_USB ? "USB" : mode == MODE_BLUETOOTH ? "BT" : "OFF";
            String connection;
            if (mode == MODE_USB) {
                connection = !usb.isStarted() ? "starting" :
                        (!usb.hasClients() ? "waiting for PC" : "PC connected");
            } else if (mode == MODE_BLUETOOTH) {
                connection = bluetooth.shortStatus();
            } else {
                connection = !usb.hasClients() ? "local" : "PC linked";
            }
            String detail = pcStats;
            if (activeBluetoothModifier() != 0) {
                String chord = activeChordPrefix();
                detail = "NEXT · " + chord.substring(0, chord.length() - 1);
            }
            status.setText(selected + " · " + connection + "\n" + detail);
            if (controllerPanel != null) {
                String controllerConnection = mode == MODE_BLUETOOTH &&
                        bluetooth.isInputLive() ? "BT · INPUT CONNECTED ✓" :
                        selected + " · " + connection;
                controllerPanel.setConnectionStatus(controllerConnection);
            }
            if (systemStatsView != null) systemStatsView.setText(pcStats);
            updateBluetoothPanel();
        });
    }

    @Override
    protected void onDestroy() {
        dismissAllPopups();
        super.onDestroy();
        bluetooth.setListener(null);
        bluetooth.cancelTyping();
        // Keep the Bluetooth link when the background service owns it.
        if (!HidService.isRunning()) HidHub.release();
        usb.close();
    }

    private LinearLayout keyboardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        params.setMargins(0, dp(1), 0, dp(1));
        return params;
    }

    private LinearLayout.LayoutParams keyParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(2), dp(1), dp(2), dp(1));
        return params;
    }

    private void addLetterKey(LinearLayout row, String letter) {
        addPrintableKey(row, letter, letter.toLowerCase(), letter.toUpperCase(), 1);
    }

    private void addPrintableKey(LinearLayout row, String label, String normal, String shifted, float weight) {
        Button key = neoButton(label, Theme.current.paper);
        key.setOnClickListener(v -> pressPrintable(normal, shifted));
        row.addView(key, keyParams(weight));
    }

    private void addSpecialKey(LinearLayout row, String label, String name, int usage,
                               float weight, int color) {
        Button key = neoButton(label, color);
        key.setOnClickListener(v -> pressSpecial(name, usage));
        row.addView(key, keyParams(weight));
    }

    private void addRepeatingSpecialKey(LinearLayout row, String label, String name, int usage,
                                        float weight, int color) {
        Button key = neoButton(label, color);
        key.setOnClickListener(v -> pressSpecial(name, usage));
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            if (key.isPressed()) {
                pressSpecial(name, usage);
                handler.postDelayed(repeat[0], repeatMs);
            }
        };
        key.setOnLongClickListener(v -> {
            repeat[0].run();
            return true;
        });
        row.addView(key, keyParams(weight));
    }

    private void addModifierKey(LinearLayout row, String label, String modifier, float weight) {
        Button key = neoButton(label, Theme.current.paper);
        switch (modifier) {
            case "SHIFT": shiftButtons.add(key); break;
            case "CAPS": capsButtons.add(key); break;
            case "CTRL": ctrlButtons.add(key); break;
            case "ALT": altButtons.add(key); break;
            case "WIN": winButtons.add(key); break;
        }
        key.setOnClickListener(v -> {
            switch (modifier) {
                case "SHIFT": shiftOn = !shiftOn; break;
                case "CAPS": capsOn = !capsOn; break;
                case "CTRL": ctrlOn = !ctrlOn; break;
                case "ALT": altOn = !altOn; break;
                case "WIN": winOn = !winOn; break;
            }
            refreshModifierStyles();
        });
        if (modifier.equals("WIN")) {
            key.setOnLongClickListener(v -> {
                winOn = false;
                sendStandaloneWindowsKey();
                refreshModifierStyles();
                return true;
            });
        }
        row.addView(key, keyParams(weight));
    }

    private void refreshModifierStyles() {
        styleModifiers(shiftButtons, shiftOn);
        styleModifiers(capsButtons, capsOn);
        styleModifiers(ctrlButtons, ctrlOn);
        styleModifiers(altButtons, altOn);
        styleModifiers(winButtons, winOn);
        updateStatus();
    }

    private void styleModifiers(List<Button> buttons, boolean active) {
        for (Button button : buttons) button.setBackground(interactiveNeoBackground(active ? Theme.current.green : Theme.current.paper));
    }

    private RadioButton radio(String label, int id) {
        return neoUi.radio(label, id);
    }

    private Button button(String label) {
        return neoButton(label, Theme.current.paper);
    }

    private Button neoButton(String label, int color) {
        return neoUi.button(label, color);
    }

    private TextView text(String value, int sp) {
        return neoUi.text(value, sp);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private GradientDrawable rounded(int color) {
        return neoUi.rounded(color);
    }

    private Drawable cardBg(int color) {
        return neoUi.card(color);
    }

    private StateListDrawable interactiveNeoBackground(int color) {
        return neoUi.interactiveBackground(color);
    }

    @Override
    public int dp(int value) {
        return neoUi.dp(value);
    }

    @Override
    public int dragHoldMs() {
        return dragHoldMs;
    }

    @Override
    public int pointerPercent() {
        return pointerPercent;
    }

    @Override
    public int scrollPercent() {
        return scrollPercent;
    }

    @Override
    public void haptic(View view, int feedback) {
        this.feedback.perform(view, feedback);
    }

    @Override
    public void trackpadVisual(View view, int state) {
        int color = state == TrackpadGestureListener.VISUAL_DRAGGING
                ? Theme.current.surfaceDrag
                : state == TrackpadGestureListener.VISUAL_PRESSED
                ? Theme.current.surfacePressed : Theme.current.surface;
        view.setBackground(rounded(color));
    }

    @Override
    public void scrollVisual(View view, boolean pressed) {
        view.setBackground(rounded(pressed ? Theme.current.bluePressed : Theme.current.blue));
    }

}
