package com.touchblocker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private LinearLayout layoutStartDaemon;
    private Button btnStartAdb;
    private Button btnStartTermux;

    private Button btnWebUi;
    private Button btnRefresh;

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

        layoutStartDaemon = findViewById(R.id.layout_start_daemon);
        btnStartAdb = findViewById(R.id.btn_start_adb);
        btnStartTermux = findViewById(R.id.btn_start_termux);

        btnWebUi = findViewById(R.id.btn_web_ui);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnAuto.setOnClickListener(v -> triggerAction("/auto", "Auto-Sync Enabled"));
        btnForceBlock.setOnClickListener(v -> triggerAction("/on", "Touchscreen Blocked"));
        btnForceUnblock.setOnClickListener(v -> triggerAction("/off", "Touchscreen Restored"));

        btnStartAdb.setOnClickListener(v -> startDaemonViaLocalAdb());
        btnStartTermux.setOnClickListener(v -> startDaemonViaTermux());

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
            mainHandler.post(() -> updateUI(status));
        });
    }

    private void triggerAction(String endpoint, String successMsg) {
        executor.execute(() -> {
            boolean ok = DaemonClient.sendAction(endpoint);
            mainHandler.post(() -> {
                if (ok) {
                    Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to connect to daemon", Toast.LENGTH_SHORT).show();
                }
                refreshStatusAsync();
            });
        });
    }

    private void startDaemonViaLocalAdb() {
        Toast.makeText(this, "Starting daemon via local shell...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean ok = AdbLocalClient.executeCommand("/data/local/tmp/touch_blocker on");
            try { Thread.sleep(500); } catch (Exception ignored) {}
            mainHandler.post(() -> {
                if (ok) {
                    Toast.makeText(this, "Daemon started successfully!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Local shell not accessible. Try Termux.", Toast.LENGTH_LONG).show();
                }
                refreshStatusAsync();
            });
        });
    }

    private void startDaemonViaTermux() {
        TermuxLauncher.launchViaTermux(this);
    }

    private void updateUI(DaemonClient.DaemonStatus status) {
        if (status.isRunning) {
            tvBadge.setText("🛡️ DAEMON ACTIVE");
            tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));

            if (status.isMouseConnected) {
                tvMouseStatus.setText("🖱️ Mouse: CONNECTED");
                tvMouseStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvMouseStatus.setText("🖱️ Mouse: NOT DETECTED");
                tvMouseStatus.setTextColor(Color.parseColor("#FFAB91"));
            }

            if (status.isBlocked) {
                tvTouchStatus.setText("🛡️ Touchscreen: BLOCKED (Ghost touches muted)");
                tvTouchStatus.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                tvTouchStatus.setText("⚠️ Touchscreen: ALLOWED (Active)");
                tvTouchStatus.setTextColor(Color.parseColor("#FFD54F"));
            }

            if (status.isAutoMode) {
                tvModeDesc.setText("⚙️ Auto-Sync ACTIVE: Touchscreen blocks automatically when mouse connects and restores touch when disconnected.");
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
        } else {
            tvBadge.setText("⚠️ DAEMON STOPPED");
            tvBadge.setBackgroundColor(Color.parseColor("#B71C1C"));

            tvMouseStatus.setText("🖱️ Mouse: Unknown (Daemon Offline)");
            tvMouseStatus.setTextColor(Color.parseColor("#AAAAAA"));

            tvTouchStatus.setText("⚠️ Touchscreen: Unblocked (Native)");
            tvTouchStatus.setTextColor(Color.parseColor("#AAAAAA"));

            tvModeDesc.setText("Blocker daemon is not running. Tap below to launch it via Local Shell or Termux.");
            tvGhostCount.setText("");

            layoutManualButtons.setVisibility(View.GONE);
            btnAuto.setVisibility(View.GONE);
            layoutStartDaemon.setVisibility(View.VISIBLE);
        }
    }
}
