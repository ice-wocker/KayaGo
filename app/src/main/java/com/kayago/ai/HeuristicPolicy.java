package com.kayago.ai;

import com.kayago.game.Board;

/**
 * 走子评估：{@code score = Σ w[i] * f[i]}，特征见 {@link MoveFeatures}，
 * 权重见 {@link Tuned#weights}（由自对弈进化调整）。
 *
 * <p>这套评分同时用于两处：
 * <ul>
 *   <li>根节点候选排序（决定搜索重点）；</li>
 *   <li>根节点 PUCT 的先验分布。</li>
 * </ul>
 */
public final class HeuristicPolicy {

    private HeuristicPolicy() {
    }

    /** 完整评估（含与最近棋子的距离等全局特征），返回浮点分。 */
    public static double rootScore(Board b, int p, byte col, int lastMove) {
        double[] f = MoveFeatures.of(b, p, col, lastMove);
        double[] w = Tuned.weights;
        double s = 0;
        for (int i = 0; i < MoveFeatures.COUNT; i++) {
            s += w[i] * f[i];
        }
        return s;
    }

    /** 与 {@link #rootScore} 相同；保留旧名字，便于外部调用。 */
    public static double quickScore(Board b, int p, byte col, int lastMove) {
        return rootScore(b, p, col, lastMove);
    }
}
