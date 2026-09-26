package com.kayago.ai;

import com.kayago.game.Board;

/**
 * 走子评估的特征提取。
 *
 * <p>评分完全表示为「权重 × 特征」的线性模型：{@code score = Σ w[i] * f[i]}。
 * 训练（tools/Trainer）与推理（{@link HeuristicPolicy}）共用这一份特征定义，
 * 保证训练出来的权重在引擎里就是同一件事，不会出现「训练/上线不一致」。
 *
 * <p>特征全部是局部结构或轻量统计，一次提取是 O(1) 级别（最近的棋子距离除外，
 * 它按棋盘扫描，但只在根节点候选排序时使用）。
 */
public final class MoveFeatures {

    public static final int COUNT = 25;

    public static final String[] NAMES = {
            "bias", "ownN", "oppN", "emptyN", "diagOwn",
            "captCount", "capStones", "saveCount", "saveStones", "selfAtari",
            "lastD1", "lastD2", "lastD3", "lastD45",
            "line0", "line1", "line2", "line3",
            "open33", "openStar", "openKomoku", "openNear",
            "nearStone234", "farStone", "centerEarly"
    };

    private static final ThreadLocal<double[]> SCRATCH = ThreadLocal.withInitial(() ->
            new double[COUNT]);

    private MoveFeatures() {
    }

    /** 便捷入口：内部用线程本地缓冲，返回长度为 {@link #COUNT} 的特征向量。 */
    public static double[] of(Board b, int move, byte col, int lastMove) {
        double[] f = SCRATCH.get();
        extract(b, move, col, lastMove, f);
        return f;
    }

    /**
     * 提取特征到 {@code out}（长度至少 {@link #COUNT}）。
     *
     * @param move 落点（内部索引）
     */
    public static void extract(Board b, int move, byte col, int lastMove, double[] out) {
        final byte opp = Board.opposite(col);
        final int p = move;

        int ownN = 0;
        int oppN = 0;
        int emptyN = 0;
        int capSize = 0;
        int saveSize = 0;
        for (int d = 0; d < 4; d++) {
            int q = b.nb(p, d);
            byte c = b.color[q];
            if (c == col) {
                ownN++;
                int id = b.cid[q];
                if (id >= 0 && b.chainLibs[id] == 1) {
                    saveSize += b.chainSize[id];
                }
            } else if (c == opp) {
                oppN++;
                int id = b.cid[q];
                if (id >= 0 && b.chainLibs[id] == 1) {
                    capSize += b.chainSize[id];
                }
            } else if (c == Board.EMPTY) {
                emptyN++;
            }
        }

        final int stride = b.stride;
        int diagOwn = 0;
        if (b.color[p - stride - 1] == col) {
            diagOwn++;
        }
        if (b.color[p - stride + 1] == col) {
            diagOwn++;
        }
        if (b.color[p + stride - 1] == col) {
            diagOwn++;
        }
        if (b.color[p + stride + 1] == col) {
            diagOwn++;
        }

        final int x = b.x(p);
        final int y = b.y(p);
        final int size = b.size;
        final int cx = Math.min(x, size - 1 - x);
        final int cy = Math.min(y, size - 1 - y);
        final int line = Math.min(cx, cy);
        final boolean early = b.stones < size;

        out[0] = 1;
        out[1] = ownN / 4.0;
        out[2] = oppN / 4.0;
        out[3] = emptyN / 4.0;
        out[4] = diagOwn / 4.0;
        out[5] = capSize > 0 ? 1 : 0;
        out[6] = Math.min(capSize, 8) / 4.0;
        out[7] = saveSize > 0 ? 1 : 0;
        out[8] = Math.min(saveSize, 8) / 4.0;
        out[9] = (ownN > 0 && emptyN == 0 && capSize == 0 && saveSize == 0) ? 1 : 0;

        if (lastMove >= 0) {
            int dist = Math.max(Math.abs(x - b.x(lastMove)), Math.abs(y - b.y(lastMove)));
            out[10] = dist == 1 ? 1 : 0;
            out[11] = dist == 2 ? 1 : 0;
            out[12] = dist == 3 ? 1 : 0;
            out[13] = (dist == 4 || dist == 5) ? 1 : 0;
        } else {
            out[10] = 0;
            out[11] = 0;
            out[12] = 0;
            out[13] = 0;
        }

        out[14] = line == 0 ? 1 : 0;
        out[15] = line == 1 ? 1 : 0;
        out[16] = line == 2 ? 1 : 0;
        out[17] = line == 3 ? 1 : 0;
        out[18] = early && cx == 2 && cy == 2 ? 1 : 0;
        out[19] = early && cx == 3 && cy == 3 ? 1 : 0;
        out[20] = early && ((cx == 2 && cy == 3) || (cx == 3 && cy == 2)) ? 1 : 0;
        out[21] = early && cx >= 2 && cy >= 2 && cx <= 4 && cy <= 4 ? 1 : 0;

        int near = nearestStone(b, x, y);
        out[22] = (near >= 2 && near <= 4) ? 1 : 0;
        out[23] = near > 6 ? 1 : 0;
        out[24] = (b.stones < size * 2 && line >= 4) ? 1 : 0;
    }

    /** 到最近棋子的切比雪夫距离；空盘返回 -1。 */
    private static int nearestStone(Board b, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (int yy = 0; yy < b.size; yy++) {
            for (int xx = 0; xx < b.size; xx++) {
                if (b.color[b.point(xx, yy)] == Board.EMPTY) {
                    continue;
                }
                int d = Math.max(Math.abs(x - xx), Math.abs(y - yy));
                if (d < best) {
                    best = d;
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }
}
