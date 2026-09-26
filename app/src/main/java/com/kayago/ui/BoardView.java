package com.kayago.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.kayago.R;
import com.kayago.game.Board;

/**
 * 木纹棋盘：细腻渐变 + 极淡木纹、立体棋子、坐标标注、最后一手提示、
 * 待确认落点（两次点击确认）、AI 建议金环、形势归属标记与落子动画。
 *
 * <p>绘制过程中不创建对象：所有 Paint / RectF / 标签字符串都在尺寸或局面变化时预分配。
 */
public class BoardView extends View {

    /** 落子回调，返回 true 表示接受（视图会清掉待确认标记）。 */
    public interface OnMoveListener {
        boolean onMove(int p);
    }

    private static final long ANIM_MS = 140L;

    private Board board;
    private int lastMove = Board.PASS;
    private int ghost = -1;
    private int hint = -1;
    private boolean inputEnabled = true;
    private int[] ownership;
    private byte ghostColor = Board.BLACK;
    private OnMoveListener moveListener;

    // ---- 预分配的画笔 ----
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stoneShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lastMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ownerBlackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ownerWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();

    private final int colorAccent;
    private final int colorStoneBlack;
    private final int colorStoneWhite;

    private final float density;

    // ---- 几何 ----
    private float step;
    private float originX;
    private float originY;
    private float boardLen;
    private float stoneR;
    private float shaderStep = -1f;
    private float shaderOriginX = Float.NaN;

    private Shader woodShader;
    private Shader blackShader;
    private Shader whiteShader;
    private Shader glossShader;

    // ---- 坐标标注与星位（尺寸变化时重建） ----
    private int builtSize = -1;
    private String[] columnLabels = new String[0];
    private String[] rowLabels = new String[0];
    private int[] starX = new int[0];
    private int[] starY = new int[0];

    // ---- 落子动画 ----
    private int animPoint = -1;
    private float animFraction = 1f;
    private ValueAnimator animator;

    public BoardView(Context context) {
        this(context, null);
    }

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        colorAccent = context.getColor(R.color.accent);
        colorStoneBlack = context.getColor(R.color.stone_black);
        colorStoneWhite = context.getColor(R.color.stone_white);

