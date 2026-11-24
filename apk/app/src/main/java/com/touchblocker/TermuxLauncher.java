package com.touchblocker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

public class TermuxLauncher {
    private static final String TAG = "TermuxLauncher";

    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_IN_BACKGROUND = "com.termux.RUN_COMMAND_IN_BACKGROUND";

    public static boolean launchViaTermux(Context context) {
        try {
            // Method 1: Send RunCommandService background intent
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra(EXTRA_ARGUMENTS, new String[]{"-c", "/data/local/tmp/touch_blocker on"});
            intent.putExtra(EXTRA_IN_BACKGROUND, true);
            context.startService(intent);
            Log.i(TAG, "Sent RUN_COMMAND service intent to Termux");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Direct Termux service intent failed: " + e.getMessage());
        }

        try {
            // Method 2: Launch Termux activity directly with clipboard helper
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("command", "/data/local/tmp/touch_blocker on\n");
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Command copied! Paste and press Enter in Termux", Toast.LENGTH_LONG).show();
            }

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage("com.termux");
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Termux: " + e.getMessage());
        }
        return false;
    }
}
