package com.example.parsecdemo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import parsec.bindings.Parsec;

public class ParsecActivity extends Activity {
    /** Diagnostic: when true, overlays a visible tile grid. */
    private static final boolean DEBUG_GRID = false;
    /** Stealth tile grid that keeps the SurfaceView routed through the window
     *  compositor (prevents the overlay-Views from being clipped beneath the
     *  GL surface on hardware-overlay-capable devices). Tiles are alpha=0x01,
     *  not visible to the eye but tracked by the compositor. */
    private static final boolean STEALTH_GRID = true;
    private static final int DEBUG_TILE_PX = 30;
    private static final int DEBUG_GAP_PX = 12;

    private Parsec parsec;
    private ClientGLSurface surface;
    private TextView statusView;
    private View cursorView;
    private FrameLayout root;
    private FrameLayout debugGrid;
    private SessionFab fab;
    private ImageButton keyboardButton;
    private EditText keyboardCapture;
    private boolean ignoreCaptureChange = false;
    private MouseButtonRow mouseButtonRow;
    private ImeAccessoryBar imeBar;
    private int imeAccessoryHeightPx;
    private int heldButtonCount = 0; // tracks how many virtual buttons are pressed
    private FrameLayout settingsOverlay;
    private Settings settings;
    private final android.os.Handler questShortcutHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean questCadHoldPending = false;
    private static final long QUEST_CAD_HOLD_MS = 900L;
    private final Runnable questCadHoldAction = () -> {
        if (!questCadHoldPending) return;
        questCadHoldPending = false;
        if (settings == null || !settings.questControllerShortcuts()) return;
        sendCtrlAltDel();
        Toast.makeText(this, "Sent Ctrl+Alt+Delete", Toast.LENGTH_SHORT).show();
    };
    /** When the IME pushes a user-positioned mouse row out of the way, we
     *  stash the original Y here and restore it when the IME closes. Null
     *  while the user-positioned row is in its normal place. */
    private Float mouseRowImeBackupY = null;

    /** Tracks the active gesture for the 4-finger recovery tap. We reset both
     *  the FAB and the mouse-button row when a single tap reaches 4 fingers
     *  down (so users can rescue them if they drift offscreen). */
    private int gestureMaxPointerCount = 0;
    private long gestureStartMs = 0L;
    private static final long FOUR_FINGER_TAP_WINDOW_MS = 500L;

    /** Connection credentials, cached so the user can reconnect from the FAB
     *  menu without bouncing back through the host list. */
    private String connSessionId;
    private String connPeerId;

    /** Virtual on-screen gamepad overlay; null when hidden. */
    private VirtualGamepad virtualGamepad;
    /** Stable id used for the on-screen pad's button/axis messages. */
    private static final int VIRTUAL_GAMEPAD_ID = 1;

    // ----- IO pump: cursor-mode, rumble, clipboard, stats (100ms tick) -----
    private android.os.Handler ioHandler;
    private final Runnable ioPump = this::tickIoPump;
    private static final long IO_PUMP_INTERVAL_MS = 100L;
    private android.os.Vibrator vibrator;
    private android.content.ClipboardManager clipboard;
    /** Stats HUD (latency / FPS), shown when Settings → Show Performance Stats. */
    private TextView statsView;
    private long lastFrameSnapshot = 0L;
    private long lastFpsSampleMs = 0L;
    private int currentFps = 0;
    private long ioTickCount = 0L;
    /** True between message 9 (get video config) and applying message 11. */
    private boolean awaitingHostVideoConfig = false;
    /** Invalidates delayed fallbacks from older configuration requests. */
    private int hostVideoConfigRequestGeneration = 0;
    /** Tracks the last relative-mode value pushed to the surface so we only
     *  toggle the cursor view / mode on an actual change. */
    private boolean lastRelativeMode = false;

    // ----- Auto-reconnect watchdog -----
    /** Looper handler that runs the health check on the main thread. */
    private android.os.Handler healthHandler;
    private final Runnable healthCheck = this::tickHealthCheck;
    /** True while we're in the middle of a reconnect handshake; suppresses
     *  re-entry from the watchdog. */
    private boolean isReconnecting = false;
    /** Consecutive ticks where networkFailure was reported. We require two
     *  in a row to avoid bouncing on a one-off blip. */
    private int consecutiveFailureTicks = 0;
    /** Backoff after a failed reconnect — keeps doubling until success or
     *  cap. Reset to base on success. */
    private long reconnectBackoffMs = 0L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 2500L;
    private static final long INITIAL_CHECK_DELAY_MS   = 5000L;
    private static final long RECONNECT_BACKOFF_BASE_MS = 2000L;
    private static final long RECONNECT_BACKOFF_MAX_MS  = 15000L;
    private static final int  FAILURE_TICKS_TO_RECONNECT = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        applyOrientationFromSettings();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // SOFT_INPUT_ADJUST_RESIZE causes WindowInsets.ime() to dispatch so
        // we can react when the soft keyboard opens.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Allow FAB shadow + cursor view to render at any edge of the viewport
        // (default FrameLayout clips children to its padding box).
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setFitsSystemWindows(false);

        statusView = new TextView(this);
        statusView.setText("Connecting…");
        statusView.setTextColor(getResources().getColor(R.color.opForeground));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(statusView, statusLp);

        buildSessionFab();

        cursorView = makeCursor();
        FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(
                cursorSizePx(), cursorSizePx());
        cursorView.setVisibility(View.GONE);
        root.addView(cursorView, cursorLp);

        buildStatsView();

        vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        setContentView(root);
        applyImmersive(); // re-apply now that root exists so insets are consumed

