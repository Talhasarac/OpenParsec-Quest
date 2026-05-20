package com.example.parsecdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

import parsec.bindings.Parsec;

/**
 * Touch overlay that emits Parsec gamepad button + axis messages so users
 * with no physical controller can still play games on the remote host.
 *
 * Layout (landscape):
 *   • Left thumbstick    — bottom-left, floating
 *   • Right thumbstick   — bottom-right, floating
 *   • DPad (up/down/L/R) — left-of-center, above the left stick
 *   • Face buttons A/B/X/Y — right-of-center, above the right stick
 *   • LB / LT shoulders  — top-left
 *   • RB / RT shoulders  — top-right
 *   • Back / Start       — bottom-center
 *
 * The overlay uses MotionEvent.getPointerId() so finger-A on the left stick
 * doesn't get hijacked by finger-B touching A/B/X/Y — each pointer tracks
 * its own target widget independently.
 */
public final class VirtualGamepad extends FrameLayout {

    public interface Listener {
        void onButton(int parsecButton, boolean pressed);
        /** Axis value is a normalized signed-16-bit short cast to int so the
         *  caller can pass it straight to Parsec.clientSendGamepadAxis(). */
        void onAxis(int parsecAxis, int value);
    }

    private final Listener listener;
    /** pointerId → widget currently capturing that finger. */
    private final Map<Integer, Capturing> capture = new HashMap<>();

    public VirtualGamepad(Context ctx, Listener l) {
        super(ctx);
        this.listener = l;
        setWillNotDraw(true);
        // Touch handling is at the overlay level so we can route multi-touch
        // to whichever child widget owns each pointer.
        setOnTouchListener(null);

        // === Left thumbstick (bottom-left) ===
        Stick lStick = new Stick(ctx, true);
        addView(lStick, posLp(dp(140), dp(140), Gravity.BOTTOM | Gravity.START,
                dp(24), 0, 0, dp(24)));

        // === Right thumbstick (bottom-right) ===
        Stick rStick = new Stick(ctx, false);
        addView(rStick, posLp(dp(140), dp(140), Gravity.BOTTOM | Gravity.END,
                0, 0, dp(24), dp(24)));

        // === DPad cluster ===
        DPad dpad = new DPad(ctx);
        addView(dpad, posLp(dp(140), dp(140), Gravity.CENTER_VERTICAL | Gravity.START,
                dp(190), 0, 0, 0));

        // === Face buttons cluster (A/B/X/Y) ===
        FaceCluster face = new FaceCluster(ctx);
        addView(face, posLp(dp(140), dp(140), Gravity.CENTER_VERTICAL | Gravity.END,
                0, 0, dp(190), 0));

        // === Shoulders / triggers — top corners ===
        ShoulderButton lb = new ShoulderButton(ctx, "LB", Parsec.GAMEPAD_BUTTON_LSHOULDER, false);
        addView(lb, posLp(dp(72), dp(40), Gravity.TOP | Gravity.START, dp(24), dp(16), 0, 0));
        ShoulderButton lt = new ShoulderButton(ctx, "LT", -1, true);
        addView(lt, posLp(dp(72), dp(40), Gravity.TOP | Gravity.START, dp(104), dp(16), 0, 0));

        ShoulderButton rb = new ShoulderButton(ctx, "RB", Parsec.GAMEPAD_BUTTON_RSHOULDER, false);
        addView(rb, posLp(dp(72), dp(40), Gravity.TOP | Gravity.END, 0, dp(16), dp(24), 0));
        ShoulderButton rt = new ShoulderButton(ctx, "RT", -2, true);
        addView(rt, posLp(dp(72), dp(40), Gravity.TOP | Gravity.END, 0, dp(16), dp(104), 0));

        // === Start / Back — bottom-center ===
        PillButton back = new PillButton(ctx, "Back", Parsec.GAMEPAD_BUTTON_BACK);
        PillButton start = new PillButton(ctx, "Start", Parsec.GAMEPAD_BUTTON_START);
        FrameLayout.LayoutParams backLp = posLp(dp(72), dp(36),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, dp(48), dp(16));
        FrameLayout.LayoutParams startLp = posLp(dp(72), dp(36),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, dp(48), 0, 0, dp(16));
        addView(back, backLp);
        addView(start, startLp);
    }

