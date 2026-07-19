package com.example.parsecdemo;

import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import parsec.bindings.Parsec;

/**
 * Translates Android KeyEvent + MotionEvent from physical/integrated gamepads
 * (Xbox controllers, ROG Phone shoulders, Razer Edge sticks, etc.) into
 * Parsec gamepad messages. Static dispatcher with per-device state — call from
 * Activity.dispatchKeyEvent / dispatchGenericMotionEvent.
 */
public final class GamepadInputHandler {

    /** The on-screen pad owns id 1; physical devices are assigned from 2. */
    private static final int FIRST_PHYSICAL_GAMEPAD_ID = 2;
    private static final SparseArray<DeviceState> DEVICE_STATES = new SparseArray<>();

    private static final class DeviceState {
        final int parsecGamepadId;
        int lastHatX;
        int lastHatY;

        DeviceState(int parsecGamepadId) {
            this.parsecGamepadId = parsecGamepadId;
        }
    }

    private GamepadInputHandler() {}

    /** Returns true if the given input device looks like a gamepad/joystick.
     *  Used to gate dispatch — non-gamepad sources are ignored. */
    public static boolean isGamepadSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Forward a KeyEvent to Parsec. Returns true if the event was consumed. */
    public static boolean handleKeyEvent(Parsec parsec, KeyEvent ev) {
        if (parsec == null) return false;
        if (!isGamepadSource(ev.getSource())) return false;
        InputDevice device = ev.getDevice();
        if (device == null) return false;
        // Touch controllers belong to Horizon pointer input and the
        // Quest-specific shortcut/scroll path. Sending their leftover events
        // as a host gamepad makes a paired Bluetooth controller fight them.
        if (QuestPlatform.isTouchController(device)) return false;
        int parsecBtn = mapKey(ev.getKeyCode());
        if (parsecBtn < 0) return false;
        if (ev.getAction() != KeyEvent.ACTION_DOWN
                && ev.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        DeviceState state = stateFor(ev.getDeviceId(), device);
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            parsec.clientSendGamepadButton(state.parsecGamepadId, parsecBtn, true);
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            parsec.clientSendGamepadButton(state.parsecGamepadId, parsecBtn, false);
            return true;
        }
        return false;
    }

    /** Forward a generic MotionEvent (stick / trigger axis updates) to
     *  Parsec. Returns true if the event was consumed. */
    public static boolean handleMotionEvent(Parsec parsec, MotionEvent ev) {
        if (parsec == null) return false;
        if (!isGamepadSource(ev.getSource())) return false;
        if (ev.getAction() != MotionEvent.ACTION_MOVE) return false;

        InputDevice dev = ev.getDevice();
        if (dev == null) return false;
        if (QuestPlatform.isTouchController(dev)) return false;
        DeviceState state = stateFor(ev.getDeviceId(), dev);
        int gamepadId = state.parsecGamepadId;
        // Left thumbstick
        sendAxis(parsec, gamepadId, dev, ev,
                MotionEvent.AXIS_X, Parsec.GAMEPAD_AXIS_LX, false);
        sendAxis(parsec, gamepadId, dev, ev,
                MotionEvent.AXIS_Y, Parsec.GAMEPAD_AXIS_LY, false);
        // Right thumbstick — Android exposes RX/RY on most controllers, but
        // some (older Xbox, certain phone controllers) use Z/RZ instead.
        // Try RX/RY first, fall back to Z/RZ if those axes don't exist.
        if (hasAxis(dev, MotionEvent.AXIS_RX) || hasAxis(dev, MotionEvent.AXIS_RY)) {
            sendAxis(parsec, gamepadId, dev, ev,
                    MotionEvent.AXIS_RX, Parsec.GAMEPAD_AXIS_RX, false);
            sendAxis(parsec, gamepadId, dev, ev,
                    MotionEvent.AXIS_RY, Parsec.GAMEPAD_AXIS_RY, false);
        } else {
            sendAxis(parsec, gamepadId, dev, ev,
                    MotionEvent.AXIS_Z, Parsec.GAMEPAD_AXIS_RX, false);
            sendAxis(parsec, gamepadId, dev, ev,
                    MotionEvent.AXIS_RZ, Parsec.GAMEPAD_AXIS_RY, false);
        }

        // Triggers — Android may expose them as LTRIGGER/RTRIGGER (0..1) or
        // packed onto BRAKE/GAS. The trigger axes ride a 0..1 range, NOT
        // -1..1 like the sticks, so they get a different normalizer.
        sendTrigger(parsec, gamepadId, dev, ev,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
                Parsec.GAMEPAD_AXIS_TRIGGERL);
        sendTrigger(parsec, gamepadId, dev, ev,
                MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
                Parsec.GAMEPAD_AXIS_TRIGGERR);

        // HAT axes on many controllers represent the DPad. -1/0/+1.
        // Do not manufacture a neutral HAT update for devices that do not
        // expose these axes; that used to release another controller's D-pad.
        if (hasAxis(dev, MotionEvent.AXIS_HAT_X)
                || hasAxis(dev, MotionEvent.AXIS_HAT_Y)) {
            float hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            dispatchHat(parsec, state, hatX, hatY);
        }

        return true;
    }

