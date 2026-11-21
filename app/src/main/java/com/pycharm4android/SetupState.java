package com.pycharm4android;

/** 全局状态（安装进度 / 运行状态），UI 轮询 */
public final class SetupState {
    public static volatile boolean running = false;
    public static volatile int percent = 0;
    public static volatile String stage = "";
    /** 下载明细：已下载 / 总量 / 速度 */
    public static volatile String detail = "";
    public static volatile boolean error = false;
    public static volatile String errorText = "";
    /** 请求中止当前安装（切换下载源时置位） */
    public static final java.util.concurrent.atomic.AtomicBoolean cancel =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 中止后是否自动用新配置重跑 */
    public static volatile boolean restartRequested = false;

    public static void set(String stageText, int p) {
        stage = stageText;
        percent = p;
        LogBus.append(stageText);
    }

    private SetupState() {}
}