        shadowPaint.setColor(0xC0000000);
        grainPaint.setColor(context.getColor(R.color.board_grain));
        gridPaint.setColor(context.getColor(R.color.board_line));
        edgeGridPaint.setColor(context.getColor(R.color.board_line));
        starPaint.setColor(context.getColor(R.color.board_line));
        labelPaint.setColor(context.getColor(R.color.board_label));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        panelEdgePaint.setStyle(Paint.Style.STROKE);
        panelEdgePaint.setColor(0x33000000);
        stoneShadowPaint.setColor(0x40000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ghostPaint.setStyle(Paint.Style.FILL);
        ownerBlackPaint.setColor(0xA01A1A1A);
        ownerWhitePaint.setColor(0x96F2EEE2);
        setFocusable(true);
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    public void setBoard(Board board) {
        this.board = board;
        this.lastMove = Board.PASS;
        this.ghost = -1;
        this.hint = -1;
        this.ownership = null;
        this.animPoint = -1;
        if (board != null && board.size != builtSize) {
            buildLabels(board.size);
        }
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

    public void setGhostColor(byte color) {
        this.ghostColor = color;
    }

    public void setHint(int p) {
        this.hint = p;
        if (p >= 0) {
            this.ghost = -1;
        }
        invalidate();
    }

    public void setOwnership(int[] map) {
        this.ownership = map;
        invalidate();
    }

    public void setOnMoveListener(OnMoveListener listener) {
        this.moveListener = listener;
    }

    public void refresh() {
        invalidate();
    }

    /** 在某个交叉点播放「落子」缩放淡入动画。 */
    public void animateStoneAt(int p) {
        if (p == Board.PASS) {
            return;
        }
        if (animator != null) {
            animator.cancel();
        }
        animPoint = p;
        animFraction = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animFraction = (Float) animation.getAnimatedValue();
            if (animFraction >= 1f) {
                animPoint = -1;
            }
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final Board b = board;
        if (b == null) {
            return;
        }
        final int size = b.size;
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        final float pad = density * 14f;
        final float avail = Math.min(w - 2f * pad, h - 2f * pad);
        if (avail <= 0) {
            return;
        }
        // 每个交叉点占 1 格，四周各留约 0.66 格放坐标与木边
        step = avail / (size + 0.32f);
        boardLen = step * (size - 1);
        originX = (w - boardLen) * 0.5f;
        originY = (h - boardLen) * 0.5f;
        stoneR = step * 0.468f;

        if (shaderStep != step || shaderOriginX != originX) {
            buildShaders();
            shaderStep = step;
            shaderOriginX = originX;
        }
        if (builtSize != size) {
            buildLabels(size);
        }

        final float mx = step * 0.66f;
        final float pl = originX - mx;
        final float pt = originY - mx;
        final float pr = originX + boardLen + mx;
        final float pb = originY + boardLen + mx;
        final float radius = step * 0.30f;

        drawPanel(canvas, pl, pt, pr, pb, radius);
        drawGrid(canvas, size);
        drawStars(canvas);
        drawCoordinates(canvas, size);
        drawOwnership(canvas, b, size);
        drawStones(canvas, b, size);
        drawLastMark(canvas, b);
        drawHint(canvas, b);
        drawGhost(canvas, b);
    }

    /** 木色棋盘：柔和外阴影 + 渐变木面 + 极淡木纹横线 + 内描边。 */
    private void drawPanel(Canvas canvas, float pl, float pt, float pr, float pb, float radius) {
        for (int i = 3; i >= 1; i--) {
            float e = step * 0.12f * i;
            shadowPaint.setAlpha(10 + (3 - i) * 10);
            rect.set(pl - e, pt - e + step * 0.05f, pr + e, pb + e + step * 0.05f);
            canvas.drawRoundRect(rect, radius + e, radius + e, shadowPaint);
        }

        rect.set(pl, pt, pr, pb);
        panelPaint.setShader(woodShader);
        canvas.drawRoundRect(rect, radius, radius, panelPaint);
        panelPaint.setShader(null);

        // 极淡木纹横线
        canvas.save();
        canvas.clipRect(pl, pt, pr, pb);
        float gy = pt;
        int i = 0;
        while (gy < pb) {
            grainPaint.setAlpha((i % 7 == 0) ? 26 : 12);
            canvas.drawLine(pl, gy, pr, gy, grainPaint);
            gy += step * 0.31f;
            i++;
        }
        canvas.restore();

        panelEdgePaint.setStrokeWidth(Math.max(density, step * 0.03f));
        rect.set(pl + 0.5f, pt + 0.5f, pr - 0.5f, pb - 0.5f);
        canvas.drawRoundRect(rect, radius, radius, panelEdgePaint);
    }

    private void drawGrid(Canvas canvas, int size) {
        final float thin = Math.max(density * 0.7f, step * 0.032f);
        gridPaint.setStrokeWidth(thin);
        for (int i = 1; i < size - 1; i++) {
            float p = originX + i * step;
            float q = originY + i * step;
            canvas.drawLine(originX, q, originX + boardLen, q, gridPaint);
            canvas.drawLine(p, originY, p, originY + boardLen, gridPaint);
        }
        edgeGridPaint.setStrokeWidth(thin * 1.45f);
        canvas.drawLine(originX, originY, originX + boardLen, originY, edgeGridPaint);
        canvas.drawLine(originX, originY + boardLen, originX + boardLen, originY + boardLen, edgeGridPaint);
        canvas.drawLine(originX, originY, originX, originY + boardLen, edgeGridPaint);
        canvas.drawLine(originX + boardLen, originY, originX + boardLen, originY + boardLen, edgeGridPaint);
    }

    private void drawStars(Canvas canvas) {
        float r = Math.max(density * 1.3f, step * 0.085f);
        for (int i = 0; i < starX.length; i++) {
            canvas.drawCircle(originX + starX[i] * step, originY + starY[i] * step, r, starPaint);
        }
    }

    /** 坐标：左侧 1..size（自下往上），底部 A..（跳过 I）。 */
    private void drawCoordinates(Canvas canvas, int size) {
        final float textSize = step * 0.32f;
        labelPaint.setTextSize(textSize);
        final float baselineShift = textSize * 0.36f;
        final float lx = originX - step * 0.40f;
        final float by = originY + boardLen + step * 0.40f;
        for (int i = 0; i < size; i++) {
            canvas.drawText(rowLabels[i], lx, originY + i * step + baselineShift, labelPaint);
            canvas.drawText(columnLabels[i], originX + i * step,
                    by + baselineShift * 0.6f, labelPaint);
        }
    }

    /** 形势归属：柔和的小圆角方块，黑白用不同透明度。 */
    private void drawOwnership(Canvas canvas, Board b, int size) {
        final int[] map = ownership;
        if (map == null) {
            return;
        }
        final float r = step * 0.185f;
        final float round = r * 0.45f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = b.point(x, y);
                if (p >= map.length || b.color[p] != Board.EMPTY) {
                    continue;
                }
                int owner = map[p];
                if (owner == 0) {
                    continue;
                }
                float cx = originX + x * step;
                float cy = originY + y * step;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawRoundRect(rect, round, round,
                        owner == Board.BLACK ? ownerBlackPaint : ownerWhitePaint);
            }
        }
    }