    /** Disconnect one Android controller without disturbing any other pad. */
    public static void unplugDevice(Parsec parsec, int androidDeviceId) {
        DeviceState state = DEVICE_STATES.get(androidDeviceId);
        if (state == null) return;
        if (parsec != null) {
            try { parsec.clientSendGamepadUnplug(state.parsecGamepadId); }
            catch (Throwable ignored) {}
        }
        DEVICE_STATES.remove(androidDeviceId);
    }

    /** Disconnect every physical pad and clear all per-device input state. */
    public static void unplug(Parsec parsec) {
        for (int i = 0; i < DEVICE_STATES.size(); i++) {
            DeviceState state = DEVICE_STATES.valueAt(i);
            if (parsec != null) {
                try { parsec.clientSendGamepadUnplug(state.parsecGamepadId); }
                catch (Throwable ignored) {}
            }
        }
        DEVICE_STATES.clear();
    }

    // -------------------- internals --------------------

    private static DeviceState stateFor(int androidDeviceId, InputDevice device) {
        DeviceState state = DEVICE_STATES.get(androidDeviceId);
        if (state != null) return state;
        state = new DeviceState(nextAvailableParsecId());
        DEVICE_STATES.put(androidDeviceId, state);
        Log.i("GamepadInput", "Mapped Android device " + androidDeviceId
                + " (" + (device == null ? "unknown" : device.getName())
                + ") to Parsec gamepad " + state.parsecGamepadId);
        return state;
    }

