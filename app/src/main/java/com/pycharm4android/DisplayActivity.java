package com.pycharm4android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * 远程桌面显示页：VncView + 自绘软键盘 + 启动日志面板。
 * 启动阶段持续显示 ide.log（Xvfb / x11vnc / PyCharm）与 LogBus，VNC 未就绪时自动重连。
 */
public class DisplayActivity extends Activity implements VncClient.Listener {

    private VncView vncView;
    private VncKeyboardView imeInput;
    private SoftKeysView softKeys;
    private TextView status;
    private LinearLayout logPanel;
    private LinearLayout dock;
    private TextView dockHandle;
    private FrameLayout.LayoutParams vncParams, logParams;
    private boolean dockCollapsed = false;
    private TextView logStage, logText;
    private ScrollView logScroll;
    private VncClient client;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean finished = false;
    private volatile boolean connected = false;
    private volatile boolean resumed = false;
    private final Runnable reconnect = this::connectAttempt;
    private boolean logsPinned = false;
    private final long startedAt = System.currentTimeMillis();
    private String shownLog = "";
    private String connectHost = "127.0.0.1";
    private int connectPort = Const.VNC_PORT;
    private int retryCount = 0;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        vncView = new VncView(this, null);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vp.bottomMargin = dp(90);   // 给底部控制坞让位
        vncParams = vp;
        root.addView(vncView, vp);

        imeInput = new VncKeyboardView(this);
        root.addView(imeInput, new FrameLayout.LayoutParams(2, 2, Gravity.LEFT | Gravity.TOP));

        softKeys = new SoftKeysView(this, imeInput, this::toggleIme);
        FrameLayout.LayoutParams skp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        skp.bottomMargin = dp(90);
        softKeys.setVisibility(View.GONE);
        root.addView(softKeys, skp);

        buildLogPanel(root);
        buildToolbar(root);
        setContentView(root);

