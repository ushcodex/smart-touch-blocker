package com.touchblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 4-Window Predictive Mouse Spotlight Blocker (v3.3 - 100% Standalone, Zero PC/ADB).
 *
 * Places 4 transparent touch-absorbing strips (Top, Bottom, Left, Right) around a
 * dynamic windowless "spotlight hole" that tracks and leads the Bluetooth mouse cursor:
 *
 * - Resting hole: 72x72 px (blocks 99.8% of the screen against ghost touches).
 * - Fast flicks: Predictive velocity lead + temporary expansion up to 192x192 px
 *   so rapid flicks and swipes always land inside the native windowless hole.
 * - Edge-catch safety net: If a fast mouse click or swipe still catches a strip edge
 *   mid-frame, its exact Path is recorded and immediately replayed via dispatchGesture
 *   so fast gestures never drop.
 * - Auto-Mouse Sync: Detaches all strips when the Bluetooth mouse disconnects
 *   (restoring normal finger touch) and re-attaches them when it reconnects.
 */
public class TouchExplorerFallbackService extends AccessibilityService
        implements InputManager.InputDeviceListener {

    private static final String TAG = "MouseSpotlight";

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_BLOCK = 1;
    public static final int MODE_FORCE_UNBLOCK = 2;

    private static final int BASE_HOLE_RADIUS_PX = 36;  // 72x72 px resting
    private static final int FAST_HOLE_RADIUS_PX = 96;  // 192x192 px during fast movement
    private static final long SHRINK_DELAY_MS = 180;

    private static volatile TouchExplorerFallbackService sInstance = null;

    private WindowManager mWindowManager;
    private InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final View[] mStrips = new View[4]; // 0=Top, 1=Bottom, 2=Left, 3=Right
    private final WindowManager.LayoutParams[] mParams = new WindowManager.LayoutParams[4];
    private boolean mStripsAttached = false;
    private boolean mStripsTemporarilyPassThrough = false;

    private int mScreenW = 1080;
    private int mScreenH = 2310;
    private int mHoleX = 540;
    private int mHoleY = 1155;
    private int mCurrentRadius = BASE_HOLE_RADIUS_PX;

    // Velocity tracking for predictive hole lead
    private float mLastHoverX = -1;
    private float mLastHoverY = -1;
    private long mLastHoverTime = 0;

    // Edge-caught mouse gesture recording (for ultra-fast flick-swipes)
    private boolean mRecordingMouseGesture = false;
    private Path mCaughtGesturePath = null;
    private long mCaughtGestureStartTime = 0;
    private float mCaughtStartX = 0;
    private float mCaughtStartY = 0;

    private volatile int mMode = MODE_AUTO;
    private volatile boolean mMouseConnected = false;
    private volatile long mBlockedGhostCount = 0;

    private final Runnable mShrinkHoleRunnable = () -> {
        if (mStripsAttached && mCurrentRadius != BASE_HOLE_RADIUS_PX) {
            mCurrentRadius = BASE_HOLE_RADIUS_PX;
            applyHoleLayout(mHoleX, mHoleY, mCurrentRadius);
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
        Log.i(TAG, "Predictive 4-Window Spotlight connected. Mouse=" + mMouseConnected + ", Active=" + mStripsAttached);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenSize();
        if (mStripsAttached) {
            applyHoleLayout(mScreenW / 2, mScreenH / 2, BASE_HOLE_RADIUS_PX);
        }
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mShrinkHoleRunnable);
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
        mCurrentRadius = BASE_HOLE_RADIUS_PX;

        for (int i = 0; i < 4; i++) {
            mStrips[i] = createStripView();
            mParams[i] = createStripParams();
        }

        computeStripRects(mHoleX, mHoleY, mCurrentRadius);

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
        mHandler.removeCallbacks(mShrinkHoleRunnable);
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
        mStripsTemporarilyPassThrough = false;
    }

    private View createStripView() {
        View v = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (mStripsTemporarilyPassThrough) {
                    return false;
                }

                if (isMouseEvent(event)) {
                    handleCaughtMouseTouch(event);
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
                    handleMouseHover(event.getRawX(), event.getRawY());
                }
                return false;
            }
        };
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private void handleMouseHover(float rawX, float rawY) {
        long now = SystemClock.uptimeMillis();
        int targetX = (int) rawX;
        int targetY = (int) rawY;
        int targetRadius = BASE_HOLE_RADIUS_PX;

        if (mLastHoverTime > 0) {
            long dt = now - mLastHoverTime;
            if (dt > 0 && dt < 120) {
                float dx = rawX - mLastHoverX;
                float dy = rawY - mLastHoverY;
                float dist = (float) Math.hypot(dx, dy);
                float speedPxPerMs = dist / dt;

                if (speedPxPerMs > 0.6f) {
                    // Fast mouse movement: expand hole & predict slightly ahead
                    float factor = Math.min(1.0f, (speedPxPerMs - 0.6f) / 2.4f);
                    targetRadius = (int) (BASE_HOLE_RADIUS_PX + factor * (FAST_HOLE_RADIUS_PX - BASE_HOLE_RADIUS_PX));
                    // Lead ~18ms ahead along motion vector, clamped to stay inside hole
                    int leadX = (int) Math.max(-targetRadius / 2f, Math.min(targetRadius / 2f, (dx / dt) * 18f));
                    int leadY = (int) Math.max(-targetRadius / 2f, Math.min(targetRadius / 2f, (dy / dt) * 18f));
                    targetX += leadX;
                    targetY += leadY;
                }
            }
        }

        mLastHoverX = rawX;
        mLastHoverY = rawY;
        mLastHoverTime = now;

        mCurrentRadius = targetRadius;
        applyHoleLayout(targetX, targetY, targetRadius);

        if (targetRadius > BASE_HOLE_RADIUS_PX) {
            mHandler.removeCallbacks(mShrinkHoleRunnable);
            mHandler.postDelayed(mShrinkHoleRunnable, SHRINK_DELAY_MS);
        }
    }

    /**
     * Safety net: if a super-fast mouse flick+press/swipe lands on a strip edge before
     * the hole finishes moving, record the exact gesture Path and replay it natively.
     */
    private void handleCaughtMouseTouch(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getRawX();
        float y = event.getRawY();

        if (action == MotionEvent.ACTION_DOWN) {
            mRecordingMouseGesture = true;
            mCaughtStartX = x;
            mCaughtStartY = y;
            mCaughtGestureStartTime = SystemClock.uptimeMillis();
            mCaughtGesturePath = new Path();
            mCaughtGesturePath.moveTo(x, y);
            // Also move the spotlight hole here immediately for subsequent actions
            applyHoleLayout((int) x, (int) y, FAST_HOLE_RADIUS_PX);
            mHandler.removeCallbacks(mShrinkHoleRunnable);
            mHandler.postDelayed(mShrinkHoleRunnable, SHRINK_DELAY_MS);
        } else if (action == MotionEvent.ACTION_MOVE && mRecordingMouseGesture && mCaughtGesturePath != null) {
            mCaughtGesturePath.lineTo(x, y);
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && mRecordingMouseGesture && mCaughtGesturePath != null) {
            mRecordingMouseGesture = false;
            mCaughtGesturePath.lineTo(x, y);
            long elapsed = SystemClock.uptimeMillis() - mCaughtGestureStartTime;
            long duration = Math.max(20, Math.min(400, elapsed));
            Path pathToReplay = mCaughtGesturePath;
            mCaughtGesturePath = null;

            // Recenter hole at release point
            applyHoleLayout((int) x, (int) y, BASE_HOLE_RADIUS_PX);

            if (action == MotionEvent.ACTION_UP) {
                replayCaughtGesture(pathToReplay, duration);
            }
        }
    }

    private void replayCaughtGesture(Path path, long durationMs) {
        if (!mStripsAttached || mWindowManager == null) return;

        // Briefly make strips pass-through so the replayed gesture reaches the underlying app
        setStripsPassThrough(true);

        mHandler.postDelayed(() -> {
            try {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
                boolean dispatched = dispatchGesture(builder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        setStripsPassThrough(false);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        setStripsPassThrough(false);
                    }
                }, mHandler);

                if (!dispatched) {
                    setStripsPassThrough(false);
                }
            } catch (Exception e) {
                setStripsPassThrough(false);
            }
            // Hard safety restore in case callback is delayed
            mHandler.postDelayed(() -> setStripsPassThrough(false), durationMs + 60);
        }, 16);
    }

    private void setStripsPassThrough(boolean passThrough) {
        if (mStripsTemporarilyPassThrough == passThrough) return;
        mStripsTemporarilyPassThrough = passThrough;
        if (!mStripsAttached || mWindowManager == null) return;

        for (int i = 0; i < 4; i++) {
            if (mStrips[i] != null && mParams[i] != null) {
                if (passThrough) {
                    mParams[i].flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    mParams[i].flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                try {
                    mWindowManager.updateViewLayout(mStrips[i], mParams[i]);
                } catch (Exception ignored) {}
            }
        }
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

    private void applyHoleLayout(int cx, int cy, int radius) {
        if (!mStripsAttached || mWindowManager == null) return;

        cx = Math.max(0, Math.min(mScreenW, cx));
        cy = Math.max(0, Math.min(mScreenH, cy));

        mHoleX = cx;
        mHoleY = cy;
        computeStripRects(cx, cy, radius);

        for (int i = 0; i < 4; i++) {
            if (mStrips[i] != null && mParams[i] != null) {
                try {
                    mWindowManager.updateViewLayout(mStrips[i], mParams[i]);
                } catch (Exception ignored) {}
            }
        }
    }

    private void computeStripRects(int cx, int cy, int radius) {
        int left = Math.max(0, cx - radius);
        int right = Math.min(mScreenW, cx + radius);
        int top = Math.max(0, cy - radius);
        int bottom = Math.min(mScreenH, cy + radius);
        int holeH = Math.max(0, bottom - top);

        setRect(mParams[0], 0, 0, mScreenW, top);
        setRect(mParams[1], 0, bottom, mScreenW, Math.max(0, mScreenH - bottom));
        setRect(mParams[2], 0, top, left, holeH);
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
