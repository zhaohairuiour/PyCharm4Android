package com.pycharm4android;

import android.content.Context;

/** 下载镜像列表：国内镜像优先，失败自动回退 */
public final class Mirrors {

    public static final String[] NAMES = {
            "自动（国内镜像优先）",
            "阿里云",
            "华为云",
            "清华 TUNA",
            "中科大 USTC",
            "Ubuntu 官方",
    };

    /** 与 NAMES 对应的基础地址；索引 0 表示自动 */
    private static final String[] BASES = {
            "",
            "https://mirrors.aliyun.com/ubuntu-cdimage",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage",
            "https://cdimage.ubuntu.com",
    };

    private static final String[] AUTO_BASES = {
            "https://mirrors.aliyun.com/ubuntu-cdimage",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage",
            "https://cdimage.ubuntu.com",
    };

    private static final String ROOTFS_PATH =
            "ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz";

    /** 按用户选择返回候选 URL（自动模式下返回全部镜像） */
    public static String[] rootfsUrls(Context c) {
        int idx = Prefs.mirrorIndex(c);
        String[] bases;
        if (idx <= 0 || idx >= BASES.length) {
            bases = AUTO_BASES;
        } else {
            bases = new String[]{BASES[idx]};
        }
        String[] urls = new String[bases.length];
        for (int i = 0; i < bases.length; i++) urls[i] = bases[i] + "/" + ROOTFS_PATH;
        return urls;
    }

    /** PyCharm 没有国内官方镜像，保留官方源，附加 CDN 作为回退 */
    public static String[] ideUrls() {
        return ideUrls(Const.IDE_URL);
    }

    public static String[] ideUrls(String primary) {
        String cdn = primary.replace("download.jetbrains.com", "download-cdn.jetbrains.com");
        return cdn.equals(primary) ? new String[]{primary} : new String[]{primary, cdn};
    }

    private Mirrors() {}
}
