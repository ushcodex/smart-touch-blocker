package com.touchblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;

public class TouchBlockerService extends Service {

    private static final String CHANNEL_ID = "touch_blocker_channel";
    private static final int NOTIF_ID = 1;
    public static final String ACTION_STOP = "com.touchblocker.STOP";

    private static volatile boolean sRunning = false;

    private WindowManager mWindowManager;
    private View mOverlayRoot;   // The fullscreen ghost-touch absorber
    private View mFloatingBtn;   // Small floating toggle button

    public static boolean isRunning() {
        return sRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        addOverlay();
        addFloatingButton();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        removeOverlay();
        removeFloatingButton();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─── Overlay (ghost-touch absorber) ───────────────────────────────────────

    private void addOverlay() {
        TouchFilterView filterView = new TouchFilterView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_FOCUSABLE: don't steal keyboard focus
                // FLAG_LAYOUT_IN_SCREEN: extend to screen edges
                // NOTE: we do NOT set FLAG_NOT_TOUCHABLE — we want to receive touch so we can filter it
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSPARENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        mWindowManager.addView(filterView, params);
        // Give the view a reference so it can mutate its own window params (mouse passthrough trick)
        filterView.setWindowManagerRef(mWindowManager, params);
        mOverlayRoot = filterView;
    }

    private void removeOverlay() {
        if (mOverlayRoot != null) {
            try { mWindowManager.removeView(mOverlayRoot); } catch (Exception ignored) {}
            mOverlayRoot = null;
        }
    }

    // ─── Floating Toggle Button ───────────────────────────────────────────────

    private void addFloatingButton() {
        mFloatingBtn = LayoutInflater.from(this).inflate(R.layout.floating_button, null);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL: only catches events on itself
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 16;
        p.y = 200;

        ImageButton btn = mFloatingBtn.findViewById(R.id.fab_stop);
        btn.setOnClickListener(v -> stopSelf());

        mWindowManager.addView(mFloatingBtn, p);
    }

    private void removeFloatingButton() {
        if (mFloatingBtn != null) {
            try { mWindowManager.removeView(mFloatingBtn); } catch (Exception ignored) {}
            mFloatingBtn = null;
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Touch Blocker", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Touch Blocker is active");
        ch.setLightColor(Color.BLUE);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, TouchBlockerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Touch Blocker Active")
                .setContentText("Touchscreen: BLOCKED · Mouse: ALLOWED")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_delete, "Turn Off", stopPi)
                .setOngoing(true)
                .build();
    }
}
