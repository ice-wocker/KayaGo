package com.kayago.ai;

/** MCTS 搜索配置。 */
public final class SearchConfig {

    /** 每手思考时间上限（毫秒）。 */
    public long maxTimeMs = 1500;

    /** 推演次数上限，0 表示只受时间限制。 */
    public int maxPlayouts = 0;

    /** 搜索线程数，0 表示按 CPU 核心数自动决定。 */
    public int threads = 0;

    /** 根节点候选手数上限（按启发式打分排序后截断）。 */
    public int rootCandidates = 48;

    /** 单个节点最大分支数，避免搜索过度分散。 */
    public int maxChildren = 60;

    /** UCT 探索常数（非根节点）。 */
    public double uctC = Tuned.uctC;

    /** 随机种子，0 表示按当前时间。 */
    public long seed = 0;

    /** 手感等级 1..5，用于界面展示。 */
    public int level = 3;

    /**
     * 根节点 PUCT 先验系数：>0 时用「启发式先验 + PUCT」代替纯 UCB1。
     * 这是引擎棋力最关键的一项——空盘和稀疏局面下 UCB1 的胜率是纯噪声，
     * 没有先验时引擎会在开局随手走废棋。
     */
    public double puctC = Tuned.puctC;

    /** 根节点 RAVE/AMAF 系数：越大越偏向「快速推演里走得好」的着法，0 表示关闭。 */
    public double raveK = Tuned.raveK;

    /** 先验分布的软化温度：越大越平缓。 */
    public double priorTemperature = Tuned.priorTemperature;

    /**
     * 是否用「按分差折算的胜率」作为推演回报（而不是非胜即负的 0/1）。
     *
     * <p>两种回报各有特点：0/1 回报目标是「赢下来」，但在局面一边倒时所有着法的
     * 回报都挤在 0 或 1 附近，搜索分辨不出好坏；分差回报则能区分「输 3 目」和
     * 「输 20 目」，分辨力更好。这个开关就是为了两者取长补短。
     */
    public boolean scoreReward = true;

    /** 分差回报的尺度：领先这么多目时胜率约 0.73。默认按棋盘大小缩放。 */
    public double scoreScale = Tuned.scoreScale;

    /** 是否导出根节点访问分布（自对弈学习用）。 */
    public boolean dumpRootStats = false;

    /**
     * 按棋力等级生成配置。等级越高思考时间越长。
     *
     * @param level     1..5
     * @param boardSize 棋盘路数（路数越大给的时间越多）
     */
    public static SearchConfig forLevel(int level, int boardSize) {
        SearchConfig c = new SearchConfig();
        c.level = level;
        double base;
        switch (level) {
            case 1:
                base = 600;
                c.rootCandidates = 28;
                c.maxChildren = 36;
                break;
            case 2:
                base = 1500;
                c.rootCandidates = 36;
                c.maxChildren = 44;
                break;
            case 4:
                base = 7000;
                c.rootCandidates = 52;
                c.maxChildren = 64;
                break;
            case 5:
                base = 12000;
                c.rootCandidates = 64;
                c.maxChildren = 80;
                break;
            default:
                base = 3500;
                c.rootCandidates = 44;
                c.maxChildren = 52;
                break;
        }
        if (boardSize >= 13) {
            base *= 1.4;
        }
        c.maxTimeMs = (long) base;
        return c;
    }
}
