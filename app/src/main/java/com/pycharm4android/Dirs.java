package com.pycharm4android;

import android.content.Context;

import java.io.File;

/** 应用私有目录布局 */
public final class Dirs {
    /** Linux 根文件系统 */
    public static File rootfs(Context c) { return new File(c.getFilesDir(), "rootfs"); }

    /** proot 及其依赖库、脚本 */
    public static File runtime(Context c) {
        File f = new File(c.getFilesDir(), "runtime");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** 下载缓存（tar.gz） */
    public static File dl(Context c) {
        File f = new File(c.getFilesDir(), "dl");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** 容器 /tmp 的宿主目录 */
    public static File tmp(Context c) {
        File f = new File(c.getFilesDir(), "tmp");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File logs(Context c) {
        File f = new File(c.getFilesDir(), "logs");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File rootfsReadyMark(Context c) { return new File(c.getFilesDir(), ".rootfs_ready"); }
    public static File setupReadyMark(Context c) { return new File(c.getFilesDir(), ".setup_ready"); }
    public static File ideReadyMark(Context c) { return new File(c.getFilesDir(), ".ide_ready"); }

    /** 已安装的 PyCharm 版本号（纯文本） */
    public static File ideVersionMark(Context c) { return new File(c.getFilesDir(), ".ide_version"); }

    /** 用户在手机共享存储里的项目目录（容器内挂到 /sdcard） */
    public static File sharedStorage() {
        File f = new File("/storage/emulated/0");
        return f.exists() ? f : null;
    }

    private Dirs() {}
}
