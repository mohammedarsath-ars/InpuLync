package com.inpulync.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Stores the secret the Windows helper must know before the phone will talk to it. */
final class UsbPairing {
    // No 0/O/1/I so the code is easy to read off the phone and type on the PC.
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String KEY = "usb_pair_code";

    private UsbPairing() {
    }

    static synchronized String code(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        String code = prefs.getString(KEY, null);
        if (code == null || code.length() != 16) {
            code = generate();
            prefs.edit().putString(KEY, code).apply();
        }
        return code;
    }

    static synchronized String regenerate(Context context) {
        String code = generate();
        context.getSharedPreferences("controls", Context.MODE_PRIVATE)
                .edit().putString(KEY, code).apply();
        return code;
    }

    /** Groups of four, e.g. ABCD-EFGH-JKLM-NPQR. */
    static String pretty(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(code.charAt(i));
        }
        return out.toString();
    }

    private static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }
}
