package com.touchblocker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * TouchFilterView — the core of the selective input blocker.
 *
 * Strategy:
 *   • The view sits in a fullscreen TYPE_APPLICATION_OVERLAY window.
 *   • onTouchEvent() is called for all pointer events (touchscreen AND mouse clicks).
 *     - SOURCE_TOUCHSCREEN  → return true  (event consumed = ghost touch discarded)
 *     - SOURCE_MOUSE        → return false (not consumed; falls through the view
 *                             hierarchy; we also dynamically set FLAG_NOT_TOUCHABLE
 *                             on the host window for 60 ms so the click actually
 *                             reaches the app behind the overlay)
 *   • onGenericMotionEvent() handles hover events (mouse movement).
 *     - Always returns false so mouse cursor movement is never blocked.
 *
 * The "60 ms FLAG_NOT_TOUCHABLE" trick:
 *   Android dispatches touch to the top-most window that covers the pointer
 *   position. Returning false inside onTouchEvent() alone is NOT enough to
 *   propagate the event to the window behind — the window manager already
 *   delivered the event to us. By atomically re-setting FLAG_NOT_TOUCHABLE on
 *   the ACTION_DOWN of a mouse button press we ensure the subsequent ACTION_UP
 *   and any immediate next ACTION_DOWN (the actual "click" in the underlying
 *   app's terms) are dispatched to the window below. We re-arm the flag after
 *   the click window expires.
 */
public class TouchFilterView extends View {

    private static final int MOUSE_PASSTHROUGH_MS = 80;

    private WindowManager mWm;
    private WindowManager.LayoutParams mParams;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mMousePassthrough = false;
    private long mGhostCount = 0;

    public TouchFilterView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
    }

    /** Called from the service after addView() so we can mutate our own layout params. */
    public void setWindowManagerRef(WindowManager wm, WindowManager.LayoutParams params) {
        mWm = wm;
        mParams = params;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int source = event.getSource();
        boolean isTouchscreen = (source & InputDevice.SOURCE_TOUCHSCREEN) != 0
                && (source & InputDevice.SOURCE_CLASS_POINTER) != 0;
        boolean isMouse = (source & InputDevice.SOURCE_MOUSE) != 0;

        if (isMouse) {
            // Let mouse clicks pass through to the app behind the overlay
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                enableMousePassthrough();
            }
            return false; // do not consume
        }

        if (isTouchscreen) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mGhostCount++;
            }
            return true; // consume = block ghost touch
        }

        // Unknown source — pass through
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Mouse hover/scroll events — always pass through
        if (MotionEvent.isFromSource(event, InputDevice.SOURCE_MOUSE)) {
            int buttons = event.getButtonState();
            if (buttons != 0 && !mMousePassthrough) {
                // Button state changed in a hover event — preemptively open the passthrough
                enableMousePassthrough();
            }
            return false;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Completely transparent — nothing drawn
    }

    // ── Mouse click pass-through helpers ──────────────────────────────────────

    private void enableMousePassthrough() {
        if (mMousePassthrough || mWm == null || mParams == null) return;
        mMousePassthrough = true;
        // Add FLAG_NOT_TOUCHABLE so the window becomes event-transparent
        mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { mWm.updateViewLayout(this, mParams); } catch (Exception ignored) {}
        // Remove the flag after the click window expires
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(this::disableMousePassthrough, MOUSE_PASSTHROUGH_MS);
    }

    private void disableMousePassthrough() {
        mMousePassthrough = false;
        if (mWm == null || mParams == null) return;
        mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { mWm.updateViewLayout(this, mParams); } catch (Exception ignored) {}
    }

    public long getGhostCount() { return mGhostCount; }
}
