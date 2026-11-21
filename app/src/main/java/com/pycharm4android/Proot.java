package com.pycharm4android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 组装并启动 proot 容器命令 */
public final class Proot {

    public static ProcessBuilder builder(Context c, List<String> innerCmd) {
        ensureMountPoints(c);
        try {
            Rootfs.verify(c); // 幂等：修复 usr-merge 链接、补可执行位
        } catch (IOException e) {
            LogBus.append("[容器] 镜像校验失败：" + e.getMessage());
        }
        File proot = new File(Dirs.runtime(c), "proot");
        File rootfs = Dirs.rootfs(c);

        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(Dirs.tmp(c).getAbsolutePath() + ":/tmp");
        cmd.add("-b");
        cmd.add(Dirs.runtime(c).getAbsolutePath() + ":/opt/host");
        cmd.add("-b");
        cmd.add(Dirs.dl(c).getAbsolutePath() + ":/root/dl");

        File shared = Dirs.sharedStorage();
        if (shared != null) {
            cmd.add("-b");
            cmd.add(shared.getAbsolutePath() + ":/sdcard");
        }

        // 兜底：若 usr-merge 链接不可用（部分机型无法在应用私有目录建符号链接），
        // 直接把 /usr/lib、/usr/bin、/usr/sbin 绑到 /lib、/bin、/sbin，
        // 否则内核找不到 ld-linux，execve(/usr/bin/env) 会报 ENOENT。
        if (!new File(rootfs, "lib/ld-linux-aarch64.so.1").exists()) {
            bindFallback(cmd, rootfs, "usr/lib", "/lib");
            bindFallback(cmd, rootfs, "usr/bin", "/bin");
            bindFallback(cmd, rootfs, "usr/sbin", "/sbin");
        }

        cmd.add("-w");
        cmd.add("/root");

        cmd.add("/usr/bin/env");
        cmd.add("-u");
        cmd.add("LD_LIBRARY_PATH");
        cmd.add("-u");
        cmd.add("TMPDIR");
        cmd.add("HOME=/root");
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=C.UTF-8");
        cmd.add("DISPLAY=:1");
        cmd.add("PYCHARM_SCREEN=" + Prefs.screen(c));
        cmd.addAll(innerCmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        // proot 自身需要能找到 libtalloc.so.2 / libandroid-shmem.so
        env.put("LD_LIBRARY_PATH", Dirs.runtime(c).getAbsolutePath());
        env.put("PROOT_TMP_DIR", Dirs.tmp(c).getAbsolutePath());
        // 关键：Termux 版 proot 的 loader 路径是编译期写死的
        // /data/data/com.termux/files/usr/libexec/proot/loader，必须用 PROOT_LOADER 覆盖，
        // 否则每次 execve 都因找不到 loader 报 ENOENT（表现为 execve("/usr/bin/env"): No such file）
        env.put("PROOT_LOADER", new File(Dirs.runtime(c), "loader").getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
        pb.directory(c.getFilesDir());
        pb.redirectErrorStream(true);
        return pb;
    }

    /** proot 的绑定目标必须已存在 */
    public static void ensureMountPoints(Context c) {
        File rootfs = Dirs.rootfs(c);
        if (!rootfs.isDirectory()) return;
        String[] dirs = {"root/dl", "opt/host", "sdcard", "tmp", "dev", "proc", "sys"};
        for (String d : dirs) {
            File f = new File(rootfs, d);
            if (!f.exists()) //noinspection ResultOfMethodCallIgnored
                f.mkdirs();
        }
    }

    /** 运行一次性命令（安装/解包等），返回退出码 */
    public static int run(Context c, String... argv) throws IOException, InterruptedException {
        List<String> inner = new ArrayList<>();
        inner.add("/bin/bash");
        inner.add("--noprofile");
        inner.add("--norc");
        inner.add("-c");
        StringBuilder sb = new StringBuilder();
        for (String a : argv) sb.append(a).append(' ');
        inner.add(sb.toString());

        Process p = builder(c, inner).start();
        pump(p);
        return p.waitFor();
    }

    /** 持续运行（启动 IDE），返回进程对象；调用方负责读取输出 */
    public static Process start(Context c, String script) throws IOException {
        List<String> inner = new ArrayList<>();
        inner.add("/bin/bash");
        inner.add("--noprofile");
        inner.add("--norc");
        inner.add(script);
        return builder(c, inner).start();
    }

    public static void pump(Process p) {
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                String s = new String(buf, 0, n);
                LogBus.append(s);
            }
        } catch (Exception ignored) {
        }
    }

    /** /usr/* 绑定到 /lib、/bin、/sbin 的兜底挂载 */
    private static void bindFallback(List<String> cmd, File rootfs, String src, String dst) {
        File srcDir = new File(rootfs, src);
        if (!srcDir.isDirectory()) return;
        File dstPath = new File(rootfs, dst);
        if (!dstPath.exists()) {
            if (!dstPath.mkdirs()) {
                //noinspection ResultOfMethodCallIgnored
                dstPath.delete();
                //noinspection ResultOfMethodCallIgnored
                dstPath.mkdirs();
            }
        }
        cmd.add("-b");
        cmd.add(srcDir.getAbsolutePath() + ":" + dst);
    }

    private Proot() {}
}
