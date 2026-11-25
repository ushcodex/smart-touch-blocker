package com.touchblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Overlay-Free Reboot Fallback Service.
 *
 * Uses Android's system_server AccessibilityInputFilter (FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
 * with ZERO overlay windows. In Android 10, AccessibilityInputFilter intercepts SOURCE_TOUCHSCREEN
 * (neutralizing raw ghost taps/drags) while passing SOURCE_MOUSE straight through unmodified.
 *
 * Automatically yields to the native kernel daemon whenever the daemon is running,
 * and automatically syncs with Bluetooth mouse connect/disconnect when the daemon is offline.
 */
public class TouchExplorerFallbackService extends AccessibilityService
        implements InputManager.InputDeviceListener {

    private static final String TAG = "TouchExplorerFallback";

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_BLOCK = 1;
    public static final int MODE_FORCE_UNBLOCK = 2;

    private static volatile TouchExplorerFallbackService sInstance = null;

    private InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private volatile int mMode = MODE_AUTO;
    private volatile boolean mDaemonRunning = false;
    private volatile boolean mMouseConnected = false;
    private volatile boolean mTouchExplorationActive = false;
    private volatile long mNeutralizedCount = 0;

    private final Runnable mDaemonChecker = new Runnable() {
        @Override
        public void run() {
            checkDaemonAndSync();
            mHandler.postDelayed(this, 3000);
        }
    };

    public static TouchExplorerFallbackService getInstance() {
        return sInstance;
    }

    public static boolean isServiceRunning() {
        return sInstance != null;
    }

    public static boolean detectMouse(Context context) {
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return false;
            int[] ids = im.getInputDeviceIds();
            for (int id : ids) {
                InputDevice dev = im.getInputDevice(id);
                if (dev == null || dev.isVirtual()) continue;
                int sources = dev.getSources();
                boolean isMouseSource = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
                String name = dev.getName() != null ? dev.getName().toLowerCase() : "";
                boolean isMouseName = name.contains("mouse") || name.contains("pointer");
                if (isMouseSource || isMouseName) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error detecting mouse: " + e.getMessage());
        }
        return false;
    }

    public int getMode() {
        return mMode;
    }

    public boolean isMouseConnected() {
        return mMouseConnected;
    }

    public boolean isTouchExplorationActive() {
        return mTouchExplorationActive;
    }

    public long getNeutralizedCount() {
        return mNeutralizedCount;
    }

    public void setMode(int mode) {
        mMode = mode;
        mMouseConnected = detectMouse(this);
        applyState();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "Overlay-free TouchExplorerFallbackService connected");

        mInputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(this, mHandler);
        }

        mMouseConnected = detectMouse(this);
        checkDaemonAndSync();
        mHandler.postDelayed(mDaemonChecker, 3000);
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mDaemonChecker);
        if (mInputManager != null) {
            try {
                mInputManager.unregisterInputDeviceListener(this);
            } catch (Exception ignored) {}
        }
        setTouchExploration(false);
        mExecutor.shutdownNow();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        mMouseConnected = detectMouse(this);
        applyState();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mMouseConnected = detectMouse(this);
        applyState();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        mMouseConnected = detectMouse(this);
        applyState();
    }

    private void checkDaemonAndSync() {
        mExecutor.execute(() -> {
            DaemonClient.DaemonStatus status = DaemonClient.getStatus();
            mDaemonRunning = status.isRunning;
            mMouseConnected = detectMouse(this);
            mHandler.post(this::applyState);
        });
    }

    private void applyState() {
        boolean shouldEnable;
        if (mDaemonRunning) {
            // Kernel daemon is handling everything with 100% precision; stay completely passive
            shouldEnable = false;
        } else if (mMode == MODE_FORCE_BLOCK) {
            shouldEnable = true;
        } else if (mMode == MODE_FORCE_UNBLOCK) {
            shouldEnable = false;
        } else {
            // AUTO mode: neutralize touchscreen only while Bluetooth mouse is connected
            shouldEnable = mMouseConnected;
        }

        if (shouldEnable != mTouchExplorationActive) {
            setTouchExploration(shouldEnable);
        }
    }

    private void setTouchExploration(boolean enable) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
                info.eventTypes = AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                        | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
                        | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.notificationTimeout = 50;
            }
            if (enable) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            }
            setServiceInfo(info);
            mTouchExplorationActive = enable;
            Log.i(TAG, "Touch exploration fallback -> " + (enable ? "ENABLED" : "DISABLED"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to update touch exploration flag: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mTouchExplorationActive || event == null) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || type == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) {
            mNeutralizedCount++;
        }

        // Immediately clear accessibility focus so ghost touches cannot double-tap a focused button
        if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    source.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
                } catch (Exception ignored) {
                } finally {
                    source.recycle();
                }
            }
        }
    }

    @Override
    protected boolean onGesture(int gestureId) {
        // Consume all touch-exploration gestures so ghost swipes never trigger Back/Home/Recents
        return mTouchExplorationActive;
    }

    @Override
    public void onInterrupt() {
        // No audio/visual feedback to interrupt
    }
}
