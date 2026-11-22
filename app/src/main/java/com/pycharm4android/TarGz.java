package com.pycharm4android;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 的 tar.gz 解包器：保留权限位、符号链接、硬链接。
 * 关键点：每个条目数据区必须补齐到 512 字节边界（pax/GNU 长名记录同样要跳填充），
 * 否则后续头部错位，会把数据块当成头部、按超大 size 读取，最终报 "Unexpected end of ZLIB input stream"。
 */
public final class TarGz {

    private static final int BLOCK = 512;

    /** 解包进度：done/total 为压缩包已读/总字节，current 为当前条目路径 */
    public interface Progress {
        void onProgress(long done, long total, String current);
    }

    public static void extract(File archive, File dest) throws IOException {
        extract(archive, dest, null);
    }

    /** gzip 尾部 ISIZE，解压前即可拿到近似总大小（< 4 GB） */
    public static long uncompressedSize(File archive) {
        if (archive == null || archive.length() < 18) return 0;
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(archive.length() - 4);
            return raf.readInt() & 0xffffffffL;
        } catch (IOException e) {
            return 0;
        }
    }

    public static void extract(File archive, File dest, Progress progress) throws IOException {
        dest.mkdirs();
        int files = 0;
        String paxPath = null, paxLink = null, longName = null, longLink = null;
        Reporter reporter = null;

        try (CountingInputStream raw = new CountingInputStream(
                new BufferedInputStream(new FileInputStream(archive), 256 * 1024));
             InputStream in = new BufferedInputStream(new GZIPInputStream(raw, 256 * 1024), 256 * 1024)) {
            reporter = new Reporter(progress, raw, archive.length());

            byte[] hdr = new byte[BLOCK];
            while (true) {
                int n = readFully(in, hdr);
                if (n < BLOCK) break;              // 正常结束或尾部不完整
                if (isZeroBlock(hdr)) break;

                String name = field(hdr, 0, 100);
                int mode = (int) (num(hdr, 100, 8) & 07777);
                long size = num(hdr, 124, 12);
                char type = (char) (hdr[156] == 0 ? '0' : hdr[156]);
                String link = field(hdr, 157, 100);

                if (type == 'x' || type == 'g') {   // pax 扩展头
                    String body = readData(in, size);
                    if (type == 'x') {
                        Map<String, String> kv = parsePax(body);
                        if (kv.containsKey("path")) paxPath = kv.get("path");
                        if (kv.containsKey("linkpath")) paxLink = kv.get("linkpath");
                    }
                    skipPad(in, size);
                    continue;
                }
                if (type == 'L') {                  // GNU 长文件名
                    longName = readData(in, size);
                    skipPad(in, size);
                    continue;
                }
                if (type == 'K') {                  // GNU 长链接名
                    longLink = readData(in, size);
                    skipPad(in, size);
                    continue;
                }

                String path = paxPath != null ? paxPath : (longName != null ? longName : name);
                String target = paxLink != null ? paxLink : (longLink != null ? longLink : link);
                paxPath = null;
                paxLink = null;
                longName = null;
                longLink = null;

                if (path.startsWith("./")) path = path.substring(2);
                if (path.isEmpty() || path.equals(".")) {
                    if (type != '5') {
                        skipFully(in, size);
                        skipPad(in, size);
                    }
                    continue;
                }

                // 设备节点在无 root 的 Android 上无法创建，跳过（运行时由 proot 绑定宿主 /dev）
                boolean isDev = path.startsWith("dev/") || path.equals("dev");
                reporter.path(path);

                File out = safeChild(dest, path);
                if (out == null) {
                    skipFully(in, size);
                    skipPad(in, size);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                switch (type) {
                    case '5': { // 目录
                        out.mkdirs();
                        Io.chmod(out.getPath(), mode | 0700);
                        if (size > 0) {
                            skipFully(in, size);
                            skipPad(in, size);
                        }
                        break;
                    }
                    case '2': { // 符号链接
                        if (size > 0) skipFully(in, size);
                        if (!isDev) {
                            try {
                                Os.symlink(target, out.getPath());
                            } catch (Exception e) {
                                LogBus.append("[解包] 符号链接失败 " + path + " : " + e.getMessage());
                            }
                        }
                        skipPad(in, size);
                        break;
                    }
                    case '1': { // 硬链接
                        if (size > 0) skipFully(in, size);
                        File src = safeChild(dest, target);
                        if (src != null && src.exists() && !isDev) {
                            try {
                                Os.link(src.getPath(), out.getPath());
                            } catch (Exception e) {
                                try { Io.copyFile(src, out); } catch (IOException ignored) {}
                            }
                        }
                        skipPad(in, size);
                        break;
                    }
                    case '3':
                    case '4':
                    case '6': { // 块设备 / 字符设备 / FIFO
                        skipFully(in, size);
                        skipPad(in, size);
                        break;
                    }
                    default: { // 普通文件（'0' / '7' / ' '）
                        if (isDev) {
                            if (path.equals("dev")) out.mkdirs();
                            skipFully(in, size);
                            skipPad(in, size);
                            break;
                        }
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            copyData(in, fos, size, reporter);
                        }
                        skipPad(in, size);
                        Io.chmod(out.getPath(), mode | 0600);
                        files++;
                        if (files % 2000 == 0) LogBus.append("[解包] 已写入 " + files + " 个文件…");
                        break;
                    }
                }
            }
        }
        if (reporter != null) reporter.finish();
        LogBus.append("[解包] 完成，共 " + files + " 个文件 -> " + dest.getAbsolutePath());
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;
        CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int n = super.read();
            if (n >= 0) count++;
            return n;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    private static final class Reporter {
        private final Progress progress;
        private final CountingInputStream counter;
        private final long total;
        private String current = "";
        private long lastAt = 0;

        Reporter(Progress progress, CountingInputStream counter, long total) {
            this.progress = progress;
            this.counter = counter;
            this.total = total;
        }

        void path(String path) {
            current = path;
            report(false);
        }

        void tick() {
            report(false);
        }

        void finish() {
            if (progress != null) progress.onProgress(total, total, current);
        }

        private void report(boolean force) {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            if (!force && now - lastAt < 250) return;
            lastAt = now;
            long done = total > 0 ? Math.min(counter.count, total) : counter.count;
            progress.onProgress(done, total, current);
        }
    }

    private static Map<String, String> parsePax(String body) {
        Map<String, String> map = new HashMap<>();
        int i = 0;
        while (i < body.length()) {
            int sp = body.indexOf(' ', i);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(body.substring(i, sp));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > body.length()) break;
            // 记录格式："<len> <key>=<value>\n"，去掉末尾换行
            int end = i + len;
            if (end > sp + 1 && body.charAt(end - 1) == '\n') end--;
            String rec = body.substring(sp + 1, end);
            int eq = rec.indexOf('=');
            if (eq > 0) map.put(rec.substring(0, eq), rec.substring(eq + 1));
            i += len;
        }
        return map;
    }

    private static File safeChild(File dest, String path) {
        try {
            File f = new File(dest, path);
            String base = dest.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (!p.equals(base) && !p.startsWith(base + File.separator)) return null;
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && end < h.length && h[end] != 0) end++;
        return new String(h, off, Math.max(0, end - off), StandardCharsets.UTF_8).trim();
    }

    private static long num(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) { // base-256
            long v = h[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            char c = (char) h[i];
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
            else if (c == ' ') continue;
            else break;
        }
        return v;
    }

    /** 跳过一个条目的 512 字节对齐填充 */
    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) skipFully(in, pad);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r < 0) throw new IOException("tar 数据截断（缺 " + remaining + " 字节）");
            remaining -= r;
        }
    }

    private static String readData(InputStream in, long size) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(size, 1 << 20));
        long remaining = size;
        byte[] buf = new byte[4096];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new IOException("tar 元数据截断");
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static void copyData(InputStream in, FileOutputStream out, long size, Reporter reporter) throws IOException {
        long remaining = size;
        byte[] buf = new byte[64 * 1024];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new IOException("tar 数据截断");
            out.write(buf, 0, n);
            remaining -= n;
            reporter.tick();
        }
    }

    private TarGz() {}
}
