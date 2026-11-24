package com.touchblocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            new Thread(() -> {
                boolean ok = AdbLocalClient.executeCommand("/data/local/tmp/touch_blocker on");
                if (!ok) {
                    TermuxLauncher.launchViaTermux(context);
                }
            }).start();
        }
    }
}
