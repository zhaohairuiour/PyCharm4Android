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
    private TextView install, launch, display, stop, version;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastLogVersion = -1;
    private boolean stickBottom = true;   // 用户滚到底部才自动跟随
    private boolean updatingLog = false;

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
        new Thread(() -> {
            long freed = 0;
            java.io.File dl = Dirs.dl(this);
            java.io.File[] files = dl.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (n.startsWith("pycharm-") && n.endsWith(".tar.gz")) {
                        freed += f.length();
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
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
                .setPositiveButton("卸载", (d, w) -> new Thread(() -> {
                    String msg;
                    try {
                        Proot.run(this, "rm -rf /opt/pycharm");
                        msg = "已卸载 PyCharm";
                    } catch (Exception e) {
                        msg = "卸载失败：" + e.getMessage();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    Dirs.ideReadyMark(this).delete();
                    //noinspection ResultOfMethodCallIgnored
                    Dirs.ideVersionMark(this).delete();
                    final String m = msg;
                    runOnUiThread(() -> {
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                        refresh();
                    });
                }, "uninstall").start())
                .setNegativeButton("取消", null)
                .show();
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
            refresh();
            // 下载阶段 400ms 刷新，能看到 MB 与速度在跳
            ui.postDelayed(this, SetupState.running ? 400 : 800);
        }
    };

    private void refresh() {
        boolean ready = isReady();
        bar.setProgress(SetupState.percent);
        bar.setIndeterminate(SetupState.running && SetupState.percent <= 0);

        String s;
        if (SetupState.running) {
            s = "安装中：" + SetupState.stage + "（" + SetupState.percent + "%）";
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
        status.setText(s);
        status.setTextColor(SetupState.error ? 0xFFFF5370 : (ready ? 0xFF41E3A0 : 0xFF7DE9FF));

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        String installedIde = Versions.installed(this);
        detail.setText("设备内存 " + (mi.totalMem / 1073741824L) + " GB · 可用 " + (mi.availMem / 1048576L)
                + " MB · 架构 " + android.os.Build.SUPPORTED_ABIS[0]
                + "\n分辨率 " + Prefs.screen(this) + " · 堆 " + Prefs.heapMb(this) + " MB"
                + "\n镜像源：" + Mirrors.NAMES[Math.min(Prefs.mirrorIndex(this), Mirrors.NAMES.length - 1)]
                + "\nPyCharm：" + Versions.pretty(installedIde)
                + " · 目标：" + (Prefs.ideVersion(this).isEmpty() ? "最新社区版" : Versions.pretty(Prefs.ideVersion(this)))
                + "\n存储可用 " + Downloader.fmt(new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes()));

        install.setEnabled(!SetupState.running);
        launch.setEnabled(ready && !SetupState.running);
        display.setEnabled(ready);
        stop.setEnabled(IdeService.RUNNING);
        version.setEnabled(!SetupState.running);
        install.setAlpha(SetupState.running ? 0.45f : 1f);
        launch.setAlpha(ready && !SetupState.running ? 1f : 0.45f);
        display.setAlpha(ready ? 1f : 0.45f);
        stop.setAlpha(IdeService.RUNNING ? 1f : 0.45f);

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
