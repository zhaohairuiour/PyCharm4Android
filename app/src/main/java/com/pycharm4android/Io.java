package com.pycharm4android;

import android.content.Context;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/** 文件与权限工具 */
public final class Io {
    public static void copyAsset(Context c, String assetPath, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = c.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** chmod，失败不抛出 */
    public static void chmod(String path, int mode) {
        try { Os.chmod(path, mode); } catch (Exception ignored) {}
    }

    /** 递归删除进度：先统计文件数与字节数，再按删除量回调 */
    public interface DeleteProgress {
        void onScan(long files, long bytes);
        void onDelete(long doneFiles, long totalFiles, long doneBytes, long totalBytes);
    }

    /** 递归删除 */
    public static void rmRf(File f) {
        rmRf(f, null);
    }

    /**
     * 递归删除并回调进度。符号链接只删链接本身，不跟随目录，
     * 避免 rootfs 内的 usr-merge 链接被递归成重复删除。
     */
    public static void rmRf(File f, DeleteProgress p) {
        if (f == null) return;
        boolean symlink = Files.isSymbolicLink(f.toPath());
        if (!symlink && !f.exists()) return;
        if (p == null) {
            deleteTree(f);
            return;
        }
        long[] total = new long[2];
        long[] last = new long[]{0};
        countTree(f, total, p, last);
        p.onDelete(0, total[0], 0, total[1]);
        long[] done = new long[2];
        deleteTree(f, p, total, done, last);
        p.onDelete(done[0], total[0], done[1], total[1]);
    }

    private static void countTree(File f, long[] total, DeleteProgress p, long[] last) {
        if (Files.isSymbolicLink(f.toPath())) {
            total[0]++;
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) countTree(k, total, p, last);
        } else {
            total[0]++;
            total[1] += Math.max(0, f.length());
        }
        long now = System.currentTimeMillis();
        if (now - last[0] >= 300) {
            last[0] = now;
            p.onScan(total[0], total[1]);
        }
    }

    private static void deleteTree(File f) {
        if (Files.isSymbolicLink(f.toPath())) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void deleteTree(File f, DeleteProgress p, long[] total, long[] done, long[] last) {
        if (Files.isSymbolicLink(f.toPath())) {
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) done[0]++;
            reportDelete(p, total, done, last);
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k, p, total, done, last);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } else {
            long len = Math.max(0, f.length());
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) {
                done[0]++;
                done[1] += len;
            }
        }
        reportDelete(p, total, done, last);
    }

    private static void reportDelete(DeleteProgress p, long[] total, long[] done, long[] last) {
        long now = System.currentTimeMillis();
        if (now - last[0] >= 250) {
            last[0] = now;
            p.onDelete(done[0], total[0], done[1], total[1]);
        }
    }

    public static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return new String(b, 0, Math.max(n, 0));
        } catch (IOException e) {
            return "";
        }
    }

    /** 读取整个文本文件（不截断），失败返回空串 */
    public static String readAll(File f) {
        if (f == null || !f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** 覆盖写入小文本，失败不抛出 */
    public static void writeText(File f, String text) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        } catch (IOException e) {
            LogBus.append("[io] 写文件失败 " + f.getName() + "：" + e.getMessage());
        }
    }

    private Io() {}
}
