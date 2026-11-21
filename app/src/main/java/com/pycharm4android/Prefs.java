package com.pycharm4android;

import android.content.Context;
import android.content.SharedPreferences;

/** 用户可调参数 */
public final class Prefs {
    private static final String NAME = "settings";

    public static String screen(Context c) {
        return sp(c).getString("screen", Const.DEFAULT_SCREEN);
    }

    public static void setScreen(Context c, String v) {
        sp(c).edit().putString("screen", v).apply();
    }

    public static int heapMb(Context c) {
        return sp(c).getInt("heap", Const.DEFAULT_HEAP_MB);
    }

    public static void setHeap(Context c, int v) {
        sp(c).edit().putInt("heap", v).apply();
    }

    public static String aptMirror(Context c) {
        return sp(c).getString("apt_mirror", Const.APT_MIRROR);
    }

    public static void setAptMirror(Context c, String v) {
        sp(c).edit().putString("apt_mirror", v).apply();
    }

    /** rootfs 下载镜像索引，见 Mirrors.NAMES（0 = 自动） */
    public static int mirrorIndex(Context c) {
        return sp(c).getInt("mirror_index", 0);
    }

    public static void setMirrorIndex(Context c, int v) {
        sp(c).edit().putInt("mirror_index", v).apply();
    }

    /** 选择的 PyCharm 版本，空串表示最新 ARM64 版本 */
    public static String ideVersion(Context c) {
        return sp(c).getString("ide_version", "");
    }

    public static void setIdeVersion(Context c, String v) {
        sp(c).edit().putString("ide_version", v).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private Prefs() {}
}
