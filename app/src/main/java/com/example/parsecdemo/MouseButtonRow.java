package com.example.parsecdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Compact bottom mouse-button strip shown in touchpad mode. Three buttons:
 *   • Left   — press-and-hold then drag with another finger = click+drag
 *   • Middle — press-and-hold to engage scroll mode; vertical drag scrolls
 *   • Right  — press-and-hold for right click+drag (or quick tap for right click)
 *
 * The leading drag handle (six-dot grip) lets the user pick the row up and
 * place it anywhere on the screen. Position is persisted via {@link Settings}.
 */
public final class MouseButtonRow extends LinearLayout {

    public interface ButtonListener {
        /** Mouse button press/release. parsecButton uses Parsec.MOUSE_L / MOUSE_MIDDLE / MOUSE_R. */
        void onButton(int parsecButton, boolean pressed);
        /** Quick tap on the middle button fires a middle-click on the host. */
        void onMiddleClick();
        /** Wheel ticks emitted while the user is dragging on the middle button
         *  itself. Positive y = scroll down (matches Parsec SDK convention). */
        void onScrollDelta(int ticksX, int ticksY);
        /** User finished dragging the row; final position should be persisted. */
        void onRepositioned(float xPx, float yPx);
    }

    static final int MOUSE_LEFT = 1;
    static final int MOUSE_MIDDLE = 2;
    static final int MOUSE_RIGHT = 3;

    private final ButtonListener listener;

    public MouseButtonRow(Context ctx, ButtonListener listener) {
        super(ctx);
        this.listener = listener;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        // Pill-shaped translucent container.
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        int container = MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        // Apply ~85% alpha so the host content shows through faintly.
        bg.setColor((container & 0x00FFFFFF) | 0xD9000000);
        bg.setCornerRadius(dp(28));
        setBackground(bg);
        int hp = dp(8);
        int vp = dp(6);
        setPadding(hp, vp, hp, vp);

        addView(makeDragHandle(ctx));
        addView(makeButton(ctx, "L", MOUSE_LEFT,   false));
        addView(divider(ctx));
        addView(makeButton(ctx, "M", MOUSE_MIDDLE, true));
        addView(divider(ctx));
        addView(makeButton(ctx, "R", MOUSE_RIGHT,  false));
    }

    private View makeDragHandle(Context ctx) {
        DotGrip grip = new DotGrip(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(36));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(6);
        grip.setLayoutParams(lp);
        grip.setOnTouchListener(new DragTouchListener());
        return grip;
    }

    /** Six-dot drag-handle (two columns × three rows) rendered with Canvas. */
    private final class DotGrip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotGrip(Context ctx) {
            super(ctx);
            paint.setColor(MaterialUi.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float dotR = dp(2);
            float colGap = w / 2f;
            float rowGap = h / 4f;
            for (int col = 0; col < 2; col++) {
                float cx = colGap * (col + 0.5f);
                for (int row = 0; row < 3; row++) {
                    float cy = rowGap * (row + 1);
                    c.drawCircle(cx, cy, dotR, paint);
                }
            }
        }
    }

    /** Touch handler that lets the user pick up the whole row and drop it
     *  somewhere else. Touch is on the drag handle, but the row's translation
     *  changes as a whole. */
    private final class DragTouchListener implements OnTouchListener {
        private float startTouchX, startTouchY;
        private float startRowX, startRowY;
        private boolean dragging;