    private FrameLayout.LayoutParams posLp(int w, int h, int gravity,
                                           int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    /** Overlay-level dispatch routes each pointer to the widget hit on DOWN
     *  and keeps it routed there until UP, so multi-touch (stick + face button)
     *  works correctly even when the second touch lands inside another child.
     *  Touches that land in empty regions are forwarded through to the GL
     *  surface beneath us — otherwise mouse input dies the moment the
     *  gamepad is shown. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // External mice always pass through — they should go straight to the
        // GL surface even when fingers are on the on-screen gamepad. The
        // user has a hardware cursor and shouldn't have to dismiss the
        // overlay to click.
        if (isMouseSource(ev)) return false;
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Defensive: never absorb mouse events here.
        if (isMouseSource(ev)) return false;

        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        int pid = ev.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                Capturing c = findChildAt(x, y);
                if (c != null) {
                    capture.put(pid, c);
                    c.widget.onDown(c.localX(x), c.localY(y));
                    return true;
                }
                // Empty region — don't consume so the touch falls through to
                // the GL surface / other overlays below.
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                boolean any = false;
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    Capturing c = capture.get(id);
                    if (c == null) continue;
                    c.widget.onMove(c.localX(ev.getX(i)), c.localY(ev.getY(i)));
                    any = true;
                }
                return any;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                Capturing c = capture.remove(pid);
                if (c != null) {
                    c.widget.onUp();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private static boolean isMouseSource(MotionEvent ev) {
        int s = ev.getSource();
        return (s & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
            || (s & android.view.InputDevice.SOURCE_MOUSE_RELATIVE) == android.view.InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private Capturing findChildAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (!(v instanceof Widget)) continue;
            if (v.getVisibility() != VISIBLE) continue;
            float lx = v.getLeft();
            float ly = v.getTop();
            float rx = v.getRight();
            float ry = v.getBottom();
            if (x >= lx && x < rx && y >= ly && y < ry) {
                Capturing c = new Capturing();
                c.widget = (Widget) v;
                c.viewLeft = lx;
                c.viewTop = ly;
                return c;
            }
        }
        return null;
    }

    private static final class Capturing {
        Widget widget;
        float viewLeft, viewTop;
        float localX(float ex) { return ex - viewLeft; }
        float localY(float ey) { return ey - viewTop; }
    }

    /** Common interface for stick / dpad / face-cluster / button widgets. */
    interface Widget {
        void onDown(float localX, float localY);
        void onMove(float localX, float localY);
        void onUp();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int dpStatic(View v, int n) {
        return Math.round(n * v.getResources().getDisplayMetrics().density);
    }

    // ============================================================
    // Sticks
    // ============================================================
    private final class Stick extends View implements Widget {
        private final boolean left;
        private final Paint baseFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint baseStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float knobDx = 0f, knobDy = 0f;
        private boolean active = false;

        Stick(Context ctx, boolean left) {
            super(ctx);
            this.left = left;
            baseFill.setColor(0x40000000);
            baseFill.setStyle(Paint.Style.FILL);
            baseStroke.setColor(0xFFFFFFFF);
            baseStroke.setStyle(Paint.Style.STROKE);
            baseStroke.setStrokeWidth(dpStatic(this, 2));
            baseStroke.setAlpha(0x60);
            knobFill.setColor(0xCCFFFFFF);
            knobFill.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float baseR = Math.min(getWidth(), getHeight()) / 2f - dpStatic(this, 4);
            c.drawCircle(cx, cy, baseR, baseFill);
            c.drawCircle(cx, cy, baseR, baseStroke);
            float knobR = baseR * 0.45f;
            c.drawCircle(cx + knobDx, cy + knobDy, knobR, knobFill);
        }

        @Override public void onDown(float lx, float ly) { active = true; onMove(lx, ly); }

        @Override public void onMove(float lx, float ly) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float dx = lx - cx;
            float dy = ly - cy;
            float maxR = Math.min(getWidth(), getHeight()) / 2f - dpStatic(this, 8);
            float r = (float) Math.hypot(dx, dy);
            if (r > maxR && r > 0) {
                dx = dx / r * maxR;
                dy = dy / r * maxR;
            }
            knobDx = dx;
            knobDy = dy;
            float nx = dx / maxR;     // [-1, 1]
            float ny = dy / maxR;     // [-1, 1]  (Android Y grows downward; Parsec axis Y likewise grows downward)
            short sx = (short) Math.max(-32768, Math.min(32767, Math.round(nx * 32767f)));
            short sy = (short) Math.max(-32768, Math.min(32767, Math.round(ny * 32767f)));
            int axisX = left ? Parsec.GAMEPAD_AXIS_LX : Parsec.GAMEPAD_AXIS_RX;
            int axisY = left ? Parsec.GAMEPAD_AXIS_LY : Parsec.GAMEPAD_AXIS_RY;
            listener.onAxis(axisX, sx);
            listener.onAxis(axisY, sy);
            invalidate();
        }

        @Override public void onUp() {
            active = false;
            knobDx = knobDy = 0f;
            int axisX = left ? Parsec.GAMEPAD_AXIS_LX : Parsec.GAMEPAD_AXIS_RX;
            int axisY = left ? Parsec.GAMEPAD_AXIS_LY : Parsec.GAMEPAD_AXIS_RY;
            listener.onAxis(axisX, 0);
            listener.onAxis(axisY, 0);
            invalidate();
        }
    }

    // ============================================================
    // DPad — sends one button at a time based on dominant quadrant
    // ============================================================
    private final class DPad extends View implements Widget {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int lastDir = -1; // 0=up 1=right 2=down 3=left

        DPad(Context ctx) {
            super(ctx);
            fill.setColor(0x40000000);
            stroke.setColor(0x60FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dpStatic(this, 2));
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = Math.min(getWidth(), getHeight()) / 2.4f;
            float thick = arm * 0.45f;
            RectF h = new RectF(cx - arm, cy - thick / 2, cx + arm, cy + thick / 2);
            RectF v = new RectF(cx - thick / 2, cy - arm, cx + thick / 2, cy + arm);
            float rad = thick * 0.35f;
            c.drawRoundRect(h, rad, rad, fill);
            c.drawRoundRect(v, rad, rad, fill);
            c.drawRoundRect(h, rad, rad, stroke);
            c.drawRoundRect(v, rad, rad, stroke);

            // Arrow ticks
            Paint ar = new Paint(Paint.ANTI_ALIAS_FLAG);
            ar.setColor(0xCCFFFFFF);
            float r = dpStatic(this, 4);
            c.drawCircle(cx, cy - arm * 0.7f, r, ar);
            c.drawCircle(cx, cy + arm * 0.7f, r, ar);
            c.drawCircle(cx - arm * 0.7f, cy, r, ar);
            c.drawCircle(cx + arm * 0.7f, cy, r, ar);
        }

        @Override public void onDown(float lx, float ly) { onMove(lx, ly); }

        @Override public void onMove(float lx, float ly) {
            float dx = lx - getWidth() / 2f;
            float dy = ly - getHeight() / 2f;
            int dir;
            if (Math.abs(dx) > Math.abs(dy)) dir = dx > 0 ? 1 : 3;
            else dir = dy > 0 ? 2 : 0;
            if (dir == lastDir) return;
            releaseLast();
            lastDir = dir;
            listener.onButton(buttonFor(dir), true);
        }

        @Override public void onUp() {
            releaseLast();
            lastDir = -1;
        }

        private void releaseLast() {
            if (lastDir < 0) return;
            listener.onButton(buttonFor(lastDir), false);
        }

        private int buttonFor(int dir) {
            switch (dir) {
                case 0: return Parsec.GAMEPAD_BUTTON_DPAD_UP;
                case 1: return Parsec.GAMEPAD_BUTTON_DPAD_RIGHT;
                case 2: return Parsec.GAMEPAD_BUTTON_DPAD_DOWN;
                default: return Parsec.GAMEPAD_BUTTON_DPAD_LEFT;
            }
        }
    }

    // ============================================================
    // Face button cluster (A/B/X/Y arranged in a diamond)
    // ============================================================
    private final class FaceCluster extends View implements Widget {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = {
                0xFFE53935, // A (red, bottom)
                0xFFFFB300, // B (yellow, right)
                0xFF1E88E5, // X (blue, left)
                0xFF43A047  // Y (green, top)
        };
        private int held = -1; // 0=A bottom, 1=B right, 2=X left, 3=Y top

        FaceCluster(Context ctx) {
            super(ctx);
            bg.setStyle(Paint.Style.FILL);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 18, getResources().getDisplayMetrics()));
            label.setFakeBoldText(true);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = Math.min(getWidth(), getHeight()) / 2.6f;
            float r = arm * 0.6f;
            drawBtn(c, cx, cy + arm, r, "A", colors[0]);
            drawBtn(c, cx + arm, cy, r, "B", colors[1]);
            drawBtn(c, cx - arm, cy, r, "X", colors[2]);
            drawBtn(c, cx, cy - arm, r, "Y", colors[3]);
        }

