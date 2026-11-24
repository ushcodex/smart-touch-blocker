package com.touchblocker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView tvBadge;
    private TextView tvStatusDesc;
    private TextView tvGhostCount;
    private Button btnAction;
    private Button btnRefresh;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateUI();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBadge = findViewById(R.id.tv_badge);
        tvStatusDesc = findViewById(R.id.tv_status_desc);
        tvGhostCount = findViewById(R.id.tv_ghost_count);
        btnAction = findViewById(R.id.btn_action);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnAction.setOnClickListener(v -> handleActionButton());
        btnRefresh.setOnClickListener(v -> updateUI());

        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        mainHandler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(statusUpdater);
    }

    private void handleActionButton() {
        SmartBlockerAccessibilityService service = SmartBlockerAccessibilityService.getInstance();
        if (service == null) {
            // Open Accessibility Settings so user can enable it with one tap
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } else {
            // Toggle blocking
            service.toggleBlocking();
            updateUI();
        }
    }

    private void updateUI() {
        SmartBlockerAccessibilityService service = SmartBlockerAccessibilityService.getInstance();

        if (service == null) {
            tvBadge.setText("⚠️ SETUP REQUIRED");
            tvBadge.setBackgroundColor(Color.parseColor("#E65100"));
            tvStatusDesc.setText("To block ghost touches independently without a PC:\n\nTap below to enable 'Touch Blocker' in Accessibility settings.\n\nOnce enabled, it auto-starts on boot!");
            tvGhostCount.setText("");
            btnAction.setText("ENABLE ACCESSIBILITY");
            btnAction.setBackgroundColor(Color.parseColor("#2979FF"));
        } else if (service.isBlocking()) {
            tvBadge.setText("🛡️ ACTIVE (INDEPENDENT)");
            tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));
            tvStatusDesc.setText("Touchscreen digitizer is 100% BLOCKED.\nMouse clicks are processed and forwarded.\n\nWorks completely standalone without PC!");
            tvGhostCount.setText("Ghost touches absorbed: " + service.getGhostCount());
            btnAction.setText("PAUSE BLOCKER");
            btnAction.setBackgroundColor(Color.parseColor("#D50000"));
        } else {
            tvBadge.setText("⚠️ PAUSED");
            tvBadge.setBackgroundColor(Color.parseColor("#B71C1C"));
            tvStatusDesc.setText("Touchscreen is unblocked.\nGhost touches are NOT filtered.");
            tvGhostCount.setText("Ghost touches absorbed: " + service.getGhostCount());
            btnAction.setText("RESUME BLOCKER");
            btnAction.setBackgroundColor(Color.parseColor("#00C853"));
        }
    }
}
