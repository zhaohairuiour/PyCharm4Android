package com.pycharm4android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 应用内自带键盘：不唤起系统输入法，悬浮在 VNC 画面之上，可随时隐藏。
 * 只处理 ASCII 与功能键；中文输入仍交给系统输入法（工具条「输入法」）。
 */
public class FloatingKeyboard extends LinearLayout {

    private final VncKeyboardView sink;
    private boolean shift = false;
    private boolean digits = false;
    private TextView shiftKey;
    private TextView digitsKey;
    private LinearLayout letters;

    private static final String ROW1_L = "qwertyuiop";
    private static final String ROW2_L = "asdfghjkl";
    private static final String ROW3_L = "zxcvbnm";
    private static final String ROW1_D = "1234567890";
    private static final String ROW2_D = "-=:;()[]{}";
    private static final String ROW3_D = "_+,.<>/?";

    public FloatingKeyboard(Context c, VncKeyboardView sink) {
        super(c);
        this.sink = sink;
        setOrientation(VERTICAL);
        setBackgroundColor(0xE6141618);
        setPadding(dp(4), dp(4), dp(4), dp(6));

        // 功能键行
        LinearLayout fn = row();
        shiftKey = key(fn, "Shift", 1.2f, v -> toggleShift());
        key(fn, "Ctrl", 1.0f, v -> toggleMod(VncKeyboardView.MOD_CTRL));
        key(fn, "Alt", 1.0f, v -> toggleMod(VncKeyboardView.MOD_ALT));
        key(fn, "Esc", 0.9f, v -> sink.sendKeyTap(Keys.XK_ESCAPE));
        key(fn, "Tab", 0.9f, v -> sink.sendKeyTap(Keys.XK_TAB));
        key(fn, "←", 0.8f, v -> sink.sendKeyTap(Keys.XK_LEFT));
        key(fn, "↑", 0.8f, v -> sink.sendKeyTap(Keys.XK_UP));
        key(fn, "↓", 0.8f, v -> sink.sendKeyTap(Keys.XK_DOWN));
        key(fn, "→", 0.8f, v -> sink.sendKeyTap(Keys.XK_RIGHT));
        key(fn, "Home", 1.0f, v -> sink.sendKeyTap(Keys.XK_HOME));
        key(fn, "End", 0.9f, v -> sink.sendKeyTap(Keys.XK_END));
        addView(fn);

        letters = new LinearLayout(c);
        letters.setOrientation(VERTICAL);
        addView(letters);
        buildLetters();

        // 底部行：空格 + 数字/字母切换 + 退格 + 回车
        LinearLayout bottom = row();
        digitsKey = key(bottom, "123", 1.0f, v -> toggleDigits());
        key(bottom, "空格", 4.0f, v -> sink.sendKeyTap(Keys.XK_SPACE));
        key(bottom, "⌫", 1.2f, v -> sink.sendKeyTap(Keys.XK_BACKSPACE));
        key(bottom, "回车", 1.4f, v -> sink.sendKeyTap(Keys.XK_RETURN));
        addView(bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 固定高度，便于外层给 VNC 留出空间
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(dp(184), MeasureSpec.EXACTLY));
    }

    private void buildLetters() {
        letters.removeAllViews();
        String r1 = digits ? ROW1_D : ROW1_L;
        String r2 = digits ? ROW2_D : ROW2_L;
        String r3 = digits ? ROW3_D : ROW3_L;
        addLetterRow(r1);
        addLetterRow(r2);
        addLetterRow(r3);
    }

    private void addLetterRow(String chars) {
        LinearLayout r = row();
        for (int i = 0; i < chars.length(); i++) {
            final char ch = chars.charAt(i);
            key(r, String.valueOf(ch), 1f, v -> typeChar(ch));
        }
        letters.addView(r);
    }

    private void typeChar(char ch) {
        char out = ch;
        if (!digits) out = shift ? Character.toUpperCase(ch) : ch;
        sink.typeChar(out);
        if (shift) {
            shift = false;
            style(shiftKey, false);
        }
    }

    private void toggleShift() {
        shift = !shift;
        style(shiftKey, shift);
    }

    private void toggleMod(int mask) {
        sink.setLatched(sink.getLatched() ^ mask);
    }

    private void toggleDigits() {
        digits = !digits;
        digitsKey.setText(digits ? "ABC" : "123");
        buildLetters();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(34));
        lp.topMargin = dp(3);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView key(LinearLayout row, String label, float weight, OnClickListener l) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(0xFFE8EAED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, 0);
        tv.setOnClickListener(l);
        style(tv, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        row.addView(tv, lp);
        return tv;
    }

    private static void style(TextView tv, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0xFF3C6E9E : 0x33FFFFFF);
        bg.setCornerRadius(6);
        tv.setBackground(bg);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 让外部按「修饰键是否锁定」刷新高亮 */
    public void refreshMods() {
        style(shiftKey, shift || (sink.getLatched() & VncKeyboardView.MOD_SHIFT) != 0);
    }

    public boolean isShift() { return shift; }

    /** 与 View 无关的占位，避免误用 Color 常量 */
    static final int UNUSED = Color.TRANSPARENT;
}
