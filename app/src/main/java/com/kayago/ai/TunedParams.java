package com.kayago.ai;

/**
 * 由自对弈进化产出的参数快照。
 *
 * <p><b>本文件由 {@code tools/Evolve} 自动生成，请勿手改。</b>
 * 运行时状态见 {@link Tuned}（它从这里取初值，并允许进化工具在内存里替换候选）。
 *
 * <p>当前内容是最初的手写参数：多次进化跑分都未能以 95% 置信度跑赢它，于是保持不变。
 */
public final class TunedParams {

    private TunedParams() {
    }

    /** 走子评估权重，顺序与 {@link MoveFeatures#NAMES} 一致。 */
    public static final double[] WEIGHTS = {
            -1, 16, 20, 0, 8, 14, 32, 10, 20, -14,
            9, 6, 3, 1,
            -8, -4, 3, 4,
            10, 12, 8, 2,
            2, -4, -3
    };

    /** 停一手的分数。 */
    public static final double PASS_SCORE = -8;

    /** 根节点 PUCT 系数。 */
    public static final double PUCT_C = 1.6;

    /** 根节点 RAVE 系数。 */
    public static final double RAVE_K = 800;

    /** 先验温度。 */
    public static final double PRIOR_TEMPERATURE = 9.0;

    /** UCB1 探索常数。 */
    public static final double UCT_C = 1.15;

    /** 分差回报尺度，0 表示按棋盘大小自动。 */
    public static final double SCORE_SCALE = 0;

    /** 生成时间戳（人类可读，便于知道是哪一轮进化的产物）。 */
    public static final String GENERATED_AT = "built-in hand-tuned defaults";
}
