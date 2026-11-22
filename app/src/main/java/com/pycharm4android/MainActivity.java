package com.pycharm4android;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status, detail, log;
    private ProgressBar bar;
    private ScrollView logScroll;
    private TextView install, launch, display, stop, version, heap;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastLogVersion = -1;
    private boolean stickBottom = true;   // 用户滚到底部才自动跟随
    private boolean updatingLog = false;
    private long lastDetailAt = 0;
    private String lastStatusText, lastDetailText;
    private int lastPercent = -1;
    private boolean lastIndeterminate, lastReady, lastRunning, lastIdeRunning;
    private boolean firstRefresh = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        // 日志区自己滚动，别被外层 ScrollView 抢走手势
        log.setOnTouchListener((v, e) -> {
            logScroll.requestDisallowInterceptTouchEvent(true);
            return false;
        });
        logScroll.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        logScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (updatingLog) return;
            View child = logScroll.getChildAt(0);
            if (child == null) return;
            stickBottom = child.getHeight() - (y + logScroll.getHeight()) <= dp(32);
        });
        TextView subtitle = findViewById(R.id.subtitle);
        subtitle.setText("在 Android 上运行 PyCharm 桌面版 · v" + Const.VERSION_NAME);
        bar = findViewById(R.id.progress);
        install = findViewById(R.id.btnInstall);
        launch = findViewById(R.id.btnLaunch);
        display = findViewById(R.id.btnDisplay);
        stop = findViewById(R.id.btnStop);
        version = findViewById(R.id.btnVersion);
        heap = findViewById(R.id.btnHeap);


        install.setOnClickListener(v -> {
            if (SetupState.running) {
                Toast.makeText(this, "正在安装，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            requestStorage();
            SetupService.start(this);
        });

        launch.setOnClickListener(v -> {
            if (!isReady()) {
                Toast.makeText(this, "请先完成安装", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!IdeService.RUNNING) IdeService.start(this);
            startActivity(new Intent(this, DisplayActivity.class));
        });

        display.setOnClickListener(v -> startActivity(new Intent(this, DisplayActivity.class)));

        stop.setOnClickListener(v -> {
            IdeService.stop(this);
            StatusBar();
            Toast.makeText(this, "已发送停止指令", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnReinstall).setOnClickListener(v -> {
            if (!SetupState.running) {
                // 保留 rootfs，只重跑 apt 与 PyCharm 检查
                //noinspection ResultOfMethodCallIgnored
                Dirs.setupReadyMark(this).delete();
                //noinspection ResultOfMethodCallIgnored
                Dirs.ideReadyMark(this).delete();
                SetupService.start(this);
            }
        });

        findViewById(R.id.btnMirror).setOnClickListener(v -> chooseMirror());
        findViewById(R.id.btnVersion).setOnClickListener(v -> chooseVersion());
        findViewById(R.id.btnHeap).setOnClickListener(v -> chooseHeap());

        refresh();
    }

    private void requestStorage() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private void chooseMirror() {
        int cur = Math.min(Prefs.mirrorIndex(this), Mirrors.NAMES.length - 1);
        final int[] sel = {cur};
        new android.app.AlertDialog.Builder(this)
                .setTitle("下载源（当前：" + Mirrors.NAMES[cur] + "）")
                .setSingleChoiceItems(Mirrors.NAMES, cur, (d, which) -> sel[0] = which)
                .setPositiveButton("应用", (d, which) -> {
                    Prefs.setMirrorIndex(this, sel[0]);
                    if (SetupState.running) {
                        SetupState.cancel.set(true);
                        SetupState.restartRequested = true;
                        Toast.makeText(this, "已切换为 " + Mirrors.NAMES[sel[0]] + "，正在用新源重启（已下载部分续传）",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "已选择：" + Mirrors.NAMES[sel[0]], Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 堆内存入口：选择 JVM -Xmx，已安装时直接改写 pycharm64.vmoptions */
    private void chooseHeap() {
        if (SetupState.running) {
            Toast.makeText(this, "安装进行中，完成后再调整堆内存", Toast.LENGTH_SHORT).show();
            return;
        }
        int cur = Prefs.heapMb(this);
        int[] opts = Const.HEAP_OPTIONS;
        String[] hints = {
                "4 GB 内存 / 轻量项目",
                "6 GB 内存 / 推荐",
                "默认 / 6–8 GB 内存",
                "8 GB 内存 / 中大型项目",
                "12 GB 内存 / 大型项目",
                "16 GB 内存 / 极限",
        };
        String[] labels = new String[opts.length];
        int checked = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " MB · " + hints[i] + (opts[i] == cur ? " · 当前" : "");
            if (opts[i] == HeapConfig.nearestOption(cur)) checked = i;
        }
        final int[] sel = {checked};
        new android.app.AlertDialog.Builder(this)
                .setTitle("堆内存（JVM -Xmx）")
                .setMessage("堆越大，索引、重构与大项目越流畅，但会占用更多物理内存。\n"
                        + "修改后重启 PyCharm 生效；当前 " + cur + " MB。")
                .setSingleChoiceItems(labels, checked, (d, which) -> sel[0] = which)
                .setPositiveButton("应用", (d, which) -> applyHeap(opts[sel[0]]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyHeap(int mb) {
        Prefs.setHeap(this, mb);
        HeapConfig.writeVmoptions(this);
        LogBus.append("[设置] JVM 堆上限 -> " + mb + " MB");
        if (!Dirs.ideReadyMark(this).exists()) {
            Toast.makeText(this, "堆已设为 " + mb + " MB，将在安装 PyCharm 时生效", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        final boolean running = IdeService.RUNNING;
        Toast.makeText(this, "正在写入 vmoptions…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean ok = HeapConfig.applyToRootfs(MainActivity.this);
            final String msg = ok
                    ? "堆已设为 " + mb + " MB" + (running ? "，重启 PyCharm 后生效" : "，下次启动生效")
                    : "未找到 pycharm64.vmoptions，请先重新安装 PyCharm";
            LogBus.append("[设置] " + msg);
            runOnUiThread(() -> {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "heap").start();
    }

    /** 版本管理入口：选择并安装 / 清理下载包 / 卸载已装 PyCharm */
    private void chooseVersion() {
        if (SetupState.running) {
            Toast.makeText(this, "安装进行中，完成后再管理版本", Toast.LENGTH_SHORT).show();
            return;
        }
        String installed = Versions.installed(this);
        String[] items = {
                "选择并安装版本",
                "清理下载缓存",
                "卸载已安装的 PyCharm",
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("版本管理（当前：" + Versions.pretty(installed) + "）")
                .setItems(items, (d, w) -> {
                    if (w == 0) chooseEdition();
                    else if (w == 1) cleanCache();
                    else uninstallIde();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void chooseEdition() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择版本类型")
                .setItems(new String[]{"社区版 Community（免费）", "专业版 Professional（需激活）"}, (d, w) -> loadVersions(w == 1))
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadVersions(boolean pro) {
        Toast.makeText(this, "正在获取" + (pro ? "专业版" : "社区版") + "版本列表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.util.List<Versions.Ver> all = Versions.list(this, false);
                java.util.List<Versions.Ver> vs = new java.util.ArrayList<>();
                for (Versions.Ver v : all) if (v.pro == pro) vs.add(v);
                if (vs.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "暂无该类型的 ARM64 版本", Toast.LENGTH_LONG).show());
                    return;
                }
                String installed = Versions.installed(this);
                String want = Prefs.ideVersion(this);
                String[] labels = new String[vs.size()];
                int selectedIdx = vs.size() - 1;
                for (int i = 0; i < vs.size(); i++) {
                    Versions.Ver v = vs.get(i);
                    labels[i] = v.label() + (v.key().equals(installed) ? "（已安装）" : "");
                    if (!want.isEmpty() && v.key().equals(want)) selectedIdx = i;
                }
                final int si = selectedIdx;
                final String ins = installed;
                runOnUiThread(() -> showVersionList(vs, labels, si, ins, pro));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "获取版本列表失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "versions").start();
    }

    private void showVersionList(java.util.List<Versions.Ver> vs, String[] labels, int selectedIdx,
                                 String installed, boolean pro) {
        new android.app.AlertDialog.Builder(this)
                .setTitle((pro ? "专业版" : "社区版") + " 版本（旧 → 新，共 " + vs.size() + " 个 ARM64 包）"
                        + (pro ? "\n需 JetBrains 账号激活，含 30 天试用" : ""))
                .setSingleChoiceItems(labels, selectedIdx, (d, which) -> {
                    Versions.Ver v = vs.get(which);
                    d.dismiss();
                    selectVersion(v, installed);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectVersion(Versions.Ver v, String installed) {
        Prefs.setIdeVersion(this, v.key());
        refresh();
        if (v.key().equals(installed)) {
            Toast.makeText(this, "当前已是 " + v.pretty(), Toast.LENGTH_SHORT).show();
            return;
        }
        long free = new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        new android.app.AlertDialog.Builder(this)
                .setTitle("安装 PyCharm " + v.pretty())
                .setMessage("安装包 " + Downloader.fmt(v.size) + "，解包后另需约 "
                        + Downloader.fmt(v.size * 2) + "；当前可用 " + Downloader.fmt(free) + "。\n"
                        + "只替换 /opt/pycharm，系统镜像、apt 依赖与项目文件保留。"
                        + (v.pro ? "\n\n专业版首次启动需登录 JetBrains 账号激活（30 天试用）。" : ""))
                .setPositiveButton("下载并安装", (dd, w) -> SetupService.startIde(this))
                .setNegativeButton("取消", null)
                .show();
    }

    private void cleanCache() {
        SetupState.running = true;
        SetupState.operation = "清理缓存";
        SetupState.error = false;
        SetupState.indeterminate = true;
        SetupState.percent = 0;
        SetupState.stage = "扫描下载缓存";
        SetupState.detail = "查找 pycharm-*.tar.gz";
        refresh();
        new Thread(() -> {
            long freed = 0;
            try {
                java.io.File dl = Dirs.dl(this);
                java.io.File[] all = dl.listFiles();
                java.util.List<java.io.File> targets = new java.util.ArrayList<>();
                long totalBytes = 0;
                if (all != null) {
                    for (java.io.File f : all) {
                        String n = f.getName();
                        if (n.startsWith("pycharm-") && n.endsWith(".tar.gz")) {
                            targets.add(f);
                            totalBytes += f.length();
                        }
                    }
                }
                int total = targets.size();
                for (int i = 0; i < total; i++) {
                    java.io.File f = targets.get(i);
                    long len = f.length();
                    //noinspection ResultOfMethodCallIgnored
                    boolean ok = f.delete();
                    if (ok) freed += len;
                    SetupState.indeterminate = false;
                    SetupState.percent = total > 0 ? Math.min(99, (int) ((i + 1) * 99.0 / total)) : 99;
                    SetupState.stage = "清理安装包缓存";
                    SetupState.detail = "已删除 " + (i + 1) + " / " + total + " 个包  ·  "
                            + Downloader.fmt(freed) + " / " + Downloader.fmt(totalBytes);
                }
            } catch (Throwable ignored) {
            }
            SetupState.running = false;
            SetupState.indeterminate = false;
            SetupState.percent = 0;
            final long freedMb = freed;
            runOnUiThread(() -> {
                Toast.makeText(this, "已清理安装包缓存 " + Downloader.fmt(freedMb), Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "clean").start();
    }

    private void uninstallIde() {
        if (SetupState.running) return;
        if (IdeService.RUNNING) {
            Toast.makeText(this, "请先停止 PyCharm 再卸载", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("卸载已安装的 PyCharm")
                .setMessage("删除 /opt/pycharm 与版本标记（约 2~4 GB）。系统镜像（rootfs）和 apt 依赖保留，之后可只重装 IDE。")
                .setPositiveButton("卸载", (d, w) -> {
                    SetupState.running = true;
                    SetupState.operation = "卸载";
                    SetupState.percent = 0;
                    SetupState.indeterminate = false;
                    SetupState.error = false;
                    SetupState.stage = "准备卸载 PyCharm";
                    SetupState.detail = "正在扫描 /opt/pycharm…";
                    refresh();
                    new Thread(() -> {
                        String msg;
                        try {
                            java.io.File ide = new java.io.File(Dirs.rootfs(MainActivity.this), "opt/pycharm");
                            deleteIdePath(ide, "卸载 PyCharm", 2, 95);
                            deleteIdePath(new java.io.File(Dirs.rootfs(MainActivity.this), "opt/.extract"),
                                    "清理临时目录", 97, 1);
                            deleteIdePath(new java.io.File(Dirs.rootfs(MainActivity.this), "opt/.extract_java"),
                                    "清理临时目录", 98, 1);
                            msg = "已卸载 PyCharm";
                        } catch (Throwable t) {
                            msg = "卸载失败：" + t.getMessage();
                        }
                        //noinspection ResultOfMethodCallIgnored
                        Dirs.ideReadyMark(MainActivity.this).delete();
                        //noinspection ResultOfMethodCallIgnored
                        Dirs.ideVersionMark(MainActivity.this).delete();
                        SetupState.running = false;
                        SetupState.indeterminate = false;
                        SetupState.percent = 0;
                        SetupState.stage = "卸载完成";
                        final String m = msg;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }, "uninstall").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 卸载/清理目录：扫描阶段不确定进度，删除阶段按字节推进 */
    private void deleteIdePath(java.io.File target, String label, int fromPct, int spanPct) {
        if (target == null || !target.exists()) return;
        SetupState.indeterminate = true;
        SetupState.stage = label;
        SetupState.detail = "正在统计文件与体积…";
        Io.rmRf(target, new Io.DeleteProgress() {
            @Override public void onScan(long files, long bytes) {
                SetupState.indeterminate = true;
                SetupState.stage = label;
                SetupState.detail = "已扫描 " + files + " 个文件 · " + Downloader.fmt(bytes);
            }

            @Override public void onDelete(long doneFiles, long totalFiles, long doneBytes, long totalBytes) {
                double frac = totalBytes > 0 ? Math.min(1.0, (double) doneBytes / totalBytes)
                        : (totalFiles > 0 ? Math.min(1.0, (double) doneFiles / totalFiles) : 1.0);
                SetupState.indeterminate = false;
                SetupState.percent = Math.min(99, fromPct + (int) (frac * spanPct));
                SetupState.stage = label;
                SetupState.detail = "已删除 " + doneFiles + " / " + totalFiles + " 个文件  ·  "
                        + Downloader.fmt(doneBytes) + " / " + Downloader.fmt(totalBytes)
                        + "  " + (int) (frac * 100) + "%";
            }
        });
        SetupState.indeterminate = false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private boolean isReady() {
        return Dirs.setupReadyMark(this).exists() && Dirs.ideReadyMark(this).exists();
    }

    private void StatusBar() {
        // 占位：状态刷新统一走 refresh()
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(true);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            // 安装/下载 400ms；空闲降到 2s，避免主界面常驻高频重绘和 StatFs 查询
            ui.postDelayed(this, SetupState.running ? 400 : 2000);
        }
    };

    private void refresh() { refresh(true); }

    private void refresh(boolean forceDetail) {
        boolean ready = isReady();
        int percent = SetupState.percent;
        boolean indeterminate = SetupState.indeterminate || (SetupState.running && percent <= 0);
        if (percent != lastPercent || indeterminate != lastIndeterminate) {
            bar.setProgress(percent);
            bar.setIndeterminate(indeterminate);
            lastPercent = percent;
            lastIndeterminate = indeterminate;
        }

        String s;
        if (SetupState.running) {
            s = SetupState.operation + "中：" + SetupState.stage + "（" + SetupState.percent + "%）";
            if (!SetupState.detail.isEmpty()) s += "\n" + SetupState.detail;
        } else if (SetupState.error) {
            s = "安装失败：" + SetupState.errorText;
        } else if (ready) {
            s = IdeService.RUNNING ? "PyCharm 正在运行" : "环境已就绪";
        } else if (Dirs.rootfsReadyMark(this).exists()) {
            s = "系统镜像已就绪，继续安装容器依赖与 PyCharm";
        } else {
            s = "尚未安装：首次使用请点击「开始安装」";
        }
        if (!s.equals(lastStatusText)) { status.setText(s); lastStatusText = s; }
        int color = SetupState.error ? 0xFFFF5370 : (ready ? 0xFF41E3A0 : 0xFF7DE9FF);
        if (status.getCurrentTextColor() != color) status.setTextColor(color);

        long now = System.currentTimeMillis();
        if (forceDetail || now - lastDetailAt >= 5000) {
            lastDetailAt = now;
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            String installedIde = Versions.installed(this);
            String d = "设备内存 " + (mi.totalMem / 1073741824L) + " GB · 可用 " + (mi.availMem / 1048576L)
                    + " MB · 架构 " + android.os.Build.SUPPORTED_ABIS[0]
                    + "\n分辨率 " + Prefs.screen(this) + " · 堆 " + Prefs.heapMb(this) + " MB"
                    + "\n镜像源：" + Mirrors.NAMES[Math.min(Prefs.mirrorIndex(this), Mirrors.NAMES.length - 1)]
                    + "\nPyCharm：" + Versions.pretty(installedIde)
                    + " · 目标：" + (Prefs.ideVersion(this).isEmpty() ? "最新社区版" : Versions.pretty(Prefs.ideVersion(this)))
                    + "\n存储可用 " + Downloader.fmt(new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes());
            if (!d.equals(lastDetailText)) { detail.setText(d); lastDetailText = d; }
        }

        boolean running = SetupState.running, ideRunning = IdeService.RUNNING;
        if (firstRefresh || ready != lastReady || running != lastRunning || ideRunning != lastIdeRunning) {
            install.setEnabled(!running);
            launch.setEnabled(ready && !running);
            display.setEnabled(ready && !running);
            stop.setEnabled(ideRunning);
            version.setEnabled(!running);
            heap.setEnabled(!running);
            install.setAlpha(running ? 0.45f : 1f);
            launch.setAlpha(ready && !running ? 1f : 0.45f);
            display.setAlpha(ready && !running ? 1f : 0.45f);
            stop.setAlpha(ideRunning ? 1f : 0.45f);
            heap.setAlpha(running ? 0.45f : 1f);
            lastReady = ready;
            lastRunning = running;
            lastIdeRunning = ideRunning;
            firstRefresh = false;
        }

        if (LogBus.version() != lastLogVersion) {
            lastLogVersion = LogBus.version();
            final boolean follow = stickBottom;
            String full = LogBus.text(300);
            String shown = log.getText().toString();
            updatingLog = true;
            if (!shown.isEmpty() && full.startsWith(shown)) {
                // 只追加新增行：不重排已有内容，滚动位置天然不动
                log.append(full.substring(shown.length()));
            } else {
                log.setText(full);   // 首次或缓冲区已截断，整体替换
            }
            if (follow) {
                logScroll.post(() -> {
                    View child = logScroll.getChildAt(0);
                    int max = child == null ? 0 : Math.max(0, child.getHeight() - logScroll.getHeight());
                    logScroll.scrollTo(0, max);
                    updatingLog = false;
                });
            } else {
                updatingLog = false;
            }
        }
    }
}
