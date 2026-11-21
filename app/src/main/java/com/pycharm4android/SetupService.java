package com.pycharm4android;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** 一次性安装任务：运行时 -> rootfs -> apt 依赖 -> PyCharm */
public class SetupService extends Service {

    private static final String ACTION_SETUP = "com.pycharm4android.SETUP";
    private static final String ACTION_IDE = "com.pycharm4android.IDE";
    private static final int NOTIF_ID = 1001;
    public static volatile boolean RUNNING = false;

    private PowerManager.WakeLock wakeLock;
    private Thread worker;

    public static void start(Context c) {
        Intent i = new Intent(c, SetupService.class);
        i.setAction(ACTION_SETUP);
        c.startForegroundService(i);
    }

    /** 只重装 PyCharm（切换版本用），不动 rootfs 与 apt 依赖 */
    public static void startIde(Context c) {
        Intent i = new Intent(c, SetupService.class);
        i.setAction(ACTION_IDE);
        c.startForegroundService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_SETUP.equals(action) && !ACTION_IDE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification("正在准备安装…", 0));
        if (worker == null) {
            RUNNING = true;
            boolean ideOnly = ACTION_IDE.equals(action);
            worker = new Thread(() -> {
                if (ideOnly) doIdeOnly();
                else runWorker();
            }, "setup");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    /** 单独装 PyCharm：进度区间单独算，避免与整机安装混淆 */
    private void doIdeOnly() {
        SetupState.running = true;
        SetupState.error = false;
        SetupState.cancel.set(false);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pycharm4android:ide");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60 * 60 * 1000L);
        }
        try {
            if (!Dirs.rootfsReadyMark(this).exists() || !new File(Dirs.rootfs(this), "bin/bash").exists()) {
                throw new Exception("系统镜像尚未就绪，请先点「开始安装」");
            }
            step("检查容器依赖", 2);
            int chk = Proot.run(this, "test -x /usr/bin/Xvfb && test -x /usr/bin/x11vnc");
            if (chk != 0) throw new Exception("容器依赖尚未安装完成，请先点「开始安装」");
            step("准备安装 PyCharm", 4);
            installIde(true);
            //noinspection ResultOfMethodCallIgnored
            Dirs.ideReadyMark(this).createNewFile();
            // apt 依赖此前已完成，这里补齐整机就绪标记，主界面「启动」按钮才可用
            if (!Dirs.setupReadyMark(this).exists()) {
                //noinspection ResultOfMethodCallIgnored
                Dirs.setupReadyMark(this).createNewFile();
            }
            step("PyCharm 安装完成", 100);
            notify("PyCharm 安装完成", 100);
        } catch (Throwable t) {
            Log.e("SetupService", "ide install failed", t);
            SetupState.error = true;
            SetupState.errorText = String.valueOf(t.getMessage());
            LogBus.append("[错误] " + t);
            notify("安装失败：" + t.getMessage(), 0);
        } finally {
            SetupState.running = false;
            RUNNING = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification notification(String text, int percent) {
        return new Notification.Builder(this, App.CH_SETUP)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("PyCharm for Android")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(percent, 0), percent <= 0)
                .build();
    }

    private void notify(String text, int percent) {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, notification(text, percent));
    }

    /** 一轮安装；切换下载源会置 restartRequested，结束后自动用新源重跑（已下载部分续传） */
    private void runWorker() {
        while (true) {
            doSetup();
            if (!SetupState.restartRequested) break;
            SetupState.restartRequested = false;
            LogBus.append("[安装] 下载源已切换，重新开始（已下载部分续传）");
        }
        stopForeground(true);
        stopSelf();
    }

