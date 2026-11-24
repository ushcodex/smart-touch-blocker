package com.touchblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
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
import android.widget.TextView;

public class SmartBlockerAccessibilityService extends AccessibilityService {

    private static final String TAG = "TouchBlockerA11y";

    private static volatile SmartBlockerAccessibilityService sInstance = null;

    private WindowManager mWindowManager;
    private View mOverlayView;
    private View mFloatingPill;
    private WindowManager.LayoutParams mOverlayParams;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsBlocking = true;
    private long mGhostCount = 0;
    private boolean mIsDispatching = false;

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
        updateFloatingPillUI();
    }

    public void toggleBlocking() {
        setBlocking(!mIsBlocking);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used
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
                if (!mIsBlocking) {
                    return false; // Paused -> let all input pass
                }

                if (mIsDispatching) {
                    return false; // Let our own synthetic gesture pass through
                }

                int toolType = event.getToolType(0);
                int source = event.getSource();
                int buttonState = event.getButtonState();

                boolean isMouse = (toolType == MotionEvent.TOOL_TYPE_MOUSE)
                        || ((source & InputDevice.SOURCE_MOUSE) != 0)
                        || (buttonState != 0);

                if (!isMouse) {
                    // TOUCHSCREEN GHOST TOUCH!
                    // Absorb it completely.
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        mGhostCount++;
                    }
                    return true;
                }

                // MOUSE CLICK
                int action = event.getActionMasked();
                float rawX = event.getRawX();
                float rawY = event.getRawY();

                if (action == MotionEvent.ACTION_DOWN) {
                    forwardMouseClick(rawX, rawY);
                }

                return true; // Consumed
            }

            @Override
            public boolean onGenericMotionEvent(MotionEvent event) {
                // Mouse hover and scroll wheel -> pass through so cursor moves freely
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

    // ── Mouse Click Forwarding via Accessibility Gestures ───────────────────

    private void forwardMouseClick(float x, float y) {
        // Strategy 1: Check accessibility node tree first for direct click
        boolean nodeClicked = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo target = findClickableNodeAt(root, (int) x, (int) y);
                if (target != null) {
                    nodeClicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    target.recycle();
                }
                root.recycle();
            }
        } catch (Exception ignored) {}

        // If direct node click succeeded, we're done!
        if (nodeClicked) {
            return;
        }

        // Strategy 2: Dispatch synthetic gesture into underlying view
        mIsDispatching = true;

        // Temporarily make overlay untouchable so the synthetic gesture hits the window behind
        if (mOverlayView != null && mOverlayParams != null) {
            mOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception ignored) {}
        }

        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 10));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                restoreOverlay();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                restoreOverlay();
            }
        }, mHandler);

        // Safety fallback timer to restore touchability
        mHandler.postDelayed(this::restoreOverlay, 35);
    }

    private void restoreOverlay() {
        mIsDispatching = false;
        if (mOverlayView != null && mOverlayParams != null) {
            mOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception ignored) {}
        }
    }

    private AccessibilityNodeInfo findClickableNodeAt(AccessibilityNodeInfo parent, int x, int y) {
        if (parent == null) return null;

        Rect bounds = new Rect();
        parent.getBoundsInScreen(bounds);

        if (!bounds.contains(x, y)) {
            return null;
        }

        // Check children from last to first (top of z-order)
        int childCount = parent.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo target = findClickableNodeAt(child, x, y);
                if (target != null) {
                    return target;
                }
                child.recycle();
            }
        }

        if (parent.isClickable() || parent.isCheckable() || parent.isFocusable()) {
            return AccessibilityNodeInfo.obtain(parent);
        }

        return null;
    }

    // ── Floating Status Pill (Corner indicator & pause button) ─────────────

    private void initFloatingPill() {
        TextView pill = new TextView(this);
        pill.setText("🛡️ BLOCKING");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(11);
        pill.setPadding(18, 10, 18, 10);
        pill.setBackgroundColor(Color.parseColor("#CC1B5E20"));

        pill.setOnClickListener(v -> toggleBlocking());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = 20;
        p.y = 80;

        mFloatingPill = pill;
        try {
            mWindowManager.addView(mFloatingPill, p);
        } catch (Exception ignored) {}
    }

    private void updateFloatingPillUI() {
        if (mFloatingPill instanceof TextView) {
            TextView tv = (TextView) mFloatingPill;
            if (mIsBlocking) {
                tv.setText("🛡️ BLOCKING");
                tv.setBackgroundColor(Color.parseColor("#CC1B5E20"));
            } else {
                tv.setText("⚠️ PAUSED");
                tv.setBackgroundColor(Color.parseColor("#CCD50000"));
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
