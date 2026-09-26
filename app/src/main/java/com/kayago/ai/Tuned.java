package com.kayago.ai;

/**
 * 可进化的参数总仓库（运行时状态）。
 *
 * <p>引擎运行时只读这里；{@code tools/Evolve} 会在自对弈进化过程中改写这些字段，
 * 用对局跑分决定是否把候选参数「晋升」为新的冠军，冠军参数最终写回
 * {@link TunedParams}（由工具生成）。
 *
 * <p>把权重和超参集中在一个可变的地方，是为了让「进化」不必重新编译就能验证候选：
 * 工具在同一个 JVM 里换一组参数就能直接跟现任冠军对打。
 */
public final class Tuned {

    private Tuned() {
    }

    /** 走子评估权重（与 {@link MoveFeatures#NAMES} 一一对应）。 */
    public static volatile double[] weights = TunedParams.WEIGHTS.clone();

    /** 停一手的分数（根候选排序用）。 */
    public static volatile double passScore = TunedParams.PASS_SCORE;

    /** 根节点 PUCT 系数，0 表示退回纯 UCB1。 */
    public static volatile double puctC = TunedParams.PUCT_C;

    /** 根节点 RAVE/AMAF 系数，0 表示关闭。 */
    public static volatile double raveK = TunedParams.RAVE_K;

    /** 先验分布软化温度。 */
    public static volatile double priorTemperature = TunedParams.PRIOR_TEMPERATURE;

    /** UCB1 探索常数（非根节点）。 */
    public static volatile double uctC = TunedParams.UCT_C;

    /** 分差回报尺度，0 表示按棋盘大小自动。 */
    public static volatile double scoreScale = TunedParams.SCORE_SCALE;

    /**
     * 最初的手写权重（固定基线，<b>不随进化改变</b>）。
     *
     * <p>跑分工具用它来回答「进化之后到底比最初强多少」；如果这里跟着
     * {@link TunedParams} 一起变，比较就会变成「自己打自己」而失去意义。
     */
    public static final double[] BUILTIN_WEIGHTS = {
            -1, 16, 20, 0, 8, 14, 32, 10, 20, -14,
            9, 6, 3, 1,
            -8, -4, 3, 4,
            10, 12, 8, 2,
            2, -4, -3
    };

    /** 恢复为 {@link TunedParams} 里的内置参数。 */
    public static void resetToBuiltIn() {
        weights = TunedParams.WEIGHTS.clone();
        passScore = TunedParams.PASS_SCORE;
        puctC = TunedParams.PUCT_C;
        raveK = TunedParams.RAVE_K;
        priorTemperature = TunedParams.PRIOR_TEMPERATURE;
        uctC = TunedParams.UCT_C;
        scoreScale = TunedParams.SCORE_SCALE;
    }

    /** 最初手写权重的副本（跑分基线，永远不变）。 */
    public static double[] defaultWeights() {
        return BUILTIN_WEIGHTS.clone();
    }
}
