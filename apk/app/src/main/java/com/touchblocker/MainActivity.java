package com.touchblocker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;

    private TextView tvStatus;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnToggle = findViewById(R.id.btn_toggle);

        btnToggle.setOnClickListener(v -> handleToggle());
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void handleToggle() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }

        if (TouchBlockerService.isRunning()) {
            stopService(new Intent(this, TouchBlockerService.class));
        } else {
            Intent svc = new Intent(this, TouchBlockerService.class);
            startForegroundService(svc);
        }

        // Small delay so the service state updates
        btnToggle.postDelayed(this::updateUI, 300);
    }

    private void updateUI() {
        boolean running = TouchBlockerService.isRunning();
        boolean hasOverlay = Settings.canDrawOverlays(this);

        if (!hasOverlay) {
            tvStatus.setText("⚠ Overlay permission required\nTap below to grant it.");
            btnToggle.setText("Grant Permission");
        } else if (running) {
            tvStatus.setText("🛡 ACTIVE\n\nTouchscreen: BLOCKED\nMouse: ALLOWED\n\nGhost touches are being discarded.");
            btnToggle.setText("Turn OFF");
        } else {
            tvStatus.setText("💤 INACTIVE\n\nTouchscreen is unblocked.\nTap below to start blocking ghost touches.");
            btnToggle.setText("Turn ON");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            updateUI();
        }
    }
}
