package com.pycharm4android;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** 隐藏的输入焦点控件：把软键盘/物理键盘事件转发给 X 服务器 */
public class VncKeyboardView extends EditText {

    public static final int MOD_CTRL = 1;
    public static final int MOD_ALT = 2;
    public static final int MOD_SHIFT = 4;

    private VncClient client;
    private int latched = 0;
    private int pressedMods = 0;
    private boolean sendingFromIme = false;

    public VncKeyboardView(Context c) {
        super(c);
        setAlpha(0f);
        setBackgroundColor(0x00000000);
        setTextColor(0x00000000);
        setCursorVisible(false);
        setSingleLine(false);
        setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setVisibility(View.VISIBLE);
    }

    public void setClient(VncClient c) { this.client = c; }

    public void setLatched(int mask) { latched = mask; }

    public int getLatched() { return latched; }

    public static void showIme(Context c, View v) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    public static void hideIme(Context c, View v) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = getInputType();
        outAttrs.imeOptions = getImeOptions();
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (client != null && text != null) {
                    for (int i = 0; i < text.length(); i++) typeChar(text.charAt(i));
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (client != null) {
                    for (int i = 0; i < beforeLength; i++) sendKeyTap(Keys.XK_BACKSPACE);
                    for (int i = 0; i < afterLength; i++) sendKeyTap(Keys.XK_DELETE);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                handleKey(event);
                return true;
            }
        };
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (client != null) {
            handleKey(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void pressMods(boolean ctrl, boolean alt, boolean shift) {
        int want = (ctrl ? MOD_CTRL : 0) | (alt ? MOD_ALT : 0) | (shift ? MOD_SHIFT : 0) | latched;
        if ((want & MOD_CTRL) != 0 && (pressedMods & MOD_CTRL) == 0) { client.key(Keys.XK_CONTROL_L, true); pressedMods |= MOD_CTRL; }
        if ((want & MOD_ALT) != 0 && (pressedMods & MOD_ALT) == 0) { client.key(Keys.XK_ALT_L, true); pressedMods |= MOD_ALT; }
        if ((want & MOD_SHIFT) != 0 && (pressedMods & MOD_SHIFT) == 0) { client.key(Keys.XK_SHIFT_L, true); pressedMods |= MOD_SHIFT; }
    }

    private void releaseMods() {
        if ((pressedMods & MOD_SHIFT) != 0) { client.key(Keys.XK_SHIFT_L, false); pressedMods &= ~MOD_SHIFT; }
        if ((pressedMods & MOD_ALT) != 0) { client.key(Keys.XK_ALT_L, false); pressedMods &= ~MOD_ALT; }
        if ((pressedMods & MOD_CTRL) != 0) { client.key(Keys.XK_CONTROL_L, false); pressedMods &= ~MOD_CTRL; }
    }

    public void sendKeyTap(int keysym) {
        if (client == null || keysym == 0) return;
        pressMods(false, false, false);
        client.key(keysym, true);
        client.key(keysym, false);
        releaseMods();
        latched = 0;
    }

    public void typeChar(char c) {
        if (client == null) return;
        int keysym = Keys.fromChar(c);
        pressMods(false, false, Character.isUpperCase(c));
        client.key(keysym, true);
        client.key(keysym, false);
        releaseMods();
        latched = 0;
    }

    private void handleKey(KeyEvent e) {
        if (client == null || sendingFromIme) return;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_SHIFT_LEFT || kc == KeyEvent.KEYCODE_SHIFT_RIGHT
                || kc == KeyEvent.KEYCODE_CTRL_LEFT || kc == KeyEvent.KEYCODE_CTRL_RIGHT
                || kc == KeyEvent.KEYCODE_ALT_LEFT || kc == KeyEvent.KEYCODE_ALT_RIGHT
                || kc == KeyEvent.KEYCODE_META_LEFT || kc == KeyEvent.KEYCODE_META_RIGHT) {
            // 修饰键由组合逻辑统一处理，避免重复按下
            return;
        }
        int keysym = Keys.fromKeyEvent(e);
        if (keysym == 0) return;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) {
                client.key(keysym, true);
                client.key(keysym, false);
                return;
            }
            pressMods(e.isCtrlPressed(), e.isAltPressed(), e.isShiftPressed());
            client.key(keysym, true);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            client.key(keysym, false);
            releaseMods();
            latched = 0;
            sendingFromIme = false;
        }
    }
}
