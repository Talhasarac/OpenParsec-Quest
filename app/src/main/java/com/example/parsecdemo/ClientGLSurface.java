package com.example.parsecdemo;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import javax.microedition.khronos.opengles.GL10;

import parsec.bindings.Parsec;

/**
 * GLSurfaceView wrapper that:
 *  - renders Parsec stream
 *  - exposes Touchpad and Direct input modes (OpenParsec-equivalent)
 *  - reports a local visual cursor position to a TrackpadListener for an
 *    Android overlay view that traverses the entire visible viewport
 *  - maps client coordinates to host coordinates with the OpenParsec
 *    aspect-ratio (letterbox) math so the host cursor can reach the
 *    edges of the rendered frame even when aspects mismatch
 */
public class ClientGLSurface extends GLSurfaceView {
    public interface TrackpadListener {
        void onTrackpadCursorChanged(float x, float y, boolean visible);
    }

    private Parsec parsec;
    private final Object parsecLock = new Object();
    private volatile boolean parsecAlive = false;

    private volatile int surfaceWidth = 0;
    private volatile int surfaceHeight = 0;

    private boolean trackpadMode = true;
    private float sensitivity = 1.6f;
    /** Set by external mouse-button row: when any virtual mouse button is held,
     *  the trackpad surface should NOT generate tap-clicks (we're in drag mode). */
    private volatile boolean externalButtonHeld = false;
    /** When middle-button scroll mode is active, vertical finger movement on the
     *  trackpad is translated into mouse wheel events instead of cursor motion. */
    private volatile boolean scrollMode = false;
    private float scrollAccum = 0f;
    /** Pixels of centroid drift per emitted wheel tick. Smaller = snappier
     *  scrolling. 8px feels close to a Mac/Windows precision touchpad — a
     *  100px finger swipe produces ~12 wheel ticks, which on Windows with
     *  the default 3-lines-per-tick setting is ~36 lines of scroll. */
    private static final float SCROLL_PX_PER_TICK = 8f;
    /** Base multiplier applied to each emitted wheel tick. The Parsec SDK
     *  wheel delta is a small integer; sending 2 per tick gives noticeably
     *  stronger scroll without overflowing the field or causing page-warps.
     *  The user-controllable {@link #scrollSensitivity} scales on top of this. */
    private static final int WHEEL_TICK_BASE = 2;
    /** User-configurable scroll sensitivity multiplier (Settings → Interactivity).
     *  1.0 = default, < 1.0 = slower, > 1.0 = faster. */
    private volatile float scrollSensitivity = 1.0f;
    private float cursorX = 0f;
    private float cursorY = 0f;
    private float lastX = 0f;
    private float lastY = 0f;
    private float downX = 0f;
    private float downY = 0f;
    private long downTime = 0L;
    private static final float TAP_SLOP_PX = 12f;
    private static final long TAP_MAX_MS = 180L;

    // ----- Multi-touch scroll state (used in both direct & trackpad modes) -----
    /** Engaged when a second finger touches down during a gesture. While true,
     *  finger motion translates to wheel events instead of cursor motion. */
    private boolean twoFingerScroll = false;
    private float twoFingerLastCentroidY = 0f;
    private float twoFingerLastCentroidX = 0f;
    private float twoFingerScrollAccum = 0f;
    private float twoFingerScrollAccumX = 0f;
    /** Tracks whether a two-finger gesture actually scrolled (moved enough to
     *  cross a scroll-tick threshold). Used to distinguish a two-finger TAP
     *  (right click on Win/Mac touchpads) from a two-finger drag. */
    private boolean twoFingerScrollFired = false;
    private long twoFingerDownTime = 0L;
    private float twoFingerDownCentroidX = 0f;
    private float twoFingerDownCentroidY = 0f;
    private static final long TWO_FINGER_TAP_MAX_MS = 220L;
    private static final float TWO_FINGER_TAP_SLOP_PX = 24f;

