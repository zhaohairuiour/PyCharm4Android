package com.pycharm4android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 主界面背景：深空渐变 + 青色网格 + 右上柔光，纯 Canvas 绘制，不依赖图片资源。
 */
public class TechBackgroundView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TechBackgroundView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // RadialGradient 在硬件层上偶发色带
        grid.setColor(0x1400E5FF);
        grid.setStrokeWidth(dp(0.7f));
        dot.setColor(0x2200E5FF);
        dot.setStrokeWidth(dp(1.2f));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bg.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                new int[]{0xFF05080F, 0xFF0A1222, 0xFF060A12},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(w, 0, Math.max(w, h) * 0.85f,
                new int[]{0x5500D5FF, 0x22006BFF, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);
        canvas.drawRect(0, 0, w, h, glow);          // 右上青色柔光

        float step = dp(34);
        for (float x = 0; x <= w; x += step) canvas.drawLine(x, 0, x, h, grid);
        for (float y = 0; y <= h; y += step) canvas.drawLine(0, y, w, y, grid);

        // 每隔 5 格一个亮一点的交点，形成“坐标纸”层次
        for (float x = 0; x <= w; x += step * 5) {
            for (float y = 0; y <= h; y += step * 5) {
                canvas.drawCircle(x, y, dp(1.1f), dot);
            }
        }
    }
}
