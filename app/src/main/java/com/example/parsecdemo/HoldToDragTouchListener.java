package com.example.parsecdemo;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Separates activation from repositioning for small floating controls.
 *
 * A release before {@link #DRAG_HOLD_MS} is always a click, even if the
 * pointer jitters. The view cannot move until the press has lasted at least
 * one second and then crosses Android's normal touch slop.
 */
final class HoldToDragTouchListener implements View.OnTouchListener {
    static final long DRAG_HOLD_MS = 1000L;

    private final View view;
    private final ViewGroup bounds;
    private final Runnable onDragStart;
    private final Runnable onDragEnd;
    private final int touchSlop;

    private float touchOffsetX;
    private float touchOffsetY;
    private float downRawX;
    private float downRawY;
    private long downTimeMs;
    private boolean dragging;
    private boolean gestureActive;

    HoldToDragTouchListener(
            View view, ViewGroup bounds, Runnable onDragStart, Runnable onDragEnd) {
        this.view = view;
        this.bounds = bounds;
        this.onDragStart = onDragStart;
        this.onDragEnd = onDragEnd;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    @Override
    public boolean onTouch(View ignored, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureActive = true;
                touchOffsetX = event.getRawX() - view.getX();
                touchOffsetY = event.getRawY() - view.getY();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downTimeMs = event.getEventTime();
                dragging = false;
                view.setPressed(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!gestureActive) return true;
                long heldMs = event.getEventTime() - downTimeMs;
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging
                        && heldMs >= DRAG_HOLD_MS
                        && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    if (onDragStart != null) onDragStart.run();
                }
                if (dragging) moveTo(event.getRawX(), event.getRawY());
                return true;

            case MotionEvent.ACTION_UP:
                if (!gestureActive) return true;
                gestureActive = false;
                view.setPressed(false);
                if (dragging) {
                    finishDrag();
                } else if (event.getEventTime() - downTimeMs < DRAG_HOLD_MS) {
                    view.performClick();
                }
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (!gestureActive) return true;
                gestureActive = false;
                view.setPressed(false);
                if (dragging) finishDrag();
                dragging = false;
                return true;

            default:
                return true;
        }
    }

    /**
     * Invalidates the current gesture without firing a click or drag callback.
     * Used when an external recovery action resets the view's position.
     */
    void cancelGesture() {
        gestureActive = false;
        dragging = false;
        view.setPressed(false);
    }

    private void moveTo(float rawX, float rawY) {
        float maxX = Math.max(0, bounds.getWidth() - view.getWidth());
        float maxY = Math.max(0, bounds.getHeight() - view.getHeight());
        float x = Math.max(0, Math.min(rawX - touchOffsetX, maxX));
        float y = Math.max(0, Math.min(rawY - touchOffsetY, maxY));
        view.setX(x);
        view.setY(y);
    }

    private void finishDrag() {
        if (onDragEnd != null) onDragEnd.run();
    }
}
