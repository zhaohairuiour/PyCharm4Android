package com.pycharm4android;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * RFB Hextile（编码 5）解码器。
 *
 * <p>纯 Java、无 Android 依赖，便于在桌面 JVM 上用 x11vnc 做像素级对拍。
 * Hextile 把矩形切成 16×16 的瓦片，每片可用「背景色 + 若干单色/彩色子矩形」表示，
 * 对 IDE 这种大面积纯色 + 小段文字的界面，通常比 Raw 少 1~2 个数量级的数据量。
 *
 * <p>像素格式约定为握手时协商的 32bpp、little-endian、RGB888（redShift=16），
 * 即 4 字节 BGRA，转成 int 时只需补 0xFF alpha。
 */
public final class HextileDecoder {

    private static final int TILE = 16;
    private static final int RAW_BYTES = TILE * TILE * 4;

    private final byte[] px = new byte[4];
    private final byte[] raw = new byte[RAW_BYTES];
    private final ByteBuffer rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    private final IntBuffer rawInts = rawBuf.asIntBuffer();

    /**
     * 解码一个 Hextile 矩形并写入帧缓冲。
     *
     * @param fb   目标帧缓冲（长度至少 fbW*fbH）
     * @param fbW  帧缓冲行宽
     * @param x,y  矩形左上角
     * @param w,h  矩形尺寸（服务端保证不越界；这里仍做裁剪防御）
     */
    public void decode(DataInputStream in, int[] fb, int fbW, int fbH,
                       int x, int y, int w, int h) throws IOException {
        int maxW = Math.min(w, fbW - x);
        int maxH = Math.min(h, fbH - y);
        if (maxW <= 0 || maxH <= 0) return;

        int bg = 0xFF000000;
        int fg = 0xFF000000;

        for (int ty = 0; ty < maxH; ty += TILE) {
            int th = Math.min(TILE, maxH - ty);
            for (int tx = 0; tx < maxW; tx += TILE) {
                int tw = Math.min(TILE, maxW - tx);
                int sub = in.readUnsignedByte();

                if ((sub & 0x01) != 0) {                 // Raw 瓦片：tw*th 个 32bpp 像素
                    int bytes = tw * th * 4;
                    in.readFully(raw, 0, bytes);
                    rawInts.clear();
                    int p = 0;
                    for (int row = 0; row < th; row++) {
                        int base = (y + ty + row) * fbW + x + tx;
                        for (int col = 0; col < tw; col++) {
                            fb[base + col] = 0xFF000000 | rawInts.get(p++);
                        }
                    }
                    continue;
                }

                if ((sub & 0x02) != 0) bg = readPixel(in);   // BackgroundSpecified
                if ((sub & 0x04) != 0) fg = readPixel(in);   // ForegroundSpecified

                // 非 Raw 瓦片先铺背景色（背景色在同一矩形内跨瓦片保持）。
                // Arrays.fill 走 JVM intrinsic，比逐像素循环快数倍。
                int baseY = y + ty, baseX = x + tx;
                for (int row = 0; row < th; row++) {
                    int base = (baseY + row) * fbW + baseX;
                    Arrays.fill(fb, base, base + tw, bg);
                }

                int n = (sub & 0x08) != 0 ? in.readUnsignedByte() : 0;   // AnySubrects
                boolean coloured = (sub & 0x10) != 0;                    // SubrectsColoured
                for (int i = 0; i < n; i++) {
                    int color = coloured ? readPixel(in) : fg;
                    int xy = in.readUnsignedByte();
                    int wh = in.readUnsignedByte();
                    int sx = xy >> 4, sy = xy & 0x0F;
                    int sw = (wh >> 4) + 1, sh = (wh & 0x0F) + 1;
                    int x2 = Math.min(tw, sx + sw), y2 = Math.min(th, sy + sh);
                    for (int row = sy; row < y2; row++) {
                        int base = (baseY + row) * fbW + baseX + sx;
                        Arrays.fill(fb, base, base + (x2 - sx), color);
                    }
                }
            }
        }
    }

    private int readPixel(DataInputStream in) throws IOException {
        in.readFully(px, 0, 4);
        return 0xFF000000
                | ((px[2] & 0xFF) << 16)
                | ((px[1] & 0xFF) << 8)
                | (px[0] & 0xFF);
    }
}
