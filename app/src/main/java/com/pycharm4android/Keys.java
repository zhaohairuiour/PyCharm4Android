package com.pycharm4android;

import android.view.KeyEvent;

/** Android KeyEvent -> X11 keysym */
public final class Keys {
    public static final int XK_SPACE = 0x0020;
    public static final int XK_BACKSPACE = 0xFF08;
    public static final int XK_TAB = 0xFF09;
    public static final int XK_RETURN = 0xFF0D;
    public static final int XK_ESCAPE = 0xFF1B;
    public static final int XK_DELETE = 0xFFFF;
    public static final int XK_HOME = 0xFF50;
    public static final int XK_LEFT = 0xFF51;
    public static final int XK_UP = 0xFF52;
    public static final int XK_RIGHT = 0xFF53;
    public static final int XK_DOWN = 0xFF54;
    public static final int XK_PAGE_UP = 0xFF55;
    public static final int XK_PAGE_DOWN = 0xFF56;
    public static final int XK_END = 0xFF57;
    public static final int XK_SHIFT_L = 0xFFE1;
    public static final int XK_CONTROL_L = 0xFFE3;
    public static final int XK_ALT_L = 0xFFE9;
    public static final int XK_SUPER_L = 0xFFEB;

    public static int fromKeyEvent(KeyEvent e) {
        int keyCode = e.getKeyCode();
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 'a' + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return '0' + (keyCode - KeyEvent.KEYCODE_0);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return ' ';
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return XK_RETURN;
            case KeyEvent.KEYCODE_DEL: return XK_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return XK_DELETE;
            case KeyEvent.KEYCODE_TAB: return XK_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return XK_ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return XK_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return XK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return XK_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return XK_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return XK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return XK_END;
            case KeyEvent.KEYCODE_PAGE_UP: return XK_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return XK_PAGE_DOWN;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return XK_SHIFT_L;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return XK_CONTROL_L;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return XK_ALT_L;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return XK_SUPER_L;
            default: {
                char c = (char) e.getUnicodeChar(e.getMetaState() & ~KeyEvent.META_CTRL_ON & ~KeyEvent.META_ALT_ON);
                if (c != 0) return fromChar(c);
                return 0;
            }
        }
    }

    public static int fromChar(char c) {
        if (c >= 0x20 && c < 0x7F) return c;
        return 0x01000000 | c; // Unicode keysym
    }

    private Keys() {}
}
