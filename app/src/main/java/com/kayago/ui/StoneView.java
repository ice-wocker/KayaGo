package com.kayago.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.kayago.game.Board;

/**
 * 圆形棋子图标：与棋盘上的棋子用同一套光影，保证观感一致。
 */
public class StoneView extends View {

    private byte stoneColor = Board.BLACK;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Shader fillShader;
    private Shader glossShader;
    private float shaderRadius = -1f;

    public StoneView(Context context) {
        this(context, null);
    }

    public StoneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        shadowPaint.setColor(0x55000000);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    public void setStoneColor(byte color) {
        if (stoneColor != color) {
            stoneColor = color;
            fillShader = null;
            glossShader = null;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(w, h) * 0.5f - Math.max(1f, Math.min(w, h) * 0.055f);
        if (r <= 0) {
            return;
        }
        if (fillShader == null || Math.abs(r - shaderRadius) > 0.5f) {
            buildShaders(r);
            shaderRadius = r;
        }
        final float cx = w * 0.5f;
        final float cy = h * 0.5f;

        canvas.drawCircle(cx + r * 0.05f, cy + r * 0.12f, r, shadowPaint);
        fillPaint.setShader(fillShader);
        canvas.drawCircle(cx, cy, r, fillPaint);
        fillPaint.setShader(glossShader);
        fillPaint.setAlpha(stoneColor == Board.BLACK ? 90 : 210);
        canvas.drawCircle(cx - r * 0.30f, cy - r * 0.34f, r * 0.58f, fillPaint);
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);

        if (stoneColor == Board.BLACK) {
            rimPaint.setStrokeWidth(Math.max(1f, r * 0.10f));
            rimPaint.setColor(0x40FFFFFF);
            canvas.drawCircle(cx, cy, r * 0.95f, rimPaint);
        } else {
            rimPaint.setStrokeWidth(Math.max(1f, r * 0.07f));
            rimPaint.setColor(0x33000000);
            canvas.drawCircle(cx, cy, r * 0.97f, rimPaint);
        }
    }

    private void buildShaders(float r) {
        if (stoneColor == Board.BLACK) {
            fillShader = new RadialGradient(-r * 0.34f, -r * 0.38f, r * 1.75f,
                    new int[]{0xFF6E6E6E, 0xFF262626, 0xFF070707},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        } else {
            fillShader = new RadialGradient(-r * 0.34f, -r * 0.38f, r * 1.75f,
                    new int[]{0xFFFFFFFF, 0xFFF7F3E8, 0xFFC6C0AE},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        glossShader = new RadialGradient(-r * 0.30f, -r * 0.34f, r * 0.72f,
                new int[]{0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }
}
