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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 4-Window Predictive Mouse Spotlight Blocker (v3.4 - 100% Standalone, Zero PC/ADB).
 *
 * Places 4 transparent touch-absorbing strips (Top, Bottom, Left, Right) around a
 * dynamic windowless "spotlight hole" that tracks the Bluetooth/USB mouse cursor:
 *
 * - Resting hole: 128x128 px (blocks 99.3% of the screen against ghost touches).
 * - Moving hole: Smoothly expands up to 240x240 px with bounded velocity lead and
 *   frame-paced diffed WindowManager updates (no IPC backlog).
 * - Always recenters on the actual cursor stop position when shrinking.
 * - Caught taps & long-presses: Immediately opens the hole on ACTION_DOWN and replays
 *   cleanly through the open hole once committed (zero pass-through race).
 * - Caught swipes & edge gestures: Waits for InputDispatcher pass-through commit before
 *   replaying a downsampled, screen-clamped Path with preserved duration, then atomically
 *   restores strips centered on the release point.
 * - Auto-Mouse Sync: Detaches all strips when the mouse disconnects and re-attaches on connect.
 */
public class TouchExplorerFallbackService extends AccessibilityService
        implements InputManager.InputDeviceListener {

    private static final String TAG = "MouseSpotlight";

    public static final int MODE_AUTO = 0;
    public static final int MODE_FORCE_BLOCK = 1;
    public static final int MODE_FORCE_UNBLOCK = 2;

    private static final int BASE_HOLE_RADIUS_PX = 64;   // 128x128 px resting (~0.65% of screen)
    private static final int FAST_HOLE_RADIUS_PX = 120;  // 240x240 px during movement/clicks
    private static final long SHRINK_DELAY_MS = 220;
    private static final long HOVER_THROTTLE_MS = 16;    // Max 1 layout update per 60Hz frame
    private static final long PASS_THROUGH_COMMIT_MS = 65; // Guaranteed InputDispatcher sync
    private static final long TAP_DURATION_MS = 50;
    private static final long LONG_PRESS_THRESHOLD_MS = 450;
    private static final float PATH_MIN_SEGMENT_PX = 8f;

    private static volatile TouchExplorerFallbackService sInstance = null;

    private WindowManager mWindowManager;
    private InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mTouchSlop = 24;

    private final View[] mStrips = new View[4]; // 0=Top, 1=Bottom, 2=Left, 3=Right
    private final WindowManager.LayoutParams[] mParams = new WindowManager.LayoutParams[4];
    private final int[] mAppliedX = new int[4];
    private final int[] mAppliedY = new int[4];
    private final int[] mAppliedW = new int[4];
    private final int[] mAppliedH = new int[4];
    private final int[] mAppliedFlags = new int[4];

    private boolean mStripsAttached = false;
    private boolean mStripsTemporarilyPassThrough = false;

    private int mScreenW = 1080;
    private int mScreenH = 2310;
    private int mHoleX = 540;
    private int mHoleY = 1155;
    private int mCurrentRadius = BASE_HOLE_RADIUS_PX;

    // Velocity & frame-paced hover tracking
    private float mLastHoverX = -1;
    private float mLastHoverY = -1;
    private long mLastHoverTime = 0;
    private long mLastLayoutUpdateTime = 0;
    private boolean mHoverUpdateScheduled = false;
    private int mPendingHoleX = 540;
    private int mPendingHoleY = 1155;
    private int mPendingHoleRadius = BASE_HOLE_RADIUS_PX;

    // Edge-caught mouse gesture recording
    private boolean mRecordingMouseGesture = false;
    private int mCaughtDeviceId = -1;
    private Path mCaughtGesturePath = null;
    private long mCaughtGestureStartTime = 0;
    private long mHoleOpenedAtCaughtPosTime = 0;
    private float mCaughtStartX = 0;
    private float mCaughtStartY = 0;
    private float mCaughtLastPathX = 0;
    private float mCaughtLastPathY = 0;
    private float mCaughtMaxDist = 0;

    // Cancellable replay runnables (prevents stale timers clobbering back-to-back gestures)
    private Runnable mPendingDispatchRunnable = null;
    private Runnable mRestoreTouchRunnable = null;
    private int mSwipeEndX = 540;
    private int mSwipeEndY = 1155;

    private volatile int mMode = MODE_AUTO;
    private volatile boolean mMouseConnected = false;
    private volatile long mBlockedGhostCount = 0;

    private final Runnable mPendingHoverRunnable = () -> {
        mHoverUpdateScheduled = false;
        if (mStripsAttached && !mStripsTemporarilyPassThrough && !mRecordingMouseGesture) {
            applyHoleLayout(mPendingHoleX, mPendingHoleY, mPendingHoleRadius, false);
        }
    };

    private final Runnable mShrinkHoleRunnable = () -> {
        if (mStripsAttached && !mStripsTemporarilyPassThrough && !mRecordingMouseGesture) {
            int cx = (mLastHoverX >= 0) ? (int) mLastHoverX : mHoleX;
            int cy = (mLastHoverY >= 0) ? (int) mLastHoverY : mHoleY;
            applyHoleLayout(cx, cy, BASE_HOLE_RADIUS_PX, false);
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
        mTouchSlop = Math.max(16, ViewConfiguration.get(this).getScaledTouchSlop());

        updateScreenSize();
        mHoleX = mScreenW / 2;
        mHoleY = mScreenH / 2;

        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(this, mHandler);
        }

        mMouseConnected = detectMouse(this);
        syncOverlayState();
        Log.i(TAG, "Predictive 4-Window Spotlight v3.4 connected. Mouse=" + mMouseConnected
                + ", Active=" + mStripsAttached + ", TouchSlop=" + mTouchSlop);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenSize();
        if (mStripsAttached) {
            mLastHoverX = mScreenW / 2f;
            mLastHoverY = mScreenH / 2f;
            applyHoleLayout(mScreenW / 2, mScreenH / 2, BASE_HOLE_RADIUS_PX, true);
        }
    }

    @Override
    public void onDestroy() {
        cancelAllPendingRunnables();
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

    private void cancelAllPendingRunnables() {
        mHandler.removeCallbacks(mShrinkHoleRunnable);
        mHandler.removeCallbacks(mPendingHoverRunnable);
        mHoverUpdateScheduled = false;
        if (mPendingDispatchRunnable != null) {
            mHandler.removeCallbacks(mPendingDispatchRunnable);
            mPendingDispatchRunnable = null;
        }
        if (mRestoreTouchRunnable != null) {
            mHandler.removeCallbacks(mRestoreTouchRunnable);
            mRestoreTouchRunnable = null;
        }
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
                recordApplied(i);
            }
            mStripsAttached = true;
            mLastLayoutUpdateTime = SystemClock.uptimeMillis();
            Log.i(TAG, "4-Window Mouse Spotlight ATTACHED (" + mScreenW + "x" + mScreenH + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to attach spotlight strips: " + e.getMessage());
            detachStrips();
        }
    }

    private void detachStrips() {
        cancelAllPendingRunnables();
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
        mRecordingMouseGesture = false;
        mCaughtGesturePath = null;
    }

    private View createStripView() {
        View v = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (mStripsTemporarilyPassThrough) {
                    return false;
                }

                if (isMouseEvent(event)
                        || (mRecordingMouseGesture && event.getDeviceId() == mCaughtDeviceId)) {
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
                if ((action == MotionEvent.ACTION_HOVER_MOVE
                        || action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_SCROLL)
                        && isMouseEvent(event)) {
                    boolean urgent = (action == MotionEvent.ACTION_HOVER_ENTER
                            || action == MotionEvent.ACTION_SCROLL);
                    handleMouseHover(event.getRawX(), event.getRawY(), urgent);
                }
                return false;
            }
        };
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    private void handleMouseHover(float rawX, float rawY, boolean urgent) {
        if (mStripsTemporarilyPassThrough || mRecordingMouseGesture) return;

        long now = SystemClock.uptimeMillis();
        float clampedX = clampX(rawX);
        float clampedY = clampY(rawY);
        int targetX = (int) clampedX;
        int targetY = (int) clampedY;
        int targetRadius = BASE_HOLE_RADIUS_PX;

        if (mLastHoverTime > 0) {
            long dt = now - mLastHoverTime;
            if (dt > 0 && dt < 120) {
                float dx = clampedX - mLastHoverX;
                float dy = clampedY - mLastHoverY;
                float dist = (float) Math.hypot(dx, dy);
                float speedPxPerMs = dist / dt;

                if (speedPxPerMs > 0.5f) {
                    float factor = Math.min(1.0f, (speedPxPerMs - 0.5f) / 2.2f);
                    targetRadius = (int) (BASE_HOLE_RADIUS_PX
                            + factor * (FAST_HOLE_RADIUS_PX - BASE_HOLE_RADIUS_PX));
                    // Keep actual cursor well inside the inner 2/3 of the hole
                    float maxLead = targetRadius / 3f;
                    int leadX = (int) Math.max(-maxLead, Math.min(maxLead, (dx / dt) * 14f));
                    int leadY = (int) Math.max(-maxLead, Math.min(maxLead, (dy / dt) * 14f));
                    targetX += leadX;
                    targetY += leadY;
                }
            }
        }

        // Always remember the exact un-leaded cursor position for shrinking/recentering
        mLastHoverX = clampedX;
        mLastHoverY = clampedY;
        mLastHoverTime = now;

        long sinceLastUpdate = now - mLastLayoutUpdateTime;
        if (urgent || sinceLastUpdate >= HOVER_THROTTLE_MS) {
            mHandler.removeCallbacks(mPendingHoverRunnable);
            mHoverUpdateScheduled = false;
            applyHoleLayout(targetX, targetY, targetRadius, false);
        } else {
            mPendingHoleX = targetX;
            mPendingHoleY = targetY;
            mPendingHoleRadius = targetRadius;
            if (!mHoverUpdateScheduled) {
                mHoverUpdateScheduled = true;
                mHandler.postDelayed(mPendingHoverRunnable, HOVER_THROTTLE_MS - sinceLastUpdate);
            }
        }

        // Always recenter and shrink back to the actual cursor position once movement stops
        mHandler.removeCallbacks(mShrinkHoleRunnable);
        mHandler.postDelayed(mShrinkHoleRunnable, SHRINK_DELAY_MS);
    }

    /**
     * Handles mouse clicks, long-presses, and swipes that land on a strip edge
     * (e.g. immediately after a previous swipe or an ultra-fast flick).
     */
    private void handleCaughtMouseTouch(MotionEvent event) {
        int action = event.getActionMasked();
        float x = clampX(event.getRawX());
        float y = clampY(event.getRawY());
        long now = SystemClock.uptimeMillis();

        if (action == MotionEvent.ACTION_DOWN) {
            mHandler.removeCallbacks(mShrinkHoleRunnable);
            mHandler.removeCallbacks(mPendingHoverRunnable);
            mHoverUpdateScheduled = false;

            mRecordingMouseGesture = true;
            mCaughtDeviceId = event.getDeviceId();
            mCaughtStartX = x;
            mCaughtStartY = y;
            mCaughtLastPathX = x;
            mCaughtLastPathY = y;
            mCaughtMaxDist = 0f;
            mCaughtGestureStartTime = now;
            mCaughtGesturePath = new Path();
            mCaughtGesturePath.moveTo(x, y);

            mLastHoverX = x;
            mLastHoverY = y;
            mLastHoverTime = now;

            // Open the hole immediately under the click so taps can replay straight through it
            applyHoleLayout((int) x, (int) y, FAST_HOLE_RADIUS_PX, true);
            mHoleOpenedAtCaughtPosTime = now;

        } else if (action == MotionEvent.ACTION_MOVE
                && mRecordingMouseGesture && mCaughtGesturePath != null) {
            float distFromStart = (float) Math.hypot(x - mCaughtStartX, y - mCaughtStartY);
            if (distFromStart > mCaughtMaxDist) {
                mCaughtMaxDist = distFromStart;
            }
            float segDist = (float) Math.hypot(x - mCaughtLastPathX, y - mCaughtLastPathY);
            if (segDist >= PATH_MIN_SEGMENT_PX) {
                mCaughtGesturePath.lineTo(x, y);
                mCaughtLastPathX = x;
                mCaughtLastPathY = y;
            }

        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && mRecordingMouseGesture && mCaughtGesturePath != null) {
            mRecordingMouseGesture = false;
            mCaughtDeviceId = -1;
            mLastHoverX = x;
            mLastHoverY = y;
            mLastHoverTime = now;

            Path pathToReplay = mCaughtGesturePath;
            mCaughtGesturePath = null;

            if (action == MotionEvent.ACTION_CANCEL) {
                mHandler.removeCallbacks(mShrinkHoleRunnable);
                mHandler.postDelayed(mShrinkHoleRunnable, SHRINK_DELAY_MS);
                return;
            }

            float finalDistFromStart = (float) Math.hypot(x - mCaughtStartX, y - mCaughtStartY);
            if (finalDistFromStart > mCaughtMaxDist) {
                mCaughtMaxDist = finalDistFromStart;
            }
            long elapsed = now - mCaughtGestureStartTime;

            if (mCaughtMaxDist < mTouchSlop) {
                // TAP or LONG PRESS: replay straight through the already-open 240x240 hole!
                Path tapPath = new Path();
                tapPath.moveTo(x, y);
                long tapDuration = (elapsed >= LONG_PRESS_THRESHOLD_MS)
                        ? Math.max(600, Math.min(1500, elapsed))
                        : TAP_DURATION_MS;
                long sinceHoleMoved = now - mHoleOpenedAtCaughtPosTime;
                long delayMs = Math.max(0, PASS_THROUGH_COMMIT_MS - sinceHoleMoved);
                replayTapThroughHole(tapPath, tapDuration, delayMs);
            } else {
                // SWIPE / DRAG / FLING: ensure final endpoint is in path, then replay with pass-through
                if (Math.hypot(x - mCaughtLastPathX, y - mCaughtLastPathY) >= 1f) {
                    pathToReplay.lineTo(x, y);
                }
                long swipeDuration = Math.max(80, Math.min(1200, elapsed));
                replayCaughtSwipe(pathToReplay, swipeDuration, (int) x, (int) y);
            }
        }
    }

    private void replayTapThroughHole(Path tapPath, long durationMs, long delayMs) {
        if (!mStripsAttached || mWindowManager == null) return;

        if (mPendingDispatchRunnable != null) {
            mHandler.removeCallbacks(mPendingDispatchRunnable);
            mPendingDispatchRunnable = null;
        }
        mHandler.removeCallbacks(mShrinkHoleRunnable);

        Runnable dispatchTask = () -> {
            mPendingDispatchRunnable = null;
            try {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(tapPath, 0, durationMs));
                dispatchGesture(builder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        scheduleHoleShrink();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        scheduleHoleShrink();
                    }
                }, mHandler);
            } catch (Exception e) {
                Log.w(TAG, "Tap replay failed: " + e.getMessage());
                scheduleHoleShrink();
            }
        };

        if (delayMs <= 0) {
            dispatchTask.run();
        } else {
            mPendingDispatchRunnable = dispatchTask;
            mHandler.postDelayed(dispatchTask, delayMs);
        }
    }

    private void replayCaughtSwipe(Path path, long durationMs, int endX, int endY) {
        if (!mStripsAttached || mWindowManager == null) return;

        // Cancel any previous pending replay or restore timers
        if (mPendingDispatchRunnable != null) {
            mHandler.removeCallbacks(mPendingDispatchRunnable);
            mPendingDispatchRunnable = null;
        }
        if (mRestoreTouchRunnable != null) {
            mHandler.removeCallbacks(mRestoreTouchRunnable);
            mRestoreTouchRunnable = null;
        }
        mHandler.removeCallbacks(mShrinkHoleRunnable);
        mHandler.removeCallbacks(mPendingHoverRunnable);
        mHoverUpdateScheduled = false;

        mSwipeEndX = endX;
        mSwipeEndY = endY;

        // Make strips pass-through and wait for InputDispatcher to commit before injecting swipe
        setStripsPassThrough(true);

        mPendingDispatchRunnable = () -> {
            mPendingDispatchRunnable = null;
            try {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
                boolean dispatched = dispatchGesture(builder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        finishSwipeReplay();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        finishSwipeReplay();
                    }
                }, mHandler);

                if (!dispatched) {
                    finishSwipeReplay();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Swipe replay failed: " + e.getMessage());
                finishSwipeReplay();
                return;
            }

            // Cancellable safety restore in case GestureResultCallback is delayed
            mRestoreTouchRunnable = this::finishSwipeReplay;
            mHandler.postDelayed(mRestoreTouchRunnable, durationMs + 200);
        };

        mHandler.postDelayed(mPendingDispatchRunnable, PASS_THROUGH_COMMIT_MS);
    }

    private void finishSwipeReplay() {
        if (mRestoreTouchRunnable != null) {
            mHandler.removeCallbacks(mRestoreTouchRunnable);
            mRestoreTouchRunnable = null;
        }
        if (!mStripsAttached || mWindowManager == null) {
            mStripsTemporarilyPassThrough = false;
            return;
        }

        // Atomically restore touchability AND recenter the hole at the swipe release point
        mStripsTemporarilyPassThrough = false;
        int cx = Math.max(0, Math.min(mScreenW, mSwipeEndX));
        int cy = Math.max(0, Math.min(mScreenH, mSwipeEndY));
        mHoleX = cx;
        mHoleY = cy;
        mCurrentRadius = BASE_HOLE_RADIUS_PX;
        computeStripRects(cx, cy, BASE_HOLE_RADIUS_PX);

        for (int i = 0; i < 4; i++) {
            if (mStrips[i] != null && mParams[i] != null) {
                mParams[i].flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                pushStripLayoutIfChanged(i);
            }
        }
        mLastLayoutUpdateTime = SystemClock.uptimeMillis();
    }

    private void scheduleHoleShrink() {
        mHandler.removeCallbacks(mShrinkHoleRunnable);
        mHandler.postDelayed(mShrinkHoleRunnable, SHRINK_DELAY_MS);
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
                pushStripLayoutIfChanged(i);
            }
        }
        mLastLayoutUpdateTime = SystemClock.uptimeMillis();
    }

    private boolean isMouseEvent(MotionEvent event) {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        int src = event.getSource();
        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
        if ((src & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) return true;
        if ((src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) return true;
        return event.getButtonState() != 0;
    }

    private float clampX(float x) {
        return Math.max(0f, Math.min(mScreenW - 1f, x));
    }

    private float clampY(float y) {
        return Math.max(0f, Math.min(mScreenH - 1f, y));
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

    private void applyHoleLayout(int cx, int cy, int radius, boolean force) {
        if (!mStripsAttached || mWindowManager == null || mStripsTemporarilyPassThrough) return;

        cx = Math.max(0, Math.min(mScreenW, cx));
        cy = Math.max(0, Math.min(mScreenH, cy));

        if (!force && radius == mCurrentRadius
                && Math.abs(cx - mHoleX) < 4 && Math.abs(cy - mHoleY) < 4) {
            return;
        }

        mHoleX = cx;
        mHoleY = cy;
        mCurrentRadius = radius;
        computeStripRects(cx, cy, radius);

        boolean anyUpdated = false;
        for (int i = 0; i < 4; i++) {
            if (pushStripLayoutIfChanged(i)) {
                anyUpdated = true;
            }
        }
        if (anyUpdated) {
            mLastLayoutUpdateTime = SystemClock.uptimeMillis();
        }
    }

    private boolean pushStripLayoutIfChanged(int i) {
        if (mStrips[i] == null || mParams[i] == null || mWindowManager == null) return false;
        WindowManager.LayoutParams p = mParams[i];
        if (p.x == mAppliedX[i] && p.y == mAppliedY[i]
                && p.width == mAppliedW[i] && p.height == mAppliedH[i]
                && p.flags == mAppliedFlags[i]) {
            return false;
        }
        try {
            mWindowManager.updateViewLayout(mStrips[i], p);
            recordApplied(i);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordApplied(int i) {
        WindowManager.LayoutParams p = mParams[i];
        mAppliedX[i] = p.x;
        mAppliedY[i] = p.y;
        mAppliedW[i] = p.width;
        mAppliedH[i] = p.height;
        mAppliedFlags[i] = p.flags;
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
