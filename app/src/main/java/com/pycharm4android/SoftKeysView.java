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
 * 应用内自绘软键盘：直接向 X 服务器注入 keysym，不弹系统输入法，
 * 因此不会把窗口顶上去或遮住整屏。中文输入仍可切到系统输入法（右下「中」）。
 */
public class SoftKeysView extends LinearLayout {

    public interface OnImeRequest { void onIme(); }

    private final VncKeyboardView target;
    private final TextView modCtrl, modAlt, modShift;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public SoftKeysView(Context c, VncKeyboardView target, OnImeRequest imeRequest) {
        super(c);
        this.target = target;
        setOrientation(VERTICAL);
        setBackgroundColor(0xF0181A1D);
        setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout r1 = row();
        key(r1, "Esc", () -> tap(Keys.XK_ESCAPE), 1f);
        key(r1, "Tab", () -> tap(Keys.XK_TAB), 1f);
        modCtrl = key(r1, "Ctrl", () -> toggleMod(VncKeyboardView.MOD_CTRL), 1.2f);
        modAlt = key(r1, "Alt", () -> toggleMod(VncKeyboardView.MOD_ALT), 1.2f);
        modShift = key(r1, "Shift", () -> toggleMod(VncKeyboardView.MOD_SHIFT), 1.4f);
        key(r1, "←", () -> tap(Keys.XK_LEFT), 1f);
        key(r1, "↑", () -> tap(Keys.XK_UP), 1f);
        key(r1, "↓", () -> tap(Keys.XK_DOWN), 1f);
        key(r1, "→", () -> tap(Keys.XK_RIGHT), 1f);

        LinearLayout r2 = row();
        for (char ch : "1234567890-=".toCharArray()) key(r2, String.valueOf(ch), () -> chr(ch), 1f);
        key(r2, "⌫", () -> tap(Keys.XK_BACKSPACE), 1.4f);

        LinearLayout r3 = row();
        for (char ch : "qwertyuiop".toCharArray()) key(r3, String.valueOf(ch), () -> chr(ch), 1f);
        key(r3, "[", () -> chr('['), 1f);
        key(r3, "]", () -> chr(']'), 1f);
        key(r3, "\\", () -> chr('\\'), 1f);

        LinearLayout r4 = row();
        for (char ch : "asdfghjkl".toCharArray()) key(r4, String.valueOf(ch), () -> chr(ch), 1f);
        key(r4, ";", () -> chr(';'), 1f);
        key(r4, "'", () -> chr('\''), 1f);
        key(r4, "⏎", () -> tap(Keys.XK_RETURN), 1.6f);

        LinearLayout r5 = row();
        for (char ch : "zxcvbnm".toCharArray()) key(r5, String.valueOf(ch), () -> chr(ch), 1f);
        key(r5, ",", () -> chr(','), 1f);
        key(r5, ".", () -> chr('.'), 1f);
        key(r5, "/", () -> chr('/'), 1f);
        key(r5, "Del", () -> tap(Keys.XK_DELETE), 1.2f);

        LinearLayout r6 = row();
        key(r6, "空格", () -> tap(' '), 3.6f);
        key(r6, "PgUp", () -> tap(Keys.XK_PAGE_UP), 1f);
        key(r6, "PgDn", () -> tap(Keys.XK_PAGE_DOWN), 1f);
        key(r6, "Home", () -> tap(Keys.XK_HOME), 1f);
        key(r6, "End", () -> tap(Keys.XK_END), 1f);
        key(r6, "中", imeRequest::onIme, 1f);
        key(r6, "收起", () -> setVisibility(View.GONE), 1.4f);

        paintMods();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        lp.bottomMargin = dp(3);
        addView(r, lp);
        return r;
    }

    private TextView key(LinearLayout row, String label, Runnable action, float weight) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#E8EAED"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#33363B"));
        bg.setCornerRadius(dp(4));
        tv.setBackground(bg);
        tv.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.rightMargin = dp(3);
        row.addView(tv, lp);
        return tv;
    }

    private void tap(int keysym) {
        target.sendKeyTap(keysym);
        paintMods();
    }

    private void chr(char c) {
        target.typeChar(c);
        paintMods();
    }

    private void toggleMod(int mask) {
        target.setLatched(target.getLatched() ^ mask);
        paintMods();
    }

    private void paintMods() {
        int latched = target.getLatched();
        tint(modCtrl, (latched & VncKeyboardView.MOD_CTRL) != 0);
        tint(modAlt, (latched & VncKeyboardView.MOD_ALT) != 0);
        tint(modShift, (latched & VncKeyboardView.MOD_SHIFT) != 0);
    }

    private void tint(TextView tv, boolean on) {
        if (tv == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(on ? "#4C8DF6" : "#33363B"));
        bg.setCornerRadius(dp(4));
        tv.setBackground(bg);
        tv.setTextColor(on ? Color.WHITE : Color.parseColor("#E8EAED"));
    }
}
