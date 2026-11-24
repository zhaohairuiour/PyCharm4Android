package com.pycharm4android;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 直接绘制 RFB 帧缓冲的视图。
 * 触摸模型：单指拖动 = 移动鼠标（mask 0，不按键）；轻点 = 左键单击；长按 0.5 s 有震动反馈，
 * 长按后移动 = 按住左键拖拽，长按后直接松手 = 右键单击；
 * 双指 = 缩放/平移。鼠标指针优先用服务端 Cursor 伪编码的形状，收不到时画内置箭头。
 */
public class VncView extends View {

    private VncClient client;
    private Bitmap bitmap;
    private int fbW, fbH;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF dst = new RectF();
    private final RectF cursorDst = new RectF();

    private float zoom = 1f;
    private float panX = 0f, panY = 0f;

    private Bitmap cursorBitmap;
    private int[] cursorRef;

    // VNC 线程按帧合并更新，避免每个 RFB 消息都向主线程投递一个 Runnable
    private final Object pendingLock = new Object();
    private final ArrayList<int[]> pendingRects = new ArrayList<>();
    private boolean updateScheduled = false;
    private final Runnable flushUpdates = this::flushUpdates;
    private boolean hasPointer = false;
    private boolean rightClickNext = false;
    private boolean downPending = false;
    private boolean longArmed = false;
    private boolean dragging = false;
    private float downRawX, downRawY;
    private int curX, curY;
    private float downSlop, tapSlop;
    private long downTime = 0L;
    private final long longPressTimeout = 500L;

    // 双指手势
    private float pinchStartDist = 0f, pinchStartZoom = 1f, pinchStartPanX = 0f, pinchStartPanY = 0f;
    private float pinchStartCx = 0f, pinchStartCy = 0f;

    // 双指轻点 = 右键（长按之外的第二条可靠路径）
    private boolean twoTapCandidate = false;
    private long twoTapStart = 0L;
    private float twoTapX = 0f, twoTapY = 0f, twoTapMax = 0f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static int hintCount = 0;

