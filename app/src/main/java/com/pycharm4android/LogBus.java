package com.pycharm4android;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 简易日志总线：安装/运行日志，UI 轮询展示；环形队列避免每次 remove(0) 搬移数组 */
public final class LogBus {
    private static final int MAX = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX);
    private static long version = 0;

    public static synchronized void append(String line) {
        if (line == null || line.isEmpty()) return;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '\n') continue;
            add(line.substring(start, i));
            start = i + 1;
        }
        if (start < line.length()) add(line.substring(start));
        version++;
    }

    private static void add(String s) {
        if (s.isEmpty()) return;
        if (LINES.size() >= MAX) LINES.removeFirst();
        LINES.addLast(s);
    }

    public static synchronized List<String> tail(int n) {
        if (n <= 0 || LINES.isEmpty()) return new ArrayList<>();
        int skip = Math.max(0, LINES.size() - n);
        List<String> out = new ArrayList<>(Math.min(n, LINES.size()));
        Iterator<String> it = LINES.iterator();
        for (int i = 0; i < skip && it.hasNext(); i++) it.next();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    public static synchronized long version() { return version; }

    public static synchronized String text(int n) {
        StringBuilder sb = new StringBuilder();
        for (String s : tail(n)) sb.append(s).append('\n');
        return sb.toString();
    }

    private LogBus() {}
}
