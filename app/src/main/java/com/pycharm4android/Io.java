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

    /** 递归删除 */
    public static void rmRf(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) rmRf(k);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
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
