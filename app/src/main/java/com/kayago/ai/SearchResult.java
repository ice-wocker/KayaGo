package com.kayago.ai;

import com.kayago.game.Board;

/** 搜索结果。 */
public final class SearchResult {

    /** 选中的一手（{@link Board#PASS} 表示停一手）。 */
    public int move = Board.PASS;

    /** 根节点视角胜率（0..1）。 */
    public double winRate = 0.5;

    /** 实际完成的推演次数。 */
    public long playouts;

    /** 根节点预估分差（黑 - 白，正数表示黑好）。 */
    public double scoreLead;

    /** 搜索耗时（毫秒）。 */
    public long timeMs;

    /** 根节点候选手（仅当 {@code SearchConfig.dumpRootStats} 打开时导出）。 */
    public int[] rootMoves;

    /** 与 {@link #rootMoves} 对应的根节点访问次数。 */
    public int[] rootVisits;
}