        // React to layout changes (fold/unfold, rotation, IME). Pushes new dimensions
        // to Parsec so the absolute mouse mapping & rendered resolution stay correct.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            if (surface != null && (w != or - ol || h != ob - ot)) {
                surface.onClientResize(w, h);
            }
        });

        String sessionId = getIntent().getStringExtra(LoginActivity.EXTRA_SESSION_ID);
        String peerId = getIntent().getStringExtra(HostListActivity.EXTRA_PEER_ID);
        if (sessionId == null || peerId == null) {
            statusView.setText("Missing session/peer.");
            return;
        }
        connSessionId = sessionId;
        connPeerId = peerId;

        parsec = new Parsec();
        parsec.setLogCallback();
        parsec.init();
        int e = connectWithConfig(parsec, sessionId, peerId);
        // If the first attempt fails (very common when reconnecting to a host
        // we just disconnected from — Parsec's relay holds the stale session
        // warm on the host for ~1-2s), schedule up to 3 deferred retries with
        // 1500ms spacing. Retries happen off the main thread to avoid ANR.
        if (e != parsec.PARSEC_OK) {
            scheduleConnectRetry(1);
            return;
        }
        if (e == parsec.PARSEC_OK) {
            statusView.setVisibility(View.GONE);
            surface = new ClientGLSurface(getApplicationContext());
            surface.setParsec(parsec);
            applySettingsToSurface();
            surface.setTrackpadListener((x, y, visible) -> {
                if (cursorView == null) return;
                if (!visible) { cursorView.setVisibility(View.GONE); return; }
                int size = cursorView.getWidth();
                if (size == 0) size = cursorSizePx();
                cursorView.setTranslationX(x - size / 2f);
                cursorView.setTranslationY(y - size / 2f);
                cursorView.setVisibility(View.VISIBLE);
            });
            root.addView(surface, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            surface.renderInit();
            buildMouseButtonRow();
            buildKeyboardCapture();
            buildKeyboardButton();
            buildImeAccessoryBar();
            if (DEBUG_GRID) {
                root.post(() -> {
                    debugGrid = DebugGrid.install(this, root, DEBUG_TILE_PX, DEBUG_GAP_PX);
                });
            } else if (STEALTH_GRID) {
                root.post(() -> {
                    // 4 invisible corner anchors keep the SurfaceView routed
                    // through the window compositor so overlay views render on top.
                    debugGrid = DebugGrid.installCornerAnchors(this, root);
                });
            }
            startHealthWatchdog();
            startIoPump();
            scheduleHostVideoConfig();
        } else {
            statusView.setText("clientConnect failed (code " + e + ")");
            Toast.makeText(this, "Connect failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void buildSessionFab() {
        List<SessionFab.Item> items = new ArrayList<>();
        items.add(new SessionFab.Item("Settings", () -> openSettings()));
        // Label reflects the CURRENT mode; tapping toggles to the other mode.
        items.add(new SessionFab.Item(
                settings.isTouchpadMode() ? "Mouse: Touchpad" : "Mouse: Direct",
                () -> {
                    String next = settings.isTouchpadMode()
                            ? Settings.CURSOR_DIRECT : Settings.CURSOR_TOUCHPAD;
                    settings.cursorMode(next);
                    if (surface != null) surface.setTrackpadMode(Settings.CURSOR_TOUCHPAD.equals(next));
                    updateMouseButtonRow();
                    rebuildSessionFab();
                }));
        items.add(new SessionFab.Item("Alt+Tab", this::sendAltTab));
        items.add(new SessionFab.Item("Copy (Ctrl+C)", this::sendCopyShortcut));
        items.add(new SessionFab.Item("Paste (Ctrl+V)", this::sendPasteShortcut));
        // Ctrl+Alt+Del stays in the menu as a reliable fallback even when
        // Horizon exposes Touch controllers only as pointing devices.
        items.add(new SessionFab.Item("Ctrl+Alt+Del", this::sendCtrlAltDel));
        items.add(new SessionFab.Item(
                virtualGamepad != null ? "Hide gamepad" : "Show gamepad",
                this::toggleVirtualGamepad));
        // Zoom is opt-in. When the toggle is OFF, pinch falls through to the
        // regular touch handler so the host receives a normal trackpad-style
        // scroll. Toggle ON to enable view-level pinch/pan without touching
        // the host stream.
        items.add(new SessionFab.Item(
                surface != null && surface.isZoomEnabled() ? "Zoom: On" : "Zoom: Off",
                () -> {
                    if (surface == null) return;
                    surface.setZoomEnabled(!surface.isZoomEnabled());
                    rebuildSessionFab();
                }));
        // Push the phone's clipboard to the host (best-effort: depends on the
        // host honoring the user-data clipboard id).
        items.add(new SessionFab.Item("Paste to host", this::sendClipboardToHost));
        items.add(new SessionFab.Item("Reconnect", this::reconnectSession));
        items.add(new SessionFab.Item("Disconnect", true, this::finish));

        fab = new SessionFab(this, root, items);
        fab.setVisible(!settings.noOverlay());
    }

    private void buildMouseButtonRow() {
        if (mouseButtonRow != null) return;
        mouseButtonRow = new MouseButtonRow(this, new MouseButtonRow.ButtonListener() {
            @Override public void onButton(int parsecButton, boolean pressed) {
                if (surface == null) return;
                surface.sendButtonExternal(parsecButton, pressed);
                heldButtonCount = Math.max(0, heldButtonCount + (pressed ? 1 : -1));
                surface.setExternalButtonHeld(heldButtonCount > 0);
            }
            @Override public void onScrollDelta(int ticksX, int ticksY) {
                if (surface == null) return;
                surface.sendScrollDelta(ticksX, ticksY);
            }
            @Override public void onMiddleClick() {
                if (surface == null) return;
                // Fire a momentary middle-button press+release on the host.
                surface.sendButtonExternal(2 /* MOUSE_MIDDLE */, true);
                surface.sendButtonExternal(2 /* MOUSE_MIDDLE */, false);
            }
            @Override public void onRepositioned(float xPx, float yPx) {
                // User chose this spot — adopt it as the new resting position
                // and forget any IME-temporary backup.
                settings.mouseRowPosition(xPx, yPx);
                mouseRowImeBackupY = null;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(16);
        root.addView(mouseButtonRow, lp);
        // Restore previously-saved drag position once the row has been measured.
        mouseButtonRow.post(this::applyMouseRowSavedPosition);
        updateMouseButtonRow();
    }

    private void applyMouseRowSavedPosition() {
        if (mouseButtonRow == null) return;
        float sx = settings.mouseRowX();
        float sy = settings.mouseRowY();
        if (sx < 0 || sy < 0) return; // never dragged → leave at default bottom-center
        // Clamp to current viewport in case rotation / fold changed the bounds.
        float maxX = Math.max(0, root.getWidth()  - mouseButtonRow.getWidth());
        float maxY = Math.max(0, root.getHeight() - mouseButtonRow.getHeight());
        mouseButtonRow.setX(Math.min(Math.max(0, sx), maxX));
        mouseButtonRow.setY(Math.min(Math.max(0, sy), maxY));
    }

    private void updateMouseButtonRow() {
        if (mouseButtonRow == null) return;
        // Show only in touchpad mode; in direct mode the user taps the screen directly.
        mouseButtonRow.setVisibility(settings.isTouchpadMode() && !settings.noOverlay()
                ? View.VISIBLE : View.GONE);
    }

    private void rebuildSessionFab() {
        if (fab != null) fab.setVisible(false);
        buildSessionFab();
    }

    /** Tiny transparent EditText that captures soft-keyboard input and
     *  translates each typed character into a ParsecKeycode press/release. */
    private void buildKeyboardCapture() {
        if (keyboardCapture != null) return;
        keyboardCapture = new EditText(this);
        // TYPE_TEXT_FLAG_MULTI_LINE makes Enter insert a newline INTO the
        // capture field instead of firing IME_ACTION_DONE (which would close
        // the soft keyboard). The TextWatcher then translates the inserted
        // '\n' into a Parsec ENTER keypress without dismissing the IME.
        keyboardCapture.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyboardCapture.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        keyboardCapture.setBackground(null);
        keyboardCapture.setCursorVisible(false);
        keyboardCapture.setAlpha(0.01f);
        keyboardCapture.setSingleLine(false);
        keyboardCapture.setHorizontallyScrolling(false);
        keyboardCapture.setText(CAPTURE_SENTINEL);
        keyboardCapture.setSelection(1);

        keyboardCapture.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreCaptureChange) return;
                // Inserted characters
                if (count > before) {
                    for (int i = start + before; i < start + count; i++) {
                        sendCharToHost(s.charAt(i));
                    }
                } else if (count < before) {
                    int dels = before - count;
                    for (int i = 0; i < dels; i++) sendKeyToHost(KeyMap.KEY_BACKSPACE, false);
                }
            }
            @Override public void afterTextChanged(Editable s) {
                if (ignoreCaptureChange) return;
                // Reset to a single sentinel so we can keep detecting backspaces
                // even when the visible text would otherwise be empty. Skip if
                // the text already IS the sentinel (calling setText(" ") on a
                // " "-valued field still re-enters afterTextChanged via some
                // IMEs and would recurse infinitely).
                if (s.length() == 1 && s.charAt(0) == CAPTURE_SENTINEL.charAt(0)) return;
                ignoreCaptureChange = true;
                try {
                    s.replace(0, s.length(), CAPTURE_SENTINEL);
                    keyboardCapture.setSelection(1);
                } finally {
                    ignoreCaptureChange = false;
                }
            }
        });

        keyboardCapture.setOnKeyListener((v, keyCode, event) -> {
            int pk = KeyMap.fromAndroidKey(keyCode);
            if (pk == 0) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                sendKey(pk, true);
                sendKey(pk, false);
                return true;
            }
            return false;
        });

        // 8x8 px is too small for the IME to accept focus on some devices.
        // Keep it tiny but big enough to be a valid input target.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(40),
                Gravity.TOP | Gravity.START);
        lp.leftMargin = -dp(48); // offscreen so it isn't visible to the user
        root.addView(keyboardCapture, lp);
    }

    private static final int PK_LCTRL = 224;
    private static final int PK_LSHIFT = 225;
    private static final int PK_LALT = 226;
    private static final int PK_LGUI = 227;

    /** Non-typeable Private Use Area character used as a placeholder in the
     *  hidden keyboardCapture EditText. Lets us detect backspaces even when
     *  the user has cleared the field, without colliding with any character
     *  a real keyboard can produce (including spacebar — previously this was
     *  ' ' which silently ate every space typed). */
    private static final String CAPTURE_SENTINEL = "";

    /** Send a key press+release surrounded by modifier press/release pairs from
     *  the accessory bar plus an optional implicit shift. One-shot modifiers
     *  are cleared via {@link ImeAccessoryBar#consumeArmedModifiers()}. */
    private void sendWithAccessoryModifiers(int parsecKey, boolean implicitShift) {
        int mods = imeBar != null ? imeBar.currentModifiers() : 0;
        boolean shift = implicitShift || (mods & ImeAccessoryBar.MOD_SHIFT) != 0;
        boolean ctrl  = (mods & ImeAccessoryBar.MOD_CTRL) != 0;
        boolean alt   = (mods & ImeAccessoryBar.MOD_ALT)  != 0;
        boolean meta  = (mods & ImeAccessoryBar.MOD_META) != 0;

        if (ctrl)  sendKey(PK_LCTRL, true);
        if (alt)   sendKey(PK_LALT,  true);
        if (meta)  sendKey(PK_LGUI,  true);
        if (shift) sendKey(PK_LSHIFT, true);
        sendKey(parsecKey, true);
        sendKey(parsecKey, false);
        if (shift) sendKey(PK_LSHIFT, false);
        if (meta)  sendKey(PK_LGUI,  false);
        if (alt)   sendKey(PK_LALT,  false);
        if (ctrl)  sendKey(PK_LCTRL, false);

        if (imeBar != null) imeBar.consumeArmedModifiers();
    }

    private void sendCharToHost(char ch) {
        if (ch == CAPTURE_SENTINEL.charAt(0)) {
            // Sentinel char — used to detect backspaces; don't forward to host.
            return;
        }
        int pk = KeyMap.fromChar(ch);
        if (pk == 0) return;
        sendWithAccessoryModifiers(pk, KeyMap.needsShift(ch));
    }

    private void sendKeyToHost(int parsecKey, boolean withShift) {
        sendWithAccessoryModifiers(parsecKey, withShift);
    }

    private void tickHealthCheck() {
        if (isFinishing() || isDestroyed()) return;
        if (isReconnecting || parsec == null) {
            scheduleNextHealthCheck();
            return;
        }
        boolean failure;
        try {
            failure = parsec.clientHasNetworkFailure();
        } catch (Throwable t) {
            failure = true;
        }
        if (failure) {
            consecutiveFailureTicks++;
            if (consecutiveFailureTicks >= FAILURE_TICKS_TO_RECONNECT) {
                Log.d("ParsecHealth", "auto-reconnect triggered after "
                        + consecutiveFailureTicks + " failure ticks");
                consecutiveFailureTicks = 0;
                reconnectSession();
                return; // reconnectSession reschedules
            }
        } else {
            // Healthy — reset both the streak and the backoff.
            consecutiveFailureTicks = 0;
            reconnectBackoffMs = 0L;
        }
        scheduleNextHealthCheck();
    }

    private void scheduleNextHealthCheck() {
        if (healthHandler == null) return;
        healthHandler.removeCallbacks(healthCheck);
        healthHandler.postDelayed(healthCheck, HEALTH_CHECK_INTERVAL_MS);
    }

    private void startHealthWatchdog() {
        if (healthHandler == null) healthHandler = new android.os.Handler(getMainLooper());
        healthHandler.removeCallbacks(healthCheck);
        healthHandler.postDelayed(healthCheck, INITIAL_CHECK_DELAY_MS);
        // Reset freeze-detection state on each (re)start so a clean session
        // can't inherit a stale signal from before.
        lastFreezeSignal = 0L;
        freezeUnchangedSamples = 0;
        healthHandler.removeCallbacks(freezeCheck);
        healthHandler.postDelayed(freezeCheck, FREEZE_CHECK_INTERVAL_MS);
    }

    private void stopHealthWatchdog() {
        if (healthHandler != null) {
            healthHandler.removeCallbacks(healthCheck);
            healthHandler.removeCallbacks(freezeCheck);
        }
    }

    // ===================== IO pump (cursor/rumble/clipboard/stats) =========

    private void startIoPump() {
        if (ioHandler == null) ioHandler = new android.os.Handler(getMainLooper());
        ioHandler.removeCallbacks(ioPump);
        ioTickCount = 0L;
        lastFpsSampleMs = 0L;
        syncStatsHud();
        ioHandler.post(ioPump);
    }

    private void stopIoPump() {
        if (ioHandler != null) ioHandler.removeCallbacks(ioPump);
        // Drop relative mode so a paused/ended session doesn't leave the cursor hidden.
        if (surface != null && lastRelativeMode) {
            surface.setRelativeMouseMode(false);
            lastRelativeMode = false;
        }
    }

    /** Fires every 100ms while connected. Consumes the C-side event state the
     *  GL render thread accumulates (cursor pointer-lock mode, rumble, host
     *  clipboard) and refreshes the stats HUD. */
    private void tickIoPump() {
        if (isFinishing() || isDestroyed() || parsec == null) return;
        ioTickCount++;

        // --- Relative (pointer-lock) cursor mode ---
        boolean rel;
        try { rel = parsec.clientGetCursorRelative(); }
        catch (Throwable t) { rel = false; }
        if (rel != lastRelativeMode) {
            lastRelativeMode = rel;
            if (surface != null) surface.setRelativeMouseMode(rel);
            // In pointer-lock the host draws its own cursor; hide ours.
            if (rel && cursorView != null) cursorView.setVisibility(View.GONE);
        }

        // --- Controller rumble (host -> client) ---
        try {
            int r = parsec.clientPollRumble();
            if (r >= 0) {
                int big = (r >> 8) & 0xFF;
                int small = r & 0xFF;
                triggerRumble(big, small);
            }
        } catch (Throwable ignored) {}

        // --- Host clipboard -> Android clipboard (experimental) ---
        try {
            String clip = parsec.clientPollClipboard();
            if (clip != null && !clip.isEmpty() && clipboard != null) {
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("Parsec", clip));
            }
        } catch (Throwable ignored) {}

        // --- Host video configuration response (message 11) ---
        try {
            String videoConfig = parsec.clientPollVideoConfig();
            if (videoConfig != null && awaitingHostVideoConfig) {
                awaitingHostVideoConfig = false;
                applySettingsToHostVideoConfig(videoConfig);
            }
        } catch (Throwable t) {
            Log.w("ParsecVideoConfig", "Could not process host video config", t);
        }

        // --- Stats HUD (refresh ~2x/sec) ---
        if (statsView != null && statsView.getVisibility() == View.VISIBLE
                && (ioTickCount % 5 == 0)) {
            updateStatsHud();
        }

        if (ioHandler != null) ioHandler.postDelayed(ioPump, IO_PUMP_INTERVAL_MS);
    }

    /** Vibrate to mirror a gamepad rumble. Scales the larger of the two motor
     *  values into a short device vibration. */
    private void triggerRumble(int big, int small) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int amp = Math.max(big, small);
        if (amp <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        60, Math.max(1, Math.min(255, amp))));
            } else {
                vibrator.vibrate(60);
            }
        } catch (Throwable ignored) {}
    }

    private void buildStatsView() {
        statsView = new TextView(this);
        statsView.setTextColor(0xFFB9F6CA);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statsView.setTypeface(Typeface.MONOSPACE);
        statsView.setBackgroundColor(0x99000000);
        statsView.setPadding(dp(8), dp(4), dp(8), dp(4));
        statsView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.topMargin = dp(8);
        lp.leftMargin = dp(8);
        root.addView(statsView, lp);
    }

    /** Show/hide the stats HUD per the current setting. Called on connect and
     *  when the settings panel closes. */
    private void syncStatsHud() {
        if (statsView == null) return;
        statsView.setVisibility(settings.showStats() ? View.VISIBLE : View.GONE);
    }

    private void updateStatsHud() {
        if (parsec == null || statsView == null) return;
        // FPS from the surface frame counter over the elapsed wall time.
        long now = android.os.SystemClock.uptimeMillis();
        if (surface != null) {
            long frames = surface.framesRenderedSnapshot();
            if (lastFpsSampleMs != 0) {
                long dtMs = now - lastFpsSampleMs;
                if (dtMs > 0) currentFps = (int) Math.round((frames - lastFrameSnapshot) * 1000.0 / dtMs);
            }
            lastFrameSnapshot = frames;
            lastFpsSampleMs = now;
        }
        float dec, net, enc;
        boolean fellBack, h265;
        try {
            dec = parsec.clientGetDecodeLatency();
            net = parsec.clientGetNetworkLatency();
            enc = parsec.clientGetEncodeLatency();
            fellBack = parsec.clientDecoderFellBack();
            h265 = parsec.clientIsH265();
        } catch (Throwable t) { return; }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US,
                "%d fps  %s  ping %.0fms\ndec %.1fms  enc %.1fms",
                currentFps, h265 ? "H.265" : "H.264", net, dec, enc));
        if (fellBack) sb.append("  [SW decode]");
        // Inline warnings — colorize red when degraded.
        boolean warn = net > 80f || dec > 30f || fellBack;
        statsView.setTextColor(warn ? 0xFFFF8A80 : 0xFFB9F6CA);
        if (net > 120f) sb.append("\n⚠ high latency");
        statsView.setText(sb.toString());
    }

    /** Sidecar watchdog that fires every {@link #FREEZE_CHECK_INTERVAL_MS}
     *  ms and looks at the SDK's reported decode + network latency. If
     *  neither value changes for {@link #FREEZE_TICKS_TO_RECONNECT}
     *  consecutive samples the client is treated as frozen and we force a
     *  reconnect. Complements the existing networkFailure watchdog —
     *  catches "transport is alive but no fresh frames" hangs that the
     *  hard-failure flag doesn't trip on. */
    private long lastFreezeSignal = 0L;
    private int freezeUnchangedSamples = 0;
    private static final long FREEZE_CHECK_INTERVAL_MS = 5000L;
    private static final int FREEZE_TICKS_TO_RECONNECT = 3; // 3 × 5s = 15s
    private final Runnable freezeCheck = this::tickFreezeCheck;

    private void tickFreezeCheck() {
        if (isFinishing() || isDestroyed()) return;
        if (isReconnecting || parsec == null) {
            scheduleNextFreezeCheck();
            return;
        }
        long signal;
        try { signal = parsec.clientGetFreezeSignal(); }
        catch (Throwable t) { signal = 0L; }
        if (signal != 0L && signal == lastFreezeSignal) {
            freezeUnchangedSamples++;
            if (freezeUnchangedSamples >= FREEZE_TICKS_TO_RECONNECT) {
                Log.d("ParsecHealth", "freeze watchdog tripped — signal stuck at 0x"
                        + Long.toHexString(signal) + " for "
                        + (FREEZE_TICKS_TO_RECONNECT * FREEZE_CHECK_INTERVAL_MS / 1000) + "s");
                freezeUnchangedSamples = 0;
                reconnectSession();
                return; // reconnectSession will reschedule
            }
        } else {
            freezeUnchangedSamples = 0;
            lastFreezeSignal = signal;
        }
        scheduleNextFreezeCheck();
    }

    private void scheduleNextFreezeCheck() {
        if (healthHandler == null) return;
        healthHandler.removeCallbacks(freezeCheck);
        healthHandler.postDelayed(freezeCheck, FREEZE_CHECK_INTERVAL_MS);
    }

    /** Deferred connect retry — same Parsec instance, schedule another
     *  clientConnect on a worker thread after a delay so a stale-session
     *  linger on the host clears before we try again. {@code attempt} is
     *  1-based for the retry (1 = first retry, 2 = second, etc.). */
    private void scheduleConnectRetry(final int attempt) {
        if (attempt > 3) {
            statusView.setText("Connect failed after retries.");
            Toast.makeText(this, "Couldn't reach host. Tap a host again to retry.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        statusView.setText("Connecting… (retry " + attempt + ")");
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed() || parsec == null) return;
            new Thread(() -> {
                final int rc = connectWithConfig(parsec, connSessionId, connPeerId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (rc == parsec.PARSEC_OK) {
                        setupAfterConnect();
                    } else {
                        Log.d("ParsecConnect", "retry " + attempt
                                + " rc=" + rc + " — scheduling next");
                        scheduleConnectRetry(attempt + 1);
                    }
                });
            }, "ParsecConnectRetry").start();
        }, 1500L);
    }

    /** Builds the surface + overlays after a successful clientConnect.
     *  Extracted so both the initial path in {@code onCreate} and the
     *  deferred retry path can share it. */
    private void setupAfterConnect() {
        statusView.setVisibility(View.GONE);
        surface = new ClientGLSurface(getApplicationContext());
        surface.setParsec(parsec);
        applySettingsToSurface();
        surface.setTrackpadListener((x, y, visible) -> {
            if (cursorView == null) return;
            if (!visible) { cursorView.setVisibility(View.GONE); return; }
            int size = cursorView.getWidth();
            if (size == 0) size = cursorSizePx();
            cursorView.setTranslationX(x - size / 2f);
            cursorView.setTranslationY(y - size / 2f);
            cursorView.setVisibility(View.VISIBLE);
        });
        root.addView(surface, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.renderInit();
        buildMouseButtonRow();
        buildKeyboardCapture();
        buildKeyboardButton();
        buildImeAccessoryBar();
        if (STEALTH_GRID) {
            root.post(() -> debugGrid = DebugGrid.installCornerAnchors(this, root));
        }
        startHealthWatchdog();
        startIoPump();
        scheduleHostVideoConfig();
    }

    /** Tear down the current Parsec session and reconnect using the cached
     *  sessionId / peerId. Lets the user recover from "session died while
     *  backgrounded / network dropped" without going back to the host list. */
    private void reconnectSession() {
        if (connSessionId == null || connPeerId == null) {
            Toast.makeText(this, "No saved connection to reconnect.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isReconnecting) return;
        isReconnecting = true;
        statusView.setText("Reconnecting…");
        statusView.setVisibility(View.VISIBLE);

        // KEEP THE GL SURFACE ALIVE across reconnects. Tearing it down and
        // recreating it forces a new EGL context, and the Parsec SDK's cached
        // GL handles from the previous context become invalid → segfault.
        // We just detach the dead Parsec instance from the surface, dispose
        // it on a worker, then hand the freshly-connected Parsec back to the
        // same surface.
        if (surface != null) surface.setParsec(null);
        final Parsec dying = parsec;
        parsec = null;
        heldButtonCount = 0;
        if (imeBar != null) imeBar.clearLatchedModifiers();

        new Thread(() -> {
            if (dying != null) {
                try { dying.clientDestroy(); } catch (Throwable ignored) {}
                try { dying.destroy(); } catch (Throwable ignored) {}
            }
            final Parsec p = new Parsec();
            p.setLogCallback();
            p.init();
            final int rc = connectWithConfig(p, connSessionId, connPeerId);
            runOnUiThread(() -> {
                isReconnecting = false;
                if (rc != 0) {
                    // Failed — bump backoff and schedule another attempt.
                    long next = Math.max(reconnectBackoffMs * 2,
                            RECONNECT_BACKOFF_BASE_MS);
                    reconnectBackoffMs = Math.min(next, RECONNECT_BACKOFF_MAX_MS);
                    statusView.setText("Reconnect failed (code " + rc + ") — retrying in "
                            + (reconnectBackoffMs / 1000) + "s");
                    try { p.clientDestroy(); } catch (Throwable ignored) {}
                    try { p.destroy(); } catch (Throwable ignored) {}
                    if (healthHandler != null) {
                        healthHandler.removeCallbacks(healthCheck);
                        healthHandler.postDelayed(this::reconnectSession, reconnectBackoffMs);
                    }
                    return;
                }
                // Success — attach the new Parsec to the existing GL surface
                // and push current dimensions so the host frame fits the
                // viewport without waiting for an onSurfaceChanged.
                reconnectBackoffMs = 0L;
                consecutiveFailureTicks = 0;
                parsec = p;
                if (surface == null) {
                    // Defensive: rebuild the surface only if it's truly gone
                    // (e.g. shutdown raced an early activity teardown).
                    surface = new ClientGLSurface(getApplicationContext());
                    surface.setTrackpadListener((x, y, visible) -> {
                        if (cursorView == null) return;
                        if (!visible) { cursorView.setVisibility(View.GONE); return; }
                        int size = cursorView.getWidth();
                        if (size == 0) size = cursorSizePx();
                        cursorView.setTranslationX(x - size / 2f);
                        cursorView.setTranslationY(y - size / 2f);
                        cursorView.setVisibility(View.VISIBLE);
                    });
                    root.addView(surface, 0, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    surface.renderInit();
                }
                surface.setParsec(parsec);
                applySettingsToSurface();
                int w = root.getWidth();
                int h = root.getHeight();
                if (w > 0 && h > 0) parsec.clientSetDimensions(w, h);
                statusView.setVisibility(View.GONE);
                scheduleNextHealthCheck();
                startIoPump();
                scheduleHostVideoConfig();
            });
        }, "ParsecReconnect").start();
    }

    /** Toggle the on-screen virtual gamepad overlay. Floats above the GL
     *  surface in landscape and emits gamepad messages on the dedicated
     *  virtual-pad id so it doesn't collide with a connected physical pad. */
    private void toggleVirtualGamepad() {
        if (virtualGamepad != null) {
            // Send a final unplug so the host doesn't hold stale state.
            if (parsec != null) {
                try { parsec.clientSendGamepadUnplug(VIRTUAL_GAMEPAD_ID); }
                catch (Throwable ignored) {}
            }
            root.removeView(virtualGamepad);
            virtualGamepad = null;
            rebuildSessionFab();
            return;
        }
        virtualGamepad = new VirtualGamepad(this, new VirtualGamepad.Listener() {
            @Override public void onButton(int parsecButton, boolean pressed) {
                if (parsec == null) return;
                parsec.clientSendGamepadButton(VIRTUAL_GAMEPAD_ID, parsecButton, pressed);
            }
            @Override public void onAxis(int parsecAxis, int value) {
                if (parsec == null) return;
                parsec.clientSendGamepadAxis(VIRTUAL_GAMEPAD_ID, parsecAxis, value);
            }
        });
        root.addView(virtualGamepad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rebuildSessionFab();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleQuestControllerShortcut(event)) return true;
        // Forward physical gamepad button presses straight to Parsec.
        if (GamepadInputHandler.handleKeyEvent(parsec, event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        // Forward physical gamepad stick / trigger axis updates.
        if (GamepadInputHandler.handleMotionEvent(parsec, ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    /** Send the phone's current clipboard text to the host as Parsec user-data.
     *  Best-effort: whether the host pastes it depends on the host honoring
     *  the reserved clipboard message id. */
    private void sendClipboardToHost() {
        if (parsec == null || clipboard == null) return;
        CharSequence text = null;
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                && clipboard.getPrimaryClip().getItemCount() > 0) {
            text = clipboard.getPrimaryClip().getItemAt(0)
                    .coerceToText(this);
        }
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            parsec.clientSendUserData(Parsec.CLIPBOARD_MSG_ID, text.toString());
            Toast.makeText(this, "Sent clipboard to host.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't send clipboard.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle Touch face buttons only when the input device identifies itself
     * as an Oculus/Meta Touch controller. A paired conventional gamepad is
     * deliberately left on the normal gamepad-forwarding path.
     */
    private boolean handleQuestControllerShortcut(KeyEvent event) {
        if (settings == null || !settings.questControllerShortcuts()
                || !QuestPlatform.isTouchController(event.getDevice())) {
            return false;
        }

        int keyCode = event.getKeyCode();
        boolean isCadButton = keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BACK;
        if (isCadButton) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                questCadHoldPending = true;
                questShortcutHandler.removeCallbacks(questCadHoldAction);
                questShortcutHandler.postDelayed(questCadHoldAction, QUEST_CAD_HOLD_MS);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                cancelQuestCadHold();
            }
            return true;
        }

        Runnable action;
        String label;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                action = this::sendAltTab;
                label = "Alt+Tab";
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                action = this::sendCopyShortcut;
                label = "Copy";
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                action = this::sendPasteShortcut;
                label = "Paste";
                break;
            default:
                return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            action.run();
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
        }
        // Consume both DOWN and UP so the same Touch press is not also
        // forwarded as a host gamepad button.
        return event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP;
    }

    private void cancelQuestCadHold() {
        questCadHoldPending = false;
        questShortcutHandler.removeCallbacks(questCadHoldAction);
    }

    private void sendAltTab() {
        sendChord(PK_LALT, KeyMap.KEY_TAB);
    }

    private void sendCopyShortcut() {
        sendChord(PK_LCTRL, KeyMap.KEY_C);
    }

    private void sendPasteShortcut() {
        sendChord(PK_LCTRL, KeyMap.KEY_V);
    }

    /** Fire the Ctrl+Alt+Del chord as a single sequence. Note: by default
     *  Windows blocks software-injected SAS; to make this work the host needs
     *  {@code host_ctrl_alt_del=1} in its Parsec config. */
    private void sendCtrlAltDel() {
        sendChord(PK_LCTRL, PK_LALT, KeyMap.KEY_DELETE);
    }

    /**
     * Press every key in order and always release all of them in reverse.
     * Releasing in a finally block protects the host from a stuck Ctrl/Alt if
     * JNI throws, the connection drops, or a shortcut is interrupted midway.
     */
    private void sendChord(int... parsecKeys) {
        if (parsec == null || parsecKeys == null || parsecKeys.length == 0) return;
        try {
            for (int key : parsecKeys) sendKey(key, true);
        } catch (Throwable t) {
            Log.w("ParsecKey", "Shortcut send failed", t);
        } finally {
            for (int i = parsecKeys.length - 1; i >= 0; i--) {
                try {
                    sendKey(parsecKeys[i], false);
                } catch (Throwable t) {
                    Log.w("ParsecKey", "Failed to release shortcut key "
                            + parsecKeys[i], t);
                }
            }
        }
    }

    private void sendKey(int parsecKey, boolean pressed) {
        if (parsec != null) {
            int rc = parsec.clientSendKeyboard(parsecKey, 0, pressed);
            Log.d("ParsecKey", "sendKey key=" + parsecKey + " pressed=" + pressed + " rc=" + rc);
        }
    }

    /** Show/hide the soft keyboard. Focuses the hidden capture EditText so the
     *  IME has somewhere to send characters. */
    private void toggleKeyboard() {
        if (keyboardCapture == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        boolean imeVisible = imeBar != null && imeBar.getVisibility() == View.VISIBLE;
        if (imeVisible) {
            imm.hideSoftInputFromWindow(keyboardCapture.getWindowToken(), 0);
        } else {
            keyboardCapture.setFocusable(true);
            keyboardCapture.setFocusableInTouchMode(true);
            keyboardCapture.requestFocus();
            imm.showSoftInput(keyboardCapture, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void buildKeyboardButton() {
        if (keyboardButton != null) return;
        keyboardButton = new ImageButton(this);
        keyboardButton.setImageResource(R.drawable.ic_keyboard);
        keyboardButton.setImageTintList(ColorStateList.valueOf(MaterialUi.color(this,
                com.google.android.material.R.attr.colorOnPrimaryContainer)));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(MaterialUi.color(this,
                com.google.android.material.R.attr.colorPrimaryContainer));
        keyboardButton.setBackground(bg);
        keyboardButton.setOnClickListener(v -> toggleKeyboard());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = dp(16);
        lp.rightMargin = dp(16);
        root.addView(keyboardButton, lp);
        updateKeyboardButton();
    }

    private void updateKeyboardButton() {
        if (keyboardButton == null) return;
        keyboardButton.setVisibility(
                settings.showKeyboardButton() && !settings.noOverlay()
                        ? View.VISIBLE : View.GONE);
    }

    /** Accessory bar that floats above the soft keyboard with Ctrl/Alt/⊞/Shift
     *  modifiers and Esc/Tab/arrows/Home/End/PgUp/PgDn/Ins/Del/F1–F12. */
    private void buildImeAccessoryBar() {
        if (imeBar != null) return;
        imeBar = new ImeAccessoryBar(this, new ImeAccessoryBar.Listener() {
            @Override public void onSpecialKey(int parsecKey) {
                Log.d("ParsecIME", "onSpecialKey parsecKey=" + parsecKey);
                sendWithAccessoryModifiers(parsecKey, false);
            }
            @Override public void onModifierTap(int parsecKey) {
                Log.d("ParsecIME", "onModifierTap parsecKey=" + parsecKey);
                // Single-tap of a modifier fires a momentary press+release
                // (e.g. ⊞ alone opens Start menu).
                sendKey(parsecKey, true);
                sendKey(parsecKey, false);
            }
            @Override public void onModifiersChanged(int modBitmask) { /* state only */ }
        });
        imeBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        root.addView(imeBar, lp);

        // Track the IME's bottom inset using the modern API. The lambda is
        // invoked every time the IME shows/hides so we can position the bar
        // immediately above it and trim the desktop letterbox to share the
        // remaining vertical space with the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int imeBottom = 0;
            int navBottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            Log.d("ParsecIME", "onApplyWindowInsets imeBottom=" + imeBottom
                    + " navBottom=" + navBottom);
            applyCutoutInsets(insets);
            onImeInsetChanged(imeBottom);
            return insets;
        });
        // Request the listener fire now to pick up an already-shown IME
        if (root.isAttachedToWindow()) root.requestApplyInsets();
    }

    /** Estimated height of the IME accessory bar (Ctrl/Alt/⊞/Shift + action row).
     *  Used directly instead of {@code imeBar.getHeight()} because the bar may
     *  not be laid out yet when the first IME-show inset arrives — reading 0
     *  would let the bar overlap the bottom of the desktop surface. */
    private int imeAccessoryHeightEstimatePx() {
        // 36dp pill button + 6dp top padding + 6dp bottom padding = 48dp
        return dp(48);
    }

    private void onImeInsetChanged(int imeBottomPx) {
        boolean imeVisible = imeBottomPx > 0;
        int barH = imeAccessoryHeightEstimatePx();

        // Accessory bar sits directly above the IME.
        if (imeBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) imeBar.getLayoutParams();
            lp.bottomMargin = imeBottomPx;
            lp.height = barH;
            imeBar.setLayoutParams(lp);
            imeBar.setVisibility(imeVisible ? View.VISIBLE : View.GONE);
            imeAccessoryHeightPx = barH;
        }

        // Dynamic viewport: when the keyboard is open, absorb its height into
        // the host's existing letterbox space FIRST before shrinking the
        // surface. So on a foldable showing 16:9 content on a near-square
        // screen, the keyboard slides into the bottom letterbox and the
        // host content keeps its full size.
        if (surface != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (slp != null) {
                int reservedBottom = imeVisible ? imeBottomPx + barH : 0;
                int letterboxBottom = computeBottomLetterboxPx();
                // If the IME + bar fit entirely inside the existing bottom
                // letterbox, leave the surface at full size (host content
                // unchanged). Otherwise shrink the surface so its bottom edge
                // sits exactly at the top of the accessory bar — the host
                // content is then re-fit by Parsec into the smaller surface.
                int targetBottomMargin = (reservedBottom > letterboxBottom)
                        ? reservedBottom : 0;
                if (slp.bottomMargin != targetBottomMargin) {
                    slp.bottomMargin = targetBottomMargin;
                    surface.setLayoutParams(slp);
                }
            }
        }

        // Lift the keyboard FAB above the accessory bar when the keyboard is
        // open.
        int liftBy = imeVisible ? imeBottomPx + barH + dp(8) : dp(16);
        if (keyboardButton != null) {
            FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) keyboardButton.getLayoutParams();
            klp.bottomMargin = liftBy;
            keyboardButton.setLayoutParams(klp);
        }

        // Mouse-button row: two modes.
        //  • Default (never dragged) — adjust its bottomMargin so it rides
        //    above the accessory bar when the IME opens.
        //  • User-positioned (dragged) — if the row would be covered by the
        //    IME, temporarily translate it just above the bar; restore the
        //    user's position when the IME closes.
        if (mouseButtonRow != null) {
            boolean userPositioned =
                    settings.mouseRowX() >= 0 && settings.mouseRowY() >= 0;
            if (!userPositioned) {
                FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
                mlp.bottomMargin = liftBy;
                mouseButtonRow.setLayoutParams(mlp);
            } else if (imeVisible) {
                // The IME (+ accessory bar) occludes from the top of imeBar
                // downwards. If the row's bottom edge crosses that, slide it up.
                int rowH = mouseButtonRow.getHeight();
                int rootH = root.getHeight();
                float occlusionTop = rootH - imeBottomPx - barH;
                float currentY = mouseButtonRow.getY();
                if (currentY + rowH > occlusionTop) {
                    if (mouseRowImeBackupY == null) mouseRowImeBackupY = currentY;
                    float targetY = Math.max(0, occlusionTop - rowH - dp(8));
                    mouseButtonRow.setY(targetY);
                }
            } else if (mouseRowImeBackupY != null) {
                // IME closed — restore the user's drag position.
                mouseButtonRow.setY(mouseRowImeBackupY);
                mouseRowImeBackupY = null;
            }
        }
    }

    /** Compute the current bottom letterbox space (in pixels) inside the
     *  surface, based on the host aspect and the surface dimensions. */
    private int computeBottomLetterboxPx() {
        if (root == null) return 0;
        int sw = root.getWidth();
        int sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return 0;
        int hostW = settings.hostWidth();
        int hostH = settings.hostHeight();
        if (hostW <= 0 || hostH <= 0) return 0;
        // Aspect-fit: host fills width OR height while preserving aspect.
        float hostAspect = (float) hostW / hostH;
        float screenAspect = (float) sw / sh;
        int renderedHeight;
        if (screenAspect > hostAspect) {
            // Screen wider than host → fills full height, side letterbox.
            renderedHeight = sh;
        } else {
            // Screen taller than host → fills full width, top+bottom letterbox.
            renderedHeight = Math.round(sw / hostAspect);
        }
        int totalLetterboxV = Math.max(0, sh - renderedHeight);
        // Symmetric — bottom half of the letterbox is what can absorb the IME.
        return totalLetterboxV / 2;
    }

    /** Cached display-cutout safe insets. Surface + root are deliberately
     *  NOT padded with these — the host frame should render edge-to-edge
     *  underneath any camera punch-out, matching how the host desktop fills
     *  a normal monitor. Only the floating overlay buttons add these to
     *  their own edge margins so they steer clear of the cutout. */
    private int cutoutSafeLeftPx = 0;
    private int cutoutSafeTopPx = 0;
    private int cutoutSafeRightPx = 0;
    private int cutoutSafeBottomPx = 0;

    private void applyCutoutInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        android.view.DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null) {
            cutoutSafeLeftPx = cutoutSafeTopPx = cutoutSafeRightPx = cutoutSafeBottomPx = 0;
        } else {
            cutoutSafeLeftPx   = cutout.getSafeInsetLeft();
            cutoutSafeTopPx    = cutout.getSafeInsetTop();
            cutoutSafeRightPx  = cutout.getSafeInsetRight();
            cutoutSafeBottomPx = cutout.getSafeInsetBottom();
        }
        // Surface fills the full root. Clear any padding a previous build set.
        if (root != null) root.setPadding(0, 0, 0, 0);
        // Re-apply button positions so they pick up the new safe-edge inset.
        applyCutoutToOverlayButtons();
    }

    /** Add the current cutout insets to each overlay button's edge margins so
     *  they stay clear of the punch-out without affecting the GL surface. */
    private void applyCutoutToOverlayButtons() {
        if (keyboardButton != null) {
            FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) keyboardButton.getLayoutParams();
            klp.rightMargin = dp(16) + cutoutSafeRightPx;
            keyboardButton.setLayoutParams(klp);
        }
        // FAB drag bounds and mouse-button row are updated in their own paths;
        // the FAB uses raw setX/setY so it just needs the right viewport size,
        // which the layout listener already provides.
    }

    private View makeCursor() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x80FFFFFF);
        g.setStroke(dp(2), getResources().getColor(R.color.opAccent));
        v.setBackground(g);
        return v;
    }

    private int cursorSizePx() {
        // dp(18) baseline, scaled by user cursorScale setting (default 1.0)
        return Math.max(dp(8), Math.round(dp(18) * settings.cursorScale()));
    }

    /** Apply the user's orientation preference (auto / landscape / portrait)
     *  via setRequestedOrientation. Re-callable from Settings → applies live. */
    private void applyOrientationFromSettings() {
        String mode = settings.orientation();
        int orient;
        switch (mode) {
            case Settings.ORIENT_LANDSCAPE:
                orient = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; break;
            case Settings.ORIENT_PORTRAIT:
                orient = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; break;
            default:
                orient = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; break;
        }
        setRequestedOrientation(orient);
    }

    /** Single choke point for clientConnect so the user's Settings
     *  (codec, software-decode, and requested host resolution) are applied
     *  consistently on the initial connect, retries, and reconnects. */
    private int connectWithConfig(Parsec p, String sessionId, String peerId) {
        return p.clientConnect(sessionId, peerId,
                settings.decoderSoftwareFlag(),
                settings.decoderH265Flag(),
                requestedHostWidth(),
                requestedHostHeight());
    }

    /**
     * Apply the official Parsec host-video configuration message used by the
     * current Android/iOS clients. Message type 11 accepts a JSON object with
     * up to three output records; OpenParsec controls the active first output.
     *
     * A bandwidth value of zero is deliberately a no-op so merely installing
     * this client never overwrites the host's existing encoder cap. The
     * official client's Constant FPS setting maps to fullFPS.
     */
    private void sendFallbackHostVideoConfig() {
        if (parsec == null || settings == null) return;
        int bandwidth = settings.bandwidthMbps();
        // Without the host's current config there is no safe bitrate value to
        // put in a complete fallback record. Constant FPS still applies on
        // current hosts through the normal GET/merge path.
        if (bandwidth <= 0) return;

        try {
            JSONArray video = new JSONArray();
            video.put(hostVideoEntry(
                    requestedHostWidth(),
                    requestedHostHeight(),
                    bandwidth,
                    settings.constantFps(),
                    settings.configFrameRate()));
            // Match the schema used by the official client. Inactive outputs
            // retain neutral defaults and do not select a physical display.
            video.put(hostVideoEntry(0, 0, 50, false, 0));
            video.put(hostVideoEntry(0, 0, 50, false, 0));

            JSONObject config = new JSONObject();
            config.put("virtualMicrophone", 0);
            config.put("virtualTablet", 0);
            config.put("video", video);

            int status = parsec.clientSendUserData(
                    Parsec.VIDEO_CONFIG_MSG_ID, config.toString());
            if (status != parsec.PARSEC_OK) {
                Log.w("ParsecVideoConfig", "Host video config failed: " + status);
            } else {
                Log.i("ParsecVideoConfig", "Requested bandwidth limit "
                        + bandwidth + " Mbps; constant FPS "
                        + settings.constantFps());
            }
        } catch (Throwable t) {
            Log.w("ParsecVideoConfig", "Could not build/send host video config", t);
        }
    }

    /**
     * Preserve the host's current display and encoder fields, changing only
     * the requested owner controls before returning message 11.
     */
    private void applySettingsToHostVideoConfig(String rawConfig) {
        if (parsec == null || settings == null) return;
        int bandwidth = settings.bandwidthMbps();
        int frameRate = settings.configFrameRate();
        int width = requestedHostWidth();
        int height = requestedHostHeight();

        try {
            JSONObject config = new JSONObject(rawConfig);
            JSONArray video = config.optJSONArray("video");
            JSONObject active = video != null && video.length() > 0
                    ? video.optJSONObject(0) : null;
            if (active == null) {
                sendFallbackHostVideoConfig();
                return;
            }
            if (width > 0 && height > 0) {
                active.put("resolutionX", width);
                active.put("resolutionY", height);
            }
            if (bandwidth > 0)
                active.put("encoderMaxBitrate", bandwidth);
            if (frameRate > 0)
                active.put("encoderFPS", frameRate);
            active.put("fullFPS", settings.constantFps());
            int status = parsec.clientSendUserData(
                    Parsec.VIDEO_CONFIG_MSG_ID, config.toString());
            if (status != parsec.PARSEC_OK) {
                Log.w("ParsecVideoConfig", "Merged video config failed: " + status);
            } else {
                Log.i("ParsecVideoConfig", "Applied owner video settings: "
                        + (width > 0 && height > 0
                                ? width + "x" + height + "; " : "host resolution; ")
                        + (bandwidth > 0 ? bandwidth + " Mbps; " : "host bitrate; ")
                        + "constant FPS " + settings.constantFps());
            }
        } catch (Throwable t) {
            Log.w("ParsecVideoConfig", "Invalid host video config; using fallback", t);
            sendFallbackHostVideoConfig();
        }
    }

    /**
     * Resolve "Match Client" to the current Quest panel dimensions. Fixed
     * resolution choices come directly from Settings. Returning zero only
     * happens before Android has measured the panel, in which case the host
     * keeps its current resolution until the delayed config request.
     */
    private int requestedHostWidth() {
        int configured = settings != null ? settings.configResolutionX() : 0;
        if (configured > 0) return configured;
        if (surface != null && surface.getWidth() > 0) return surface.getWidth();
        return root != null ? Math.max(0, root.getWidth()) : 0;
    }

    private int requestedHostHeight() {
        int configured = settings != null ? settings.configResolutionY() : 0;
        if (configured > 0) return configured;
        if (surface != null && surface.getHeight() > 0) return surface.getHeight();
        return root != null ? Math.max(0, root.getHeight()) : 0;
    }

    /** Ask for the current config before applying owner video settings. */
    private void requestHostVideoConfig() {
        if (parsec == null || settings == null) return;

        awaitingHostVideoConfig = true;
        final int generation = ++hostVideoConfigRequestGeneration;
        int status;
        try {
            status = parsec.clientSendUserData(Parsec.GET_VIDEO_CONFIG_MSG_ID, "");
        } catch (Throwable t) {
            status = -1;
        }
        if (status != parsec.PARSEC_OK) {
            awaitingHostVideoConfig = false;
            sendFallbackHostVideoConfig();
            return;
        }

        // Older hosts may accept SET but never answer GET. Do not leave the
        // user's selection inert in that case.
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (generation == hostVideoConfigRequestGeneration
                    && awaitingHostVideoConfig && !isFinishing() && !isDestroyed()) {
                awaitingHostVideoConfig = false;
                sendFallbackHostVideoConfig();
            }
        }, 1000L);
    }

    private static JSONObject hostVideoEntry(
            int width, int height, int bandwidth, boolean constantFps, int frameRate)
            throws org.json.JSONException {
        JSONObject entry = new JSONObject();
        entry.put("encoderFPS", frameRate);
        entry.put("resolutionX", width);
        entry.put("resolutionY", height);
        entry.put("fullFPS", constantFps);
        entry.put("hostOS", 0);
        entry.put("output", "none");
        entry.put("encoderMaxBitrate", bandwidth);
        return entry;
    }

    /** The host-side control channel becomes ready just after clientConnect. */
    private void scheduleHostVideoConfig() {
        if (settings == null) return;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && !isReconnecting) {
                requestHostVideoConfig();
            }
        }, 750L);
    }

    private void applySettingsToSurface() {
        if (surface == null) return;
        surface.setTrackpadMode(settings.isTouchpadMode());
        surface.setSensitivity(settings.mouseSensitivity());
        surface.setScrollSensitivity(settings.scrollSensitivity());
        surface.setHostDimensions(settings.hostWidth(), settings.hostHeight());
        if (cursorView != null) {
            int sz = cursorSizePx();
            ViewGroup.LayoutParams lp = cursorView.getLayoutParams();
            if (lp != null) {
                lp.width = sz; lp.height = sz;
                cursorView.setLayoutParams(lp);
            }
        }
        if (fab != null) fab.setVisible(!settings.noOverlay());
        updateMouseButtonRow();
        updateKeyboardButton();
        // Re-apply orientation in case user changed it from the in-session
        // settings panel.
        applyOrientationFromSettings();
    }

    private void openSettings() {
        if (settingsOverlay != null) return;
        settingsOverlay = SettingsPanel.build(this, settings, this::closeSettings);
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeSettings() {
        if (settingsOverlay != null) {
            root.removeView(settingsOverlay);
            settingsOverlay = null;
            applySettingsToSurface();
            syncStatsHud(); // user may have toggled Show Performance Stats
            if (parsec != null) {
                try {
                    int status = parsec.clientSetDecoder(
                            settings.decoderSoftwareFlag(),
                            settings.decoderH265Flag());
                    if (status != parsec.PARSEC_OK)
                        Log.w("ParsecCodec", "Could not apply decoder setting: " + status);
                } catch (Throwable t) {
                    Log.w("ParsecCodec", "Could not apply decoder setting", t);
                }
            }
            requestHostVideoConfig();
        }
    }

    private void applyImmersive() {
        Window window = getWindow();
        // Edge-to-edge: extend the layout under system bars (does NOT relocate
        // the window — leaves the Material3 NoActionBar theme to handle bounds).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            window.getDecorView();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (root != null) {
            root.setFitsSystemWindows(false);
            // Insets listener intentionally NOT set here: buildImeAccessoryBar
            // installs its own listener that tracks IME state. Re-applying
            // immersive on onResume must not overwrite it.
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 4-finger tap recovery: if any single gesture reaches 4 simultaneous
        // pointers, snap the FAB and mouse-button row back to their default
        // positions so the user can recover from accidentally dragging them
        // offscreen.
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureMaxPointerCount = 1;
                gestureStartMs = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pc = ev.getPointerCount();
                if (pc > gestureMaxPointerCount) gestureMaxPointerCount = pc;
                if (gestureMaxPointerCount >= 4
                        && System.currentTimeMillis() - gestureStartMs < FOUR_FINGER_TAP_WINDOW_MS) {
                    triggerOverlayReset();
                    // Reset only once per gesture
                    gestureMaxPointerCount = 0;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureMaxPointerCount = 0;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Recovery hatch invoked by a 4-finger tap. Snaps overlays back to their
     *  default positions and clears any saved drag location, so a user who
     *  has dragged the mouse row or FAB offscreen can get them back. */
    private void triggerOverlayReset() {
        if (fab != null) fab.resetPosition();
        if (mouseButtonRow != null) {
            settings.resetMouseRowPosition();
            mouseRowImeBackupY = null;
            // Clear drag translation so the LayoutParams gravity takes over again.
            mouseButtonRow.setTranslationX(0);
            mouseButtonRow.setTranslationY(0);
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
            mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mlp.bottomMargin = dp(16);
            mouseButtonRow.setLayoutParams(mlp);
        }
        Toast.makeText(this, "Controls reset", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
        // A fold/unfold can drop touch events mid-gesture and shrinks/expands
        // the viewport, which can leave overlays offscreen and host input
        // state stuck. Re-fit everything once the new layout pass has run.
        root.post(this::recoverFromConfigurationChange);
    }

    /** Run after a fold/unfold (or other config change) once the layout pass
     *  has produced new dimensions. Re-centers the desktop view, releases any
     *  phantom input state, and re-clamps overlays so the user can't lose them
     *  by folding around them. */
    private void recoverFromConfigurationChange() {
        // FIRST: clear any stale layout state (e.g. surface bottomMargin from a
        // pre-fold IME-open scenario) so the surface re-occupies the full root.
        // This must happen BEFORE the GL onSurfaceChanged callback fires, so
        // the host frame is centered against the correct viewport.
        if (surface != null) {
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (slp != null && slp.bottomMargin != 0) {
                slp.bottomMargin = 0;
                surface.setLayoutParams(slp);
            }
            surface.requestLayout();
        }
        // Release any locked/armed modifiers — the host may not have received
        // the matching release events if the gesture was interrupted by fold.
        if (imeBar != null) imeBar.clearLatchedModifiers();
        heldButtonCount = 0;
        // Re-clamp user-positioned mouse-button row so it can't drift offscreen.
        reclampMouseRow();
        // After the next layout pass, reset touch state & cursor against the
        // freshly-laid-out surface dimensions.
        root.post(() -> {
            if (surface != null) surface.resetTouchState();
        });
    }

    /** If the mouse-button row has a saved drag position, clamp it to the
     *  current viewport bounds. Folding from large→small screen can leave the
     *  saved coords entirely offscreen. */
    private void reclampMouseRow() {
        if (mouseButtonRow == null) return;
        float sx = settings.mouseRowX();
        float sy = settings.mouseRowY();
        if (sx < 0 || sy < 0) return; // never dragged
        int rw = root.getWidth();
        int rh = root.getHeight();
        int rowW = mouseButtonRow.getWidth();
        int rowH = mouseButtonRow.getHeight();
        if (rw <= 0 || rh <= 0 || rowW <= 0 || rowH <= 0) return;
        // If the row would be more than half offscreen, snap it back to the
        // default bottom-center placement and forget the saved position.
        float maxX = Math.max(0, rw - rowW);
        float maxY = Math.max(0, rh - rowH);
        boolean wayOff = sx > rw - rowW / 2f || sy > rh - rowH / 2f
                || sx + rowW / 2f < 0 || sy + rowH / 2f < 0;
        if (wayOff) {
            settings.resetMouseRowPosition();
            mouseRowImeBackupY = null;
            mouseButtonRow.setTranslationX(0);
            mouseButtonRow.setTranslationY(0);
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mouseButtonRow.getLayoutParams();
            mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mlp.bottomMargin = dp(16);
            mouseButtonRow.setLayoutParams(mlp);
            return;
        }
        float cx = Math.min(Math.max(0, sx), maxX);
        float cy = Math.min(Math.max(0, sy), maxY);
        if (cx != sx || cy != sy) settings.mouseRowPosition(cx, cy);
        mouseButtonRow.setX(cx);
        mouseButtonRow.setY(cy);
    }

    @Override
    protected void onPause() {
        cancelQuestCadHold();
        if (surface != null) {
            // Stop the GL/audio polling thread first, then flush device audio.
            // This ordering prevents a final poll from refilling the stream.
            surface.onPause();
            surface.pauseAudioForLifecycle();
        }
        // Release any held physical-gamepad inputs so the host doesn't see a
        // stuck button while we're backgrounded.
        GamepadInputHandler.unplug(parsec);
        // Pause the watchdog while backgrounded; checking a dead session over
        // and over while the OS has the network paused is pointless.
        stopHealthWatchdog();
        stopIoPump();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surface != null) {
            // Catch the SDK up to live audio before the render thread begins
            // polling again; otherwise the buffered sleep audio plays late.
            int staleAudioPackets = surface.resumeAudioForLifecycle();
            if (staleAudioPackets > 0) {
                Log.i("ParsecActivity", "Discarded " + staleAudioPackets
                        + " stale audio packets after resume");
            }
            surface.onResume();
        }
        applyImmersive();
        // Resume the watchdog. If we came back from background and the session
        // died, the first health tick (5s after resume) will catch it and
        // auto-reconnect within ~2 ticks.
        if (parsec != null) { startHealthWatchdog(); startIoPump(); }
    }

    @Override
    protected void onDestroy() {
        cancelQuestCadHold();
        stopHealthWatchdog();
        stopIoPump();
        if (surface != null) surface.shutdown();
        if (parsec != null) {
            parsec.clientDestroy();
            parsec.destroy();
            parsec = null;
        }
        super.onDestroy();
    }
}
