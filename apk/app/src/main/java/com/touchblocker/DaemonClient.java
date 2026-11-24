package com.touchblocker;

import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class DaemonClient {
    private static final String TAG = "DaemonClient";
    private static final String BASE_URL = "http://127.0.0.1:9876";

    public static class DaemonStatus {
        public boolean isRunning;
        public boolean isBlocked;
        public boolean isMouseConnected;
        public boolean isAutoMode;
        public long ghostCount;

        public DaemonStatus(boolean running) {
            this.isRunning = running;
        }
    }

    public static DaemonStatus getStatus() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/api/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1200);
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

                JSONObject json = new JSONObject(sb.toString());
                DaemonStatus status = new DaemonStatus(true);
                status.isBlocked = json.optBoolean("blocked", false);
                status.isMouseConnected = json.optBoolean("mouse", false);
                status.isAutoMode = json.optBoolean("auto", true);
                status.ghostCount = json.optLong("ghosts", 0);
                return status;
            }
        } catch (Exception e) {
            // Daemon is likely stopped or not responding
            Log.d(TAG, "Daemon status check: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new DaemonStatus(false);
    }

    public static boolean sendAction(String endpoint) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send action " + endpoint + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
