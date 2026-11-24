package com.touchblocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Auto-start the blocker on boot if it was previously active
            // (For simplicity, always start on boot — user can disable via the app)
            Intent svc = new Intent(context, TouchBlockerService.class);
            context.startForegroundService(svc);
        }
    }
}