        @Override public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startTouchX = ev.getRawX();
                    startTouchY = ev.getRawY();
                    startRowX = MouseButtonRow.this.getX();
                    startRowY = MouseButtonRow.this.getY();
                    dragging = true;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return false;
                    float dx = ev.getRawX() - startTouchX;
                    float dy = ev.getRawY() - startTouchY;
                    moveTo(startRowX + dx, startRowY + dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) return false;
                    dragging = false;
                    listener.onRepositioned(MouseButtonRow.this.getX(), MouseButtonRow.this.getY());
                    return true;
                }
            }
            return false;
        }

        private void moveTo(float x, float y) {
            ViewGroup parent = (ViewGroup) MouseButtonRow.this.getParent();
            if (parent == null) return;
            // Clamp inside parent so the row can't be dragged offscreen.
            float maxX = Math.max(0, parent.getWidth()  - MouseButtonRow.this.getWidth());
            float maxY = Math.max(0, parent.getHeight() - MouseButtonRow.this.getHeight());
            float clampedX = Math.min(Math.max(0, x), maxX);
            float clampedY = Math.min(Math.max(0, y), maxY);
            MouseButtonRow.this.setX(clampedX);
            MouseButtonRow.this.setY(clampedY);
        }
    }

    private View makeButton(Context ctx, String label, final int parsecButton, final boolean scroll) {
        final FrameLayout btn = new FrameLayout(ctx);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        final int idleBg = MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHighest);
        final int idleFg = MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorOnSurface);
        final int pressedBg = MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorPrimaryContainer);
        final int pressedFg = MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorOnPrimaryContainer);
        bg.setColor(idleBg);
        bg.setCornerRadius(dp(20));
        btn.setBackground(bg);

        final TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(idleFg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        btn.addView(t, tlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(44));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        btn.setLayoutParams(lp);

        if (scroll) {
            // Middle button = press-and-drag scroll wheel.
            //
            // Finger A touches M, then slides up/down WITHOUT lifting. Because
            // the M button captures the ACTION_DOWN, all subsequent MOVE/UP
            // events for that pointer are delivered here, even when the finger
            // is well past the button bounds. We translate the Y-delta into
            // wheel ticks directly. If the finger never moves enough to fire
            // a tick AND lifts quickly, we fire MOUSE_MIDDLE instead.
            final long[] downAtMs = { 0L };
            final float[] lastY = { 0f };
            final float[] accumY = { 0f };
            final boolean[] hasScrolled = { false };
            // Match ClientGLSurface.SCROLL_PX_PER_TICK (raw px) so the
            // user's Scroll Sensitivity slider produces the same scroll
            // speed on the M button as it does on two-finger surface
            // scrolls. Previously this was dp(8) which on a ~3x density
            // device meant ~3x more finger travel per tick.
            final float pxPerTick = 8f;

            btn.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bg.setColor(pressedBg);
                        t.setTextColor(pressedFg);
                        downAtMs[0] = System.currentTimeMillis();
                        lastY[0] = ev.getRawY();
                        accumY[0] = 0f;
                        hasScrolled[0] = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = ev.getRawY() - lastY[0];
                        lastY[0] = ev.getRawY();
                        accumY[0] += dy;
                        int ticks = (int) (accumY[0] / pxPerTick);
                        if (ticks != 0) {
                            accumY[0] -= ticks * pxPerTick;
                            // Parsec wheel: positive y = scroll down.
                            // Finger drag DOWN should scroll the page DOWN
                            // (Windows touchpad convention), so ticks already
                            // match (positive dy → positive ticks).
                            listener.onScrollDelta(0, ticks);
                            hasScrolled[0] = true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        bg.setColor(idleBg);
                        t.setTextColor(idleFg);
                        if (!hasScrolled[0]) {
                            long dt = System.currentTimeMillis() - downAtMs[0];
                            if (dt < MIDDLE_CLICK_MAX_MS) listener.onMiddleClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        bg.setColor(idleBg);
                        t.setTextColor(idleFg);
                        return true;
                }
                return false;
            });
        } else {
            btn.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bg.setColor(pressedBg);
                        t.setTextColor(pressedFg);
                        listener.onButton(parsecButton, true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bg.setColor(idleBg);
                        t.setTextColor(idleFg);
                        listener.onButton(parsecButton, false);
                        return true;
                }
                return false;
            });
        }
        return btn;
    }

    /** Below this duration on the middle button, treat the gesture as a tap
     *  and fire a middle-click after release. Above it, the user was holding
     *  to scroll and no click should fire. */
    private static final long MIDDLE_CLICK_MAX_MS = 220L;

    private View divider(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(20));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        v.setLayoutParams(lp);
        v.setBackgroundColor(MaterialUi.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