        reportLastCrash();
        if (!IdeService.RUNNING) IdeService.start(this);
        startLogPump();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!connected) connectAttempt();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(reconnect);
        if (client != null) client.close();     // 后台不再抓帧，避免息屏/切后台后仍在重连
        connected = false;
        super.onPause();
    }

    /** 上次崩溃的堆栈：写入 LogBus，日志面板可见，然后删除，避免重复 */
    private void reportLastCrash() {
        File f = new File(getFilesDir(), "crash.txt");
        if (!f.exists()) return;
        String s = Io.readAll(f);
        Io.rmRf(f);
        if (!s.isEmpty()) {
            LogBus.append("[上次崩溃]\n" + s);
        }
    }

    private void buildLogPanel(FrameLayout root) {
        logPanel = new LinearLayout(this);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setBackgroundColor(0xE60E1013);
        logPanel.setPadding(dp(10), dp(10), dp(10), dp(10));

        logStage = new TextView(this);
        logStage.setTextColor(0xFF8AB4F8);
        logStage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logStage.setTypeface(Typeface.DEFAULT_BOLD);
        logStage.setText("正在启动容器…");
        logPanel.addView(logStage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logText = new TextView(this);
        logText.setTextColor(0xFFCFD3D8);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(logText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(6);
        logPanel.addView(logScroll, lp);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pp.topMargin = dp(8);
        pp.bottomMargin = dp(94);
        logParams = pp;
        root.addView(logPanel, pp);

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xB3000000);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(8);
        root.addView(status, sp);
    }

    private void buildToolbar(FrameLayout root) {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21A1C20);
        bg.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0});
        bg.setStroke(dp(1), 0xFF34373D);
        dock.setBackground(bg);
        dock.setPadding(dp(6), dp(6), dp(6), dp(8));

        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        primary.setGravity(Gravity.CENTER_VERTICAL);
        primary.addView(pill("收起 ▾", v -> setDockCollapsed(true)));
        primary.addView(pill("键盘", v -> toggleSoftKeys()));
        primary.addView(pill("中文", v -> toggleIme()));
        primary.addView(pill("日志", v -> toggleLogs()));
        primary.addView(pill("诊断", v -> diagnose()));
        primary.addView(pill("重连", v -> {
            if (!IdeService.RUNNING) IdeService.start(this);
            if (client != null) client.close();
            retryCount = 0;
            connectAttempt();
        }));
        primary.addView(pillExit());

        HorizontalScrollView row1 = new HorizontalScrollView(this);
        row1.setHorizontalScrollBarEnabled(false);
        row1.addView(primary);
        dock.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.addView(pill("Ctrl", v -> toggleMod(VncKeyboardView.MOD_CTRL)));
        keys.addView(pill("Alt", v -> toggleMod(VncKeyboardView.MOD_ALT)));
        keys.addView(pill("Shift", v -> toggleMod(VncKeyboardView.MOD_SHIFT)));
        keys.addView(pill("Esc", v -> send(Keys.XK_ESCAPE)));
        keys.addView(pill("Tab", v -> send(Keys.XK_TAB)));
        keys.addView(pill("←", v -> send(Keys.XK_LEFT)));
        keys.addView(pill("→", v -> send(Keys.XK_RIGHT)));
        keys.addView(pill("↑", v -> send(Keys.XK_UP)));
        keys.addView(pill("↓", v -> send(Keys.XK_DOWN)));
        keys.addView(pill("PgUp", v -> send(Keys.XK_PAGE_UP)));
        keys.addView(pill("PgDn", v -> send(Keys.XK_PAGE_DOWN)));
        keys.addView(pill("右键", v -> vncView.setRightClickNext(true)));
        keys.addView(pill("放大", v -> vncView.setZoom(vncView.getZoom() * 1.25f)));
        keys.addView(pill("缩小", v -> vncView.setZoom(vncView.getZoom() / 1.25f)));
        keys.addView(pill("适配", v -> vncView.setZoom(1f)));

        HorizontalScrollView row2 = new HorizontalScrollView(this);
        row2.setHorizontalScrollBarEnabled(false);
        row2.addView(keys);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(4);
        dock.addView(row2, r2);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(dock, bp);

        dockHandle = pill("控制栏 ▲", 0xFFB9F4FF, 0xE61A1C20, 0xFF3B5668, v -> setDockCollapsed(false));
        dockHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dockHandle.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(8);
        root.addView(dockHandle, hp);
    }

    /** 收起/展开底部控制坞；收起后 VNC 画布与日志面板扩展到底部 */
    private void setDockCollapsed(boolean collapsed) {
        dockCollapsed = collapsed;
        dock.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        dockHandle.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        int bottom = dp(collapsed ? 42 : 90);
        vncParams.bottomMargin = bottom;
        vncView.setLayoutParams(vncParams);
        logParams.bottomMargin = collapsed ? dp(46) : dp(94);
        logPanel.setLayoutParams(logParams);
        if (collapsed) {
            softKeys.setVisibility(View.GONE);
            hideIme();
        }
    }

    /** 圆角胶囊按钮 + 水波纹反馈，替代原来的灰色文字块 */
    private TextView pill(String text, View.OnClickListener l) {
        return pill(text, 0xFFE6E8EB, 0xFF2A2D31, 0xFF3B3E44, l);
    }

    private TextView pillExit() {
        return pill("退出", 0xFFFF8A80, 0xFF3A2323, 0xFF5A3434, v -> finish());
    }

    private TextView pill(String text, int textColor, int fill, int stroke, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(17));
        shape.setStroke(dp(1), stroke);
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addBtn(LinearLayout bar, String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE8EAED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(2);
        bar.addView(tv, lp);
    }

    private void send(int keysym) {
        imeInput.sendKeyTap(keysym);
    }

    private void toggleMod(int mask) {
        imeInput.setLatched(imeInput.getLatched() ^ mask);
        int latched = imeInput.getLatched();
        status.setVisibility(latched == 0 ? View.GONE : View.VISIBLE);
        if (latched != 0) status.setText("修饰键锁定：" +
                (((latched & VncKeyboardView.MOD_CTRL) != 0) ? "Ctrl " : "") +
                (((latched & VncKeyboardView.MOD_ALT) != 0) ? "Alt " : "") +
                (((latched & VncKeyboardView.MOD_SHIFT) != 0) ? "Shift" : ""));
    }

    private void toggleSoftKeys() {
        boolean show = softKeys.getVisibility() != View.VISIBLE;
        softKeys.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) hideIme();
    }

    private void toggleIme() {
        softKeys.setVisibility(View.GONE);
        imeInput.requestFocus();
        VncKeyboardView.showIme(this, imeInput);
    }

    private void hideIme() {
        VncKeyboardView.hideIme(this, imeInput);
    }

    /** 收集容器内 X/VNC 状态到 LogBus，日志面板「服务日志」段可见 */
    private void diagnose() {
        android.widget.Toast.makeText(this, "正在收集诊断信息…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Proot.run(this, "echo '=== DIAG START ==='; "
                        + "date; "
                        + "ls -l /tmp/.X11-unix 2>&1; "
                        + "pgrep -a Xvfb 2>&1; pgrep -a x11vnc 2>&1; pgrep -af pycharm 2>&1 | head -2; "
                        + "tail -n 25 /root/xvfb.log 2>&1; tail -n 25 /root/vnc.log 2>&1; "
                        + "echo '=== DIAG END ==='");
            } catch (Throwable t) {
                LogBus.append("[诊断] 失败：" + t);
            }
        }, "diag").start();
    }

    private void toggleLogs() {
        boolean show = logPanel.getVisibility() != View.VISIBLE;
        logsPinned = show;
        logPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------- 启动日志 ----------------

    private void startLogPump() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (finished) return;
                pumpLogs();
                ui.postDelayed(this, 800);
            }
        }, 200);
    }

    private void pumpLogs() {
        File ideLog = new File(Dirs.rootfs(this), "root/ide.log");
        String ide = composeLog(tail(ideLog, 200_000));
        String body = ide + "\n\n── 服务日志 ──\n" + LogBus.text(120);
        if (body.length() > 6000) body = body.substring(body.length() - 6000);
        if (!body.equals(shownLog)) {
            if (!shownLog.isEmpty() && body.startsWith(shownLog)) {
                logText.append(body.substring(shownLog.length()));
            } else {
                logText.setText(body);
            }
            shownLog = body;
            logScroll.post(() -> {
                View child = logScroll.getChildAt(0);
                int max = child == null ? 0 : Math.max(0, child.getHeight() - logScroll.getHeight());
                logScroll.scrollTo(0, max);
            });
        }
        logStage.setText(stage(ide));
    }

    /** 关键事件优先：run-ide / VNC / Xvfb / 诊断行，再附末尾 25 行原始输出 */
    private static String composeLog(String ide) {
        if (ide.isEmpty()) return "（ide.log 尚未生成）";
        String[] lines = ide.split("\n");
        java.util.ArrayList<String> key = new java.util.ArrayList<>();
        for (String ln : lines) {
            if (ln.contains("[run-ide") || ln.contains("VNC_") || ln.contains("X11VNC")
                    || ln.contains("x11vnc") || ln.contains("Xvfb") || ln.contains("=== DIAG")) {
                key.add(ln);
                if (key.size() > 150) key.remove(0);
            }
        }
        StringBuilder sb = new StringBuilder("── 关键事件 ──\n");
        for (String ln : key) sb.append(ln).append('\n');
        sb.append("\n── 末尾输出 ──\n");
        int from = Math.max(0, lines.length - 25);
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private String stage(String ide) {
        int sec = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        String s;
        if (connected) {
            s = "已连接";
        } else if (ide.contains("VNC_FAIL")) {
            s = "x11vnc 启动失败，点「诊断」查看 vnc.log";
        } else if (ide.contains("X 套接字未出现")) {
            s = "Xvfb 启动失败（见 xvfb.log）";
        } else if (ide.contains("PyCharm 启动")) {
            s = "PyCharm JVM 启动中（首次 1~3 分钟，之后会快）";
        } else if (ide.contains("x11vnc")) {
            s = "启动 x11vnc / 窗口管理器";
        } else if (ide.contains("虚拟屏")) {
            s = "启动 Xvfb 虚拟屏";
        } else if (IdeService.RUNNING) {
            s = "容器已启动，等待 run-ide.sh 输出";
        } else {
            s = "正在拉起容器（proot + Ubuntu）";
        }
        return s + " · 已等待 " + sec + " s";
    }

    /** 读取文件末尾 maxBytes，从第一个换行后开始，避免截断半行 */
    private static String tail(File f, int maxBytes) {
        if (f == null || !f.exists()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            int off = 0;
            if (start > 0) {
                while (off < buf.length && buf[off] != '\n') off++;
                off++;
            }
            return new String(buf, off, buf.length - off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- VNC 连接 ----------------

    private void connectAttempt() {
        if (finished || connected || !resumed) return;
        status.setVisibility(View.VISIBLE);
        connectPort = Const.VNC_PORT;
        status.setText("连接 " + connectHost + ":" + connectPort + " …");
        if (client != null) client.close();
        client = new VncClient(connectHost, connectPort, this);
        vncView.setClient(client);
        imeInput.setClient(client);
        new Thread(client, "vnc").start();
    }

    @Override
    public void onConnected(int w, int h) {
        connected = true;
        ui.post(() -> {
            status.setVisibility(View.GONE);
            if (!logsPinned) logPanel.setVisibility(View.GONE);
            vncView.onConnected(w, h);
        });
    }

    @Override
    public void onUpdate(List<int[]> rects) {
        ui.post(() -> vncView.applyUpdate(rects));
    }

    @Override
    public void onError(Throwable t) {
        connected = false;
        ui.post(() -> {
            if (finished || !resumed) return;
            String msg = t == null ? "未知错误" : String.valueOf(t.getMessage());
            status.setVisibility(View.VISIBLE);
            if (msg.contains("Connection refused") || msg.contains("ECONNREFUSED")) {
                status.setText("x11vnc 尚未监听 " + connectHost + ":" + connectPort + "（属正常），1.5 s 后换端口/地址重试");
            } else {
                status.setText("VNC 断开 " + connectHost + ":" + connectPort + "：" + msg + "，1.5 s 后重连");
            }
            // 优先 127.0.0.1；每 4 次失败才换 ::1 试一轮
            retryCount++;
            if (retryCount % 4 == 0) {
                connectHost = "127.0.0.1".equals(connectHost) ? "::1" : "127.0.0.1";
            }
            if (!IdeService.RUNNING) {
                status.setText("容器已停止（IDE 已退出），点「重连」会重新拉起容器");
                return;                      // 容器不在，不再空转重连
            }
            ui.postDelayed(reconnect, 1500);
        });
    }

    @Override
    protected void onDestroy() {
        finished = true;
        resumed = false;
        if (client != null) client.close();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
