package com.touchblocker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private TextView tvBadge;
    private TextView tvMouseStatus;
    private TextView tvTouchStatus;
    private TextView tvModeDesc;
    private TextView tvGhostCount;

    private Button btnAuto;
    private LinearLayout layoutManualButtons;
    private Button btnForceBlock;
    private Button btnForceUnblock;
    private Button btnEnableFallback;

    private LinearLayout layoutStartDaemon;
    private Button btnStartAdb;

    private Button btnWebUi;
    private Button btnRefresh;

    private volatile boolean mLastDaemonRunning = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            refreshStatusAsync();
            mainHandler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBadge = findViewById(R.id.tv_badge);
        tvMouseStatus = findViewById(R.id.tv_mouse_status);
        tvTouchStatus = findViewById(R.id.tv_touch_status);
        tvModeDesc = findViewById(R.id.tv_mode_desc);
        tvGhostCount = findViewById(R.id.tv_ghost_count);

        btnAuto = findViewById(R.id.btn_auto);
        layoutManualButtons = findViewById(R.id.layout_manual_buttons);
        btnForceBlock = findViewById(R.id.btn_force_block);
        btnForceUnblock = findViewById(R.id.btn_force_unblock);
        btnEnableFallback = findViewById(R.id.btn_enable_fallback);

        layoutStartDaemon = findViewById(R.id.layout_start_daemon);
        btnStartAdb = findViewById(R.id.btn_start_adb);

        btnWebUi = findViewById(R.id.btn_web_ui);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnAuto.setOnClickListener(v -> handleModeSwitch(TouchExplorerFallbackService.MODE_AUTO, "/auto", "Auto-Sync Enabled"));
        btnForceBlock.setOnClickListener(v -> handleModeSwitch(TouchExplorerFallbackService.MODE_FORCE_BLOCK, "/on", "Touchscreen Blocked"));
        btnForceUnblock.setOnClickListener(v -> handleModeSwitch(TouchExplorerFallbackService.MODE_FORCE_UNBLOCK, "/off", "Touchscreen Restored"));

        btnEnableFallback.setOnClickListener(v -> {
            Toast.makeText(this, "Enable 'Touch Blocker' under Accessibility", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnStartAdb.setOnClickListener(v -> startDaemonViaLocalAdb());

        btnWebUi.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:9876"));
            startActivity(intent);
        });

        btnRefresh.setOnClickListener(v -> refreshStatusAsync());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(statusUpdater);
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void refreshStatusAsync() {
        executor.execute(() -> {
            DaemonClient.DaemonStatus status = DaemonClient.getStatus();
            mLastDaemonRunning = status.isRunning;
            mainHandler.post(() -> updateUI(status));
        });
    }

    private void handleModeSwitch(int mode, String daemonEndpoint, String successMsg) {
        if (mLastDaemonRunning) {
            executor.execute(() -> {
                boolean ok = DaemonClient.sendAction(daemonEndpoint);
                mainHandler.post(() -> {
                    Toast.makeText(this, ok ? successMsg : "Failed to reach daemon", Toast.LENGTH_SHORT).show();
                    refreshStatusAsync();
                });
            });
        } else {
            TouchExplorerFallbackService svc = TouchExplorerFallbackService.getInstance();
            if (svc != null) {
                svc.setMode(mode);
                Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
                refreshStatusAsync();
            } else {
                Toast.makeText(this, "Enable Touch Blocker in Accessibility first", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDaemonViaLocalAdb() {
        executor.execute(() -> {
            boolean ok = AdbLocalClient.executeCommand("/data/local/tmp/touch_blocker on");
            try { Thread.sleep(400); } catch (Exception ignored) {}
            mainHandler.post(() -> {
                Toast.makeText(this, ok ? "Kernel daemon started!" : "Local ADB unavailable (using Spotlight mode)", Toast.LENGTH_SHORT).show();
                refreshStatusAsync();
            });
        });
    }

    private void updateUI(DaemonClient.DaemonStatus status) {
        TouchExplorerFallbackService spotlight = TouchExplorerFallbackService.getInstance();
        boolean spotlightReady = (spotlight != null);

        btnEnableFallback.setVisibility(spotlightReady ? View.GONE : View.VISIBLE);

        if (status.isRunning) {
            tvBadge.setText("🛡️ KERNEL DAEMON ACTIVE");
            tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));

            setMouseText(status.isMouseConnected);

            if (status.isBlocked) {
                tvTouchStatus.setText("🛡️ Touchscreen: BLOCKED (Kernel)");
                tvTouchStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvTouchStatus.setText("⚠️ Touchscreen: ALLOWED");
                tvTouchStatus.setTextColor(Color.parseColor("#FFD54F"));
            }

            tvModeDesc.setText(status.isAutoMode
                    ? "Auto-Sync ACTIVE: Blocks touch when mouse connects, restores when disconnected."
                    : "MANUAL OVERRIDE: Tap Auto Mode to sync with mouse.");
            btnAuto.setText(status.isAutoMode ? "✓ AUTO-SYNC ACTIVE" : "🔄 SWITCH TO AUTO MODE");
            btnAuto.setBackgroundColor(Color.parseColor(status.isAutoMode ? "#0D47A1" : "#1976D2"));

            tvGhostCount.setText("Ghost touches blocked: " + status.ghostCount);

            layoutManualButtons.setVisibility(View.VISIBLE);
            btnAuto.setVisibility(View.VISIBLE);
            layoutStartDaemon.setVisibility(View.GONE);
            btnWebUi.setVisibility(View.VISIBLE);

        } else if (spotlightReady) {
            boolean mouseNow = TouchExplorerFallbackService.detectMouse(this);
            boolean blocking = spotlight.isBlockingActive();
            int mode = spotlight.getMode();

            if (blocking) {
                tvBadge.setText("🛡️ SPOTLIGHT BLOCKER ACTIVE (NO PC)");
                tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));
                tvTouchStatus.setText("🛡️ Touchscreen: BLOCKED (Mouse Spotlight Hole)");
                tvTouchStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvBadge.setText("⚠️ STANDBY (TOUCH ALLOWED)");
                tvBadge.setBackgroundColor(Color.parseColor("#E65100"));
                tvTouchStatus.setText("⚠️ Touchscreen: ALLOWED (No overlays)");
                tvTouchStatus.setTextColor(Color.parseColor("#FFD54F"));
            }

            setMouseText(mouseNow);

            if (mode == TouchExplorerFallbackService.MODE_AUTO) {
                tvModeDesc.setText("Auto-Sync ACTIVE: Covers 99.9% of screen against ghost touches while keeping a live windowless hole under your mouse cursor.");
                btnAuto.setText("✓ AUTO-SYNC ACTIVE");
                btnAuto.setBackgroundColor(Color.parseColor("#0D47A1"));
            } else {
                tvModeDesc.setText("MANUAL OVERRIDE: Tap Auto Mode to sync with Bluetooth mouse.");
                btnAuto.setText("🔄 SWITCH TO AUTO MODE");
                btnAuto.setBackgroundColor(Color.parseColor("#1976D2"));
            }

            tvGhostCount.setText("Real ghost touches absorbed: " + spotlight.getBlockedGhostCount());

            layoutManualButtons.setVisibility(View.VISIBLE);
            btnAuto.setVisibility(View.VISIBLE);
            layoutStartDaemon.setVisibility(View.GONE);
            btnWebUi.setVisibility(View.GONE);

        } else {
            boolean mouseNow = TouchExplorerFallbackService.detectMouse(this);

            tvBadge.setText("⚠️ SERVICE NOT ENABLED");
            tvBadge.setBackgroundColor(Color.parseColor("#B71C1C"));

            setMouseText(mouseNow);

            tvTouchStatus.setText("⚠️ Touchscreen: Unblocked");
            tvTouchStatus.setTextColor(Color.parseColor("#AAAAAA"));

            tvModeDesc.setText("Tap 'ENABLE REBOOT FALLBACK' below to activate standalone touch blocking without a PC.");
            tvGhostCount.setText("");

            layoutManualButtons.setVisibility(View.GONE);
            btnAuto.setVisibility(View.GONE);
            layoutStartDaemon.setVisibility(View.GONE);
            btnWebUi.setVisibility(View.GONE);
        }
    }

    private void setMouseText(boolean connected) {
        if (connected) {
            tvMouseStatus.setText("🖱️ Mouse: CONNECTED");
            tvMouseStatus.setTextColor(Color.parseColor("#69F0AE"));
        } else {
            tvMouseStatus.setText("🖱️ Mouse: NOT DETECTED");
            tvMouseStatus.setTextColor(Color.parseColor("#FFAB91"));
        }
    }
}
