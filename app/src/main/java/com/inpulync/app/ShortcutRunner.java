package com.inpulync.app;

import android.content.Context;
import android.util.Base64;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/** Sends shortcuts and pasted text over whichever transport is active. */
final class ShortcutRunner {
    interface Host {
        boolean usbMode();

        boolean bluetoothMode();

        int hostLayout();
    }

    /** Longest paste accepted over USB (Bluetooth is capped by TextPlanner). */
    static final int MAX_USB_CHARS = 5000;

    private final Context context;
    private final BluetoothHidController bluetooth;
    private final UsbBridgeServer usb;
    private final Host host;

    ShortcutRunner(Context context, BluetoothHidController bluetooth, UsbBridgeServer usb, Host host) {
        this.context = context;
        this.bluetooth = bluetooth;
        this.usb = usb;
        this.host = host;
    }

    void runShortcut(String spec) {
        ShortcutParser.Shortcut shortcut = ShortcutParser.parse(spec, host.hostLayout());
        if (shortcut == null) {
            toast("Shortcut not understood: " + spec);
            return;
        }
        if (shortcut.isText()) {
            typeText(shortcut.text);
            return;
        }
        if (host.usbMode()) {
            if (shortcut.usbChord == null) {
                toast("This key only works in Bluetooth mode");
                return;
            }
            if (!usb.hasClients()) {
                toast("USB helper is not connected");
                return;
            }
            usb.send("HOTKEY " + shortcut.usbChord);
        } else if (host.bluetoothMode()) {
            if (!bluetooth.isInputLive()) {
                toast("Not connected to a PC yet");
                return;
            }
            bluetooth.sendKey(shortcut.modifier, shortcut.usage);
        } else {
            toast("Turn on Bluetooth or USB mode first");
        }
    }

    void typeText(String text) {
        if (text == null || text.isEmpty()) return;
        if (host.usbMode()) {
            if (!usb.hasClients()) {
                toast("USB helper is not connected");
                return;
            }
            sendUsbText(text);
        } else if (host.bluetoothMode()) {
            if (!bluetooth.isInputLive()) {
                toast("Not connected to a PC yet");
                return;
            }
            TextPlanner.Plan plan = TextPlanner.plan(text, host.hostLayout());
            bluetooth.typeSteps(plan.steps);
            StringBuilder message = new StringBuilder("Typing " + plan.steps.size() +
                    " characters. Tap the status text to stop.");
            if (plan.skipped > 0) message.append("\n").append(plan.skipped)
                    .append(" characters can't be typed over Bluetooth (use USB mode).");
            if (plan.truncated > 0) message.append("\n").append(plan.truncated)
                    .append(" characters past the ").append(TextPlanner.MAX_CHARS)
                    .append(" limit were left out.");
            toast(message.toString());
        } else {
            toast("Turn on Bluetooth or USB mode first");
        }
    }

    /** Unicode text goes as TEXT lines; Enter and Tab are real key presses. */
    private void sendUsbText(String text) {
        String limited = text.length() > MAX_USB_CHARS ? text.substring(0, MAX_USB_CHARS) : text;
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < limited.length(); i++) {
            char c = limited.charAt(i);
            if (c == '\r') continue;
            if (c == '\n' || c == '\t') {
                flushUsbText(chunk);
                usb.send(c == '\n' ? "KEY ENTER" : "KEY TAB");
            } else {
                chunk.append(c);
            }
        }
        flushUsbText(chunk);
        if (limited.length() < text.length()) toast((text.length() - limited.length()) +
                " characters past the " + MAX_USB_CHARS + " limit were left out.");
    }

    private void flushUsbText(StringBuilder chunk) {
        if (chunk.length() == 0) return;
        usb.send("TEXT " + Base64.encodeToString(
                chunk.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        chunk.setLength(0);
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
