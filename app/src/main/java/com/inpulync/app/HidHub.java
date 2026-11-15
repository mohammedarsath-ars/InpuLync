package com.inpulync.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Process-wide owner of the Bluetooth HID link, so it survives the screen being closed. */
final class HidHub {
    private static BluetoothHidController instance;

    private HidHub() {
    }

    static synchronized BluetoothHidController get(Context context) {
        if (instance == null) {
            instance = new BluetoothHidController(context.getApplicationContext(),
                    new Handler(Looper.getMainLooper()));
        }
        return instance;
    }

    /** Unregisters the HID app and drops the controller; the next get() starts fresh. */
    static synchronized void release() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }
}
