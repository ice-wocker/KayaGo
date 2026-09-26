package com.kayago.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.kayago.R;
import com.kayago.game.Board;

/**
 * 棋盘绘制与落子交互。
 *
 * <p>采用“两次点击确认”的落子方式：第一次点选会在交叉点显示半透明棋子，
 * 再点同一点才真正落子，避免手机上误触。
 */
public class BoardView extends View {

    /** 落子回调，返回 true 表示接受（视图会清掉待确认标记）。 */
    public interface OnMoveListener {
        boolean onMove(int p);
    }

    private Board board;
    private int lastMove = Board.PASS;
    private int ghost = -1;
    private int hint = -1;
    private boolean inputEnabled = true;
    private int[] ownership;
    private OnMoveListener moveListener;

    private final Paint boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stoneEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ownerBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ownerWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF boardRect = new RectF();

    private final int colorWood;
    private final int colorLine;
    private final int colorBlack;
    private final int colorWhite;
    private final int colorAccent;
    private final float density;

    private float step = 0;
    private float originX = 0;
    private float originY = 0;
    private float stoneRadius = 0;
    private float shaderStep = -1;
    private Shader blackShader;
    private Shader whiteShader;

    public BoardView(Context context) {
        this(context, null);
    }

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        colorWood = context.getColor(R.color.board_wood);
        colorLine = context.getColor(R.color.board_line);
        colorBlack = context.getColor(R.color.stone_black);
        colorWhite = context.getColor(R.color.stone_white);
        colorAccent = context.getColor(R.color.accent);

