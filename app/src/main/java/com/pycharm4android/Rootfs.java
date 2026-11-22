package com.pycharm4android;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/**
 * rootfs 结构校验与修复。
 * Ubuntu 24.04 采用 usr-merge：/bin、/lib、/sbin 都是指向 /usr/... 的符号链接，
 * 缺失或悬挂时内核找不到 ELF 解释器，proot 启动会直接报
 * execve("/usr/bin/env"): No such file or directory，而不只是 apt 失败。
 */
public final class Rootfs {

    /** 校验关键路径与可执行位；首次校验后写标记，后续启动只做关键项检查 */
    public static void verify(Context c) throws IOException {
        File r = Dirs.rootfs(c);
        if (!new File(r, "usr/bin").isDirectory()) {
            throw new IOException("系统镜像缺少 /usr/bin（解包不完整）");
        }
        ensureLink(r, "bin", "usr/bin");
        ensureLink(r, "lib", "usr/lib");
        ensureLink(r, "sbin", "usr/sbin");
        if (new File(r, "usr/lib64").isDirectory()) ensureLink(r, "lib64", "usr/lib64");

        // 关键文件每次都确认存在并补可执行位，成本只有 5 次 chmod
        for (String p : new String[]{"usr/bin/env", "usr/bin/bash", "usr/bin/tar", "usr/bin/sh"}) {
            File f = new File(r, p);
            if (!f.exists()) throw new IOException("系统镜像缺少 /" + p + "（解包不完整）");
            Io.chmod(f.getPath(), 0755);
        }
        File ld = new File(r, "lib/ld-linux-aarch64.so.1");
        if (!ld.exists()) throw new IOException("系统镜像缺少 aarch64 动态链接器（/lib/ld-linux-aarch64.so.1）");
        Io.chmod(ld.getPath(), 0755);

        File mark = new File(r, ".p4a-rootfs-verified");
        if (mark.isFile()) return;   // 已做过全量 chmod，避免每次 proot 启动遍历整个 usr/bin
        // 首次安装/重新解包后做一次全量兜底：部分机型解包后模式位可能没落地
        long t = System.currentTimeMillis();
        chmodTree(new File(r, "usr/bin"), 0755);
        chmodTree(new File(r, "usr/sbin"), 0755);
        Io.writeText(mark, "verified " + t);
        LogBus.append("[rootfs] 结构校验通过：env/bash/tar/ld 均可执行（全量 chmod "
                + (System.currentTimeMillis() - t) + " ms）");
    }

    /** 链接可用返回 true，不做创建 */
    public static boolean linkOk(File rootfs, String name) {
        return new File(rootfs, name).exists();
    }

    private static void ensureLink(File rootfs, String name, String target) {
        File path = new File(rootfs, name);
        if (path.exists()) return;   // 正常路径不刷日志，避免 Proot.builder 每次调用都产生 5 条字符串
        if (isSymlink(path)) { // 悬挂链接
            //noinspection ResultOfMethodCallIgnored
            path.delete();
        }
        try {
            Os.symlink(target, path.getPath());
            LogBus.append("[rootfs] 已重建链接 " + name + " -> " + target);
        } catch (Exception e) {
            LogBus.append("[rootfs] 建链接失败 " + name + "：" + e.getMessage() + "，改用硬链接镜像目录");
            mirror(new File(rootfs, target), path);
        }
    }

    /** 符号链接不可用时：把目标目录镜像成实体目录（优先硬链接，失败再复制） */
    private static void mirror(File src, File dst) {
        if (!src.exists()) {
            LogBus.append("[rootfs] 镜像源不存在：" + src.getAbsolutePath());
            return;
        }
        if (src.isDirectory()) {
            if (!dst.exists()) //noinspection ResultOfMethodCallIgnored
                dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) mirror(k, new File(dst, k.getName()));
            return;
        }
        try {
            Os.link(src.getPath(), dst.getPath());
        } catch (Exception e) {
            try { Io.copyFile(src, dst); } catch (IOException ignored) { }
        }
    }

    private static void chmodTree(File dir, int mode) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) chmodTree(k, mode);
            else Io.chmod(k.getPath(), mode);
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }


    private Rootfs() {}
}
