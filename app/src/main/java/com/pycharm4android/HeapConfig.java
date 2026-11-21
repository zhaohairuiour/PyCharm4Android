package com.pycharm4android;

import android.content.Context;

import java.io.File;

/** PyCharm JVM 堆参数：生成 vmoptions 托管块，并在 rootfs 内原位更新 */
public final class HeapConfig {
    private static final String START = "# >>> PYCHARM4ANDROID >>>";
    private static final String END = "# <<< PYCHARM4ANDROID <<<";
    private static final String IDE_VMOPTIONS = "opt/pycharm/bin/pycharm64.vmoptions";

    /** 写入 app 私有 runtime/vmoptions.txt，安装与热更新共用 */
    public static void writeVmoptions(Context c) {
        Io.writeText(new File(Dirs.runtime(c), "vmoptions.txt"), buildVmoptions(c));
    }

    /**
     * 直接改写 rootfs 内的 pycharm64.vmoptions。
     * 先删除上一版托管块与 1.9.7 及更早追加的无标记参数，再写入当前托管块；
     * 重复执行不会产生重复 -Xmx。JVM 只在启动时读取，运行中改文件不影响当前进程。
     *
     * @return false 表示 PyCharm 尚未安装
     */
    public static boolean applyToRootfs(Context c) {
        File f = new File(Dirs.rootfs(c), IDE_VMOPTIONS);
        if (!f.isFile()) return false;
        String kept = stripManaged(Io.readAll(f));
        if (!kept.isEmpty() && !kept.endsWith("\n")) kept += "\n";
        Io.writeText(f, kept + buildVmoptions(c));
        return Io.readAll(f).contains("-Xmx" + Prefs.heapMb(c) + "m");
    }

    /** 最近的可选档位，防止历史偏好值不在选项里 */
    public static int nearestOption(int mb) {
        int best = Const.HEAP_OPTIONS[0];
        for (int v : Const.HEAP_OPTIONS) {
            if (Math.abs(v - mb) < Math.abs(best - mb)) best = v;
        }
        return best;
    }

    private static String buildVmoptions(Context c) {
        StringBuilder vm = new StringBuilder();
        vm.append(START).append('\n');
        vm.append("-Xmx").append(Prefs.heapMb(c)).append("m\n");
        vm.append("-XX:ReservedCodeCacheSize=256m\n");
        vm.append("-XX:MaxMetaspaceSize=512m\n");
        vm.append("-Dide.browser.jcef.enabled=false\n");
        vm.append("-Didea.initially.ask.config=false\n");
        vm.append("-Dnosplash=true\n");
        vm.append("-Dsun.java2d.opengl=false\n");
        vm.append(END).append('\n');
        return vm.toString();
    }

    private static String stripManaged(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inBlock = false;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.equals(START)) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (line.equals(END)) inBlock = false;
                continue;
            }
            if (isManagedLine(line)) continue;
            out.append(line).append('\n');
        }
        // 收敛尾部空行，避免每次应用都变长
        while (out.length() >= 2 && out.charAt(out.length() - 1) == '\n'
                && out.charAt(out.length() - 2) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static boolean isManagedLine(String line) {
        return line.matches("-Xmx[0-9]+m")
                || line.equals("-XX:ReservedCodeCacheSize=256m")
                || line.equals("-XX:MaxMetaspaceSize=512m")
                || line.equals("-Dide.browser.jcef.enabled=false")
                || line.equals("-Didea.initially.ask.config=false")
                || line.equals("-Dnosplash=true")
                || line.equals("-Dsun.java2d.opengl=false");
    }

    private HeapConfig() {}
}
