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
            mainHandler.postDelayed(this, 1200);
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
            Toast.makeText(this, "Enable 'Touch Blocker' under Accessibility (No overlay — safe for mouse!)", Toast.LENGTH_LONG).show();
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

    private void handleModeSwitch(int fallbackMode, String daemonEndpoint, String successMsg) {
        if (mLastDaemonRunning) {
            executor.execute(() -> {
                boolean ok = DaemonClient.sendAction(daemonEndpoint);
                mainHandler.post(() -> {
                    Toast.makeText(this, ok ? successMsg : "Failed to connect to daemon", Toast.LENGTH_SHORT).show();
                    refreshStatusAsync();
                });
            });
        } else {
            TouchExplorerFallbackService fallback = TouchExplorerFallbackService.getInstance();
            if (fallback != null) {
                fallback.setMode(fallbackMode);
                Toast.makeText(this, successMsg + " (Fallback Mode)", Toast.LENGTH_SHORT).show();
                refreshStatusAsync();
            } else {
                Toast.makeText(this, "Enable Reboot Fallback in Accessibility first", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDaemonViaLocalAdb() {
        Toast.makeText(this, "Trying local ADB shell (127.0.0.1:5555)...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean ok = AdbLocalClient.executeCommand("/data/local/tmp/touch_blocker on");
            try { Thread.sleep(500); } catch (Exception ignored) {}
            mainHandler.post(() -> {
                if (ok) {
                    Toast.makeText(this, "Kernel daemon started!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Port 5555 closed after reboot. Using Reboot Fallback instead.", Toast.LENGTH_LONG).show();
                }
                refreshStatusAsync();
            });
        });
    }

    private void updateUI(DaemonClient.DaemonStatus status) {
        TouchExplorerFallbackService fallback = TouchExplorerFallbackService.getInstance();
        boolean fallbackReady = (fallback != null);

        // Show one-tap setup button whenever Accessibility fallback isn't enabled yet
        btnEnableFallback.setVisibility(fallbackReady ? View.GONE : View.VISIBLE);

        if (status.isRunning) {
            // ── MODE 1: KERNEL DAEMON (100% hardware block) ──────────────────
            tvBadge.setText("🛡️ KERNEL DAEMON ACTIVE");
            tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));

            setMouseText(status.isMouseConnected);

            if (status.isBlocked) {
                tvTouchStatus.setText("🛡️ Touchscreen: BLOCKED (Kernel EVIOCGRAB)");
                tvTouchStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvTouchStatus.setText("⚠️ Touchscreen: ALLOWED (Active)");
                tvTouchStatus.setTextColor(Color.parseColor("#FFD54F"));
            }

            if (status.isAutoMode) {
                tvModeDesc.setText("⚙️ Auto-Sync ACTIVE: Blocks touch when mouse connects, restores touch when disconnected."
                        + (fallbackReady ? "\n✓ Reboot Fallback armed for restarts." : ""));
                btnAuto.setText("✓ AUTO-SYNC ACTIVE");
                btnAuto.setBackgroundColor(Color.parseColor("#0D47A1"));
            } else {
                tvModeDesc.setText("⚙️ MANUAL OVERRIDE: Tap Auto Mode below to re-enable automatic mouse sync.");
                btnAuto.setText("🔄 SWITCH TO AUTO MODE");
                btnAuto.setBackgroundColor(Color.parseColor("#1976D2"));
            }

            tvGhostCount.setText("Ghost touches filtered: " + status.ghostCount);

            layoutManualButtons.setVisibility(View.VISIBLE);
            btnAuto.setVisibility(View.VISIBLE);
            layoutStartDaemon.setVisibility(View.GONE);
            btnWebUi.setVisibility(View.VISIBLE);

        } else if (fallbackReady) {
            // ── MODE 2: OVERLAY-FREE REBOOT FALLBACK (No PC needed!) ─────────
            boolean mouseNow = TouchExplorerFallbackService.detectMouse(this);
            boolean active = fallback.isTouchExplorationActive();
            int mode = fallback.getMode();

            tvBadge.setText("🛡️ REBOOT FALLBACK ACTIVE (NO PC)");
            tvBadge.setBackgroundColor(Color.parseColor("#00695C"));

            setMouseText(mouseNow);

            if (active) {
                tvTouchStatus.setText("🛡️ Touchscreen: NEUTRALIZED (Mouse Native)");
                tvTouchStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvTouchStatus.setText("⚠️ Touchscreen: ALLOWED (Active)");
                tvTouchStatus.setTextColor(Color.parseColor("#FFD54F"));
            }

            if (mode == TouchExplorerFallbackService.MODE_AUTO) {
                tvModeDesc.setText("⚙️ Overlay-Free Fallback (Auto-Sync): Neutralizes ghost taps when mouse is connected while keeping mouse 100% native. Survives reboots without PC!");
                btnAuto.setText("✓ AUTO-SYNC ACTIVE");
                btnAuto.setBackgroundColor(Color.parseColor("#0D47A1"));
            } else {
                tvModeDesc.setText("⚙️ Fallback MANUAL OVERRIDE: Tap Auto Mode to sync with Bluetooth mouse.");
                btnAuto.setText("🔄 SWITCH TO AUTO MODE");
                btnAuto.setBackgroundColor(Color.parseColor("#1976D2"));
            }

            tvGhostCount.setText("Ghost events neutralized: " + fallback.getNeutralizedCount());

            layoutManualButtons.setVisibility(View.VISIBLE);
            btnAuto.setVisibility(View.VISIBLE);
            layoutStartDaemon.setVisibility(View.VISIBLE);
            btnWebUi.setVisibility(View.GONE);

        } else {
            // ── MODE 3: NEITHER DAEMON NOR FALLBACK ACTIVE ───────────────────
            boolean mouseNow = TouchExplorerFallbackService.detectMouse(this);

            tvBadge.setText("⚠️ PROTECTION INACTIVE");
            tvBadge.setBackgroundColor(Color.parseColor("#B71C1C"));

            setMouseText(mouseNow);

            tvTouchStatus.setText("⚠️ Touchscreen: Unblocked (Native)");
            tvTouchStatus.setTextColor(Color.parseColor("#AAAAAA"));

            tvModeDesc.setText("Kernel daemon stopped after reboot.\n\nTap 'ENABLE REBOOT FALLBACK' below so the app automatically protects your phone after every reboot without needing a PC.");
            tvGhostCount.setText("");

            layoutManualButtons.setVisibility(View.GONE);
            btnAuto.setVisibility(View.GONE);
            layoutStartDaemon.setVisibility(View.VISIBLE);
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
