package com.pycharm4android;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

/** 前台服务：维持容器（Xvfb + x11vnc + PyCharm）运行 */
public class IdeService extends Service {

    private static final String ACTION_START = "com.pycharm4android.IDE_START";
    private static final int NOTIF_ID = 1002;
    public static volatile boolean RUNNING = false;

    private Process process;
    private Thread worker;

    public static void start(Context c) {
        Intent i = new Intent(c, IdeService.class);
        i.setAction(ACTION_START);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, IdeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, notification());
        if (worker == null) {
            worker = new Thread(this::runIde, "ide");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        return new Notification.Builder(this, App.CH_IDE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("PyCharm 运行中")
                .setContentText("虚拟桌面 127.0.0.1:5901")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** 每次启动都用 APK 内的最新脚本覆盖 /opt/host，避免升级后仍跑旧 run-ide.sh */
    private void refreshScripts() {
        try {
            File rt = Dirs.runtime(this);
            for (String n : new String[]{"run-ide.sh", "extract-ide.sh", "setup.sh", "openbox-theme.tar", "openbox-rc.xml"}) {
                File dst = new File(rt, n);
                Io.copyAsset(this, "scripts/" + n, dst);
                Io.chmod(dst.getPath(), 0755);
            }
            LogBus.append("[ide] 已刷新启动脚本 run-ide.sh=" + new File(rt, "run-ide.sh").length() + "B");
            boolean vmOk = HeapConfig.writeVmoptions(this);
            LogBus.append("[ide] JVM 运行参数 vmoptions=" + (vmOk ? "OK" : "FAIL"));
        } catch (Throwable t) {
            LogBus.append("[ide] 刷新启动脚本失败：" + t);
        }
    }

    private void runIde() {
        RUNNING = true;
        refreshScripts();
        File log = new File(Dirs.logs(this), "ide.log");
        try {
            LogBus.append("[ide] 启动容器 /opt/host/run-ide.sh");
            process = Proot.start(this, "/opt/host/run-ide.sh");
            Proot.pump(process);
            int code = process.waitFor();
            LogBus.append("[ide] 容器退出，exit=" + code);
        } catch (Throwable t) {
            Log.e("IdeService", "run failed", t);
            LogBus.append("[ide] 启动失败：" + t);
        } finally {
            RUNNING = false;
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