        gridPaint.setColor(colorLine);
        starPaint.setColor(colorLine);
        markPaint.setColor(colorAccent);
        accentPaint.setColor(colorAccent);
        accentPaint.setStyle(Paint.Style.STROKE);
        stoneEdgePaint.setStyle(Paint.Style.STROKE);
        stoneEdgePaint.setColor(0x33000000);
        boardPaint.setColor(colorWood);
        boardEdgePaint.setColor(0x40000000);
        shadowPaint.setColor(0x38000000);
        ownerBlackPaint.setColor(0x99202020);
        ownerWhitePaint.setColor(0x99F0F0E6);
        setFocusable(true);
    }

    // ------------------------------------------------------------------

    public void setBoard(Board board) {
        this.board = board;
        this.lastMove = Board.PASS;
        this.ghost = -1;
        this.hint = -1;
        this.ownership = null;
        invalidate();
    }

    public void setLastMove(int p) {
        this.lastMove = p;
        if (p != Board.PASS) {
            this.hint = -1;
        }
        invalidate();
    }

    public void setInputEnabled(boolean enabled) {
        this.inputEnabled = enabled;
        if (!enabled) {
            this.ghost = -1;
        }
        invalidate();
    }

    public void setHint(int p) {
        this.hint = p;
        invalidate();
    }

    public void setOwnership(int[] map) {
        this.ownership = map;
        invalidate();
    }

    public void clearGhost() {
        this.ghost = -1;
        invalidate();
    }

    public void setOnMoveListener(OnMoveListener l) {
        this.moveListener = l;
    }

    /** 棋盘变化后刷新。 */
    public void refresh() {
        invalidate();
    }

    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (board == null) {
            return;
        }
        final int size = board.size;
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float pad = density * 6f;
        float avail = Math.min(w - 2 * pad, h - 2 * pad);
        step = avail / (size + 1.35f);
        float boardLen = step * (size - 1);
        originX = (w - boardLen) / 2f;
        originY = (h - boardLen) / 2f;
        stoneRadius = step * 0.47f;

        if (step != shaderStep) {
            buildShaders();
            shaderStep = step;
        }

        float ex = step * 0.72f;
        boardRect.set(originX - ex, originY - ex, originX + boardLen + ex, originY + boardLen + ex);
        float round = step * 0.22f;
        canvas.drawRoundRect(boardRect, round, round, boardEdgePaint);
        RectF inner = new RectF(boardRect);
        inner.inset(density * 1.5f, density * 1.5f);
        canvas.drawRoundRect(inner, round, round, boardPaint);

        // 网格
        gridPaint.setStrokeWidth(Math.max(density, step * 0.045f));
        for (int i = 0; i < size; i++) {
            float y = originY + i * step;
            float x = originX + i * step;
            canvas.drawLine(originX, y, originX + boardLen, y, gridPaint);
            canvas.drawLine(x, originY, x, originY + boardLen, gridPaint);
        }

        // 星位
        for (int idx : starPoints(size)) {
            float cx = originX + (idx % size) * step;
            float cy = originY + (idx / size) * step;
            canvas.drawCircle(cx, cy, Math.max(density * 1.2f, step * 0.1f), starPaint);
        }

        // 归属（形势显示）
        if (ownership != null) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int p = board.point(x, y);
                    if (board.color[p] != Board.EMPTY || p >= ownership.length) {
                        continue;
                    }
                    int owner = ownership[p];
                    if (owner == 0) {
                        continue;
                    }
                    float cx = originX + x * step;
                    float cy = originY + y * step;
                    Paint paint = owner == Board.BLACK ? ownerBlackPaint : ownerWhitePaint;
                    float r = step * 0.16f;
                    canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint);
                }
            }
        }

        // 棋子
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                byte c = board.color[board.point(x, y)];
                if (c == Board.EMPTY || c == Board.WALL) {
                    continue;
                }
                drawStone(canvas, originX + x * step, originY + y * step, c);
            }
        }

        // 最后一手
        if (lastMove >= 0 && board.colorAt(lastMove) != Board.EMPTY) {
            float cx = originX + board.x(lastMove) * step;
            float cy = originY + board.y(lastMove) * step;
            byte c = board.color[lastMove];
            markPaint.setColor(c == Board.BLACK ? colorWhite : 0xFFD0342C);
            canvas.drawCircle(cx, cy, Math.max(density * 1.6f, stoneRadius * 0.26f), markPaint);
        }

        // 待确认落点
        if (ghost >= 0 && board.colorAt(ghost) == Board.EMPTY) {
            float cx = originX + board.x(ghost) * step;
            float cy = originY + board.y(ghost) * step;
            byte c = guessGhostColor();
            ghostPaint.setColor(c == Board.BLACK ? 0xAA141414 : 0xAAFFFFFF);
            canvas.drawCircle(cx, cy, stoneRadius, ghostPaint);
            accentPaint.setStrokeWidth(Math.max(density * 1.6f, step * 0.06f));
            canvas.drawCircle(cx, cy, stoneRadius * 0.95f, accentPaint);
        }

        // AI 建议
        if (hint >= 0 && board.colorAt(hint) == Board.EMPTY) {
            float cx = originX + board.x(hint) * step;
            float cy = originY + board.y(hint) * step;
            accentPaint.setStrokeWidth(Math.max(density * 1.8f, step * 0.07f));
            canvas.drawCircle(cx, cy, stoneRadius * 0.8f, accentPaint);
        }
    }

    private void drawStone(Canvas canvas, float cx, float cy, byte color) {
        float off = Math.max(density * 0.8f, step * 0.035f);
        canvas.drawCircle(cx + off * 0.35f, cy + off, stoneRadius * 1.02f, shadowPaint);
        stonePaint.setShader(color == Board.BLACK ? blackShader : whiteShader);
        canvas.drawCircle(cx, cy, stoneRadius, stonePaint);
        stonePaint.setShader(null);
        stoneEdgePaint.setStrokeWidth(Math.max(density * 0.6f, step * 0.02f));
        stoneEdgePaint.setColor(color == Board.BLACK ? 0x44000000 : 0x33000000);
        canvas.drawCircle(cx, cy, stoneRadius, stoneEdgePaint);
    }

    private void buildShaders() {
        float r = Math.max(stoneRadius, 1f);
        blackShader = new RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.7f,
                new int[]{0xFF5A5A5A, 0xFF1A1A1A, colorBlack},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        whiteShader = new RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.7f,
                new int[]{0xFFFFFFFF, 0xFFF6F3E8, 0xFFCCC7B6},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
    }

    private byte guessGhostColor() {
        return ghostColor == 0 ? Board.BLACK : ghostColor;
    }

    private byte ghostColor = Board.BLACK;

    public void setGhostColor(byte color) {
        this.ghostColor = color;
    }

    /** 标准星位（逻辑序号）。 */
    public static int[] starPoints(int size) {
        if (size == 9) {
            return new int[]{2 * size + 2, 2 * size + 6, 6 * size + 2, 6 * size + 6, 4 * size + 4};
        }
        if (size == 13) {
            return new int[]{3 * size + 3, 3 * size + 9, 9 * size + 3, 9 * size + 9, 6 * size + 6};
        }
        int[] pts = new int[9];
        int[] lines = {3, size / 2, size - 4};
        int k = 0;
        for (int y : lines) {
            for (int x : lines) {
                pts[k++] = y * size + x;
            }
        }
        return pts;
    }

    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!inputEnabled || board == null) {
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        int p = locate(event.getX(), event.getY());
        if (p < 0) {
            return true;
        }
        if (board.colorAt(p) != Board.EMPTY) {
            clearGhost();
            return true;
        }
        if (p == ghost) {
            if (moveListener != null && moveListener.onMove(p)) {
                ghost = -1;
                invalidate();
            }
        } else {
            ghost = p;
            hint = -1;
            invalidate();
        }
        return true;
    }

    /** 把屏幕坐标换算成最近的交叉点内部索引，太远返回 -1。 */
    private int locate(float x, float y) {
        if (step <= 0) {
            return -1;
        }
        int xi = Math.round((x - originX) / step);
        int yi = Math.round((y - originY) / step);
        if (xi < 0 || yi < 0 || xi >= board.size || yi >= board.size) {
            return -1;
        }
        float dx = x - (originX + xi * step);
        float dy = y - (originY + yi * step);
        float limit = step * 0.62f;
        if (dx * dx + dy * dy > limit * limit) {
            return -1;
        }
        return board.point(xi, yi);
    }
}