    // ----- Tap-tap-hold drag (trackpad mode) -----
    /** Time of the most recent ACTION_UP that completed a quick tap. A second
     *  ACTION_DOWN within {@link #TAP_TAP_HOLD_WINDOW_MS} of this time engages
     *  click-and-drag: the left button is held while that finger moves, and
     *  released on its lift. Standard trackpad behavior. */
    private long lastTapUpMs = 0L;
    private float lastTapUpX = 0f, lastTapUpY = 0f;
    private boolean tapTapHoldDragActive = false;
    private static final long TAP_TAP_HOLD_WINDOW_MS = 280L;
    private static final float TAP_TAP_HOLD_SLOP_PX = 32f;

    /** Left button is currently held by direct-touch mode (1-finger drag). */
    private boolean directLeftHeld = false;

    // ----- Pinch zoom + pan (RustDesk-style, opt-in) -----
    /** Gating flag. When OFF (default), pinch / two-finger gestures pass
     *  through to the regular trackpad-style touch handler so the host
     *  sees them as normal scroll/touch input — no view-level zoom. When
     *  ON, two fingers zoom + pan the local GL surface. Toggled from the
     *  in-session FAB menu. */
    private volatile boolean zoomEnabled = false;
    private ScaleGestureDetector scaleDetector;
    private float zoom = 1.0f;
    private float panX = 0f, panY = 0f;
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 5.0f;
    /** Triggered when the user is actively pinching. Suppresses cursor /
     *  scroll handling so the pinch doesn't double-fire as e.g. a wheel
     *  event. */
    private boolean pinching = false;
    /** True for the duration of a 2-finger gesture once we've decided it's
     *  a PAN (not a scroll). Switching mid-gesture would feel jittery, so
     *  the decision sticks until all fingers lift. */
    private boolean panning = false;

    private int hostWidth = 1920;
    private int hostHeight = 1080;

    private TrackpadListener trackpadListener;

