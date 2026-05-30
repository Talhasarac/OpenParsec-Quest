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
    public native int clientConnect(String sessionID, String peerID);
    public native void clientPollAudio();
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
