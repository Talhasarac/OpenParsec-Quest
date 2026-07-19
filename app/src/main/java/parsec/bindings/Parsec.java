package parsec.bindings;

public class Parsec {
    static {
        System.loadLibrary("parsec-bindings");
    }

    // ParsecStatus
    public int PARSEC_OK = 0;

    // ParsecMouseButton
    public int MOUSE_L = 1;
    public int MOUSE_MIDDLE = 2;
    public int MOUSE_R = 3;

    private long parsec; // Parsec *
    private long aaudio; // struct aaudio *

    public native void setLogCallback();
    public native void init();
    public native void destroy();
    /** Connect with explicit client config. decoderSoftware forces software
     *  decode; decoderH265 prefers HEVC with automatic H.264 fallback;
     *  resolutionX/Y request a host resolution (only
     *  honored when this client is the owner's first connection — i.e. you're
     *  streaming your own PC). Pass 0 for resolution to leave it untouched. */
    public native int clientConnect(String sessionID, String peerID,
                                    int decoderSoftware, int decoderH265,
                                    int resolutionX, int resolutionY);
    /** Apply decoder and codec preferences to the active stream. */
    public native int clientSetDecoder(int decoderSoftware, int decoderH265);
    public native void clientPollAudio();
    public native void clientPauseAudio();
    /** Discard audio accumulated while backgrounded, restart output, and
     *  return the number of stale SDK packets removed. */
    public native int clientResumeAudio();
    public native void clientDestroy();
    public native void clientSetDimensions(int x, int y);
    public native void clientGLRenderFrame();
    public native int clientSendMouseMotion(boolean relative, int x, int y);
    public native int clientSendMouseButton(int button, boolean pressed);
    public native int clientSendMouseWheel(int x, int y);
    public native int clientSendKeyboard(int keyCode, int keyMod, boolean pressed);
    public native int clientSendGamepadButton(int gamepadID, int button, boolean pressed);
    public native int clientSendGamepadAxis(int gamepadID, int axis, int value);
    public native int clientSendGamepadUnplug(int gamepadID);
    /** True if the SDK is currently reporting a network failure on the client
     *  side (no host frames, transport down). Used by the activity-level
     *  health watchdog to trigger an auto-reconnect. */
    public native boolean clientHasNetworkFailure();
    /** Returns a packed snapshot of decode + network latency (both * 1000).
     *  When neither value changes for 15s straight, the activity treats it
     *  as a freeze and reconnects even though networkFailure is still false. */
    public native long clientGetFreezeSignal();

    // ---- Stats overlay metrics ----
    public native float clientGetDecodeLatency();   // ms
    public native float clientGetNetworkLatency();  // ms (round-trip)
    public native float clientGetEncodeLatency();   // ms
    public native boolean clientDecoderFellBack();  // true if SW-decode fallback occurred
    public native boolean clientIsH265();            // actual negotiated stream codec

    // ---- Client event pump (call once per render frame) ----
    /** Drain pending client events (cursor mode, rumble, host user-data). */
    public native void clientPollEvents();
    /** True when the host has requested relative (pointer-lock / FPS) mouse mode. */
    public native boolean clientGetCursorRelative();
    /** Packed (motorBig<<8 | motorSmall) if a new rumble is pending, else -1. */
    public native int clientPollRumble();
    /** Latest host user-data (clipboard) text, or null. Clears on read. */
    public native String clientPollClipboard();
    /** Latest host video configuration JSON (message 11), or null. */
    public native String clientPollVideoConfig();
    /** Send a user-defined message to the host. */
    public native int clientSendUserData(int id, String text);

    /** User-data message id used for clipboard interop (best-effort). */
    public static final int CLIPBOARD_MSG_ID = 1;
    /** Request the host's current video configuration. */
    public static final int GET_VIDEO_CONFIG_MSG_ID = 9;
    /** Official Parsec host video-configuration user-data message. */
    public static final int VIDEO_CONFIG_MSG_ID = 11;

    // ParsecGamepadButton
    public static final int GAMEPAD_BUTTON_A          = 0;
    public static final int GAMEPAD_BUTTON_B          = 1;
    public static final int GAMEPAD_BUTTON_X          = 2;
    public static final int GAMEPAD_BUTTON_Y          = 3;
    public static final int GAMEPAD_BUTTON_BACK       = 4;
    public static final int GAMEPAD_BUTTON_GUIDE      = 5;
    public static final int GAMEPAD_BUTTON_START      = 6;
    public static final int GAMEPAD_BUTTON_LSTICK     = 7;
    public static final int GAMEPAD_BUTTON_RSTICK     = 8;
    public static final int GAMEPAD_BUTTON_LSHOULDER  = 9;
    public static final int GAMEPAD_BUTTON_RSHOULDER  = 10;
    public static final int GAMEPAD_BUTTON_DPAD_UP    = 11;
    public static final int GAMEPAD_BUTTON_DPAD_DOWN  = 12;
    public static final int GAMEPAD_BUTTON_DPAD_LEFT  = 13;
    public static final int GAMEPAD_BUTTON_DPAD_RIGHT = 14;

    // ParsecGamepadAxis
    public static final int GAMEPAD_AXIS_LX       = 0;
    public static final int GAMEPAD_AXIS_LY       = 1;
    public static final int GAMEPAD_AXIS_RX       = 2;
    public static final int GAMEPAD_AXIS_RY       = 3;
    public static final int GAMEPAD_AXIS_TRIGGERL = 4;
    public static final int GAMEPAD_AXIS_TRIGGERR = 5;
}