    private void drawStones(Canvas canvas, Board b, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int p = b.point(x, y);
                byte c = b.color[p];
                if (c != Board.BLACK && c != Board.WHITE) {
                    continue;
                }
                float cx = originX + x * step;
                float cy = originY + y * step;
                if (p == animPoint) {
                    float f = animFraction;
                    float s = 0.70f + 0.30f * f;
                    int alpha = (int) (255f * f);
                    if (alpha < 0) {
                        alpha = 0;
                    } else if (alpha > 255) {
                        alpha = 255;
                    }
                    drawStone(canvas, cx, cy, c, s, alpha);
                } else {
                    drawStone(canvas, cx, cy, c, 1f, 255);
                }
            }
        }
    }

    /** 立体棋子：径向渐变 + 底部投影 + 高光；黑子补一点边缘反光。 */
    private void drawStone(Canvas canvas, float cx, float cy, byte color, float scale, int alpha) {
        canvas.save();
        canvas.translate(cx, cy);
        if (scale != 1f) {
            canvas.scale(scale, scale);
        }

        stoneShadowPaint.setAlpha(alpha * 0x40 / 255);
        canvas.drawCircle(stoneR * 0.06f, stoneR * 0.13f, stoneR * 1.04f, stoneShadowPaint);

        stonePaint.setAlpha(alpha);
        stonePaint.setShader(color == Board.BLACK ? blackShader : whiteShader);
        canvas.drawCircle(0f, 0f, stoneR, stonePaint);

        stonePaint.setShader(glossShader);
        stonePaint.setAlpha(color == Board.BLACK ? alpha * 78 / 255 : alpha * 175 / 255);
        canvas.drawCircle(-stoneR * 0.30f, -stoneR * 0.33f, stoneR * 0.60f, stonePaint);
        stonePaint.setShader(null);
        stonePaint.setAlpha(255);

        if (color == Board.BLACK) {
            rimPaint.setStrokeWidth(Math.max(density * 0.7f, stoneR * 0.10f));
            rimPaint.setColor(0x38FFFFFF);
            rect.set(-stoneR, -stoneR, stoneR, stoneR);
            canvas.drawArc(rect, 20f, 102f, false, rimPaint);
        } else {
            rimPaint.setStrokeWidth(Math.max(density * 0.7f, stoneR * 0.06f));
            rimPaint.setColor(0x33000000);
            canvas.drawCircle(0f, 0f, stoneR * 0.97f, rimPaint);
        }
        canvas.restore();
    }

    /** 最后一手：对比色小圆点（动画中的那一手等动画结束后再画）。 */
    private void drawLastMark(Canvas canvas, Board b) {
        if (lastMove < 0 || lastMove >= b.total || lastMove == animPoint) {
            return;
        }
        if (b.colorAt(lastMove) == Board.EMPTY) {
            return;
        }
        float cx = originX + b.x(lastMove) * step;
        float cy = originY + b.y(lastMove) * step;
        lastMarkPaint.setColor(b.color[lastMove] == Board.BLACK ? colorAccent : 0xFFB8442F);
        canvas.drawCircle(cx, cy, Math.max(density * 1.6f, stoneR * 0.23f), lastMarkPaint);
    }

    /** AI 建议：金色圆环。 */
    private void drawHint(Canvas canvas, Board b) {
        if (hint < 0 || hint >= b.total || b.colorAt(hint) != Board.EMPTY) {
            return;
        }
        float cx = originX + b.x(hint) * step;
        float cy = originY + b.y(hint) * step;
        ringPaint.setColor(colorAccent);
        ringPaint.setStrokeWidth(Math.max(density * 2f, step * 0.055f));
        canvas.drawCircle(cx, cy, stoneR * 0.82f, ringPaint);
        ringPaint.setStrokeWidth(Math.max(density, step * 0.028f));
        ringPaint.setColor(0x66D8A657);
        canvas.drawCircle(cx, cy, stoneR * 1.02f, ringPaint);
    }

    /** 待确认落点：半透明棋子 + 金色描边。 */
    private void drawGhost(Canvas canvas, Board b) {
        if (ghost < 0 || ghost >= b.total || b.colorAt(ghost) != Board.EMPTY) {
            return;
        }
        float cx = originX + b.x(ghost) * step;
        float cy = originY + b.y(ghost) * step;
        ghostPaint.setColor(ghostColor == Board.BLACK ? 0x99101010 : 0x99F5F2E8);
        canvas.drawCircle(cx, cy, stoneR, ghostPaint);
        ringPaint.setColor(colorAccent);
        ringPaint.setStrokeWidth(Math.max(density * 1.6f, step * 0.05f));
        canvas.drawCircle(cx, cy, stoneR * 0.94f, ringPaint);
    }

    // ------------------------------------------------------------------
    // 几何 / 着色器缓存
    // ------------------------------------------------------------------

    private void buildShaders() {
        final float r = Math.max(stoneR, 1f);
        woodShader = new LinearGradient(
                originX - step * 0.66f, originY - step * 0.66f,
                originX + boardLen + step * 0.66f, originY + boardLen + step * 0.66f,
                new int[]{0xFFF3D9A8, 0xFFE3B778, 0xFFBF8E4C},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        blackShader = new RadialGradient(-r * 0.34f, -r * 0.38f, r * 1.75f,
                new int[]{0xFF6E6E6E, 0xFF262626, colorStoneBlack},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        whiteShader = new RadialGradient(-r * 0.34f, -r * 0.38f, r * 1.75f,
                new int[]{0xFFFFFFFF, colorStoneWhite, 0xFFC6C0AE},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glossShader = new RadialGradient(-r * 0.30f, -r * 0.33f, r * 0.74f,
                new int[]{0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    private void buildLabels(int size) {
        builtSize = size;
        columnLabels = new String[size];
        rowLabels = new String[size];
        for (int i = 0; i < size; i++) {
            char letter = (char) ('A' + i);
            if (letter >= 'I') {
                letter++;
            }
            columnLabels[i] = String.valueOf(letter);
            rowLabels[i] = String.valueOf(size - i);
        }
        if (size == 9) {
            starX = new int[]{2, 6, 2, 6, 4};
            starY = new int[]{2, 2, 6, 6, 4};
        } else if (size == 13) {
            starX = new int[]{3, 9, 3, 9, 6};
            starY = new int[]{3, 3, 9, 9, 6};
        } else if (size == 19) {
            int[] lines = {3, size / 2, size - 4};
            starX = new int[9];
            starY = new int[9];
            int k = 0;
            for (int yy : lines) {
                for (int xx : lines) {
                    starX[k] = xx;
                    starY[k] = yy;
                    k++;
                }
            }
        } else {
            starX = new int[0];
            starY = new int[0];
        }
    }

    // ------------------------------------------------------------------
    // 触摸
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
            ghost = -1;
            invalidate();
            return true;
        }
        if (p == ghost) {
            if (moveListener != null && moveListener.onMove(p)) {
                ghost = -1;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
            }
        } else {
            ghost = p;
            hint = -1;
            invalidate();
        }
        return true;
    }

    /** 屏幕坐标 -> 最近的交叉点（命中半径按格子自适应），太远返回 -1。 */
    private int locate(float x, float y) {
        if (step <= 0f || board == null) {
            return -1;
        }
        final float fx = (x - originX) / step;
        final float fy = (y - originY) / step;
        final int xi = Math.round(fx);
        final int yi = Math.round(fy);
        if (xi < 0 || yi < 0 || xi >= board.size || yi >= board.size) {
            return -1;
        }
        final float dx = fx - xi;
        final float dy = fy - yi;
        if (dx * dx + dy * dy > 0.34f) {
            return -1;
        }
        return board.point(xi, yi);
    }
}
