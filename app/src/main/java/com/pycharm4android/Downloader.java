package com.pycharm4android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/** 支持断点续传 + 多镜像自动回退的下载器 */
public final class Downloader {

    /** 当前实际提供数据的镜像主机（供 UI 显示） */
    public static volatile String currentHost = "";

    public interface Progress {
        /**
         * @param done  已下载字节
         * @param total 总字节（-1 表示未知）
         * @param speed 当前速度（字节/秒，0 表示尚未测出）
         */
        void onProgress(long done, long total, long speed);
    }

    /** 依次尝试多个镜像，任一成功即返回；全部失败抛出最后一个错误 */
    public static void downloadAny(String[] urls, File dst, Progress p, AtomicBoolean cancel) throws IOException {
        IOException last = null;
        for (String url : urls) {
            try {
                download(url, dst, p, cancel);
                return;
            } catch (IOException e) {
                last = e;
                if (cancel != null && cancel.get()) throw e;
                LogBus.append("[下载] 镜像不可用 " + url + " → " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("没有可用的下载镜像");
    }

    /**
     * 下载并校验：大小下限 + SHA-256。任一步不通过即删除文件并换下一个源，
     * 避免把截断/损坏的包留给解包步骤（旧版曾因只比大小而反复报 ZLIB 截断）。
     */
    public static void downloadVerified(String[] urls, File dst, Progress p, AtomicBoolean cancel,
                                       String expectSha, long minBytes) throws IOException {
        IOException last = null;
        for (String url : urls) {
            if (cancel != null && cancel.get()) throw new IOException("已取消");
            try {
                download(url, dst, p, cancel);
                if (minBytes > 0 && dst.length() < minBytes) {
                    throw new BadData("文件不完整（" + fmt(dst.length()) + "）");
                }
                if (expectSha != null && !expectSha.isEmpty()) {
                    String actual = sha256(dst);
                    if (!actual.equalsIgnoreCase(expectSha)) {
                        throw new BadData("SHA-256 不匹配（" + fmt(dst.length()) + "）");
                    }
                    LogBus.append("[下载] 校验通过 " + dst.getName() + " " + fmt(dst.length()));
                }
                return;
            } catch (BadData e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                LogBus.append("[下载] " + hostOf(url) + " 数据无效：" + e.getMessage() + "，已删除重试");
            } catch (IOException e) {
                last = e;
                if (cancel != null && cancel.get()) throw e;
                LogBus.append("[下载] " + hostOf(url) + " 失败：" + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("没有可用的下载镜像");
    }

    /** 校验类错误：文件已写入但内容不对，必须删除 */
    private static class BadData extends IOException {
        BadData(String m) { super(m); }
    }

    /** 文件 SHA-256（小写十六进制） */
    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[1024 * 1024];
                int n;
                while ((n = in.read(b)) > 0) md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用");
        }
    }

    /**
     * 下载到 dst（写入原文件实现续传；换源后仍可继续）
     */
    public static void download(String url, File dst, Progress p, AtomicBoolean cancel) throws IOException {
        dst.getParentFile().mkdirs();
        long existing = dst.exists() ? dst.length() : 0;

        HttpURLConnection conn = open(url, existing);
        int code = conn.getResponseCode();

        if (code == 416) { // 续传位置无效，重来
            conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            conn = open(url, 0);
            code = conn.getResponseCode();
        }
        if (code != 200 && code != 206) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }

        boolean append = (code == 206) && existing > 0;
        if (!append) {
            if (existing > 0) LogBus.append("[下载] " + hostOf(url) + " 不支持续传，从头下载");
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        currentHost = hostOf(url);
        long total = conn.getContentLengthLong() > 0
                ? (append ? existing + conn.getContentLengthLong() : conn.getContentLengthLong())
                : -1;

        LogBus.append((append ? "[下载] 续传 " : "[下载] 开始 ") + dst.getName()
                + (total > 0 ? "，共 " + fmt(total) : "") + "  ← " + hostOf(url));

        long done = append ? existing : 0;
        long lastNotify = 0, lastLog = 0;
        long winStart = System.currentTimeMillis(), winBytes = done, speed = 0;

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dst, append)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    out.flush();
                    conn.disconnect();
                    throw new IOException("已取消");
                }
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();

                if (now - winStart >= 1000) {
                    speed = (done - winBytes) * 1000L / (now - winStart);
                    winStart = now;
                    winBytes = done;
                }
                // 250ms 刷新一次，进度文字/进度条不跳帧
                if (p != null && (now - lastNotify >= 250 || total == done)) {
                    lastNotify = now;
                    p.onProgress(done, total, speed);
                }
                if (now - lastLog >= 3000) {
                    lastLog = now;
                    LogBus.append("[下载] " + fmt(done) + (total > 0 ? " / " + fmt(total) : "")
                            + "  " + fmt(speed) + "/s");
                }
            }
        } finally {
            conn.disconnect();
        }

        if (total > 0 && dst.length() < total) {
            throw new IOException("下载不完整：" + dst.length() + " / " + total);
        }
        if (p != null) p.onProgress(dst.length(), Math.max(dst.length(), total), speed);
        LogBus.append("[下载] 完成 " + dst.getName() + " " + fmt(dst.length()));
    }

    public static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "PyCharm4Android/1.0");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (from > 0) conn.setRequestProperty("Range", "bytes=" + from + "-");
        return conn;
    }

    /** 读取小文本（如 .sha256），失败返回 null */
    public static String fetchText(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "PyCharm4Android/1.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                byte[] b = new byte[8192];
                int n = in.read(b);
                return new String(b, 0, Math.max(n, 0)).trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String fmt(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1048576.0);
        return String.format("%.2fGB", bytes / 1073741824.0);
    }

    private Downloader() {}
}
