package com.inpulync.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Owns Bluetooth HID registration, connection recovery, and report delivery. */
final class BluetoothHidController {
    interface Listener {
        void onStateChanged();
        void onInputConnected(String hostName);
    }

    private static final String TAG = "InpuLync";
    private static final byte[] HID_DESCRIPTOR = hex(
            "05010906A1018501050719E029E715002501750195088102" +
            "95017508810195067508150025650507190029658100C0" +
            "05010902A10185020901A100050919012905150025019505" +
            "7501810295017503810105010930093109381581257F7508" +
            "95038106" +
            // Horizontal scroll (AC Pan), one signed byte after the wheel.
            "050C0A3802" + "1581257F750895018106C0C0" +
            "050C0901A1018503150026FF0319002AFF03751095018100C0" +
            // Gamepad, report 4: four signed axes, two analog triggers (Rx, Ry as 0..255),
            // 16 buttons, and an 8-way hat.
            "05010905A1018504" +
            "09300931093209351581257F750895048102" +
            "09330934150026FF00750895028102" +
            "05091901291015002501750195108102" +
            "05010939150025073500463B016514750495018142" +
            "6500750495018101C0");

    private final Context context;
    private final Handler handler;
    private final BluetoothAdapter adapter;
    private volatile Listener listener;
    private volatile Runnable observer;

    private BluetoothHidDevice hid;
    private BluetoothDevice connectedHost;
    private boolean registered;
    private boolean binding;
    private boolean registrationPending;
    private boolean recoveryScheduled;
    private boolean foreground;
    private boolean active;
    private boolean destroyed;
    private int connectionState = BluetoothProfile.STATE_DISCONNECTED;
    private long lastConnectRequestAt;
    private long nextReportAt;
    private static final int TYPE_CHAR_MS = 30;
    private final Object typingToken = new Object();
    private long typingUntil;
    private int recoveryAttempts;
    private int consecutiveFailures;
    private boolean receiversRegistered;

