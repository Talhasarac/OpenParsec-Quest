package com.example.parsecdemo;

import android.view.KeyEvent;

/** Android KeyEvent.KEYCODE_* → Parsec ParsecKeycode. */
final class KeyMap {
    private KeyMap() {}

    // Parsec keycodes (from parsec.h ParsecKeycode enum)
    static final int KEY_A = 4;
    static final int KEY_C = KEY_A + 2;
    static final int KEY_D = KEY_A + 3;
    static final int KEY_V = KEY_A + 21;
    static final int KEY_1 = 30;
    static final int KEY_ENTER = 40;
    static final int KEY_ESCAPE = 41;
    static final int KEY_BACKSPACE = 42;
    static final int KEY_TAB = 43;
    static final int KEY_SPACE = 44;
    static final int KEY_MINUS = 45;
    static final int KEY_EQUALS = 46;
    static final int KEY_LBRACKET = 47;
    static final int KEY_RBRACKET = 48;
    static final int KEY_BACKSLASH = 49;
    static final int KEY_SEMICOLON = 51;
    static final int KEY_APOSTROPHE = 52;
    static final int KEY_BACKTICK = 53;
    static final int KEY_COMMA = 54;
    static final int KEY_PERIOD = 55;
    static final int KEY_SLASH = 56;
    static final int KEY_RIGHT = 79;
    static final int KEY_LEFT = 80;
    static final int KEY_DOWN = 81;
    static final int KEY_UP = 82;
    static final int KEY_DELETE = 76;
    static final int KEY_HOME = 74;
    static final int KEY_END = 77;
    static final int KEY_PAGEUP = 75;
    static final int KEY_PAGEDOWN = 78;
    static final int KEY_LSHIFT = 225;

    /** Translate an Android KeyEvent keyCode into a ParsecKeycode. Returns 0 if unmapped. */
    static int fromAndroidKey(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            return KEY_A + (code - KeyEvent.KEYCODE_A);
        }
        if (code == KeyEvent.KEYCODE_0) return 39;
        if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
            return KEY_1 + (code - KeyEvent.KEYCODE_1);
        }
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return KEY_ENTER;
            case KeyEvent.KEYCODE_ESCAPE: return KEY_ESCAPE;
            case KeyEvent.KEYCODE_DEL: return KEY_BACKSPACE;
            case KeyEvent.KEYCODE_TAB: return KEY_TAB;
            case KeyEvent.KEYCODE_SPACE: return KEY_SPACE;
            case KeyEvent.KEYCODE_MINUS: return KEY_MINUS;
            case KeyEvent.KEYCODE_EQUALS: return KEY_EQUALS;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return KEY_LBRACKET;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return KEY_RBRACKET;
            case KeyEvent.KEYCODE_BACKSLASH: return KEY_BACKSLASH;
            case KeyEvent.KEYCODE_SEMICOLON: return KEY_SEMICOLON;
            case KeyEvent.KEYCODE_APOSTROPHE: return KEY_APOSTROPHE;
            case KeyEvent.KEYCODE_GRAVE: return KEY_BACKTICK;
            case KeyEvent.KEYCODE_COMMA: return KEY_COMMA;
            case KeyEvent.KEYCODE_PERIOD: return KEY_PERIOD;
            case KeyEvent.KEYCODE_SLASH: return KEY_SLASH;
            case KeyEvent.KEYCODE_DPAD_LEFT: return KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return KEY_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return KEY_DOWN;
            case KeyEvent.KEYCODE_FORWARD_DEL: return KEY_DELETE;
            case KeyEvent.KEYCODE_MOVE_HOME: return KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return KEY_END;
            case KeyEvent.KEYCODE_PAGE_UP: return KEY_PAGEUP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return KEY_PAGEDOWN;
            default: return 0;
        }
    }

    /** Translate a typed character into a ParsecKeycode (returns 0 if unmappable).
     *  Shifted US-layout punctuation maps to the base key — {@link #needsShift}
     *  reports true for the same chars so the activity-level send path wraps
     *  the keypress in a shift hold automatically. */
    static int fromChar(char ch) {
        if (ch >= 'a' && ch <= 'z') return KEY_A + (ch - 'a');
        if (ch >= 'A' && ch <= 'Z') return KEY_A + (ch - 'A');
        if (ch == '0') return 39;
        if (ch >= '1' && ch <= '9') return KEY_1 + (ch - '1');
        switch (ch) {
            case ' ':  return KEY_SPACE;
            case '\n': return KEY_ENTER;
            case '\t': return KEY_TAB;
            // Unshifted punctuation
            case '-':  return KEY_MINUS;
            case '=':  return KEY_EQUALS;
            case '[':  return KEY_LBRACKET;
            case ']':  return KEY_RBRACKET;
            case '\\': return KEY_BACKSLASH;
            case ';':  return KEY_SEMICOLON;
            case '\'': return KEY_APOSTROPHE;
            case '`':  return KEY_BACKTICK;
            case ',':  return KEY_COMMA;
            case '.':  return KEY_PERIOD;
            case '/':  return KEY_SLASH;
            // Shifted punctuation — map to the same base key as the
            // unshifted variant. needsShift() returns true for these so the
            // sender wraps the press in a shift hold.
            // Number-row shifted variants — KEY_1..KEY_9 are KEY_1 + (n-1)
            case '!':  return KEY_1;
            case '@':  return KEY_1 + 1;
            case '#':  return KEY_1 + 2;
            case '$':  return KEY_1 + 3;
            case '%':  return KEY_1 + 4;
            case '^':  return KEY_1 + 5;
            case '&':  return KEY_1 + 6;
            case '*':  return KEY_1 + 7;
            case '(':  return KEY_1 + 8;
            case ')':  return 39 /* KEY_0 */;
            case '_':  return KEY_MINUS;
            case '+':  return KEY_EQUALS;
            case '{':  return KEY_LBRACKET;
            case '}':  return KEY_RBRACKET;
            case '|':  return KEY_BACKSLASH;
            case ':':  return KEY_SEMICOLON;
            case '"':  return KEY_APOSTROPHE;
            case '~':  return KEY_BACKTICK;
            case '<':  return KEY_COMMA;
            case '>':  return KEY_PERIOD;
            case '?':  return KEY_SLASH;
            default:   return 0;
        }
    }

    /** True if the character requires shift-modifier on a US keyboard. */
    static boolean needsShift(char ch) {
        if (ch >= 'A' && ch <= 'Z') return true;
        switch (ch) {
            case '!': case '@': case '#': case '$': case '%':
            case '^': case '&': case '*': case '(': case ')':
            case '_': case '+': case '{': case '}': case '|':
            case ':': case '"': case '~': case '<': case '>': case '?':
                return true;
        }
        return false;
    }
}
