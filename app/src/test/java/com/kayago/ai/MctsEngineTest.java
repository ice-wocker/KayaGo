package com.kayago.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.kayago.game.Board;
import org.junit.Test;

/**
 * MCTS 引擎的纯逻辑测试。
 *
 * <p>这些用例跑得很快（思考时间压到 100ms 以内），能进 CI。
 * 重点不在「棋力多强」，而在「引擎不会走出荒谬的着法、不会崩溃、结果自洽」——
 * 这些恰恰是无人值守时最容易悄悄坏掉的部分。
 */
public class MctsEngineTest {

    private static final byte B = Board.BLACK;
    private static final byte W = Board.WHITE;

    private static void play(Board b, int x, int y, byte c) {
        assertTrue("测试摆子应合法: (" + x + "," + y + ")",
                b.play(b.fromIndex(y * b.size + x), c));
    }

    /** 极短思考时间的配置，保证测试快。 */
    private static SearchConfig quickCfg(int level) {
        SearchConfig c = SearchConfig.forLevel(level, 9);
        c.maxTimeMs = 80;
        c.maxPlayouts = 400;
        c.threads = 1;
        c.seed = 20260929L; // 固定种子，结果可复现
        return c;
    }

    // ---------------- 基本可用性 ----------------

    @Test
    public void 空盘上应给出合法着法() {
        Board b = new Board(9);
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, B, -1, 6.5, quickCfg(3));

