package com.touchblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 4-Window Mouse Spotlight Blocker (100% Standalone, Zero PC/ADB Required).
 *
 * Places 4 transparent touch-absorbing strips (Top, Bottom, Left, Right) around a
 * small windowless "spotlight hole" centered on the Bluetooth mouse pointer:
 *
 *   +-----------------------------+
 *   |          TOP STRIP          |
 *   +-------+-----------+---------+
 *   | LEFT  |  HOLE     |  RIGHT  |
 *   +-------+-----------+---------+
 *   |        BOTTOM STRIP         |
 *   +-----------------------------+
 *
 * - 99.87% of the screen is covered by touch-absorbing strips that genuinely consume
 *   and destroy ghost touches from the broken touchscreen.
 * - Directly under the mouse cursor, there is NO window at all, so mouse clicks,
 *   drags, scroll wheel, right-clicks, and keyboard taps go 100% natively to the
 *   underlying app.
 * - Whenever the mouse moves to the edge of the hole, ACTION_HOVER_MOVE hits a strip
 *   and the 4 strips instantly recenter around the new cursor position.
 * - Automatically removes all strips when the Bluetooth mouse disconnects (restoring
 *   normal finger touch) and re-attaches them when the mouse reconnects.
 */
public class TouchExplorerFallbackService extends AccessibilityService
        implements InputManager.InputDeviceListener {

    private static final String TAG = "MouseSpotlight";

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_BLOCK = 1;
    public static final int MODE_FORCE_UNBLOCK = 2;

    // Half-size of the windowless hole around the mouse cursor (56x56 px total)
    private static final int HOLE_RADIUS_PX = 28;

    private static volatile TouchExplorerFallbackService sInstance = null;

    private WindowManager mWindowManager;
    private InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final View[] mStrips = new View[4]; // 0=Top, 1=Bottom, 2=Left, 3=Right
    private final WindowManager.LayoutParams[] mParams = new WindowManager.LayoutParams[4];
    private boolean mStripsAttached = false;

    private int mScreenW = 1080;
    private int mScreenH = 2310;
    private int mHoleX = 540;
    private int mHoleY = 1155;

    private volatile int mMode = MODE_AUTO;
    private volatile boolean mMouseConnected = false;
    private volatile long mBlockedGhostCount = 0;

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

    public boolean isBlockingActive() {
        return mStripsAttached;
    }

    public long getBlockedGhostCount() {
        return mBlockedGhostCount;
    }

    public void setMode(int mode) {
        mMode = mode;
        mMouseConnected = detectMouse(this);
        mHandler.post(this::syncOverlayState);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mInputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);

        updateScreenSize();
        mHoleX = mScreenW / 2;
        mHoleY = mScreenH / 2;

        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(this, mHandler);
        }

        mMouseConnected = detectMouse(this);
        syncOverlayState();
        Log.i(TAG, "4-Window Mouse Spotlight service connected. Mouse=" + mMouseConnected + ", Active=" + mStripsAttached);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenSize();
        if (mStripsAttached) {
            recenterHole(mScreenW / 2, mScreenH / 2);
        }
    }

    @Override
    public void onDestroy() {
        if (mInputManager != null) {
            try {
                mInputManager.unregisterInputDeviceListener(this);
            } catch (Exception ignored) {}
        }
        detachStrips();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        mMouseConnected = detectMouse(this);
        syncOverlayState();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mMouseConnected = detectMouse(this);
        syncOverlayState();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        mMouseConnected = detectMouse(this);
        syncOverlayState();
    }

    private void updateScreenSize() {
        if (mWindowManager == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(dm);
        mScreenW = dm.widthPixels;
        mScreenH = dm.heightPixels;
    }

    private void syncOverlayState() {
        boolean shouldBlock;
        if (mMode == MODE_FORCE_BLOCK) {
            shouldBlock = true;
        } else if (mMode == MODE_FORCE_UNBLOCK) {
            shouldBlock = false;
        } else {
            // AUTO mode: block touchscreen ONLY when a Bluetooth/USB mouse is connected
            shouldBlock = mMouseConnected;
        }

        if (shouldBlock && !mStripsAttached) {
            attachStrips();
        } else if (!shouldBlock && mStripsAttached) {
            detachStrips();
        }
    }

    private void attachStrips() {
        if (mWindowManager == null || mStripsAttached) return;
        updateScreenSize();

        for (int i = 0; i < 4; i++) {
            mStrips[i] = createStripView();
            mParams[i] = createStripParams();
        }

        computeStripRects(mHoleX, mHoleY);

        try {
            for (int i = 0; i < 4; i++) {
                mWindowManager.addView(mStrips[i], mParams[i]);
            }
            mStripsAttached = true;
            Log.i(TAG, "4-Window Mouse Spotlight ATTACHED (" + mScreenW + "x" + mScreenH + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to attach spotlight strips: " + e.getMessage());
            detachStrips();
        }
    }

    private void detachStrips() {
        if (mWindowManager != null) {
            for (int i = 0; i < 4; i++) {
                if (mStrips[i] != null) {
                    try {
                        mWindowManager.removeViewImmediate(mStrips[i]);
                    } catch (Exception ignored) {}
                    mStrips[i] = null;
                }
                mParams[i] = null;
            }
        }
        mStripsAttached = false;
        Log.i(TAG, "4-Window Mouse Spotlight DETACHED (touch restored)");
    }

    private View createStripView() {
        View v = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (isMouseEvent(event)) {
                    // User clicked slightly before hover recentered; immediately move hole under cursor
                    recenterHole((int) event.getRawX(), (int) event.getRawY());
                    return true;
                }
                // Genuine touchscreen event (finger or phantom ghost touch) -> CONSUME & DESTROY
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mBlockedGhostCount++;
                }
                return true;
            }

            @Override
            public boolean onGenericMotionEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if ((action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER)
                        && isMouseEvent(event)) {
                    recenterHole((int) event.getRawX(), (int) event.getRawY());
                }
                return false;
            }
        };
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private boolean isMouseEvent(MotionEvent event) {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        int src = event.getSource();
        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
        return event.getButtonState() != 0;
    }

    private WindowManager.LayoutParams createStripParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    private void recenterHole(int cx, int cy) {
        if (!mStripsAttached || mWindowManager == null) return;

        cx = Math.max(0, Math.min(mScreenW, cx));
        cy = Math.max(0, Math.min(mScreenH, cy));

        // Skip redundant layout updates if cursor barely moved
        if (Math.abs(cx - mHoleX) < 6 && Math.abs(cy - mHoleY) < 6) {
            return;
        }

        mHoleX = cx;
        mHoleY = cy;
        computeStripRects(cx, cy);

        for (int i = 0; i < 4; i++) {
            if (mStrips[i] != null && mParams[i] != null) {
                try {
                    mWindowManager.updateViewLayout(mStrips[i], mParams[i]);
                } catch (Exception ignored) {}
            }
        }
    }

    private void computeStripRects(int cx, int cy) {
        int left = Math.max(0, cx - HOLE_RADIUS_PX);
        int right = Math.min(mScreenW, cx + HOLE_RADIUS_PX);
        int top = Math.max(0, cy - HOLE_RADIUS_PX);
        int bottom = Math.min(mScreenH, cy + HOLE_RADIUS_PX);
        int holeH = Math.max(0, bottom - top);

        // 0: Top strip (full width, 0 .. top)
        setRect(mParams[0], 0, 0, mScreenW, top);
        // 1: Bottom strip (full width, bottom .. screenH)
        setRect(mParams[1], 0, bottom, mScreenW, Math.max(0, mScreenH - bottom));
        // 2: Left strip (0 .. left, top .. bottom)
        setRect(mParams[2], 0, top, left, holeH);
        // 3: Right strip (right .. screenW, top .. bottom)
        setRect(mParams[3], right, top, Math.max(0, mScreenW - right), holeH);
    }

    private void setRect(WindowManager.LayoutParams p, int x, int y, int w, int h) {
        p.x = x;
        p.y = y;
        p.width = Math.max(0, w);
        p.height = Math.max(0, h);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed — all filtering happens in the 4 spotlight strips
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt
    }
}
