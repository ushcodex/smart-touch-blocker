package com.touchblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.List;

public class SmartBlockerAccessibilityService extends AccessibilityService {

    private static final String TAG = "TouchBlockerA11y";
    private static volatile SmartBlockerAccessibilityService sInstance = null;

    private WindowManager mWindowManager;
    private View mOverlayView;
    private TextView mFloatingPill;
    private WindowManager.LayoutParams mOverlayParams;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsBlocking = true;
    private long mGhostCount = 0;
    private boolean mIsForwarding = false;

    public static SmartBlockerAccessibilityService getInstance() {
        return sInstance;
    }

    public static boolean isServiceRunning() {
        return sInstance != null;
    }

    public boolean isBlocking() {
        return mIsBlocking;
    }

    public long getGhostCount() {
        return mGhostCount;
    }

    public void setBlocking(boolean block) {
        mIsBlocking = block;
        updateOverlayState();
        updateFloatingPillUI();
    }

    public void toggleBlocking() {
        setBlocking(!mIsBlocking);
    }

    private void updateOverlayState() {
        if (mOverlayView != null && mOverlayParams != null && mWindowManager != null) {
            if (mIsBlocking) {
                // ACTIVE: Overlay absorbs touches (NOT touchable flag is removed)
                mOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                // PAUSED: Overlay becomes 100% touch-transparent
                mOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update overlay flags", e);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed for filtering
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "Smart Blocker Accessibility Service connected!");

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        initOverlay();
        initFloatingPill();
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        removeOverlay();
        removeFloatingPill();
        super.onDestroy();
    }

    // ── Fullscreen Ghost-Touch Filter Overlay ───────────────────────────────

    private void initOverlay() {
        mOverlayView = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                // If paused or currently forwarding a gesture, pass through
                if (!mIsBlocking || mIsForwarding) {
                    return false;
                }

                int toolType = event.getToolType(0);
                int source = event.getSource();
                int buttonState = event.getButtonState();

                boolean isMouse = (toolType == MotionEvent.TOOL_TYPE_MOUSE)
                        || ((source & InputDevice.SOURCE_MOUSE) != 0)
                        || (buttonState != 0);

                if (!isMouse) {
                    // TOUCHSCREEN GHOST TOUCH!
                    // Absorb completely so it never reaches any app
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        mGhostCount++;
                    }
                    return true;
                }

                // MOUSE INPUT
                int action = event.getActionMasked();
                float rawX = event.getRawX();
                float rawY = event.getRawY();

                if (action == MotionEvent.ACTION_DOWN) {
                    forwardClick(rawX, rawY);
                }

                return true; // Consumed
            }

