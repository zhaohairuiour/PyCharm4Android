package com.pycharm4android;

/** 全局状态（安装进度 / 运行状态），UI 轮询 */
public final class SetupState {
    public static volatile boolean running = false;
    /** 当前任务：安装 / 卸载 / 清理缓存 */
    public static volatile String operation = "安装";
    public static volatile int percent = 0;
    public static volatile boolean indeterminate = false;
    public static volatile String stage = "";
    /** 进度明细：下载量、解包位置、删除文件数等 */
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
        indeterminate = false;
        LogBus.append(stageText);
    }

    private SetupState() {}
}