    public ClientGLSurface(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                        pinching = true;
                        return true;
                    }
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float prevZoom = zoom;
                        float newZoom = clamp(zoom * d.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                        if (newZoom == prevZoom) return true;
                        // Zoom around the gesture focal point so the pixel
                        // under the user's fingers stays put.
                        float fx = d.getFocusX();
                        float fy = d.getFocusY();
                        float ratio = newZoom / prevZoom;
                        // Solve: new_pan + fx * new_zoom = old_pan + fx * old_zoom
                        // (we want the host pixel currently under fx to remain
                        // under fx after the scale change).
                        panX = fx - (fx - panX) * ratio;
                        panY = fy - (fy - panY) * ratio;
                        zoom = newZoom;
                        clampPanAndApply();
                        return true;
                    }
                    @Override public void onScaleEnd(ScaleGestureDetector d) {
                        pinching = false;
                    }
                });
    }

    /** Apply current zoom/pan as View transforms. Touch events are
     *  matrix-inverted by Android automatically, so motion coordinates
     *  reported to onTouchEvent stay in the un-transformed local space —
     *  no manual remapping required for cursor / direct-touch dispatch. */
    private void applyTransform() {
        setPivotX(0f);
        setPivotY(0f);
        setScaleX(zoom);
        setScaleY(zoom);
        setTranslationX(panX);
        setTranslationY(panY);
    }

    /** Clamp pan so the scaled surface can't be dragged completely off the
     *  visible area. At zoom=1 this snaps pan to (0,0). */
    private void clampPanAndApply() {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            float scaledW = w * zoom;
            float scaledH = h * zoom;
            float minX = w - scaledW; // negative or zero
            float minY = h - scaledH;
            if (zoom <= 1.001f) {
                panX = 0f;
                panY = 0f;
            } else {
                if (panX > 0) panX = 0;
                if (panX < minX) panX = minX;
                if (panY > 0) panY = 0;
                if (panY < minY) panY = minY;
            }
        }
        applyTransform();
    }

    /** Reset zoom/pan back to 1× / centered. Called from the FAB menu. */
    public void resetZoom() {
        zoom = 1.0f;
        panX = panY = 0f;
        clampPanAndApply();
    }

    public boolean isZoomed() { return zoom > 1.001f; }

    /** Enable / disable view-level pinch zoom. When disabled, two-finger
     *  gestures fall through to the regular trackpad / direct-touch path
     *  (which translates them to host scroll events). Snaps view back to
     *  1× when toggled off so the user doesn't have a permanently-zoomed
     *  surface after disabling. */
    public void setZoomEnabled(boolean enabled) {
        this.zoomEnabled = enabled;
        if (!enabled) {
            pinching = false;
            panning = false;
            resetZoom();
        }
    }
    public boolean isZoomEnabled() { return zoomEnabled; }

    public void setParsec(Parsec parsec) {
        synchronized (parsecLock) {
            this.parsec = parsec;
            this.parsecAlive = parsec != null;
        }
    }

    public void setTrackpadListener(TrackpadListener l) {
        this.trackpadListener = l;
    }

    public void setTrackpadMode(boolean enabled) {
        this.trackpadMode = enabled;
        if (enabled) {
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                cursorX = surfaceWidth / 2f;
                cursorY = surfaceHeight / 2f;
                sendAbsoluteMotionMapped(cursorX, cursorY);
                notifyCursor(true);
            }
        } else {
            notifyCursor(false);
        }
    }

    public boolean isTrackpadMode() { return trackpadMode; }

    public void setSensitivity(float v) { this.sensitivity = Math.max(0.1f, v); }

    /** Setter for the scroll-sensitivity multiplier. Lower bound at 0.1f to
     *  avoid swallowing scroll entirely; upper bound is enforced by the UI
     *  slider in {@code SettingsPanel}. */
    public void setScrollSensitivity(float v) {
        this.scrollSensitivity = Math.max(0.1f, v);
    }

    /** Resolved per-tick wheel delta (base × user multiplier, rounded). */
    private int scaledTick(int ticks) {
        int out = Math.round(ticks * (float) WHEEL_TICK_BASE * scrollSensitivity);
        // Preserve sign even for very small multipliers — at least one notch
        // in the direction the user is dragging so scroll never feels dead.
        if (out == 0 && ticks != 0) out = ticks > 0 ? 1 : -1;
        return out;
    }

    /** When true, suppress the auto-tap-to-click behaviour so the user can hold
     *  an external mouse button and drag with another finger. */
    public void setExternalButtonHeld(boolean held) { this.externalButtonHeld = held; }

    /** When true, vertical drags on the trackpad are translated to wheel ticks. */
    public void setScrollMode(boolean on) {
        this.scrollMode = on;
        this.scrollAccum = 0f;
    }

    /** Emit a wheel event with the user's scroll-sensitivity multiplier
     *  applied. Called by the on-screen middle-button row when the user
     *  drags up/down on the M button itself. */
    public void sendScrollDelta(int ticksX, int ticksY) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) {
                parsec.clientSendMouseWheel(scaledTick(ticksX), scaledTick(ticksY));
            }
        }
    }

    /** Press or release a specific mouse button (used by the on-screen button row). */
    public void sendButtonExternal(int parsecButton, boolean pressed) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseButton(parsecButton, pressed);
        }
    }

    public void setHostDimensions(int w, int h) {
        if (w > 0) this.hostWidth = w;
        if (h > 0) this.hostHeight = h;
    }

    /** Called by ParsecActivity when the visible view is resized (fold/unfold, rotation).
     *  Note: this does NOT push dimensions to Parsec directly — the GL renderer's
     *  onSurfaceChanged is the authoritative source and runs against the actual
     *  EGL surface size. Passing root dimensions here would race with that and
     *  could leave the host renderer with the wrong viewport (off-center). */
    public void onClientResize(int width, int height) {
        // Only update bookkeeping if it isn't already matching the GL side.
        if (surfaceWidth != width || surfaceHeight != height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }
        // Re-center cursor to the new viewport
        cursorX = width / 2f;
        cursorY = height / 2f;
        if (trackpadMode) notifyCursor(true);
    }

    /** Wipe transient input state so a fold/unfold (which can drop touch events
     *  mid-gesture) doesn't leave us with phantom held buttons, stuck scroll
     *  mode, or a frozen finger position. Releases all mouse buttons on the
     *  host side, clears scroll accumulation, and re-centers the cursor. */
    public void resetTouchState() {
        scrollMode = false;
        scrollAccum = 0f;
        externalButtonHeld = false;
        downTime = 0L;
        downX = downY = 0f;
        lastX = lastY = 0f;
        twoFingerScroll = false;
        twoFingerScrollAccum = 0f;
        twoFingerLastCentroidY = 0f;
        tapTapHoldDragActive = false;
        directLeftHeld = false;
        lastTapUpMs = 0L;
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) {
                // Release all standard mouse buttons defensively.
                parsec.clientSendMouseButton(1 /* MOUSE_L */, false);
                parsec.clientSendMouseButton(2 /* MOUSE_MIDDLE */, false);
                parsec.clientSendMouseButton(3 /* MOUSE_R */, false);
            }
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            cursorX = surfaceWidth / 2f;
            cursorY = surfaceHeight / 2f;
            if (trackpadMode) {
                sendAbsoluteMotionMapped(cursorX, cursorY);
                notifyCursor(true);
            }
        }
    }

    public void renderInit() {
        setEGLContextClientVersion(2);
        setRenderer(new Renderer() {
            @Override public void onSurfaceCreated(GL10 gl10, javax.microedition.khronos.egl.EGLConfig eglConfig) {}
            @Override public void onSurfaceChanged(GL10 gl10, int width, int height) {
                surfaceWidth = width;
                surfaceHeight = height;
                if (cursorX == 0f && cursorY == 0f) {
                    cursorX = width / 2f;
                    cursorY = height / 2f;
                }
                synchronized (parsecLock) {
                    if (parsecAlive && parsec != null) parsec.clientSetDimensions(width, height);
                }
                if (trackpadMode) notifyCursor(true);
            }
            @Override public void onDrawFrame(GL10 gl10) {
                synchronized (parsecLock) {
                    if (!parsecAlive || parsec == null) return;
                    parsec.clientPollAudio();
                    parsec.clientGLRenderFrame();
                }
            }
        });
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void shutdown() {
        synchronized (parsecLock) {
            parsecAlive = false;
            parsec = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);
        if (!parsecAlive) return true;

        // External mouse / trackpad input (Bluetooth, USB, DeX, ChromeOS) —
        // route to a dedicated handler so right-click + middle-click +
        // wheel land on the right host buttons.
        if (isMouseSource(ev)) return handleMouseTouchEvent(ev);

        // Zoom mode is opt-in via the FAB. When it's OFF, every touch flows
        // straight to the regular trackpad / direct handler — pinches become
        // scroll/touch events on the host, matching how a real precision
        // touchpad behaves.
        if (zoomEnabled) {
            scaleDetector.onTouchEvent(ev);

            int pointers = ev.getPointerCount();
            int action = ev.getActionMasked();

            if (action == MotionEvent.ACTION_POINTER_DOWN && pointers == 2) {
                // While zoom mode is active, the 2-finger drag pans the
                // surface unconditionally (matches RustDesk). At 1× the pan
                // is clamped to (0,0) so it's a no-op; the user has to pinch
                // first.
                panning = true;
                panLastCx = centroidX(ev);
                panLastCy = centroidY(ev);
            }
            if (action == MotionEvent.ACTION_UP) {
                panning = false;
            }

            if (panning && pointers >= 2 && action == MotionEvent.ACTION_MOVE
                    && !pinching) {
                float cx = centroidX(ev);
                float cy = centroidY(ev);
                panX += cx - panLastCx;
                panY += cy - panLastCy;
                panLastCx = cx;
                panLastCy = cy;
                clampPanAndApply();
                return true;
            }
            if (pinching || panning) return true;
        }

        if (trackpadMode) return onTrackpadEvent(ev);
        return onDirectTouchEvent(ev);
    }

    private float panLastCx = 0f, panLastCy = 0f;

    /** Last-seen mouse button bitmask (BUTTON_PRIMARY, BUTTON_SECONDARY, ...).
     *  We diff against the new state on each event so left→right transitions
     *  generate one release + one press, never a stuck button. */
    private int lastMouseButtons = 0;

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (!parsecAlive) return false;
        if (!isMouseSource(ev)) return super.onGenericMotionEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_EXIT) {
            // Hover with no buttons → absolute cursor motion.
            sendAbsoluteMotionMapped(ev.getX(), ev.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_SCROLL) {
            float vs = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hs = ev.getAxisValue(MotionEvent.AXIS_HSCROLL);
            // Parsec wheel y is "positive = scroll down", Android VSCROLL is
            // "positive = away from user = scroll up", so we invert.
            int ticksY = Math.round(-vs);
            int ticksX = Math.round(hs);
            if (ticksY != 0 || ticksX != 0) {
                sendWheel(scaledTick(ticksX), scaledTick(ticksY));
            }
            return true;
        }
        return super.onGenericMotionEvent(ev);
    }

    /** Handle a SOURCE_MOUSE touch event: position update + edge-triggered
     *  button press/release. */
    private boolean handleMouseTouchEvent(MotionEvent ev) {
        // Position update — always (mouse moves while held = drag).
        sendAbsoluteMotionMapped(ev.getX(), ev.getY());

        int now = ev.getButtonState();
        int changed = now ^ lastMouseButtons;
        if (changed != 0) {
            dispatchMouseButton(changed, now, MotionEvent.BUTTON_PRIMARY,
                    1 /* MOUSE_L */);
            dispatchMouseButton(changed, now, MotionEvent.BUTTON_SECONDARY,
                    3 /* MOUSE_R */);
            dispatchMouseButton(changed, now, MotionEvent.BUTTON_TERTIARY,
                    2 /* MOUSE_MIDDLE */);
            lastMouseButtons = now;
        }
        return true;
    }

    private void dispatchMouseButton(int changed, int now, int androidMask,
                                     int parsecButton) {
        if ((changed & androidMask) == 0) return;
        boolean pressed = (now & androidMask) != 0;
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) {
                parsec.clientSendMouseButton(parsecButton, pressed);
            }
        }
    }

    private static boolean isMouseSource(MotionEvent ev) {
        int s = ev.getSource();
        return (s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            || (s & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private boolean onDirectTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int pointers = ev.getPointerCount();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = ev.getX();
                float y = ev.getY();
                cursorX = x; cursorY = y;
                sendAbsoluteMotionMapped(x, y);
                sendButton(true);
                directLeftHeld = true;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger joined — enter two-finger scroll mode. Release
                // the left button so we don't drag-select while scrolling.
                if (directLeftHeld) {
                    sendButton(false);
                    directLeftHeld = false;
                }
                beginTwoFingerScroll(ev);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (twoFingerScroll && pointers >= 2) {
                    updateTwoFingerScroll(ev);
                } else if (!twoFingerScroll) {
                    float x = ev.getX();
                    float y = ev.getY();
                    cursorX = x; cursorY = y;
                    sendAbsoluteMotionMapped(x, y);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                // Stay in scroll mode until the LAST finger lifts — bouncing
                // back to drag mid-gesture causes spurious clicks.
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (twoFingerScroll) {
                    if (action == MotionEvent.ACTION_UP) maybeFireTwoFingerTap(ev);
                    twoFingerScroll = false;
                } else if (directLeftHeld) {
                    sendButton(false);
                    directLeftHeld = false;
                }
                return true;
            }
        }
        return true;
    }

    private boolean onTrackpadEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int pointers = ev.getPointerCount();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                lastX = ev.getX();
                lastY = ev.getY();
                downX = lastX;
                downY = lastY;
                downTime = ev.getEventTime();
                notifyCursor(true);

                // Tap-tap-hold drag: if this DOWN follows a quick recent tap-up
                // near the same spot, engage left-button drag for this gesture.
                long sinceLastTap = downTime - lastTapUpMs;
                float distFromLastTap = Math.abs(downX - lastTapUpX) + Math.abs(downY - lastTapUpY);
                if (lastTapUpMs > 0
                        && sinceLastTap <= TAP_TAP_HOLD_WINDOW_MS
                        && distFromLastTap <= TAP_TAP_HOLD_SLOP_PX
                        && !externalButtonHeld) {
                    tapTapHoldDragActive = true;
                    sendButton(true);
                }
                // Consume the trigger either way so a slow third tap doesn't
                // re-engage drag.
                lastTapUpMs = 0L;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger → two-finger scroll. If a tap-tap-hold drag was
                // in progress, release the left button so we don't drag-select.
                if (tapTapHoldDragActive) {
                    sendButton(false);
                    tapTapHoldDragActive = false;
                }
                beginTwoFingerScroll(ev);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (twoFingerScroll && pointers >= 2) {
                    updateTwoFingerScroll(ev);
                    return true;
                }
                float dx = ev.getX() - lastX;
                float dy = ev.getY() - lastY;
                lastX = ev.getX();
                lastY = ev.getY();
                if (scrollMode) {
                    // Middle-button scroll mode: vertical drag → wheel ticks.
                    scrollAccum += dy;
                    int ticks = (int) (scrollAccum / SCROLL_PX_PER_TICK);
                    if (ticks != 0) {
                        scrollAccum -= ticks * SCROLL_PX_PER_TICK;
                        // Parsec wheel: positive y = scroll down (SDK header).
                        sendWheel(0, scaledTick(ticks));
                    }
                } else {
                    cursorX = clamp(cursorX + dx * sensitivity, 0, surfaceWidth - 1);
                    cursorY = clamp(cursorY + dy * sensitivity, 0, surfaceHeight - 1);
                    sendAbsoluteMotionMapped(cursorX, cursorY);
                    notifyCursor(true);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP: {
                if (twoFingerScroll) {
                    maybeFireTwoFingerTap(ev);
                    twoFingerScroll = false;
                    return true;
                }
                if (tapTapHoldDragActive) {
                    sendButton(false);
                    tapTapHoldDragActive = false;
                    return true;
                }
                long dt = ev.getEventTime() - downTime;
                float totalDx = ev.getX() - downX;
                float totalDy = ev.getY() - downY;
                boolean moved = (totalDx * totalDx + totalDy * totalDy) > TAP_SLOP_PX * TAP_SLOP_PX;
                // Tap-to-click is suppressed while an external mouse button is held
                // (the user is mid-click-drag) and while in scroll mode.
                if (!moved && dt <= TAP_MAX_MS && !externalButtonHeld && !scrollMode) {
                    sendButton(true);
                    sendButton(false);
                    // Remember this tap so a quick follow-up tap-and-hold can
                    // promote into a click-drag gesture.
                    lastTapUpMs = ev.getEventTime();
                    lastTapUpX = ev.getX();
                    lastTapUpY = ev.getY();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (twoFingerScroll) twoFingerScroll = false;
                if (tapTapHoldDragActive) {
                    sendButton(false);
                    tapTapHoldDragActive = false;
                }
                return true;
        }
        return true;
    }

    private void beginTwoFingerScroll(MotionEvent ev) {
        twoFingerScroll = true;
        twoFingerScrollAccum = 0f;
        twoFingerScrollAccumX = 0f;
        twoFingerScrollFired = false;
        twoFingerLastCentroidY = centroidY(ev);
        twoFingerLastCentroidX = centroidX(ev);
        twoFingerDownTime = ev.getEventTime();
        twoFingerDownCentroidY = twoFingerLastCentroidY;
        twoFingerDownCentroidX = twoFingerLastCentroidX;
    }

    private void updateTwoFingerScroll(MotionEvent ev) {
        float cy = centroidY(ev);
        float cx = centroidX(ev);
        float dy = cy - twoFingerLastCentroidY;
        float dx = cx - twoFingerLastCentroidX;
        twoFingerLastCentroidY = cy;
        twoFingerLastCentroidX = cx;
        twoFingerScrollAccum += dy;
        twoFingerScrollAccumX += dx;
        int ticksY = (int) (twoFingerScrollAccum  / SCROLL_PX_PER_TICK);
        int ticksX = (int) (twoFingerScrollAccumX / SCROLL_PX_PER_TICK);
        if (ticksY != 0 || ticksX != 0) {
            twoFingerScrollAccum  -= ticksY * SCROLL_PX_PER_TICK;
            twoFingerScrollAccumX -= ticksX * SCROLL_PX_PER_TICK;
            // Parsec wheel.x: positive = scroll right; wheel.y: positive = down
            sendWheel(scaledTick(ticksX), scaledTick(ticksY));
            twoFingerScrollFired = true;
        }
    }

    /** Emit a right-click if the just-ended two-finger gesture qualifies as a
     *  tap (short duration, minimal centroid drift, no wheel ticks fired).
     *  Matches Windows precision touchpad / macOS secondary-click. */
    private void maybeFireTwoFingerTap(MotionEvent ev) {
        if (twoFingerScrollFired) return;
        long dt = ev.getEventTime() - twoFingerDownTime;
        if (dt > TWO_FINGER_TAP_MAX_MS) return;
        float totalDx = Math.abs(twoFingerLastCentroidX - twoFingerDownCentroidX);
        float totalDy = Math.abs(twoFingerLastCentroidY - twoFingerDownCentroidY);
        if (totalDx + totalDy > TWO_FINGER_TAP_SLOP_PX) return;
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) {
                parsec.clientSendMouseButton(3 /* MOUSE_R */, true);
                parsec.clientSendMouseButton(3 /* MOUSE_R */, false);
            }
        }
    }

    private float centroidY(MotionEvent ev) {
        int n = ev.getPointerCount();
        if (n <= 0) return 0f;
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += ev.getY(i);
        return sum / n;
    }

    private float centroidX(MotionEvent ev) {
        int n = ev.getPointerCount();
        if (n <= 0) return 0f;
        float sum = 0f;
        for (int i = 0; i < n; i++) sum += ev.getX(i);
        return sum / n;
    }

    private float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Absolute mouse motion in Parsec's SDK is interpreted in the client-window
     * coordinate space configured via ParsecClientSetDimensions — NOT the host's
     * native desktop resolution. Parsec handles the host-side mapping internally,
     * so we just forward the raw client coordinates to get 1:1 behaviour.
     */
    private void sendAbsoluteMotionMapped(float clientX, float clientY) {
        int w = surfaceWidth > 0 ? surfaceWidth : 1;
        int h = surfaceHeight > 0 ? surfaceHeight : 1;
        int ix = (int) clamp(clientX, 0, w - 1);
        int iy = (int) clamp(clientY, 0, h - 1);
        sendAbsoluteMotion(ix, iy);
    }

    private void sendAbsoluteMotion(int x, int y) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseMotion(false, x, y);
        }
    }

    private void sendButton(boolean pressed) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseButton(parsec.MOUSE_L, pressed);
        }
    }

    private void sendWheel(int x, int y) {
        synchronized (parsecLock) {
            if (parsecAlive && parsec != null) parsec.clientSendMouseWheel(x, y);
        }
    }

    private void notifyCursor(final boolean visible) {
        if (trackpadListener == null) return;
        final float x = cursorX;
        final float y = cursorY;
        post(new Runnable() {
            @Override public void run() {
                if (trackpadListener != null) trackpadListener.onTrackpadCursorChanged(x, y, visible);
            }
        });
    }
}
