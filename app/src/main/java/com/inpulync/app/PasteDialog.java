package com.inpulync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

/** Type on the phone (any keyboard, any language) and send the text to the PC. */
final class PasteDialog {
    private PasteDialog() {
    }

    static void show(Activity activity, ShortcutRunner runner) {
        EditText input = new EditText(activity);
        input.setHint("Type or paste text to send to the PC");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Paste text to PC")
                .setView(input)
                .setPositiveButton("TYPE IT", null)
                .setNeutralButton("CLIPBOARD", null)
                .setNegativeButton("CANCEL", null)
                .create();
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE |
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();

        // Set after show() so a click can be rejected without closing the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString();
            if (text.isEmpty()) {
                Toast.makeText(activity, "Nothing to type yet", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            runner.typeText(text);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = clip != null && clip.getItemCount() > 0
                    ? clip.getItemAt(0).coerceToText(activity) : null;
            if (text == null || text.length() == 0) {
                Toast.makeText(activity, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            } else {
                input.setText(text);
                input.setSelection(input.getText().length());
            }
        });
    }
}
