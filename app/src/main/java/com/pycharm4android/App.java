package com.pycharm4android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {
    public static final String CH_SETUP = "setup";
    public static final String CH_IDE = "ide";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_SETUP, "环境安装", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_IDE, "PyCharm 运行", NotificationManager.IMPORTANCE_LOW));
        LogBus.append("应用启动");
        installCrashHandler();
    }

    /** 未捕获异常写入 crash.txt，下次启动可在日志面板看到堆栈 */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String s = "线程 " + t.getName() + "\n" + sw;
                if (s.length() > 5000) s = s.substring(0, 5000);
                Io.writeText(new java.io.File(getFilesDir(), "crash.txt"), s);
                LogBus.append("[崩溃] " + e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