    // Mouse / gamepad rate limiting. Everything below runs on the main thread.
    private static final int MIN_REPORT_INTERVAL_MS = 8;
    private int pendingDx;
    private int pendingDy;
    private int pendingWheel;
    private int pendingPan;
    private int lastMouseButtons;
    private long lastMouseSendAt;
    private boolean mouseFlushScheduled;
    private final Runnable mouseFlush = () -> {
        mouseFlushScheduled = false;
        flushMouse();
    };
    private byte[] lastGamepadReport;
    private byte[] pendingGamepadReport;
    private long lastGamepadSendAt;
    private boolean gamepadFlushScheduled;
    private final Runnable gamepadFlush = () -> {
        gamepadFlushScheduled = false;
        byte[] report = pendingGamepadReport;
        pendingGamepadReport = null;
        if (report != null && isInputLive()) writeGamepad(report);
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    recoveryAttempts = 0;
                    handler.postDelayed(BluetoothHidController.this::ensureReady, 700);
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF ||
                        state == BluetoothAdapter.STATE_OFF) {
                    dropStaleProfile();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                BluetoothDevice preferred = preferredHost();
                if (device != null && device.equals(preferred) && !isInputLive()) {
                    // The PC just woke up or came back in range: connect input right away.
                    recoveryAttempts = 0;
                    lastConnectRequestAt = 0;
                    handler.postDelayed(BluetoothHidController.this::ensureReady, 400);
                }
            }
        }
    };

    BluetoothHidController(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
    }

    /** The visible screen attaches here; the controller itself outlives the screen. */
    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean hasListener() {
        return listener != null;
    }

    /** The background service uses this to keep its notification text current. */
    void setObserver(Runnable observer) {
        this.observer = observer;
    }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (foreground) ensureReady();
    }

    void setActive(boolean active) {
        this.active = active;
        if (active) {
            ensureReady();
        } else {
            releaseLink();
        }
        notifyChanged();
    }

    /**
     * Stops the Bluetooth keyboard/mouse entirely: releases any held keys or buttons on the
     * PC, disconnects it, and unregisters the HID app. Used when Bluetooth mode is turned off
     * (including from the notification's Stop button).
     */
    private void releaseLink() {
        BluetoothHidDevice proxy = hid;
        BluetoothDevice host = connectedHost;
        handler.removeCallbacksAndMessages(typingToken);
        typingUntil = 0;
        handler.removeCallbacks(mouseFlush);
        handler.removeCallbacks(gamepadFlush);
        mouseFlushScheduled = false;
        gamepadFlushScheduled = false;
        pendingDx = pendingDy = pendingWheel = pendingPan = 0;
        pendingGamepadReport = null;
        lastGamepadReport = null;
        lastMouseButtons = 0;
        if (proxy != null && host != null && registered && hasPermission()) {
            try {
                // Let go of anything still held so the PC does not keep a stuck key or button.
                proxy.sendReport(host, 1, new byte[8]);
                proxy.sendReport(host, 2, new byte[5]);
                proxy.sendReport(host, 4, new byte[9]);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not release held input before disconnect", e);
            }
        }
        if (proxy != null && host != null && hasPermission()) {
            try {
                proxy.disconnect(host);
            } catch (RuntimeException e) {
                Log.w(TAG, "Disconnect failed", e);
            }
        }
        if (proxy != null && registered && hasPermission()) {
            try {
                proxy.unregisterApp();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unregister failed", e);
            }
        }
        registered = false;
        registrationPending = false;
        connectedHost = null;
        connectionState = BluetoothProfile.STATE_DISCONNECTED;
    }

    void bind() {
        registerReceivers();
        if (destroyed || binding || hid != null || adapter == null || !hasPermission()) {
            notifyChanged();
            return;
        }
        binding = true;
        boolean requested = adapter.getProfileProxy(context,
                new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        binding = false;
                        hid = (BluetoothHidDevice) proxy;
                        ensureReady();
                    }

                    @Override public void onServiceDisconnected(int profile) {
                        binding = false;
                        hid = null;
                        registered = false;
                        registrationPending = false;
                        connectedHost = null;
                        connectionState = BluetoothProfile.STATE_DISCONNECTED;
                        Log.w(TAG, "Bluetooth HID profile disconnected");
                        notifyChanged();
                        scheduleRecovery();
                    }
                }, BluetoothProfile.HID_DEVICE);
        if (!requested) {
            binding = false;
            Log.w(TAG, "Bluetooth HID profile bind was rejected");
            scheduleRecovery();
        }
    }

    void connect(BluetoothDevice device) {
        switchTo(device);
    }

    /**
     * Makes {@code device} the active PC. If another PC is connected right now it is
     * disconnected first; a plain connect would be ignored while a host is live.
     */
    void switchTo(BluetoothDevice device) {
        if (device == null) return;
        rememberHost(device);
        active = true;
        recoveryAttempts = 0;
        lastConnectRequestAt = 0;
        BluetoothDevice current = connectedHost;
        BluetoothHidDevice proxy = hid;
        if (current != null && !current.equals(device) && proxy != null) {
            connectedHost = null;
            connectionState = BluetoothProfile.STATE_DISCONNECTED;
            pendingDx = pendingDy = pendingWheel = pendingPan = 0;
            pendingGamepadReport = null;
            cancelTyping();
            proxy.disconnect(current);
            notifyChanged();
            handler.postDelayed(this::ensureReady, 700);
            return;
        }
        ensureReady();
    }

    /** Paired devices that look like PCs (or every paired device if none do), by name. */
    List<BluetoothDevice> hostCandidates() {
        if (!hasPermission() || adapter == null) return Collections.emptyList();
        List<BluetoothDevice> computers = new ArrayList<>();
        List<BluetoothDevice> everything = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            everything.add(device);
            BluetoothClass type = device.getBluetoothClass();
            if (type != null && type.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER)
                computers.add(device);
        }
        List<BluetoothDevice> pick = computers.isEmpty() ? everything : computers;
        Collections.sort(pick, (a, b) -> safeName(a).compareToIgnoreCase(safeName(b)));
        return pick;
    }

    /** The PC after the current one in the list, or null when there is nothing to switch to. */
    BluetoothDevice nextHost() {
        List<BluetoothDevice> hosts = hostCandidates();
        if (hosts.size() < 2) return null;
        BluetoothDevice current = connectedHost != null ? connectedHost : preferredHost();
        int index = current == null ? -1 : hosts.indexOf(current);
        return hosts.get((index + 1) % hosts.size());
    }

    /** Types pasted text quickly: one press+release per step, in order, cancellable. */
    void typeSteps(List<int[]> steps) {
        if (!isInputLive() || steps.isEmpty()) return;
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        long start = reserveReportWindow(steps.size() * TYPE_CHAR_MS + 40);
        typingUntil = start + (long) steps.size() * TYPE_CHAR_MS;
        long at = start;
        for (int[] step : steps) {
            byte[] down = new byte[]{(byte) step[0], 0, (byte) step[1], 0, 0, 0, 0, 0};
            handler.postAtTime(() -> sendReport(targetHid, targetHost, 1, down),
                    typingToken, at);
            handler.postAtTime(() -> sendReport(targetHid, targetHost, 1, new byte[8]),
                    typingToken, at + TYPE_CHAR_MS / 2);
            at += TYPE_CHAR_MS;
        }
    }

    boolean isTyping() {
        return SystemClock.uptimeMillis() < typingUntil;
    }

    /** Drops queued pasted characters and releases any key that is still down. */
    void cancelTyping() {
        handler.removeCallbacksAndMessages(typingToken);
        typingUntil = 0;
        synchronized (this) {
            nextReportAt = SystemClock.uptimeMillis();
        }
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        if (isInputLive() && targetHid != null && targetHost != null)
            sendReport(targetHid, targetHost, 1, new byte[8]);
    }

    /** User-triggered retry: skip the backoff and attempt the saved host immediately. */
    void reconnectNow() {
        active = true;
        recoveryAttempts = 0;
        lastConnectRequestAt = 0;
        ensureReady();
        notifyChanged();
    }

    Set<BluetoothDevice> bondedDevices() {
        if (!hasPermission() || adapter == null) return Collections.emptySet();
        return adapter.getBondedDevices();
    }

    boolean isConnectedTo(BluetoothDevice device) {
        return connectedHost != null && connectedHost.equals(device) && isInputLive();
    }

    boolean isInputLive() {
        return registered && connectedHost != null &&
                connectionState == BluetoothProfile.STATE_CONNECTED;
    }

    String shortStatus() {
        if (!hasPermission()) return "PERMISSION NEEDED";
        if (isInputLive()) return "INPUT CONNECTED ✓";
        if (preferredHost() == null) return "NOT PAIRED";
        if (!registered) return "PAIRED · STARTING HID";
        if (connectionState == BluetoothProfile.STATE_CONNECTING)
            return "PAIRED · CONNECTING";
        return "PAIRED · INPUT OFFLINE";
    }

    String detailedStatus() {
        if (!hasPermission()) return "NEARBY DEVICES PERMISSION NEEDED";
        if (isInputLive()) return "INPUT CONNECTED ✓\n" + safeName(connectedHost);
        if (preferredHost() == null) return "NOT PAIRED · USE WINDOWS ADD DEVICE";
        if (!registered) return "PAIRED · STARTING INPUT SERVICE…";
        if (connectionState == BluetoothProfile.STATE_CONNECTING)
            return "PAIRED · CONNECTING INPUT…";
        return "PAIRED · INPUT OFFLINE\nTap CONNECT INPUT below";
    }

    String safeName(BluetoothDevice device) {
        if (device == null || !hasPermission()) return "paired host";
        String name = device.getName();
        return name == null ? device.getAddress() : name;
    }

    void sendKey(int modifier, int usage) {
        if (!isInputLive()) return;
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        byte[] modifiersDown = new byte[]{(byte) modifier, 0, 0, 0, 0, 0, 0, 0};
        byte[] chordDown = new byte[]{(byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0};
        byte[] allUp = new byte[8];
        long start = reserveReportWindow(modifier != 0 && usage != 0 ? 190 : 130);
        Log.d(TAG, "BT key modifier=0x" + Integer.toHexString(modifier) +
                " usage=0x" + Integer.toHexString(usage));
        if (modifier != 0 && usage != 0) {
            postReport(targetHid, targetHost, 1, modifiersDown, start);
            postReport(targetHid, targetHost, 1, chordDown, start + 45);
            postReport(targetHid, targetHost, 1, modifiersDown, start + 115);
            postReport(targetHid, targetHost, 1, allUp, start + 160);
        } else {
            postReport(targetHid, targetHost, 1, chordDown, start);
            postReport(targetHid, targetHost, 1, allUp, start + 85);
        }
    }

    void sendConsumer(int usage) {
        if (!isInputLive()) return;
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        byte[] down = new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)};
        long start = reserveReportWindow(130);
        Log.d(TAG, "BT consumer usage=0x" + Integer.toHexString(usage));
        postReport(targetHid, targetHost, 3, down, start);
        postReport(targetHid, targetHost, 3, new byte[2], start + 85);
    }

    void sendMouse(int buttons, int dx, int dy, int wheel) {
        sendMouse(buttons, dx, dy, wheel, 0);
    }

    void sendMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (!isInputLive()) {
            pendingDx = pendingDy = pendingWheel = pendingPan = 0;
            return;
        }
        if (buttons != lastMouseButtons) {
            // Button edges are never delayed or merged: flush motion first, then the edge.
            flushMouse();
            lastMouseButtons = buttons;
            writeMouse(buttons, dx, dy, wheel, pan);
            return;
        }
        if (dx == 0 && dy == 0 && wheel == 0 && pan == 0) {
            flushMouse();
            writeMouse(buttons, 0, 0, 0, 0);
            return;
        }
        pendingDx += dx;
        pendingDy += dy;
        pendingWheel += wheel;
        pendingPan += pan;
        long elapsed = SystemClock.uptimeMillis() - lastMouseSendAt;
        if (elapsed >= MIN_REPORT_INTERVAL_MS) {
            flushMouse();
        } else if (!mouseFlushScheduled) {
            mouseFlushScheduled = true;
            handler.postDelayed(mouseFlush, MIN_REPORT_INTERVAL_MS - elapsed + 1);
        }
    }

    private void flushMouse() {
        int dx = pendingDx, dy = pendingDy, wheel = pendingWheel, pan = pendingPan;
        pendingDx = pendingDy = pendingWheel = pendingPan = 0;
        if (dx == 0 && dy == 0 && wheel == 0 && pan == 0) return;
        if (!isInputLive()) return;
        lastMouseSendAt = SystemClock.uptimeMillis();
        writeMouse(lastMouseButtons, dx, dy, wheel, pan);
    }

    private void writeMouse(int buttons, int dx, int dy, int wheel, int pan) {
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        if (targetHid == null || targetHost == null) return;
        boolean empty = dx == 0 && dy == 0 && wheel == 0 && pan == 0;
        while (dx != 0 || dy != 0 || wheel != 0 || pan != 0) {
            int x = clamp(dx), y = clamp(dy), w = clamp(wheel), h = clamp(pan);
            sendReport(targetHid, targetHost, 2,
                    new byte[]{(byte) buttons, (byte) x, (byte) y, (byte) w, (byte) h});
            dx -= x;
            dy -= y;
            wheel -= w;
            pan -= h;
        }
        if (empty) sendReport(targetHid, targetHost, 2,
                new byte[]{(byte) buttons, 0, 0, 0, 0});
    }

    void sendGamepad(int buttons, int leftX, int leftY,
                     int rightX, int rightY, int hat, int leftTrigger, int rightTrigger) {
        if (!isInputLive()) return;
        // Layout follows the descriptor: X, Y, Z, Rz, Rx, Ry, 16 buttons, hat.
        byte[] report = new byte[]{
                (byte) clamp(leftX), (byte) clamp(leftY),
                (byte) clamp(rightX), (byte) clamp(rightY),
                (byte) trigger(leftTrigger), (byte) trigger(rightTrigger),
                (byte) (buttons & 0xFF), (byte) ((buttons >> 8) & 0xFF),
                (byte) (hat & 0x0F)
        };
        boolean digitalSame = lastGamepadReport != null &&
                report[6] == lastGamepadReport[6] && report[7] == lastGamepadReport[7] &&
                report[8] == lastGamepadReport[8];
        if (!digitalSame) {
            // Button / d-pad edges go out immediately and carry the latest stick state.
            pendingGamepadReport = null;
            writeGamepad(report);
            return;
        }
        if (java.util.Arrays.equals(report, lastGamepadReport)) return;
        long elapsed = SystemClock.uptimeMillis() - lastGamepadSendAt;
        if (elapsed >= MIN_REPORT_INTERVAL_MS) {
            pendingGamepadReport = null;
            writeGamepad(report);
        } else {
            // Stick/trigger-only change inside the window: keep only the newest value and
            // always deliver it, so an axis can never get stuck off-centre.
            pendingGamepadReport = report;
            if (!gamepadFlushScheduled) {
                gamepadFlushScheduled = true;
                handler.postDelayed(gamepadFlush, MIN_REPORT_INTERVAL_MS - elapsed + 1);
            }
        }
    }

    private void writeGamepad(byte[] report) {
        BluetoothHidDevice targetHid = hid;
        BluetoothDevice targetHost = connectedHost;
        if (targetHid == null || targetHost == null) return;
        lastGamepadReport = report;
        lastGamepadSendAt = SystemClock.uptimeMillis();
        sendReport(targetHid, targetHost, 4, report);
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacks(mouseFlush);
        handler.removeCallbacks(gamepadFlush);
        if (receiversRegistered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiversRegistered = false;
        }
        if (hid != null && registered) hid.unregisterApp();
        if (adapter != null && hid != null)
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
    }

    private void ensureReady() {
        if (destroyed || !foreground || !active || !hasPermission()) return;
        if (hid == null) bind();
        else if (!registered) registerApp();
        else connectPreferredHost();
    }

    private void registerApp() {
        if (destroyed || !foreground || !active || hid == null || registered ||
                registrationPending) return;
        registrationPending = true;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "InpuLync", "Offline keyboard, mouse, and gamepad", "Local", (byte) 0xC0,
                HID_DESCRIPTOR);
        boolean requested = hid.registerApp(sdp, null, null, context.getMainExecutor(),
                new BluetoothHidDevice.Callback() {
                    @Override public void onAppStatusChanged(BluetoothDevice plugged,
                                                              boolean isRegistered) {
                        registrationPending = false;
                        registered = isRegistered;
                        Log.d(TAG, "Bluetooth HID app registered=" + isRegistered);
                        if (plugged != null && preferredHost() == null) rememberHost(plugged);
                        if (isRegistered) {
                            BluetoothDevice candidate = plugged != null ? plugged : preferredHost();
                            connectionState = candidate == null || hid == null
                                    ? BluetoothProfile.STATE_DISCONNECTED
                                    : hid.getConnectionState(candidate);
                            connectedHost = connectionState == BluetoothProfile.STATE_CONNECTED
                                    ? candidate : null;
                            connectPreferredHost();
                        } else {
                            connectedHost = null;
                            connectionState = BluetoothProfile.STATE_DISCONNECTED;
                            scheduleRecovery();
                        }
                        notifyChanged();
                    }

                    @Override public void onConnectionStateChanged(BluetoothDevice device,
                                                                    int state) {
                        Log.d(TAG, "Bluetooth HID state=" + state + " host=" + safeName(device));
                        if (state == BluetoothProfile.STATE_DISCONNECTED && connectedHost != null &&
                                !device.equals(connectedHost)) {
                            // Late disconnect from the PC we just switched away from.
                            notifyChanged();
                            return;
                        }
                        boolean wasConnected = isInputLive();
                        connectionState = state;
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            connectedHost = device;
                            // Remember automatically only when no PC was chosen yet. Otherwise an
                            // old PC reconnecting would undo the user's SWITCH PC choice.
                            if (preferredHost() == null) rememberHost(device);
                            recoveryScheduled = false;
                            recoveryAttempts = 0;
                            consecutiveFailures = 0;
                            lastGamepadReport = null;
                            Listener current = listener;
                            if (!wasConnected && current != null)
                                current.onInputConnected(safeName(device));
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (device.equals(connectedHost)) connectedHost = null;
                            scheduleRecovery();
                        }
                        notifyChanged();
                    }
                });
        if (!requested) {
            registrationPending = false;
            Log.w(TAG, "Bluetooth HID app registration was rejected");
            scheduleRecovery();
        } else {
            handler.postDelayed(() -> {
                if (registrationPending) {
                    registrationPending = false;
                    Log.w(TAG, "Bluetooth HID registration timed out");
                    scheduleRecovery();
                }
            }, 4000);
        }
    }

    private void connectPreferredHost() {
        if (destroyed || !foreground || !active || hid == null || !registered ||
                connectedHost != null) return;
        BluetoothDevice preferred = preferredHost();
        if (preferred == null) {
            notifyChanged();
            return;
        }
        connectionState = hid.getConnectionState(preferred);
        if (connectionState == BluetoothProfile.STATE_CONNECTED) {
            connectedHost = preferred;
            notifyChanged();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (connectionState == BluetoothProfile.STATE_CONNECTING ||
                now - lastConnectRequestAt < 1200) {
            notifyChanged();
            scheduleRecovery();
            return;
        }
        lastConnectRequestAt = now;
        Log.d(TAG, "Connecting preferred HID host " + safeName(preferred));
        if (hid.connect(preferred)) connectionState = BluetoothProfile.STATE_CONNECTING;
        else Log.w(TAG, "Bluetooth HID connect request was rejected");
        notifyChanged();
        scheduleRecovery();
    }

    private BluetoothDevice preferredHost() {
        if (!hasPermission() || adapter == null) return null;
        String address = context.getSharedPreferences("controls", Context.MODE_PRIVATE)
                .getString("last_hid_host", null);
        if (address == null) return null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            return adapter.getBondedDevices().contains(device) ? device : null;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Saved Bluetooth host address is invalid", e);
            return null;
        }
    }

    private void rememberHost(BluetoothDevice device) {
        if (!hasPermission() || device == null) return;
        context.getSharedPreferences("controls", Context.MODE_PRIVATE).edit()
                .putString("last_hid_host", device.getAddress()).apply();
    }

    private void scheduleRecovery() {
        if (destroyed || !foreground || !active || recoveryScheduled || isInputLive()) return;
        recoveryScheduled = true;
        // Retry quickly at first, then back off so a PC that is off does not drain the phone.
        long delay = recoveryAttempts < 6 ? 1500 : recoveryAttempts < 16 ? 3500 : 8000;
        handler.postDelayed(() -> {
            recoveryScheduled = false;
            recoveryAttempts++;
            ensureReady();
            if (!isInputLive()) scheduleRecovery();
        }, delay);
    }

    private void registerReceivers() {
        if (receiversRegistered || destroyed || !hasPermission()) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        context.registerReceiver(receiver, filter);
        receiversRegistered = true;
    }

    /** Bluetooth was switched off: the old profile proxy is dead, so drop it and rebind later. */
    private void dropStaleProfile() {
        BluetoothHidDevice stale = hid;
        hid = null;
        binding = false;
        registered = false;
        registrationPending = false;
        connectedHost = null;
        connectionState = BluetoothProfile.STATE_DISCONNECTED;
        pendingDx = pendingDy = pendingWheel = pendingPan = 0;
        pendingGamepadReport = null;
        if (stale != null && adapter != null) {
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, stale);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not close stale HID proxy", e);
            }
        }
        notifyChanged();
    }

    private long reserveReportWindow(int durationMs) {
        synchronized (this) {
            long start = Math.max(SystemClock.uptimeMillis(), nextReportAt);
            nextReportAt = start + durationMs;
            return start;
        }
    }

    private void postReport(BluetoothHidDevice targetHid, BluetoothDevice targetHost,
                            int id, byte[] value, long at) {
        handler.postAtTime(() -> sendReport(targetHid, targetHost, id, value), at);
    }

    private void sendReport(BluetoothHidDevice targetHid, BluetoothDevice targetHost,
                            int id, byte[] value) {
        sendReport(targetHid, targetHost, id, value, 0);
    }

    private void sendReport(BluetoothHidDevice targetHid, BluetoothDevice targetHost,
                            int id, byte[] value, int attempt) {
        if (targetHid.sendReport(targetHost, id, value)) {
            consecutiveFailures = 0;
            return;
        }
        Log.w(TAG, "Bluetooth rejected HID report " + id);
        boolean release = (id == 1 || id == 3) && allZero(value);
        if (release && attempt < 3) {
            // A lost key-up would leave the key held down on the PC, so retry it.
            handler.postDelayed(() -> {
                if (targetHost.equals(connectedHost))
                    sendReport(targetHid, targetHost, id, value, attempt + 1);
            }, 30L * (attempt + 1));
            return;
        }
        // One congested report is not a dead link: only recover after repeated failures.
        if (!release && ++consecutiveFailures < 6) return;
        consecutiveFailures = 0;
        if (targetHost.equals(connectedHost)) connectedHost = null;
        connectionState = BluetoothProfile.STATE_DISCONNECTED;
        targetHid.disconnect(targetHost);
        notifyChanged();
        scheduleRecovery();
    }

    private static boolean allZero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }

    private void notifyChanged() {
        handler.post(() -> {
            Listener current = listener;
            if (current != null) current.onStateChanged();
            Runnable watcher = observer;
            if (watcher != null) watcher.run();
        });
    }

    private static int clamp(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    /** Triggers are unsigned 0..255. */
    private static int trigger(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
