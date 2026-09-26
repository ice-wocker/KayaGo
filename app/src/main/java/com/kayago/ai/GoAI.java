package com.kayago.ai;

import com.kayago.game.Board;

/** 围棋 AI 引擎接口。 */
public interface GoAI {

    /**
     * 思考并给出下一手。
     *
     * @param board     当前局面（不会被修改）
     * @param toMove    轮到谁走
     * @param lastMove  对手上一手的内部索引，没有则为 {@link Board#PASS}
     * @param komi      贴目（黑贴白，用于终局数子）
     * @param config    搜索配置
     */
    SearchResult think(Board board, byte toMove, int lastMove, double komi, SearchConfig config);
}
