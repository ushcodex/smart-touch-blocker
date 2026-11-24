package com.touchblocker;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String BASE_URL = "http://127.0.0.1:9876";

    private TextView tvBadge;
    private TextView tvStatusDesc;
    private TextView tvGhostCount;
    private Button btnAction;
    private Button btnRefresh;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isBlocked = false;
    private boolean isDaemonRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBadge = findViewById(R.id.tv_badge);
        tvStatusDesc = findViewById(R.id.tv_status_desc);
        tvGhostCount = findViewById(R.id.tv_ghost_count);
        btnAction = findViewById(R.id.btn_action);
        btnRefresh = findViewById(R.id.btn_refresh);

        btnAction.setOnClickListener(v -> sendCommand("/toggle"));
        btnRefresh.setOnClickListener(v -> checkStatus());

        checkStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkStatus();
    }

    private void checkStatus() {
        sendCommand("/");
    }

    private void sendCommand(String endpoint) {
        tvStatusDesc.setText("Updating...");
        btnAction.setEnabled(false);

        executor.execute(() -> {
            try {
                URL url = new URL(BASE_URL + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String body = sb.toString();
                    boolean blocked = body.contains("TOUCHSCREEN BLOCKED");
                    String ghostStr = "";
                    int ghostIdx = body.indexOf("Ghost touches blocked: ");
                    if (ghostIdx != -1) {
                        int endIdx = body.indexOf("</p>", ghostIdx);
                        if (endIdx != -1) {
                            ghostStr = body.substring(ghostIdx, endIdx);
                        }
                    }

                    final String finalGhost = ghostStr;
                    mainHandler.post(() -> updateUI(true, blocked, finalGhost));
                } else {
                    mainHandler.post(() -> updateUI(false, false, ""));
                }
            } catch (Exception e) {
                mainHandler.post(() -> updateUI(false, false, ""));
            }
        });
    }

    private void updateUI(boolean daemonActive, boolean blocked, String ghostText) {
        btnAction.setEnabled(true);
        isDaemonRunning = daemonActive;
        isBlocked = blocked;

        if (!daemonActive) {
            tvBadge.setText("⚠️ DAEMON INACTIVE");
            tvBadge.setBackgroundColor(Color.parseColor("#B71C1C"));
            tvStatusDesc.setText("The touch blocker daemon is not running.\n\nConnect PC once and run:\nadb shell /data/local/tmp/touch_blocker on");
            tvGhostCount.setText("");
            btnAction.setText("CHECK AGAIN");
            btnAction.setBackgroundColor(Color.parseColor("#333333"));
            btnAction.setOnClickListener(v -> checkStatus());
        } else if (blocked) {
            tvBadge.setText("🛡 TOUCHSCREEN BLOCKED");
            tvBadge.setBackgroundColor(Color.parseColor("#1B5E20"));
            tvStatusDesc.setText("Malfunctioning digitizer ghost touches are completely blocked.\n\nMouse input is 100% active.");
            tvGhostCount.setText(ghostText);
            btnAction.setText("RESTORE TOUCHSCREEN");
            btnAction.setBackgroundColor(Color.parseColor("#D50000"));
            btnAction.setOnClickListener(v -> sendCommand("/off"));
        } else {
            tvBadge.setText("⚠️ TOUCHSCREEN RESTORED");
            tvBadge.setBackgroundColor(Color.parseColor("#FF6F00"));
            tvStatusDesc.setText("Touchscreen digitizer is currently active.\n\nGhost touches are NOT blocked.");
            tvGhostCount.setText(ghostText);
            btnAction.setText("BLOCK TOUCHSCREEN");
            btnAction.setBackgroundColor(Color.parseColor("#00C853"));
            btnAction.setOnClickListener(v -> sendCommand("/on"));
        }
    }
}