            @Override
            public boolean onGenericMotionEvent(MotionEvent event) {
                // Mouse hover and scroll wheel
                if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                    float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vscroll != 0) {
                        forwardScroll(event.getRawX(), event.getRawY(), vscroll > 0);
                    }
                }
                return false;
            }
        };

        mOverlayView.setBackgroundColor(Color.TRANSPARENT);

        mOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT
        );
        mOverlayParams.gravity = Gravity.TOP | Gravity.START;

        try {
            mWindowManager.addView(mOverlayView, mOverlayParams);
            Log.i(TAG, "Filter overlay successfully added!");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add filter overlay", e);
        }
    }

    private void removeOverlay() {
        if (mOverlayView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mOverlayView);
            } catch (Exception ignored) {}
            mOverlayView = null;
        }
    }

    // ── Mouse Click & Scroll Forwarding ────────────────────────────────────

    private void forwardClick(float x, float y) {
        // Strategy 1: Find clickable node in active interactive window
        boolean clicked = tryNodeClick((int) x, (int) y);
        if (clicked) {
            return;
        }

        // Strategy 2: Gesture dispatch with proper vsync delay so WindowManager
        // applies FLAG_NOT_TOUCHABLE before the gesture is injected
        mIsForwarding = true;
        if (mOverlayView != null && mOverlayParams != null && mWindowManager != null) {
            mOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception ignored) {}
        }

        // Wait 25ms (1.5 frames) for WindowManagerService to apply the flag
        mHandler.postDelayed(() -> {
            Path clickPath = new Path();
            clickPath.moveTo(x, y);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 15));

            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    mHandler.postDelayed(() -> restoreOverlay(), 35);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    restoreOverlay();
                }
            }, mHandler);

            // Safety timeout
            mHandler.postDelayed(this::restoreOverlay, 80);
        }, 25);
    }

    private void restoreOverlay() {
        mIsForwarding = false;
        if (mOverlayView != null && mOverlayParams != null && mWindowManager != null && mIsBlocking) {
            mOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception ignored) {}
        }
    }

    private boolean tryNodeClick(int x, int y) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    boolean res = clickNodeAt(root, x, y);
                    root.recycle();
                    return res;
                }
                return false;
            }

            for (AccessibilityWindowInfo window : windows) {
                // Skip our own overlay window
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    continue;
                }

                Rect windowBounds = new Rect();
                window.getBoundsInScreen(windowBounds);
                if (windowBounds.contains(x, y)) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        boolean clicked = clickNodeAt(root, x, y);
                        root.recycle();
                        if (clicked) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean clickNodeAt(AccessibilityNodeInfo parent, int x, int y) {
        if (parent == null) return false;

        Rect bounds = new Rect();
        parent.getBoundsInScreen(bounds);

        if (!bounds.contains(x, y)) {
            return false;
        }

        // Search children from top to bottom
        int count = parent.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child != null) {
                if (clickNodeAt(child, x, y)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }

        if (parent.isClickable() || parent.isCheckable() || parent.isFocusable()) {
            return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        return false;
    }

    private void forwardScroll(float x, float y, boolean scrollUp) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo scrollable = findScrollableAt(root, (int) x, (int) y);
                if (scrollable != null) {
                    int action = scrollUp
                            ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    scrollable.performAction(action);
                    scrollable.recycle();
                    root.recycle();
                    return;
                }
                root.recycle();
            }
        } catch (Exception ignored) {}

        // Fallback: Dispatch drag gesture
        Path scrollPath = new Path();
        float dy = scrollUp ? 300 : -300;
        scrollPath.moveTo(x, y);
        scrollPath.lineTo(x, y + dy);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(scrollPath, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    private AccessibilityNodeInfo findScrollableAt(AccessibilityNodeInfo parent, int x, int y) {
        if (parent == null) return null;

        Rect bounds = new Rect();
        parent.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;

        int count = parent.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo s = findScrollableAt(child, x, y);
                if (s != null) {
                    return s;
                }
                child.recycle();
            }
        }

        if (parent.isScrollable()) {
            return AccessibilityNodeInfo.obtain(parent);
        }

        return null;
    }

    // ── Floating Pill (Corner Indicator & One-Click Pause) ──────────────────

    private void initFloatingPill() {
        mFloatingPill = new TextView(this);
        mFloatingPill.setText("🛡️ BLOCKING");
        mFloatingPill.setTextColor(Color.WHITE);
        mFloatingPill.setTextSize(11);
        mFloatingPill.setPadding(16, 8, 16, 8);
        mFloatingPill.setBackgroundColor(Color.parseColor("#CC1B5E20"));

        mFloatingPill.setOnClickListener(v -> toggleBlocking());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 24;
        p.y = 80;

        try {
            mWindowManager.addView(mFloatingPill, p);
        } catch (Exception ignored) {}
    }

    private void updateFloatingPillUI() {
        if (mFloatingPill != null) {
            if (mIsBlocking) {
                mFloatingPill.setText("🛡️ BLOCKING");
                mFloatingPill.setBackgroundColor(Color.parseColor("#CC1B5E20"));
            } else {
                mFloatingPill.setText("⚠️ PAUSED");
                mFloatingPill.setBackgroundColor(Color.parseColor("#CCD50000"));
            }
        }
    }

    private void removeFloatingPill() {
        if (mFloatingPill != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mFloatingPill);
            } catch (Exception ignored) {}
            mFloatingPill = null;
        }
    }
}