    private void doSetup() {
        SetupState.running = true;
        SetupState.error = false;
        SetupState.cancel.set(false);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pycharm4android:setup");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60 * 60 * 1000L);
        }
        try {
            step("部署 proot 运行时", 3);
            extractRuntime();

            step("检查 Linux 系统镜像", 7);
            ensureRootfs();

            step("安装容器内依赖（apt，约 5-15 分钟）", 28);
            runAptSetup();

            step("下载 PyCharm", 54);
            installIde(false);

            step("安装完成", 100);
            //noinspection ResultOfMethodCallIgnored
            Dirs.ideReadyMark(this).createNewFile();
            //noinspection ResultOfMethodCallIgnored
            Dirs.setupReadyMark(this).createNewFile();
            notify("安装完成", 100);
        } catch (Throwable t) {
            Log.e("SetupService", "setup failed", t);
            SetupState.error = true;
            SetupState.errorText = String.valueOf(t.getMessage());
            LogBus.append("[错误] " + t);
            notify("安装失败：" + t.getMessage(), 0);
        } finally {
            SetupState.running = false;
            RUNNING = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void step(String text, int percent) {
        SetupState.set(text, percent);
        SetupState.detail = "";
        notify(text, percent);
    }

    private static long lastNotifyAt = 0;

    /** 下载过程中高频刷新：进度条 + 明细 + 通知（1s 一次，避免刷爆通知栏） */
    private void onDownloadProgress(String label, int fromPct, int spanPct, long done, long total, long speed) {
        double frac = total > 0 ? Math.min(1.0, (double) done / total) : 0;
        SetupState.percent = fromPct + (int) (frac * spanPct);
        SetupState.stage = label;
        SetupState.detail = Downloader.fmt(done) + (total > 0 ? " / " + Downloader.fmt(total) : "")
                + (total > 0 ? "  " + (int) (frac * 100) + "%" : "")
                + (speed > 0 ? "  ·  " + Downloader.fmt(speed) + "/s" : "")
                + (Downloader.currentHost.isEmpty() ? "" : "  ·  " + Downloader.currentHost);
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt >= 1000) {
            lastNotifyAt = now;
            notify(label + " " + SetupState.detail, SetupState.percent);
        }
    }

    private void extractRuntime() throws Exception {
        File rt = Dirs.runtime(this);
        String[][] assets = {
                {"proot/proot", "proot"},
                {"proot/libtalloc.so.2", "libtalloc.so.2"},
                {"proot/libandroid-shmem.so", "libandroid-shmem.so"},
                {"proot/loader", "loader"},
                {"scripts/setup.sh", "setup.sh"},
                {"scripts/run-ide.sh", "run-ide.sh"},
                {"scripts/extract-ide.sh", "extract-ide.sh"},
        };
        for (String[] a : assets) {
            File dst = new File(rt, a[1]);
            Io.copyAsset(this, a[0], dst);
        }
        Io.chmod(new File(rt, "proot").getPath(), 0755);
        Io.chmod(new File(rt, "loader").getPath(), 0755);
        Io.chmod(new File(rt, "libtalloc.so.2").getPath(), 0644);
        Io.chmod(new File(rt, "libandroid-shmem.so").getPath(), 0644);
        Io.chmod(new File(rt, "setup.sh").getPath(), 0755);
        Io.chmod(new File(rt, "run-ide.sh").getPath(), 0755);
        LogBus.append("[运行时] loader=" + new File(rt, "loader").length() + "B proot=" + new File(rt, "proot").length() + "B");

        // 生成的 vmoptions 片段（受管块，重复写入不会产生重复 -Xmx）
        HeapConfig.writeVmoptions(this);
        LogBus.append("[运行时] proot 已部署 · 堆 " + Prefs.heapMb(this) + " MB");
    }

