package com.pycharm4android;

import java.util.ArrayList;
import java.util.List;

/** 简易日志总线：安装/运行日志，UI 轮询展示 */
public final class LogBus {
    private static final int MAX = 600;
    private static final List<String> LINES = new ArrayList<>();
    private static long version = 0;

    public static synchronized void append(String line) {
        if (line == null) return;
        for (String s : line.split("\n")) {
            if (s.isEmpty()) continue;
            LINES.add(s);
            if (LINES.size() > MAX) LINES.remove(0);
        }
        version++;
    }

    public static synchronized List<String> tail(int n) {
        int from = Math.max(0, LINES.size() - n);
        return new ArrayList<>(LINES.subList(from, LINES.size()));
    }

    public static synchronized long version() { return version; }

    public static synchronized String text(int n) {
        StringBuilder sb = new StringBuilder();
        for (String s : tail(n)) sb.append(s).append('\n');
        return sb.toString();
    }

    private LogBus() {}
}
