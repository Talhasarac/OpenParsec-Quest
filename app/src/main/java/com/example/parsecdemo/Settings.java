package com.example.parsecdemo;

import android.content.Context;
import android.content.SharedPreferences;

public final class Settings {
    private static final String PREFS = "openparsec_settings";

    public static final String CURSOR_TOUCHPAD = "touchpad";
    public static final String CURSOR_DIRECT = "direct";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT  = "light";
    public static final String THEME_DARK   = "dark";

    public static final String DECODER_H264 = "h264";
    public static final String DECODER_H265 = "h265";

    public static final String RIGHTCLICK_FIRST = "first";
    public static final String RIGHTCLICK_MIDDLE = "middle";
    public static final String RIGHTCLICK_SECOND = "second";

    public static final int[] RESOLUTIONS_W = { 0, 1280, 1920, 2560, 3840 };
    public static final int[] RESOLUTIONS_H = { 0,  720, 1080, 1440, 2160 };
    public static final String[] RESOLUTION_LABELS = {
            "Match Client", "1280×720", "1920×1080", "2560×1440", "3840×2160" };

    /** Values exposed by the official Parsec client's Bandwidth Limit menu.
     *  Zero is our opt-in-safe "Host Default" sentinel and is never sent. */
    public static final int[] BANDWIDTH_VALUES = {
            0, 3, 5, 7, 10, 15, 20, 25, 30, 35, 40, 45, 50 };
    public static final String[] BANDWIDTH_LABELS = {
            "Host Default", "3 Mbps", "5 Mbps", "7 Mbps", "10 Mbps",
            "15 Mbps", "20 Mbps", "25 Mbps", "30 Mbps", "35 Mbps",
            "40 Mbps", "45 Mbps", "50 Mbps" };

    private final SharedPreferences sp;
    private final boolean quest;

    public Settings(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.sp = app
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.quest = QuestPlatform.isQuest(app);
    }

    public String cursorMode() {
        return sp.getString("cursorMode", quest ? CURSOR_DIRECT : CURSOR_TOUCHPAD);
    }
    public void cursorMode(String v) { sp.edit().putString("cursorMode", v).apply(); }

    public float cursorScale() { return sp.getFloat("cursorScale", 1.0f); }
    public void cursorScale(float v) { sp.edit().putFloat("cursorScale", v).apply(); }

    public float mouseSensitivity() { return sp.getFloat("mouseSensitivity", 1.6f); }
    public void mouseSensitivity(float v) { sp.edit().putFloat("mouseSensitivity", v).apply(); }

    /** Multiplier applied to wheel ticks emitted by the two-finger / middle-button
     *  scroll paths. Default 4 puts the slider in the comfortable middle of
     *  the 1–10 range; users can scale up or down from there. */
    public float scrollSensitivity() { return sp.getFloat("scrollSensitivity", 4.0f); }
    public void scrollSensitivity(float v) { sp.edit().putFloat("scrollSensitivity", v).apply(); }

    public String decoder() {
        String value = sp.getString("decoder", DECODER_H264);
        return DECODER_H265.equals(value) ? DECODER_H265 : DECODER_H264;
    }
    public void decoder(String v) {
        if (DECODER_H264.equals(v) || DECODER_H265.equals(v))
            sp.edit().putString("decoder", v).apply();
    }

    /** Quest defaults to 1080p: sharp enough for a large virtual screen while
     *  staying comfortably inside Quest 2's low-latency H.264 decode budget. */
    public int resolutionIndex() { return sp.getInt("resolutionIndex", quest ? 2 : 0); }
    public void resolutionIndex(int v) { sp.edit().putInt("resolutionIndex", v).apply(); }

    public int preferredFps() { return sp.getInt("preferredFps", 60); }
    public void preferredFps(int v) { sp.edit().putInt("preferredFps", v).apply(); }

    /** Requested Parsec host encoder cap in Mbps, or 0 to leave it unchanged. */
    public int bandwidthMbps() {
        int value = sp.getInt("bandwidthMbps", 0);
        for (int allowed : BANDWIDTH_VALUES) {
            if (allowed == value) return value;
        }
        return 0;
    }
    public void bandwidthMbps(int v) {
        for (int allowed : BANDWIDTH_VALUES) {
            if (allowed == v) {
                sp.edit().putInt("bandwidthMbps", v).apply();
                return;
            }
        }
    }

    /** Ask the host to encode at the requested FPS even when the screen is
     *  mostly static. This is the official Android client's "Constant FPS"
     *  setting and maps to message-11's fullFPS field. */
    public boolean constantFps() { return sp.getBoolean("constantFps", true); }
    public void constantFps(boolean v) {
        sp.edit().putBoolean("constantFps", v).apply();
    }

    /** "Decoder Compatibility" = force software decoding (ParsecClientConfig.decoderSoftware).
     *  For devices whose hardware MediaCodec path is flaky. */
    public boolean decoderCompatibility() { return sp.getBoolean("decoderCompat", false); }
    public void decoderCompatibility(boolean v) { sp.edit().putBoolean("decoderCompat", v).apply(); }

