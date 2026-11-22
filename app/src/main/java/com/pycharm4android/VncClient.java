package com.pycharm4android;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 极简 RFB(RFB 3.8) 客户端：只使用 Raw / CopyRect 编码。
 * 本地回环 + 不做压缩，省掉编码/解码开销，直接把 BGRA 像素写进 int[] 帧缓冲。
 */
public final class VncClient implements Runnable {

    public interface Listener {
        void onConnected(int w, int h);
        void onUpdate(List<int[]> rects);
        void onError(Throwable t);
    }

    private final String host;
    private final int port;
    private final Listener listener;

    private volatile Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private int width, height;
    private int[] fb;
    private byte[] rawBuf = new byte[4];
    private int[] rawInts = new int[1];
    private IntBuffer rawIntBuffer;
    // 输入事件不能直接在 UI 线程写 socket（会抛 NetworkOnMainThreadException），统一排队由单线程发送
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile int[] cursorPix;
    private volatile int cursorW, cursorH, cursorHotX, cursorHotY;
    private final Object fbLock = new Object();
    private volatile boolean running = false;

    public VncClient(String host, int port, Listener l) {
        this.host = host;
        this.port = port;
        this.listener = l;
    }

    public boolean isRunning() { return running; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Object lock() { return fbLock; }
    public int[] framebuffer() { return fb; }
    public int[] cursorPixels() { return cursorPix; }
    public int cursorWidth() { return cursorW; }
    public int cursorHeight() { return cursorH; }
    public int cursorHotX() { return cursorHotX; }
    public int cursorHotY() { return cursorHotY; }

    @Override
    public void run() {
        running = true;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(512 * 1024);
            socket.setSoTimeout(12000);   // 握手阶段防止半包卡死
            // RFB 头/矩形头都是逐字节读取；不缓冲会让每个小字段都触发一次 socket read
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 16 * 1024));

            handshake();
            listener.onConnected(width, height);