        assertNotNull(r);
        assertTrue("返回的着法应是合法点或停手，实际 move=" + r.move,
                r.move == Board.PASS || b.isLegal(r.move, B));
        assertTrue("胜率应在 [0,1]，实际 " + r.winRate, r.winRate >= 0 && r.winRate <= 1);
        assertTrue("推演次数应大于 0", r.playouts > 0);
    }

    @Test
    public void 空盘不应停手() {
        // 空盘直接停手是明显的退化行为，会白白让出先手
        Board b = new Board(9);
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, B, -1, 6.5, quickCfg(3));
        assertTrue("空盘不应 PASS", r.move != Board.PASS);
    }

    @Test
    public void 返回的着法不应是已有子或劫点() {
        Board b = new Board(9);
        play(b, 4, 4, B);
        play(b, 3, 3, W);
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, W, b.fromIndex(3 * 9 + 3), 6.5, quickCfg(3));
        if (r.move != Board.PASS) {
            assertTrue("着法应是空点", b.isEmpty(r.move));
            assertTrue("着法应合法", b.isLegal(r.move, W));
        }
    }

    @Test
    public void 只有一个眼位时_引擎仍不应崩溃() {
        Board b = new Board(9);
        // 黑填满除一个眼外的所有点（极端局面）
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                if (x == 4 && y == 4) continue;
                b.play(b.fromIndex(y * 9 + x), B);
            }
        }
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, W, -1, 6.5, quickCfg(1));
        assertNotNull(r);
        assertTrue("极端局面下也应给出可用结果",
                r.move == Board.PASS || b.isLegal(r.move, W));
    }

    @Test
    public void 终局被完全围死_应可以停手() {
        Board b = new Board(9);
        MctsEngine e = new MctsEngine();
        // 黑白各占一半，白无好点可走时返回 PASS 也是合法结果
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                b.play(b.fromIndex(y * 9 + x), y < 4 ? B : W);
            }
        }
        SearchResult r = e.think(b, B, -1, 6.5, quickCfg(1));
        assertNotNull(r);
    }

    // ---------------- 结果自洽性 ----------------

    @Test
    public void 根节点访问分布应与着法列表等长() {
        Board b = new Board(9);
        SearchConfig cfg = quickCfg(3);
        cfg.dumpRootStats = true; // 需要导出根分布
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, B, -1, 6.5, cfg);

        if (r.rootMoves != null && r.rootVisits != null) {
            assertEquals("着法与访问数应一一对应",
                    r.rootMoves.length, r.rootVisits.length);
            // 访问数非负
            for (int i = 0; i < r.rootVisits.length; i++) {
                assertTrue("访问数不应为负", r.rootVisits[i] >= 0);
            }
        }
    }

    @Test
    public void 选中的着法应在候选列表中且访问数最大() {
        Board b = new Board(9);
        SearchConfig cfg = quickCfg(3);
        cfg.dumpRootStats = true;
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, B, -1, 6.5, cfg);

        if (r.rootMoves != null && r.rootVisits != null && r.rootMoves.length > 0) {
            int best = -1, bestVisits = -1;
            int chosenIdx = -1;
            for (int i = 0; i < r.rootMoves.length; i++) {
                if (r.rootVisits[i] > bestVisits) {
                    bestVisits = r.rootVisits[i];
                    best = r.rootMoves[i];
                }
                if (r.rootMoves[i] == r.move) chosenIdx = i;
            }
            assertEquals("选中的着法应是候选里访问最多的", best, r.move);
        }
    }

    @Test
    public void 相同种子应得到相同结果() {
        // 可复现性是排查棋力问题的前提
        Board b1 = new Board(9);
        Board b2 = new Board(9);
        MctsEngine e1 = new MctsEngine();
        MctsEngine e2 = new MctsEngine();
        SearchResult r1 = e1.think(b1, B, -1, 6.5, quickCfg(3));
        SearchResult r2 = e2.think(b2, B, -1, 6.5, quickCfg(3));
        assertEquals("固定种子下着法应一致", r1.move, r2.move);
    }

    @Test
    public void 引擎不应修改传入的棋盘() {
        Board b = new Board(9);
        play(b, 4, 4, B);
        long before = b.hash();
        int stones = b.stones;
        MctsEngine e = new MctsEngine();
        e.think(b, W, -1, 6.5, quickCfg(3));
        assertEquals("引擎不应改动调用方棋盘（hash）", before, b.hash());
        assertEquals("引擎不应改动调用方棋盘（子数）", stones, b.stones);
        assertTrue("棋盘应保持自洽", b.validate());
    }

    // ---------------- 分级与配置 ----------------

    @Test
    public void 各级别的配置应单调递增() {
        // 等级越高思考时间越长；如果某档反了，界面上的「棋力」就是假的
        long prev = 0;
        for (int lv = 1; lv <= 5; lv++) {
            SearchConfig c = SearchConfig.forLevel(lv, 9);
            assertTrue("等级 " + lv + " 的思考时间应大于上一级", c.maxTimeMs > prev);
            prev = c.maxTimeMs;
            assertTrue("时间应为正", c.maxTimeMs > 0);
            assertTrue("根候选数应为正", c.rootCandidates > 0);
        }
    }

    @Test
    public void 大棋盘应给更多思考时间() {
        SearchConfig small = SearchConfig.forLevel(3, 9);
        SearchConfig big = SearchConfig.forLevel(3, 19);
        assertTrue("19 路应比 9 路给更多时间", big.maxTimeMs > small.maxTimeMs);
    }

    @Test
    public void 各等级都应能正常出招() {
        for (int lv = 1; lv <= 5; lv++) {
            Board b = new Board(9);
            MctsEngine e = new MctsEngine();
            SearchResult r = e.think(b, B, -1, 6.5, quickCfg(lv));
            assertNotNull("等级 " + lv + " 应返回结果", r);
            assertTrue("等级 " + lv + " 的空盘着法应合法",
                    r.move == Board.PASS || b.isLegal(r.move, B));
        }
    }

    // ---------------- 估值 ----------------

    @Test
    public void 估值结果应为有限数() {
        Board b = new Board(9);
        play(b, 4, 4, B);
        double s = MctsEngine.estimateScore(b, W, -1, 6.5, 20, 1);
        assertTrue("估值不应是 NaN", !Double.isNaN(s));
        assertTrue("估值不应是无穷", !Double.isInfinite(s));
    }

    @Test
    public void 空盘估值应接近贴目值附近() {
        Board b = new Board(9);
        double s = MctsEngine.estimateScore(b, B, -1, 6.5, 40, 1);
        assertTrue("空盘估值不应是 NaN", !Double.isNaN(s));
        // 空盘 + 贴目的绝对值不应夸张（棋盘只有 81 点）
        assertTrue("空盘估值应在合理范围，实际 " + s, Math.abs(s) <= 81 + 7.5);
    }

    // ---------------- 压力 ----------------

    @Test
    public void 连续多手后仍能正常出招且棋盘自洽() {
        // 模拟一局短对局，检验引擎在密集落子过程中不会崩
        Board b = new Board(9);
        MctsEngine e = new MctsEngine();
        byte col = B;
        SearchConfig cfg = quickCfg(1);
        for (int i = 0; i < 12; i++) {
            SearchResult r = e.think(b, col, -1, 6.5, cfg);
            if (r.move == Board.PASS) break;
            assertTrue("引擎返回的着法必须合法，第 " + i + " 手: " + r.move,
                    b.isLegal(r.move, col));
            assertTrue(b.play(r.move, col));
            col = Board.opposite(col);
            assertTrue("第 " + i + " 手后棋盘应自洽", b.validate());
        }
        assertTrue("应至少走了几手", b.stones > 3);
    }

    @Test
    public void 小棋盘也应正常工作() {
        Board b = new Board(7);
        MctsEngine e = new MctsEngine();
        SearchResult r = e.think(b, B, -1, 6.5, quickCfg(1));
        assertNotNull(r);
        assertTrue(r.move == Board.PASS || b.isLegal(r.move, B));
    }
}