        private void drawBtn(Canvas c, float x, float y, float r, String text, int color) {
            bg.setColor(color);
            c.drawCircle(x, y, r, bg);
            c.drawText(text, x, y + label.getTextSize() / 3, label);
        }

        @Override public void onDown(float lx, float ly) {
            int hit = hitTest(lx, ly);
            held = hit;
            if (hit >= 0) listener.onButton(buttonFor(hit), true);
        }

        @Override public void onMove(float lx, float ly) {
            int hit = hitTest(lx, ly);
            if (hit == held) return;
            // Sliding off the held button releases it; sliding onto a new one
            // does NOT engage it (matches Xbox controller — must lift+repress).
            if (held >= 0) listener.onButton(buttonFor(held), false);
            held = -1;
        }

        @Override public void onUp() {
            if (held >= 0) listener.onButton(buttonFor(held), false);
            held = -1;
        }

        private int hitTest(float lx, float ly) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float arm = Math.min(getWidth(), getHeight()) / 2.6f;
            float r = arm * 0.6f;
            // A (bottom)
            if (within(lx, ly, cx, cy + arm, r)) return 0;
            if (within(lx, ly, cx + arm, cy, r)) return 1;
            if (within(lx, ly, cx - arm, cy, r)) return 2;
            if (within(lx, ly, cx, cy - arm, r)) return 3;
            return -1;
        }

        private boolean within(float lx, float ly, float bx, float by, float r) {
            float dx = lx - bx;
            float dy = ly - by;
            return dx * dx + dy * dy <= r * r;
        }

        private int buttonFor(int idx) {
            switch (idx) {
                case 0: return Parsec.GAMEPAD_BUTTON_A;
                case 1: return Parsec.GAMEPAD_BUTTON_B;
                case 2: return Parsec.GAMEPAD_BUTTON_X;
                default: return Parsec.GAMEPAD_BUTTON_Y;
            }
        }
    }

    // ============================================================
    // Pill / shoulder buttons
    // ============================================================
    private class PillButton extends View implements Widget {
        protected final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        protected final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        protected final String text;
        protected final int parsecButton;
        protected boolean held = false;

        PillButton(Context ctx, String text, int parsecButton) {
            super(ctx);
            this.text = text;
            this.parsecButton = parsecButton;
            bg.setStyle(Paint.Style.FILL);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
            label.setFakeBoldText(true);
        }

        @Override protected void onDraw(Canvas c) {
            bg.setColor(held ? 0xFF1976D2 : 0x80000000);
            RectF rect = new RectF(0, 0, getWidth(), getHeight());
            float r = Math.min(getWidth(), getHeight()) / 2f;
            c.drawRoundRect(rect, r, r, bg);
            c.drawText(text, getWidth() / 2f,
                    getHeight() / 2f + label.getTextSize() / 3, label);
        }

        @Override public void onDown(float lx, float ly) {
            held = true;
            listener.onButton(parsecButton, true);
            invalidate();
        }
        @Override public void onMove(float lx, float ly) {}
        @Override public void onUp() {
            held = false;
            listener.onButton(parsecButton, false);
            invalidate();
        }
    }

    /** Trigger-style shoulder. When {@code asTrigger} is true, sends an axis
     *  (LT/RT) at full-press value instead of a digital button. */
    private final class ShoulderButton extends PillButton {
        private final boolean asTrigger;
        ShoulderButton(Context ctx, String text, int parsecButton, boolean asTrigger) {
            super(ctx, text, parsecButton);
            this.asTrigger = asTrigger;
        }

        @Override public void onDown(float lx, float ly) {
            held = true;
            if (asTrigger) {
                int axis = "LT".equals(text) ? Parsec.GAMEPAD_AXIS_TRIGGERL
                        : Parsec.GAMEPAD_AXIS_TRIGGERR;
                listener.onAxis(axis, 32767);
            } else {
                listener.onButton(parsecButton, true);
            }
            invalidate();
        }

        @Override public void onUp() {
            held = false;
            if (asTrigger) {
                int axis = "LT".equals(text) ? Parsec.GAMEPAD_AXIS_TRIGGERL
                        : Parsec.GAMEPAD_AXIS_TRIGGERR;
                listener.onAxis(axis, 0);
            } else {
                listener.onButton(parsecButton, false);
            }
            invalidate();
        }
    }
}
