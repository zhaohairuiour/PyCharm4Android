package com.pycharm4android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * PyCharm 版本目录：同时读 Community(PCC) 与 Professional(PCP) 的官方 releases API，
 * 只保留提供 linuxARM64 包的版本，按版本号升序（2022.3 → 2026.x）。
 * 缓存为逐行文本（version;edition;url;sha;size），避免整段 JSON 被 4096 字节截断。
 */
public final class Versions {

    private static final String API_CE =
            "https://data.services.jetbrains.com/products/releases?code=PCC&type=release&latest=false";
    private static final String API_PRO =
            "https://data.services.jetbrains.com/products/releases?code=PCP&type=release&latest=false";
    private static final long CACHE_TTL = 12L * 3600 * 1000;

    public static final class Ver {
        public final String version;
        public final boolean pro;
        public final String url;
        public final String shaUrl;
        public final long size;

        Ver(String version, boolean pro, String url, String shaUrl, long size) {
            this.version = version;
            this.pro = pro;
            this.url = url;
            this.shaUrl = shaUrl;
            this.size = size;
        }

        public String key() {
            return (pro ? "pro:" : "ce:") + version;
        }

        public String label() {
            return version + (pro ? " · 专业版" : " · 社区版") + "  ·  " + Downloader.fmt(size);
        }

        public String pretty() {
            return version + (pro ? "（专业版）" : "（社区版）");
        }
    }

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "pycharm-versions.txt");
    }

    /** 升序版本列表（旧 → 新）；refresh=true 强制联网刷新 */
    public static List<Ver> list(Context c, boolean refresh) throws IOException {
        File f = cacheFile(c);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_TTL;
        if (fresh && !refresh) {
            List<Ver> cached = readCache(f);
            if (!cached.isEmpty()) return cached;
        }
        List<Ver> out = new ArrayList<>();
        for (String[] api : new String[][]{{API_CE, "PCC"}, {API_PRO, "PCP"}}) {
            boolean pro = "PCP".equals(api[1]);
            try {
                out.addAll(parse(fetch(api[0]), api[1], pro));
            } catch (IOException e) {
                LogBus.append("[版本] " + (pro ? "专业版" : "社区版") + "列表获取失败：" + e.getMessage());
            }
        }
        if (out.isEmpty()) {
            List<Ver> cached = readCache(f);
            if (!cached.isEmpty()) return cached;
            LogBus.append("[版本] 使用内置兜底版本列表");
            return fallback();
        }
        sort(out);
        writeCache(f, out);
        return out;
    }

    /** 按 key（ce:2025.3 / pro:2026.3）解析；空 key 返回最新社区版（无则最新任意版） */
    public static Ver resolve(Context c, String key) throws IOException {
        List<Ver> vs = list(c, false);
        if (key != null && !key.isEmpty()) {
            for (Ver v : vs) if (v.key().equals(key)) return v;
            // 兼容旧配置：只存了纯版本号
            for (Ver v : vs) if (!v.pro && v.version.equals(key)) return v;
            for (Ver v : vs) if (v.version.equals(key)) return v;
        }
        for (int i = vs.size() - 1; i >= 0; i--) if (!vs.get(i).pro) return vs.get(i);
        return vs.get(vs.size() - 1);
    }

    /** 已安装版本 key（未安装返回空串） */
    public static String installed(Context c) {
        return Io.readAll(Dirs.ideVersionMark(c)).trim();
    }

    public static String pretty(String key) {
        if (key == null || key.isEmpty()) return "未安装";
        boolean pro = key.startsWith("pro:");
        String v = key.startsWith("ce:") || pro ? key.substring(4) : key;
        return v + (pro ? "（专业版）" : "（社区版）");
    }

    private static void sort(List<Ver> out) {
        Collections.sort(out, new Comparator<Ver>() {
            @Override
            public int compare(Ver a, Ver b) {
                int c = compareVersion(a.version, b.version);
                if (c != 0) return c;
                return (a.pro ? 1 : 0) - (b.pro ? 1 : 0);
            }
        });
    }

    public static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<Ver> parse(String body, String rootKey, boolean pro) throws IOException {
        List<Ver> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(body).optJSONArray(rootKey);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String v = item.optString("version", "");
                JSONObject dl = item.optJSONObject("downloads");
                if (v.isEmpty() || dl == null) continue;
                JSONObject arm = dl.optJSONObject("linuxARM64");
                if (arm == null) continue;
                String link = arm.optString("link", "");
                if (link.isEmpty()) continue;
                out.add(new Ver(v, pro, link, arm.optString("checksumLink", ""), arm.optLong("size", 0)));
            }
        } catch (Exception e) {
            throw new IOException("解析版本列表失败：" + e.getMessage());
        }
        return out;
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "PyCharm4Android/1.0");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    private static void writeCache(File f, List<Ver> vs) {
        StringBuilder sb = new StringBuilder();
        for (Ver v : vs) {
            sb.append(v.version).append(';')
              .append(v.pro ? "1" : "0").append(';')
              .append(v.url).append(';')
              .append(v.shaUrl).append(';')
              .append(v.size).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(sb.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            LogBus.append("[版本] 写缓存失败：" + e.getMessage());
        }
    }

    private static List<Ver> readCache(File f) {
        List<Ver> out = new ArrayList<>();
        String text = Io.readAll(f);
        if (text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            String[] p = line.split(";");
            if (p.length < 5) continue;
            try {
                out.add(new Ver(p[0], "1".equals(p[1]), p[2], p[3], Long.parseLong(p[4])));
            } catch (NumberFormatException ignored) { }
        }
        sort(out);
        return out;
    }

    /** 内置兜底：两个 API 都不可达且无缓存时用 */
    private static List<Ver> fallback() {
        List<Ver> vs = new ArrayList<>();
        vs.add(new Ver("2022.3", false, ce("2022.3"), ce("2022.3") + ".sha256", 533_000_000L));
        vs.add(new Ver("2023.3", false, ce("2023.3"), ce("2023.3") + ".sha256", 0));
        vs.add(new Ver("2024.3.5", false, ce("2024.3.5"), ce("2024.3.5") + ".sha256", 750_000_000L));
        vs.add(new Ver("2025.3", false, "https://download.jetbrains.com/python/pycharm-2025.3-aarch64.tar.gz",
                "https://download.jetbrains.com/python/pycharm-2025.3-aarch64.tar.gz.sha256", 1_202_847_207L));
        vs.add(new Ver("2026.3", true, "https://download.jetbrains.com/python/pycharm-2026.3-aarch64.tar.gz",
                "https://download.jetbrains.com/python/pycharm-2026.3-aarch64.tar.gz.sha256", 1_311_000_000L));
        sort(vs);
        return vs;
    }

    private static String ce(String v) {
        return "https://download.jetbrains.com/python/pycharm-community-" + v + "-aarch64.tar.gz";
    }

    private Versions() {}
}