    private void ensureRootfs() throws Exception {
        File rootfs = Dirs.rootfs(this);
        if (Dirs.rootfsReadyMark(this).exists() && new File(rootfs, "bin/bash").exists()) {
            try {
                step("校验系统镜像结构", 7);
                Rootfs.verify(this);
                LogBus.append("[rootfs] 已存在，校验通过，跳过解包");
                return;
            } catch (IOException e) {
                LogBus.append("[rootfs] 现有镜像损坏：" + e.getMessage() + "，将重新解包");
                Io.rmRf(rootfs);
                //noinspection ResultOfMethodCallIgnored
                Dirs.rootfsReadyMark(this).delete();
            }
        }
        File tgz = new File(Dirs.dl(this), Const.ROOTFS_FILE);

        if (tgz.exists()) {
            step("校验系统镜像", 8);
            boolean ok = false;
            try {
                ok = tgz.length() >= Const.ROOTFS_MIN_BYTES
                        && Const.ROOTFS_SHA256.equalsIgnoreCase(Downloader.sha256(tgz));
            } catch (IOException e) {
                LogBus.append("[rootfs] 校验异常：" + e.getMessage());
            }
            if (ok) {
                LogBus.append("[rootfs] 本地镜像校验通过 " + Downloader.fmt(tgz.length()));
            } else {
                LogBus.append("[rootfs] 本地镜像无效（" + Downloader.fmt(tgz.length()) + "），删除重下");
                Io.rmRf(tgz);
            }
        }
        if (!tgz.exists()) downloadRootfs(tgz);

        step("解包系统镜像", 25);
        for (int attempt = 1; ; attempt++) {
            checkCancel();
            Io.rmRf(rootfs);
            try {
                TarGz.extract(tgz, rootfs);
                if (!new File(rootfs, "bin/bash").exists()) throw new IOException("rootfs 解包不完整");
                Rootfs.verify(this);
                break;
            } catch (IOException e) {
                LogBus.append("[rootfs] 解包失败（第 " + attempt + " 次）：" + e.getMessage());
                Io.rmRf(rootfs);
                if (attempt >= 2) throw new Exception("系统镜像两次解包均失败：" + e.getMessage() + "，请换下载源后重试");
                Io.rmRf(tgz); // 视为损坏，整包重下
                step("重新下载系统镜像", 20);
                downloadRootfs(tgz);
                step("重新解包系统镜像", 25);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        Dirs.rootfsReadyMark(this).createNewFile();
        LogBus.append("[rootfs] 完成");
    }

    private void downloadRootfs(File tgz) throws Exception {
        requireSpace(400_000_000L, "系统镜像");
        String[] urls = Mirrors.rootfsUrls(this);
        LogBus.append("[下载] 系统镜像候选源 " + urls.length + " 个，首选 " + Downloader.hostOf(urls[0]));
        Downloader.downloadVerified(urls, tgz,
                (done, total, speed) -> onDownloadProgress("下载系统镜像", 8, 16, done, total, speed),
                SetupState.cancel, Const.ROOTFS_SHA256, Const.ROOTFS_MIN_BYTES);
    }

    private void runAptSetup() throws Exception {
        // 容器自检：能执行 /usr/bin/env 才说明 rootfs 结构与解释器就绪
        step("检查容器可用性", 26);
        int probe = Proot.run(this, "echo CONTAINER_OK");
        if (probe != 0) {
            File r = Dirs.rootfs(this);
            LogBus.append("[容器] 自检失败 exit=" + probe
                    + " rootfs[bin=" + new File(r, "bin").exists()
                    + " lib=" + new File(r, "lib").exists()
                    + " ld=" + new File(r, "lib/ld-linux-aarch64.so.1").exists()
                    + " env=" + new File(r, "usr/bin/env").exists()
                    + " envSize=" + new File(r, "usr/bin/env").length()
                    + " bashSize=" + new File(r, "usr/bin/bash").length()
                    + " loader=" + new File(Dirs.runtime(this), "loader").length()
                    + " proot=" + new File(Dirs.runtime(this), "proot").length() + "]");
            throw new Exception("容器启动失败（exit " + probe + "）：proot 无法执行 /usr/bin/env，详见日志");
        }
        String mirror = Prefs.aptMirror(this);
        int code = Proot.run(this,
                "export APT_MIRROR='" + mirror + "'; /opt/host/setup.sh");
        if (code != 0) throw new Exception("容器依赖安装失败（exit " + code + "），请查看日志");
        LogBus.append("[apt] 依赖安装完成");
    }

    /**
     * 安装（或切换）PyCharm 版本：
     * - 版本取自 Versions（JetBrains API，2022.3 → 最新），用户在 UI 选择后存 Prefs
     * - 已装版本与目标一致则只重写运行参数
     * - 包名带版本号，换版本不会误用旧包；旧版本目录先删再解包
     */
    private void installIde(boolean ideOnly) throws Exception {
        Versions.Ver ver = Versions.resolve(this, Prefs.ideVersion(this));
        LogBus.append("[PyCharm] 目标版本 " + ver.pretty() + "（" + Downloader.fmt(ver.size) + "）");
        if (ver.pro) LogBus.append("[PyCharm] 专业版首次启动需登录 JetBrains 账号激活（含 30 天试用）");

        File ide = new File(Dirs.rootfs(this), "opt/pycharm");
        String installed = Versions.installed(this);
        if (new File(ide, "bin/pycharm.sh").exists() && (installed.equals(ver.key()) || installed.equals(ver.version))) {
            LogBus.append("[PyCharm] 已安装 " + installed + "，跳过下载");
            configureIde(ideOnly, ver.key());
            return;
        }
        if (!installed.isEmpty() && !installed.equals(ver.key()) && !installed.equals(ver.version)) {
            LogBus.append("[PyCharm] 版本切换 " + Versions.pretty(installed) + " → " + ver.pretty());
        }

        File tgz = new File(Dirs.dl(this), ideFileName(ver));
        if (tgz.exists() && ver.size > 0 && tgz.length() > ver.size) {
            LogBus.append("[PyCharm] 本地包大于官方体积，删除");
            Io.rmRf(tgz);
        }
        if (tgz.exists()) {
            String sha = shaOf(ver);
            if (sha != null && !Downloader.sha256(tgz).equalsIgnoreCase(sha)) {
                LogBus.append("[PyCharm] 本地包校验失败，删除重下");
                Io.rmRf(tgz);
            }
        }
        if (!tgz.exists() || (ver.size > 0 && tgz.length() < ver.size)) {
            downloadIde(tgz, ver, ideOnly);
        }

        step("解包 PyCharm " + ver.pretty(), 82);
        int rc = extractIdeInContainer(tgz);
        if (rc == 4) {                      // gzip 校验失败 => 包损坏，重下一次
            LogBus.append("[PyCharm] 压缩包校验失败，重新下载");
            Io.rmRf(tgz);
            downloadIde(tgz, ver, ideOnly);
            rc = extractIdeInContainer(tgz);
        }
        if (rc != 0) {
            LogBus.append("[PyCharm] 容器内解包失败（exit " + rc + "），改用 Java 解包器");
            step("解包 PyCharm（Java）", 82);
            if (!extractIdeInJava(tgz)) {
                Io.rmRf(tgz);
                throw new Exception("PyCharm 解包失败（容器 exit " + rc + "，Java 亦失败），详见日志 TAR_RC/DF 行");
            }
        }
        configureIde(ideOnly, ver.key());
        // 释放空间
        int rm = Proot.run(this, "rm -f /root/dl/" + tgz.getName());
        LogBus.append("[PyCharm] 安装完成 " + ver.pretty() + " rc=" + rm);
        //noinspection ResultOfMethodCallIgnored
        Dirs.ideReadyMark(this).createNewFile();
    }

    private static String ideFileName(Versions.Ver ver) {
        return (ver.pro ? "pycharm-pro-" : "pycharm-") + ver.version + "-aarch64.tar.gz";
    }

    private void configureIde(boolean ideOnly, String version) throws Exception {
        step("写入运行参数", ideOnly ? 90 : 88);
        HeapConfig.applyToRootfs(this);
        Proot.run(this,
                "grep -q 'ide.no.platform.update' /opt/pycharm/bin/idea.properties || echo 'ide.no.platform.update=true' >> /opt/pycharm/bin/idea.properties; "
                        + "chmod +x /opt/pycharm/bin/pycharm.sh");
        Io.writeText(Dirs.ideVersionMark(this), version);
        step("准备就绪", ideOnly ? 96 : 96);
    }

    private String shaOf(Versions.Ver ver) {
        if (ver.shaUrl == null || ver.shaUrl.isEmpty()) return null;
        String text = Downloader.fetchText(ver.shaUrl);
        if (text == null || text.isEmpty()) return null;
        String[] parts = text.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : null;
    }

    private void downloadIde(File tgz, Versions.Ver ver, boolean ideOnly) throws Exception {
        long need = ver.size > 0 ? (long) (ver.size * 3.9) + 400_000_000L : 4_200_000_000L;
        requireSpace(Math.max(4_200_000_000L, need), "PyCharm 下载与解包");
        String expect = shaOf(ver);
        if (expect == null) LogBus.append("[校验] 无官方 sha256，仅按官方体积校验（" + Downloader.fmt(ver.size) + "）");
        long min = ver.size > 0 ? ver.size : Const.IDE_MIN_BYTES;
        int from = ideOnly ? 10 : 54;
        int span = ideOnly ? 70 : 24;
        Downloader.downloadVerified(Mirrors.ideUrls(ver.url), tgz,
                (done, total, speed) -> onDownloadProgress("下载 PyCharm " + ver.pretty(), from, span, done, total, speed),
                SetupState.cancel, expect, min);
    }

    private void requireSpace(long bytes, String what) throws Exception {
        long free = new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        LogBus.append("[空间] " + what + " 需要约 " + Downloader.fmt(bytes) + "，当前可用 " + Downloader.fmt(free));
        if (free < bytes) {
            throw new Exception(what + "空间不足：需要约 " + Downloader.fmt(bytes) + "，仅剩 " + Downloader.fmt(free));
        }
    }

    private void checkCancel() throws Exception {
        if (SetupState.cancel.get()) throw new IOException("已取消（下载源已切换）");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * 容器内解包，返回 shell 退出码：0 成功 / 4 gzip 损坏 / 5 tar 失败 / 6 未找到 pycharm.sh / 其他为 bash 错误。
     * 全程打印诊断：文件列表、空间、inode、tar/gzip 版本、gzip -t、tar 输出的最后 40 行。
     */
    private int extractIdeInContainer(File tgz) throws Exception {
        File sh = new File(Dirs.runtime(this), "extract-ide.sh");
        Io.copyAsset(this, "scripts/extract-ide.sh", sh);
        Io.chmod(sh.getPath(), 0755);
        LogBus.append("[解包] 使用 /opt/host/extract-ide.sh 解包 " + tgz.getName());
        return Proot.run(this, "/opt/host/extract-ide.sh /root/dl/" + tgz.getName());
    }

    /** 不依赖容器 tar：用纯 Java TarGz 解到 rootfs/opt/.extract_java，再改名为 opt/pycharm */
    private boolean extractIdeInJava(File tgz) {
        File tmp = new File(Dirs.rootfs(this), "opt/.extract_java");
        File ide = new File(Dirs.rootfs(this), "opt/pycharm");
        try {
            Io.rmRf(tmp);
            Io.rmRf(ide);
            long t0 = System.currentTimeMillis();
            TarGz.extract(tgz, tmp);
            File root = findPycharmRoot(tmp, 0);
            if (root == null) {
                LogBus.append("[解包] Java 解包后未找到 bin/pycharm.sh");
                Io.rmRf(tmp);
                return false;
            }
            if (!root.renameTo(ide)) {
                LogBus.append("[解包] Java 解包：改名失败 " + root + " -> " + ide);
                Io.rmRf(tmp);
                return false;
            }
            Io.rmRf(tmp);
            Io.chmod(new File(ide, "bin/pycharm.sh").getPath(), 0755);
            LogBus.append("[解包] Java 解包完成，用时 " + ((System.currentTimeMillis() - t0) / 1000) + " s");
            return true;
        } catch (Exception e) {
            LogBus.append("[解包] Java 异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
            Io.rmRf(tmp);
            return false;
        }
    }

    /** 在解包目录里向下最多 3 层找含 bin/pycharm.sh 的根目录 */
    private static File findPycharmRoot(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3) return null;
        if (new File(dir, "bin/pycharm.sh").isFile()) return dir;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = findPycharmRoot(k, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

}