    /** Show the in-session performance stats overlay (latency / FPS HUD). */
    public boolean showStats() { return sp.getBoolean("showStats", false); }
    public void showStats(boolean v) { sp.edit().putBoolean("showStats", v).apply(); }

    // ---- ParsecClientConfig mapping helpers ----
    // A zero resolution selects the measured client panel size. ParsecActivity
    // resolves it immediately before sending the owner video configuration.

    /** Requested fixed host width, or 0 for "Match Client". */
    public int configResolutionX() {
        int idx = resolutionIndex();
        if (idx <= 0 || idx >= RESOLUTIONS_W.length) return 0;
        return RESOLUTIONS_W[idx];
    }

    /** Requested fixed host height, or 0 for "Match Client". */
    public int configResolutionY() {
        int idx = resolutionIndex();
        if (idx <= 0 || idx >= RESOLUTIONS_H.length) return 0;
        return RESOLUTIONS_H[idx];
    }

    /** Requested host encoder frame rate, or 0 for "Auto" (leave unchanged). */
    public int configFrameRate() {
        int fps = preferredFps();
        return fps <= 0 ? 0 : fps;
    }

    public int decoderSoftwareFlag() { return decoderCompatibility() ? 1 : 0; }
    public int decoderH265Flag() { return DECODER_H265.equals(decoder()) ? 1 : 0; }

    public boolean noOverlay() { return sp.getBoolean("noOverlay", false); }
    public void noOverlay(boolean v) { sp.edit().putBoolean("noOverlay", v).apply(); }

    public boolean hideStatusBar() { return sp.getBoolean("hideStatusBar", true); }
    public void hideStatusBar(boolean v) { sp.edit().putBoolean("hideStatusBar", v).apply(); }

    public boolean showKeyboardButton() { return sp.getBoolean("showKeyboardButton", true); }
    public void showKeyboardButton(boolean v) { sp.edit().putBoolean("showKeyboardButton", v).apply(); }

    /** Map Quest Touch face buttons to desktop shortcuts when Horizon exposes
     *  those controllers as Android input devices. Defaults on only for Quest. */
    public boolean questControllerShortcuts() {
        return sp.getBoolean("questControllerShortcuts", quest);
    }
    public void questControllerShortcuts(boolean v) {
        sp.edit().putBoolean("questControllerShortcuts", v).apply();
    }
    public boolean isQuestDevice() { return quest; }

    /** Orientation policy.
     *  - "auto"      : follow device sensor (RustDesk default — both work)
     *  - "landscape" : force sensorLandscape
     *  - "portrait"  : force sensorPortrait
     */
    public static final String ORIENT_AUTO      = "auto";
    public static final String ORIENT_LANDSCAPE = "landscape";
    public static final String ORIENT_PORTRAIT  = "portrait";
    public String orientation() {
        return sp.getString("orientation", quest ? ORIENT_LANDSCAPE : ORIENT_AUTO);
    }
    public void orientation(String v) { sp.edit().putString("orientation", v).apply(); }

    public String rightClickPosition() { return sp.getString("rightClickPosition", RIGHTCLICK_FIRST); }
    public void rightClickPosition(String v) { sp.edit().putString("rightClickPosition", v).apply(); }

    public String themeMode() { return sp.getString("themeMode", THEME_SYSTEM); }
    public void themeMode(String v) { sp.edit().putString("themeMode", v).apply(); }

    /** User-positioned mouse-button row, in pixels relative to the root view's
     *  top-left. -1 means "not set" → use the default bottom-center placement. */
    public float mouseRowX() { return sp.getFloat("mouseRowX", -1f); }
    public float mouseRowY() { return sp.getFloat("mouseRowY", -1f); }
    public void mouseRowPosition(float x, float y) {
        sp.edit().putFloat("mouseRowX", x).putFloat("mouseRowY", y).apply();
    }
    public void resetMouseRowPosition() {
        sp.edit().remove("mouseRowX").remove("mouseRowY").apply();
    }

    /** Persistent session token returned by the Parsec login API. When set,
     *  the app skips the login screen and goes straight to HostListActivity.
     *  Cleared on explicit logout or when the saved token stops working. */
    public String sessionId() { return sp.getString("sessionId", null); }
    public void sessionId(String v) { sp.edit().putString("sessionId", v).apply(); }
    public void clearSession() { sp.edit().remove("sessionId").apply(); }

    /** Optional last-used email so the login form can prefill it. Password is
     *  NEVER persisted — only the session token is. */
    public String lastEmail() { return sp.getString("lastEmail", ""); }
    public void lastEmail(String v) { sp.edit().putString("lastEmail", v).apply(); }

    public boolean isTouchpadMode() { return CURSOR_TOUCHPAD.equals(cursorMode()); }

}