            while (running) {
                requestUpdate();
                readMessage();
            }
        } catch (Throwable t) {
            if (running) {
                Log.w("VncClient", "断开", t);
                listener.onError(t);
            }
        } finally {
            running = false;
            close();
        }
    }

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String sver = new String(ver);
        if (!sver.startsWith("RFB ")) throw new IOException("不是 RFB 服务：" + sver);
        out.write("RFB 003.008\n".getBytes("US-ASCII"));
        out.flush();

        int nTypes = in.readUnsignedByte();
        if (nTypes == 0) {
            String reason = in.readUTF();
            throw new IOException("服务端拒绝：" + reason);
        }
        byte[] types = new byte[nTypes];
        in.readFully(types);
        int chosen = -1;
        for (byte t : types) if (t == 1) chosen = 1; // None
        if (chosen < 0) throw new IOException("需要 VNC 密码认证，但服务端未开放 None 认证");
        out.writeByte(chosen);
        out.flush();

        int secResult = in.readInt();
        if (secResult != 0) throw new IOException("认证失败");

        out.writeByte(1); // shared
        out.flush();

        width = in.readUnsignedShort();
        height = in.readUnsignedShort();
        int bpp = in.readUnsignedByte();
        int depth = in.readUnsignedByte();
        in.readUnsignedByte(); // big-endian
        in.readUnsignedByte(); // true colour
        in.readUnsignedShort(); // red max
        in.readUnsignedShort(); // green max
        in.readUnsignedShort(); // blue max
        int redShift = in.readUnsignedByte();
        in.readUnsignedByte(); // green shift
        in.readUnsignedByte(); // blue shift
        in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte(); // 3 字节 padding（原来多读了 1 字节，把 nameLen 吃掉，握手永久阻塞）
        int nameLen = in.readInt();
        if (nameLen < 0 || nameLen > 4096) throw new IOException("ServerInit 异常：nameLen=" + nameLen);
        byte[] name = new byte[nameLen];
        in.readFully(name);
        Log.i("VncClient", "server " + new String(name) + " " + width + "x" + height + " bpp=" + bpp + " depth=" + depth + " redShift=" + redShift);

        synchronized (fbLock) {
            fb = new int[width * height];
        }

        // SetPixelFormat：type(1)+pad(3) + 16 字节 pixel-format
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(32);   // bits-per-pixel
        out.writeByte(24);   // depth
        out.writeByte(0);    // little-endian
        out.writeByte(1);    // true colour
        out.writeShort(255); // red max
        out.writeShort(255); // green max
        out.writeShort(255); // blue max
        out.writeByte(16);   // red shift
        out.writeByte(8);    // green shift
        out.writeByte(0);    // blue shift
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);    // 3 字节 padding
        out.flush();

        socket.setSoTimeout(0);   // 握手完成，恢复阻塞读

        // SetEncodings: Raw(0), CopyRect(1)
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(2);
        out.writeInt(0);          // Raw
        out.writeInt(1);          // CopyRect
        // 不请求 Cursor 伪编码：x11vnc 的 Cursor 帧格式与实现不一致会造成协议错位，
        // 指针用 VncView 内的箭头兜底绘制。
        out.flush();
    }

    private void requestUpdate() throws IOException {
        synchronized (out) {
            out.writeByte(3);
            out.writeByte(1); // incremental
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(width);
            out.writeShort(height);
            out.flush();
        }
    }

    private void readMessage() throws IOException {
        int type = in.readUnsignedByte();
        switch (type) {
            case 0: readFramebufferUpdate(); break;
            case 1: { // SetColourMapEntries
                in.readUnsignedByte();
                in.readUnsignedShort();
                int n = in.readUnsignedShort();
                for (int i = 0; i < n * 3; i++) in.readUnsignedShort();
                break;
            }
            case 2: break; // Bell
            case 3: { // ServerCutText
                in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                int len = in.readInt();
                in.readFully(new byte[Math.max(len, 0)]);
                break;
            }
            default: throw new IOException("未知消息类型 " + type);
        }
    }

    private void readFramebufferUpdate() throws IOException {
        in.readUnsignedByte();
        int nRects = in.readUnsignedShort();
        List<int[]> rects = new ArrayList<>(nRects);
        for (int i = 0; i < nRects; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int enc = in.readInt();
            if (enc == 0) {
                readRaw(x, y, w, h);
                rects.add(new int[]{x, y, w, h});
            } else if (enc == -239) {          // Cursor 伪编码
                readCursor();
                rects.add(new int[]{0, 0, 0, 0});   // 仅触发一次重绘
            } else if (enc == 1) {
                int sx = in.readUnsignedShort();
                int sy = in.readUnsignedShort();
                copyRect(sx, sy, x, y, w, h);
                rects.add(new int[]{x, y, w, h});
            } else {
                throw new IOException("不支持的编码 " + enc);
            }
        }
        if (!rects.isEmpty()) listener.onUpdate(rects);
    }

    private void readCursor() throws IOException {
        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        int hx = in.readUnsignedShort();
        int hy = in.readUnsignedShort();
        if (w <= 0 || h <= 0 || w > 256 || h > 256) throw new IOException("Cursor 尺寸异常 " + w + "x" + h);
        int bytes = w * h * 4;
        byte[] buf = new byte[bytes];
        in.readFully(buf);
        int[] pix = new int[w * h];
        for (int i = 0; i < pix.length; i++) {
            int b = buf[i * 4] & 0xFF, g = buf[i * 4 + 1] & 0xFF, r = buf[i * 4 + 2] & 0xFF;
            int a = buf[i * 4 + 3] & 0xFF;
            if (a == 0) a = (r | g | b) != 0 ? 0xFF : 0x00;
            pix[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        cursorW = w; cursorH = h; cursorHotX = hx; cursorHotY = hy;
        cursorPix = pix;
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        if (w <= 0 || h <= 0) return;
        long pixelsLong = (long) w * h;
        if (pixelsLong > 16_000_000L) throw new IOException("Raw 矩形过大 " + w + "x" + h);
        int pixels = (int) pixelsLong;
        int bytes = pixels * 4;
        if (rawIntBuffer == null || rawInts.length < pixels || rawBuf.length < bytes) {
            // 复用缓冲区，避免每帧 4~8 MB 分配；大矩形保留复用
            rawInts = new int[pixels];
            rawBuf = new byte[bytes];
            rawIntBuffer = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        }
        in.readFully(rawBuf, 0, bytes);
        rawIntBuffer.clear();
        rawIntBuffer.get(rawInts, 0, pixels);
        synchronized (fbLock) {
            int[] f = fb;
            int p = 0;
            // SetPixelFormat 固定 32bpp little-endian、redShift=16；BGRA 字节序在小端下
            // 正好是 0xAARRGGBB，只需补不透明 alpha，避免逐通道 4 次数组访问和移位。
            for (int row = 0; row < h; row++) {
                int base = (y + row) * width + x;
                for (int col = 0; col < w; col++) {
                    f[base + col] = 0xFF000000 | rawInts[p++];
                }
            }
        }
    }

    private void copyRect(int sx, int sy, int dx, int dy, int w, int h) {
        synchronized (fbLock) {
            int[] f = fb;
            // 原地拷贝；纵向从下往上，避免覆盖尚未读取的源行。
            // 每行 System.arraycopy 自身可正确处理横向重叠，不再分配 w*h 临时数组。
            if (dy <= sy) {
                for (int row = 0; row < h; row++) {
                    System.arraycopy(f, (sy + row) * width + sx,
                            f, (dy + row) * width + dx, w);
                }
            } else {
                for (int row = h - 1; row >= 0; row--) {
                    System.arraycopy(f, (sy + row) * width + sx,
                            f, (dy + row) * width + dx, w);
                }
            }
        }
    }

    /** 鼠标事件：buttonMask 1=左键 2=中键 4=右键 8/16=滚轮 */
    public void pointer(int x, int y, int mask) {
        final int fx = clamp(x, width), fy = clamp(y, height);
        sender.execute(() -> writePointer(fx, fy, mask));
    }

    private void writePointer(int x, int y, int mask) {
        DataOutputStream o = out;
        if (!running || o == null) return;
        try {
            synchronized (o) {
                o.writeByte(5);
                o.writeByte(mask & 0xFF);
                o.writeShort(x);
                o.writeShort(y);
                o.flush();
            }
        } catch (IOException e) {
            Log.w("VncClient", "pointer", e);
        }
    }

    public void key(int keysym, boolean down) {
        sender.execute(() -> writeKey(keysym, down));
    }

    private void writeKey(int keysym, boolean down) {
        DataOutputStream o = out;
        if (!running || o == null) return;
        try {
            synchronized (o) {
                o.writeByte(4);
                o.writeByte(down ? 1 : 0);
                o.writeByte(0);
                o.writeByte(0);
                o.writeInt(keysym);
                o.flush();
            }
        } catch (IOException e) {
            Log.w("VncClient", "key", e);
        }
    }

    private static int clamp(int v, int max) {
        if (v < 0) return 0;
        return v >= max ? max - 1 : v;
    }

    public void close() {
        running = false;
        sender.shutdownNow();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }
}