    private static int nextAvailableParsecId() {
        int candidate = FIRST_PHYSICAL_GAMEPAD_ID;
        while (true) {
            boolean used = false;
            for (int i = 0; i < DEVICE_STATES.size(); i++) {
                if (DEVICE_STATES.valueAt(i).parsecGamepadId == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
            candidate++;
        }
    }

    private static boolean hasAxis(InputDevice dev, int axis) {
        if (dev == null) return false;
        InputDevice.MotionRange r = dev.getMotionRange(axis);
        return r != null;
    }

    private static void sendAxis(Parsec parsec, int gamepadId,
                                 InputDevice dev, MotionEvent ev,
                                 int androidAxis, int parsecAxis,
                                 boolean invertY) {
        if (dev == null) return;
        InputDevice.MotionRange r = dev.getMotionRange(androidAxis);
        if (r == null) return;
        float raw = ev.getAxisValue(androidAxis);
        // Apply the device's reported flat (deadzone). Below deadzone → 0.
        float flat = r.getFlat();
        if (Math.abs(raw) < flat) raw = 0f;
        if (invertY) raw = -raw;
        int scaled = Math.round(raw * 32767f);
        if (scaled > 32767) scaled = 32767;
        if (scaled < -32768) scaled = -32768;
        parsec.clientSendGamepadAxis(gamepadId, parsecAxis, scaled);
    }

    private static void sendTrigger(Parsec parsec, int gamepadId,
                                    InputDevice dev, MotionEvent ev,
                                    int primaryAxis, int fallbackAxis, int parsecAxis) {
        if (dev == null) return;
        float raw = 0f;
        InputDevice.MotionRange r = dev.getMotionRange(primaryAxis);
        if (r != null) {
            raw = ev.getAxisValue(primaryAxis);
        } else {
            r = dev.getMotionRange(fallbackAxis);
            if (r != null) raw = ev.getAxisValue(fallbackAxis);
        }
        if (r == null) return;
        float flat = r.getFlat();
        if (raw < flat) raw = 0f;
        // Trigger range is 0..1 → scale to 0..32767 (Parsec triggers ride the
        // positive half of the int16 range; iOS reference does the same).
        int scaled = Math.round(raw * 32767f);
        if (scaled < 0) scaled = 0;
        if (scaled > 32767) scaled = 32767;
        parsec.clientSendGamepadAxis(gamepadId, parsecAxis, scaled);
    }

    private static void dispatchHat(
            Parsec parsec, DeviceState state, float hx, float hy) {
        int hxI = hx > 0.5f ? 1 : (hx < -0.5f ? -1 : 0);
        int hyI = hy > 0.5f ? 1 : (hy < -0.5f ? -1 : 0);
        if (hxI != state.lastHatX) {
            if (state.lastHatX != 0) {
                parsec.clientSendGamepadButton(state.parsecGamepadId,
                        state.lastHatX > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_RIGHT
                                     : Parsec.GAMEPAD_BUTTON_DPAD_LEFT, false);
            }
            if (hxI != 0) {
                parsec.clientSendGamepadButton(state.parsecGamepadId,
                        hxI > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_RIGHT
                                : Parsec.GAMEPAD_BUTTON_DPAD_LEFT, true);
            }
            state.lastHatX = hxI;
        }
        if (hyI != state.lastHatY) {
            if (state.lastHatY != 0) {
                parsec.clientSendGamepadButton(state.parsecGamepadId,
                        state.lastHatY > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_DOWN
                                     : Parsec.GAMEPAD_BUTTON_DPAD_UP, false);
            }
            if (hyI != 0) {
                parsec.clientSendGamepadButton(state.parsecGamepadId,
                        hyI > 0 ? Parsec.GAMEPAD_BUTTON_DPAD_DOWN
                                : Parsec.GAMEPAD_BUTTON_DPAD_UP, true);
            }
            state.lastHatY = hyI;
        }
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return Parsec.GAMEPAD_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B: return Parsec.GAMEPAD_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X: return Parsec.GAMEPAD_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return Parsec.GAMEPAD_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return Parsec.GAMEPAD_BUTTON_LSHOULDER;
            case KeyEvent.KEYCODE_BUTTON_R1: return Parsec.GAMEPAD_BUTTON_RSHOULDER;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return Parsec.GAMEPAD_BUTTON_LSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return Parsec.GAMEPAD_BUTTON_RSTICK;
            case KeyEvent.KEYCODE_BUTTON_START: return Parsec.GAMEPAD_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Parsec.GAMEPAD_BUTTON_BACK;
            case KeyEvent.KEYCODE_BUTTON_MODE: return Parsec.GAMEPAD_BUTTON_GUIDE;
            // Some Android-as-gamepad sources fire DPad as discrete key events
            // (rather than HAT axis), so handle both paths.
            case KeyEvent.KEYCODE_DPAD_UP: return Parsec.GAMEPAD_BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Parsec.GAMEPAD_BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Parsec.GAMEPAD_BUTTON_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Parsec.GAMEPAD_BUTTON_DPAD_RIGHT;
            default: return -1;
        }
    }
}
