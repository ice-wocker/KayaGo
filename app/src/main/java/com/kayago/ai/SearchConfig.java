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

    /** UCT 探索常数。 */
    public double uctC = 1.15;

    /** 随机种子，0 表示按当前时间。 */
    public long seed = 0;

    /** 手感等级 1..5，用于界面展示。 */
    public int level = 3;
}