    /** 直接震动 40 ms：performHapticFeedback 受系统「触摸震动」开关影响，关掉时完全无感 */
    private void buzz() {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(80, 255));
            } else {
                v.vibrate(80);
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable longPress = () -> {
        // 只按时间判定：手指按住时的轻微位移不再影响（Handler 可能早 1~2 ms 触发，这里不再二次校验）
        if (downPending && !longArmed && !dragging) armLongPress();
    };

    /** 进入长按状态：震动 + Toast + 日志，只做一次。随后移动才按左键，直接松手发右键。 */
    private void armLongPress() {
        if (longArmed || !downPending || dragging || client == null) return;
        longArmed = true;
        buzz();   // 直接震动，不依赖系统触感开关
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);   // 忽略系统“触摸反馈”开关
        Toast.makeText(getContext(), "长按已识别：拖动=拖拽，松手=右键", Toast.LENGTH_SHORT).show();
        LogBus.append("[触摸] 长按已识别（移动=拖拽，松手=右键）");
        postInvalidate();
    }

    public VncView(Context c, AttributeSet a) {
        super(c, a);
        setFocusable(true);
        setFocusableInTouchMode(true);
        downSlop = 80f * c.getResources().getDisplayMetrics().density;   // 只有明显滑动（80 dp）才取消长按
        tapSlop = 12f * c.getResources().getDisplayMetrics().density;    // 超过此位移不算单击
        arrowFill.setColor(Color.WHITE);
        arrowFill.setStyle(Paint.Style.FILL);
        arrowEdge.setColor(Color.BLACK);
        arrowEdge.setStyle(Paint.Style.STROKE);
        arrowEdge.setStrokeWidth(2f);
    }

    public void setClient(VncClient c) { this.client = c; }

    public void onConnected(int w, int h) {
        if (w <= 0 || h <= 0) return;
        fbW = w;
        fbH = h;
        // 不清 pending：握手后首个 RFB 更新可能已先于本回调排队，必须保留首帧
        // 同尺寸重连复用 Bitmap，避免每次重连分配约 4 MB；旧帧直接擦黑等首帧覆盖
        if (bitmap != null && bitmap.getWidth() == w && bitmap.getHeight() == h) {
            bitmap.eraseColor(Color.BLACK);
        } else {
            // 不 recycle 旧 bitmap：重连时绘制线程可能仍在读
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        postInvalidate();
    }

    /** 连接前清空上一连接的残留更新（旧 client 已 close） */
    public void clearPending() {
        synchronized (pendingLock) {
            pendingRects.clear();
            updateScheduled = false;
        }
    }

    /** VNC 线程调用：先入队，合并到下一次 UI 帧再 copy */
    public void queueUpdate(List<int[]> rects) {
        if (rects == null || rects.isEmpty()) return;
        synchronized (pendingLock) {
            for (int[] r : rects) {
                if (r != null && r.length >= 4) pendingRects.add(r);
            }
            if (updateScheduled) return;
            updateScheduled = true;
        }
        if (!post(flushUpdates)) {
            synchronized (pendingLock) {
                pendingRects.clear();
                updateScheduled = false;
            }
        }
    }

    private void flushUpdates() {
        List<int[]> batch;
        synchronized (pendingLock) {
            if (pendingRects.isEmpty()) {
                updateScheduled = false;
                return;
            }
            batch = new ArrayList<>(pendingRects);
            pendingRects.clear();
            updateScheduled = false;
        }
        applyUpdate(batch);
    }

    public void applyUpdate(List<int[]> rects) {
        if (bitmap == null || client == null) return;
        int[] fb = client.framebuffer();
        if (fb == null || fb.length < fbW * fbH) return;
        synchronized (client.lock()) {
            for (int[] r : rects) {
                int x = r[0], y = r[1], w = r[2], h = r[3];
                if (x < 0 || y < 0 || w <= 0 || h <= 0) continue;
                if (x + w > fbW) w = fbW - x;
                if (y + h > fbH) h = fbH - y;
                if (w <= 0 || h <= 0) continue;
                try {
                    bitmap.setPixels(fb, y * fbW + x, fbW, x, y, w, h);
                } catch (Throwable t) {
                    LogBus.append("[vnc] 绘制矩形失败 x=" + x + " y=" + y + " w=" + w + " h=" + h + "：" + t);
                }
            }
        }
        postInvalidate();
    }

    public void setZoom(float z) {
        zoom = Math.max(0.5f, Math.min(6f, z));
        if (zoom <= 1f) { panX = 0; panY = 0; }
        postInvalidate();
    }

    public float getZoom() { return zoom; }

    public void setRightClickNext(boolean v) { rightClickNext = v; }

    private float fitScale() {
        if (fbW == 0 || fbH == 0) return 1f;
        float sx = getWidth() / (float) fbW;
        float sy = getHeight() / (float) fbH;
        return Math.min(sx, sy);
    }

    private void computeDst() {
        float s = fitScale() * zoom;
        if (s <= 0f) s = 1f;
        float w = fbW * s, h = fbH * s;
        float maxPanX = Math.max(0, (w - getWidth()) / 2f);
        float maxPanY = Math.max(0, (h - getHeight()) / 2f);
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
        float left = (getWidth() - w) / 2f + panX;
        float top = (getHeight() - h) / 2f + panY;
        dst.set(left, top, left + w, top + h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (bitmap == null) return;
        computeDst();
        canvas.drawBitmap(bitmap, null, dst, paint);
        drawCursor(canvas);
    }

    /** 优先用服务端 Cursor 伪编码的形状；收不到就画内置箭头，保证能看见指针 */
    private void drawCursor(Canvas canvas) {
        if (client == null || !hasPointer || client.cursorHidden()) return;
        float s = fitScale() * zoom;
        if (s <= 0f) return;

        int[] cp = client.cursorPixels();
        int cw = client.cursorWidth(), ch = client.cursorHeight();
        if (cp != null && cw > 0 && ch > 0) {
            if (cp != cursorRef || cursorBitmap == null) {
                cursorRef = cp;
                try {
                    cursorBitmap = Bitmap.createBitmap(cp, cw, ch, Bitmap.Config.ARGB_8888);
                } catch (Throwable ignored) {
                    cursorBitmap = null;
                }
            }
            if (cursorBitmap != null) {
                // 服务端光标按 RFB 像素尺寸绘制；限制最大 16dp，避免异常大光标挡住桌面
                float drawW = cw * s, drawH = ch * s;
                float cap = getResources().getDisplayMetrics().density * 16f;
                float k = Math.max(drawW, drawH) > cap ? cap / Math.max(drawW, drawH) : 1f;
                float x = dst.left + curX * s - client.cursorHotX() * s * k;
                float y = dst.top + curY * s - client.cursorHotY() * s * k;
                cursorDst.set(x, y, x + drawW * k, y + drawH * k);
                canvas.drawBitmap(cursorBitmap, null, cursorDst, paint);
                return;
            }
        }

        float px = dst.left + curX * s, py = dst.top + curY * s;
        // 没有服务端光标时才画小箭头；不能让它随屏幕密度变成大块遮挡
        float k = getResources().getDisplayMetrics().density * 0.55f;
        arrow.reset();
        arrow.moveTo(px, py);
        arrow.lineTo(px + 13 * k, py + 9 * k);
        arrow.lineTo(px + 8 * k, py + 11 * k);
        arrow.lineTo(px + 12 * k, py + 19 * k);
        arrow.lineTo(px + 9 * k, py + 20 * k);
        arrow.lineTo(px + 5 * k, py + 12 * k);
        arrow.lineTo(px + 1 * k, py + 15 * k);
        arrow.close();
        arrowFill.setAlpha(210);
        arrowEdge.setAlpha(180);
        canvas.drawPath(arrow, arrowFill);
        canvas.drawPath(arrow, arrowEdge);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        postInvalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            return handleTouch(e);
        } catch (Throwable t) {
            LogBus.append("[触摸] 处理失败：" + t);
            return true;
        }
    }

    private boolean handleTouch(MotionEvent e) {
        if (client == null || bitmap == null) return true;
        float s = fitScale() * zoom;
        if (s <= 0.01f) s = 1f;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downRawX = e.getX();
                downRawY = e.getY();
                curX = clampX((int) ((e.getX() - dst.left) / s));
                curY = clampY((int) ((e.getY() - dst.top) / s));
                hasPointer = true;
                downPending = true;
                longArmed = false;
                dragging = false;
                downTime = SystemClock.uptimeMillis();
                client.pointer(curX, curY, 0);     // 先把鼠标移到触点，不按键
                ui.postDelayed(longPress, longPressTimeout);
                LogBus.append("[触摸] DOWN " + curX + "," + curY);
                postInvalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                ui.removeCallbacks(longPress);
                downPending = false;
                longArmed = false;
                if (dragging) { client.pointer(curX, curY, 0); dragging = false; }
                if (e.getPointerCount() == 2) {
                    pinchStartDist = dist(e);
                    pinchStartZoom = zoom;
                    pinchStartPanX = panX;
                    pinchStartPanY = panY;
                    pinchStartCx = (e.getX(0) + e.getX(1)) / 2f;
                    pinchStartCy = (e.getY(0) + e.getY(1)) / 2f;
                    twoTapCandidate = true;
                    twoTapStart = SystemClock.uptimeMillis();
                    twoTapX = pinchStartCx;
                    twoTapY = pinchStartCy;
                    twoTapMax = 0f;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2 && pinchStartDist > 0) {
                    float d = dist(e);
                    zoom = Math.max(0.5f, Math.min(6f, pinchStartZoom * (d / pinchStartDist)));
                    float cx = (e.getX(0) + e.getX(1)) / 2f;
                    float cy = (e.getY(0) + e.getY(1)) / 2f;
                    panX = pinchStartPanX + (cx - pinchStartCx);
                    panY = pinchStartPanY + (cy - pinchStartCy);
                    if (twoTapCandidate) {
                        twoTapMax = Math.max(twoTapMax, (float) Math.hypot(cx - twoTapX, cy - twoTapY));
                        if (twoTapMax > 24f * getResources().getDisplayMetrics().density) twoTapCandidate = false;
                    }
                    postInvalidate();
                } else {
                    float moved = (float) Math.hypot(e.getX() - downRawX, e.getY() - downRawY);
                    long elapsed = SystemClock.uptimeMillis() - downTime;
                    if (downPending && !longArmed && moved > downSlop && elapsed < longPressTimeout) {
                        downPending = false;   // 明显滑动：普通移动鼠标，不算长按
                        ui.removeCallbacks(longPress);
                    }
                    if (downPending && !longArmed && elapsed >= longPressTimeout) {
                        armLongPress();        // 不依赖 Handler：即使定时器被 UI 卡顿延迟，这里也能补上
                    }
                    curX = clampX((int) ((e.getX() - dst.left) / s));
                    curY = clampY((int) ((e.getY() - dst.top) / s));
                    if (longArmed && !dragging && moved > 12f * getResources().getDisplayMetrics().density) {
                        dragging = true;
                        client.pointer(curX, curY, 1);   // 长按后移动：按下左键开始拖拽
                        LogBus.append("[触摸] 长按拖拽开始");
                    }
                    client.pointer(curX, curY, dragging ? 1 : 0);   // 只有长按拖拽才带左键
                    postInvalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                pinchStartDist = 0;
                if (twoTapCandidate && e.getPointerCount() >= 2) {
                    long held = SystemClock.uptimeMillis() - twoTapStart;
                    if (held <= 400 && twoTapMax <= 24f * getResources().getDisplayMetrics().density) {
                        float mx = (e.getX(0) + e.getX(1)) / 2f;
                        float my = (e.getY(0) + e.getY(1)) / 2f;
                        int fx = clampX((int) ((mx - dst.left) / s));
                        int fy = clampY((int) ((my - dst.top) / s));
                        client.pointer(fx, fy, 4);
                        client.pointer(fx, fy, 0);
                        Toast.makeText(getContext(), "双指轻点 = 右键", Toast.LENGTH_SHORT).show();
                        LogBus.append("[触摸] 双指轻点 → 右键 " + fx + "," + fy);
                    }
                }
                twoTapCandidate = false;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                ui.removeCallbacks(longPress);
                float moved = (float) Math.hypot(e.getX() - downRawX, e.getY() - downRawY);
                long elapsed = SystemClock.uptimeMillis() - downTime;
                LogBus.append("[触摸] UP moved=" + (int) moved + " t=" + elapsed
                        + "ms armed=" + longArmed + " drag=" + dragging);
                if (dragging) {
                    client.pointer(curX, curY, 0);
                    dragging = false;
                } else if (longArmed || (downPending && elapsed >= longPressTimeout)) {
                    if (!longArmed) armLongPress();   // 一次 MOVE 都没收到也能给出震动/Toast
                    // 长按后没有拖动：按右键，等价于桌面端 context menu
                    client.pointer(curX, curY, 4);
                    client.pointer(curX, curY, 0);
                    LogBus.append("[触摸] 长按松手 → 右键");
                } else if (downPending && moved <= tapSlop) {
                    int mask = rightClickNext ? 4 : 1;
                    rightClickNext = false;
                    client.pointer(curX, curY, mask);   // 轻点 = 单击
                    client.pointer(curX, curY, 0);
                }
                downPending = false;
                longArmed = false;
                pinchStartDist = 0;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                ui.removeCallbacks(longPress);
                LogBus.append("[触摸] CANCEL");
                if (dragging) { client.pointer(curX, curY, 0); dragging = false; }
                downPending = false;
                longArmed = false;
                pinchStartDist = 0;
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int clampX(int x) { return Math.max(0, Math.min(fbW - 1, x)); }
    private int clampY(int y) { return Math.max(0, Math.min(fbH - 1, y)); }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
