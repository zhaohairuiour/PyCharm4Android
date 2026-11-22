package com.pycharm4android;

/** 常量与可调参数 */
public final class Const {
    public static final String PKG = "com.pycharm4android";
    /** 与 build.gradle.kts 的 versionName 保持一致，主界面显示，便于确认装的是哪版 */
    public static final String VERSION_NAME = "1.14.0";

    /** Ubuntu 24.04 base rootfs（arm64，tar.gz，约 30 MB，官方地址） */
    public static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz";
    public static final String ROOTFS_FILE = "ubuntu-base-arm64.tar.gz";
    /** 官方 SHA-256（ubuntu-base 24.04.4 base arm64）；镜像站文件应一致 */
    public static final String ROOTFS_SHA256 =
            "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
    /** 完整性下限：低于此值一律视为下载未完成 */
    public static final long ROOTFS_MIN_BYTES = 20_000_000L;

    /** PyCharm Community（arm64 Linux 版，免登录、免授权） */
    public static final String IDE_VERSION = "2024.3.5";
    public static final String IDE_URL =
            "https://download.jetbrains.com/python/pycharm-community-" + IDE_VERSION + "-aarch64.tar.gz";
    public static final String IDE_FILE = "pycharm.tar.gz";
    public static final long IDE_MIN_BYTES = 600_000_000L;

    /** 容器内 apt 源（可在设置里改） */
    public static final String APT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports";

    /** 虚拟机默认分辨率（越低越流畅） */
    public static final String DEFAULT_SCREEN = "1280x800";
    /** JVM 堆上限（MB），6 GB 内存设备建议 768~1024 */
    public static final int DEFAULT_HEAP_MB = 1024;
    /** 可选堆档位（MB），从保守到激进 */
    public static final int[] HEAP_OPTIONS = {512, 768, 1024, 1536, 2048, 3072};

    public static final int VNC_PORT = 5901;
    public static final int WEB_PORT = 6080;

    private Const() {}
}
