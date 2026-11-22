package com.pycharm4android;

import android.content.Context;

import java.io.File;

/** PyCharm JVM 堆参数：生成 vmoptions 托管块，并在 rootfs 内原位更新 */
public final class HeapConfig {
    private static final String START = "# >>> PYCHARM4ANDROID >>>";
    private static final String END = "# <<< PYCHARM4ANDROID <<<";

    /** 写入 app 私有 runtime/vmoptions.txt，安装与热更新共用；返回回读校验结果 */
    public static boolean writeVmoptions(Context c) {
        File f = new File(Dirs.runtime(c), "vmoptions.txt");
        File base = findVmoptions(c);
        String kept = base != null ? stripManaged(Io.readAll(base)) : "";
        Io.writeText(f, merge(kept, buildVmoptions(c)));
        return Io.readAll(f).contains("-Xmx" + Prefs.heapMb(c) + "m");
    }

    /**
     * 直接改写 rootfs 内的 pycharm64.vmoptions。
     * 先删除上一版托管块与 1.9.7 及更早追加的无标记参数，再写入当前托管块；
     * 重复执行不会产生重复 -Xmx。JVM 只在启动时读取，运行中改文件不影响当前进程。
     *
     * @return false 表示安装目录里没有可写的默认 vmoptions（仍会使用 runtime/vmoptions.txt）
     */
    public static boolean applyToRootfs(Context c) {
        File f = findVmoptions(c);
        if (f == null) return false;
        Io.chmod(f.getPath(), 0644);
        String kept = stripManaged(Io.readAll(f));
        Io.writeText(f, merge(kept, buildVmoptions(c)));
        boolean ok = Io.readAll(f).contains("-Xmx" + Prefs.heapMb(c) + "m");
        if (ok) writeVmoptions(c);   // 同步 PYCHARM_VM_OPTIONS 文件，保留官方默认 JVM 参数
        return ok;
    }

    private static String merge(String base, String managed) {
        if (base.isEmpty()) return managed;
        return base.endsWith("\n") ? base + managed : base + "\n" + managed;
    }

    /** 官方 Linux 包的默认文件；部分旧包/构建可能叫 pycharm.vmoptions */
    private static File findVmoptions(Context c) {
        File ide = new File(Dirs.rootfs(c), "opt/pycharm/bin");
        for (String name : new String[]{"pycharm64.vmoptions", "pycharm.vmoptions"}) {
            File f = new File(ide, name);
            if (f.isFile()) return f;
        }
        return null;
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
        vm.append("-Didea.filewatcher.disabled=true\n");
        vm.append("-Dide.no.platform.update=true\n");
        vm.append("-Dnosplash=true\n");
        vm.append("-Dsun.java2d.opengl=false\n");
        vm.append(END).append('\n');
        return vm.toString();
    }

    private static String stripManaged(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inBlock = false;
        String[] lines = text.split("\n", -1);
        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
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
        return line.matches("-Xmx[0-9]+[mMgG]")
                || line.equals("-XX:ReservedCodeCacheSize=256m")
                || line.equals("-XX:MaxMetaspaceSize=512m")
                || line.equals("-Dide.browser.jcef.enabled=false")
                || line.equals("-Didea.initially.ask.config=false")
                || line.equals("-Didea.filewatcher.disabled=true")
                || line.equals("-Dide.no.platform.update=true")
                || line.equals("-Dnosplash=true")
                || line.equals("-Dsun.java2d.opengl=false");
    }

    private HeapConfig() {}
}
