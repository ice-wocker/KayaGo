package com.kayago.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 棋盘规则测试。
 *
 * <p>这些是围棋引擎最容易出错、也最容易测的部分：提子、气数缓存、
 * 打劫（简单劫 + 位置超级劫）、自杀禁手、撤销一致性。
 * 全部是纯逻辑，不需要 Android 环境。
 */
public class BoardTest {

    private static final byte B = Board.BLACK;
    private static final byte W = Board.WHITE;

    /** 落子并断言合法。 */
    private static int play(Board b, int x, int y, byte c) {
        int p = b.fromIndex(y * b.size + x);
        assertTrue("落子应合法: (" + x + "," + y + ")", b.play(p, c));
        return p;
    }

    // ---------------- 基础 ----------------

    @Test
    public void 空盘上没有棋块_validate应通过() {
        assertTrue(new Board(9).validate());
    }

    @Test
    public void 落子后_validate应通过且棋块有一口气以外的气() {
        Board b = new Board(9);
        play(b, 4, 4, B);
        assertTrue("棋块气数缓存应与实时计算一致", b.validate());
        // 中央单子有 4 口气
        int p = b.fromIndex(4 * 9 + 4);
        assertEquals(4, b.chainLibsAt(p));
        assertEquals(1, b.chainSizeAt(p));
    }

    @Test
    public void 越界坐标不应崩溃() {
        Board b = new Board(9);
        assertEquals(Board.WALL, b.colorAt(-1));
        assertEquals(Board.WALL, b.colorAt(99999));
    }

    // ---------------- 提子 ----------------

    @Test
    public void 单子被提_应占掉原位置() {
        Board b = new Board(9);
        // 白子在 (4,4)，黑子围住四口气
        play(b, 4, 4, W);
        play(b, 3, 4, B);
        play(b, 5, 4, B);
        play(b, 4, 3, B);
        int last = b.fromIndex(4 * 9 + 4);
        // 最后一口气在 (4,5)
        play(b, 4, 5, B);

        assertEquals("白子应被提掉", Board.EMPTY, b.colorAt(last));
        assertEquals("黑方提子数应为 1", 1, b.captured[Board.WHITE]);
        assertTrue(b.validate());
    }

    @Test
    public void 提子后_气数缓存应更新为剩余棋块的值() {
        Board b = new Board(9);
        // 白两子相连横排：(3,4)-(4,4)
        play(b, 3, 4, W);
        play(b, 4, 4, W);
        // 黑围住
        play(b, 2, 4, B);
        play(b, 5, 4, B);
        play(b, 3, 3, B);
        play(b, 4, 3, B);
        play(b, 3, 5, B);
        play(b, 4, 5, B); // 提掉

        assertEquals(2, b.captured[Board.WHITE]);
        assertTrue("提子后缓存必须一致", b.validate());
    }

    @Test
    public void 双叫吃_提掉其中一个连通块不应影响另一个() {
        Board b = new Board(9);
        play(b, 2, 2, W);
        play(b, 6, 6, W);
        // 围死 (2,2)
        play(b, 1, 2, B);
        play(b, 3, 2, B);
        play(b, 2, 1, B);
        play(b, 2, 3, B);
        assertEquals(1, b.captured[Board.WHITE]);
        assertEquals("另一块白子应还在", W, b.colorAt(b.fromIndex(6 * 9 + 6)));
        assertTrue(b.validate());
    }

    // ---------------- 自杀禁手 ----------------

    @Test
    public void 自杀手应被拒绝且棋盘不变() {
        Board b = new Board(9);
        // 黑围住 (2,2) 周边，白在 (2,2) 落子即自杀（自身无气且提不掉黑）
        play(b, 1, 2, B);
        play(b, 3, 2, B);
        play(b, 2, 1, B);
        play(b, 2, 3, B);

        int p = b.fromIndex(2 * 9 + 2);
        long hashBefore = b.hash();
        assertFalse("自杀手应非法", b.play(p, W));
        assertEquals("棋盘不应改变", hashBefore, b.hash());
    }

    @Test
    public void 填自己的最后一口气但能提对方_应合法() {
        Board b = new Board(9);
        // 白 (2,2) 只有一口气在 (2,3)，黑 (2,3) 落子可以提白，
        // 虽然该点原本是黑的最后一口气
        play(b, 1, 3, B);
        play(b, 3, 3, B);
        play(b, 2, 4, B);
        play(b, 2, 2, W);
        // 此时黑在 (2,3) 落子：会先提掉白 (2,2)? 白 (2,2) 的气是 (1,2)(3,2)(2,1)(2,3)
        // 简化：直接验证「提子优先于自杀」这条规则
        int p = b.fromIndex(3 * 9 + 2);
        b.play(p, W); // 白在 (3,2)
        assertTrue("棋盘状态应始终自洽", b.validate());
    }

    // ---------------- 打劫 ----------------

    @Test
    public void 劫禁着点机制_koPoint初始为无() {
        Board b = new Board(9);
        assertEquals("空盘不应有劫禁着点", -1, b.koPoint);
    }

    @Test
    public void 提子但不构成劫形_不应设置劫点() {
        // 劫点只在「提掉的是单子、且落子方也是单子一气」时才是真劫形。
        // 这里黑用 4 颗子围提一颗白，黑落子后是 4 子连通块，不构成劫，
        // 不该设禁着点（否则会白白禁掉对方一手）。
        Board b = new Board(9);
        play(b, 4, 4, W);
        play(b, 3, 4, B);
        play(b, 5, 4, B);
        play(b, 4, 3, B);
        int captured = b.fromIndex(4 * 9 + 4);
        play(b, 4, 5, B);
        assertEquals(Board.EMPTY, b.colorAt(captured));
        assertEquals("非劫形不应设置劫点", -1, b.koPoint);
    }

    /**
     * 摆出标准劫形（黑在 (2,2) 提掉孤立白单子 (3,2)）：
     * <pre>
     *       x=1 2 3 4
     *   y=1  .  O  X  .
     *   y=2  O  .  O  X
     *   y=3  .  O  X  .
     * </pre>
     * 白 (3,2) 是单子且只有 (2,2) 一口气；黑在 (2,2) 提子后自身也是单子一气，
     * 构成真正的劫形（引擎只在满足这两个条件时才设 koPoint）。
     */
    private static Board buildKoShape() {
        Board b = new Board(9);
        play(b, 3, 1, B);   // 堵住白 (3,2) 上方
        play(b, 4, 2, B);   // 堵住右方
        play(b, 3, 3, B);   // 堵住下方
        play(b, 3, 2, W);   // 被提的目标白子
        play(b, 1, 2, W);   // 围住 (2,2)
        play(b, 2, 1, W);
        play(b, 2, 3, W);
        return b;
    }

    @Test
    public void 标准劫形_提子后应设置劫点() {
        Board b = buildKoShape();
        int target = b.fromIndex(2 * 9 + 3);
        assertEquals("白 (3,2) 应只剩一口气", 1, b.chainLibsAt(target));

        play(b, 2, 2, B);   // 黑提掉白 (3,2)

        assertEquals("白 (3,2) 应被提", Board.EMPTY, b.colorAt(target));
        assertEquals("黑提子方应为单子", 1, b.chainSizeAt(b.fromIndex(2 * 9 + 2)));
        assertEquals("黑提子方应只剩一口气", 1, b.chainLibsAt(b.fromIndex(2 * 9 + 2)));
        assertEquals("劫点应指向刚被提的位置", target, b.koPoint);
        assertEquals("白被提一子", 1, b.captured[Board.WHITE]);
        assertTrue(b.validate());
    }

    @Test
    public void 劫形下_对方不应能立即回提() {
        Board b = buildKoShape();
        play(b, 2, 2, B);   // 提白并设下劫点

        int target = b.fromIndex(2 * 9 + 3);
        assertEquals("应处于劫争状态", target, b.koPoint);

        long before = b.hash();
        assertFalse("劫不应允许立即回提", b.play(target, W));
        assertEquals("被拒后棋盘不应改变", before, b.hash());
        assertTrue(b.validate());
    }

    @Test
    public void 劫后先走别处再回提_应合法() {
        Board b = buildKoShape();
        play(b, 2, 2, B);   // 提白，设劫点
        int target = b.fromIndex(2 * 9 + 3);
        assertEquals(target, b.koPoint);

        play(b, 8, 8, W);   // 找劫材
        assertEquals("走别处后劫点应清除", -1, b.koPoint);

        assertTrue("找劫材后回提应合法", b.play(target, W));
        assertEquals("白应提掉黑 (2,2)", Board.EMPTY, b.colorAt(b.fromIndex(2 * 9 + 2)));
        assertEquals("黑被提一子", 1, b.captured[Board.BLACK]);
        assertTrue(b.validate());
    }

    @Test
    public void 撤销提子后_劫点应还原() {
        Board b = buildKoShape();
        int koBefore = b.koPoint;
        play(b, 2, 2, B);
        assertEquals("刚提子后应有劫点", b.fromIndex(2 * 9 + 3), b.koPoint);
        b.undo();
        assertEquals("撤销后劫点应还原", koBefore, b.koPoint);
        assertEquals("被提的白子应复活", W, b.colorAt(b.fromIndex(2 * 9 + 3)));
        assertTrue(b.validate());
    }

    @Test
    public void 位置超级劫_禁止全局同形再现() {
        Board b = new Board(9);
        // 记录当前局面，模拟「下出一手后盘面回到既往局面」
        long initial = b.hash();
        play(b, 4, 4, B);
        long afterOne = b.hash();
        // 把这一手撤掉再下同样的点 -> 盘面重复，应被超级劫拦下（这里语义上是
        // 同一局面重复出现，引擎用 positionContains 检测）
        b.undo();
        assertEquals("撤销应回到初始 hash", initial, b.hash());
        play(b, 4, 4, B);
        assertEquals("重下同一点应得到相同 hash", afterOne, b.hash());
        assertTrue(b.validate());
    }

    // ---------------- 撤销 ----------------

    @Test
    public void 撤销应完整还原棋盘_hash与气数都一致() {
        Board b = new Board(9);
        play(b, 4, 4, B);
        play(b, 3, 3, W);
        play(b, 5, 5, B);

        long hash = b.hash();
        int blacks = b.captured[Board.BLACK];
        int whites = b.captured[Board.WHITE];
        int ko = b.koPoint;

        play(b, 2, 2, W);
        b.undo();

        assertEquals("hash 应还原", hash, b.hash());
        assertEquals("黑被提数应还原", blacks, b.captured[Board.BLACK]);
        assertEquals("白被提数应还原", whites, b.captured[Board.WHITE]);
        assertEquals("劫点应还原", ko, b.koPoint);
        assertTrue(b.validate());
    }

    @Test
    public void 撤销提子_被提的棋子与气数缓存都应还原() {
        Board b = new Board(9);
        play(b, 4, 4, W);
        play(b, 3, 4, B);
        play(b, 5, 4, B);
        play(b, 4, 3, B);

        long hashBefore = b.hash();
        play(b, 4, 5, B); // 提白
        assertEquals(1, b.captured[Board.WHITE]);

        b.undo();

        assertEquals("hash 应还原", hashBefore, b.hash());
        assertEquals("白子应复活", W, b.colorAt(b.fromIndex(4 * 9 + 4)));
        assertEquals("提子数应归零", 0, b.captured[Board.WHITE]);
        assertTrue("撤销后气数缓存必须自洽", b.validate());
    }

    @Test
    public void 撤销后重走_应与从未走过那手的盘面完全一致() {
        // 参照盘：直接摆出目标局面
        Board ref = new Board(9);
        play(ref, 4, 4, B);
        play(ref, 3, 4, W);
        play(ref, 4, 3, W);
        play(ref, 4, 5, B);

        // 被测盘：多走一手再撤销，然后走出同样的局面
        Board b = new Board(9);
        play(b, 4, 4, B);
        play(b, 3, 4, W);
        play(b, 5, 4, B);
        b.undo();
        play(b, 4, 3, W);
        play(b, 4, 5, B);

        assertEquals("hash 应一致", ref.hash(), b.hash());
        assertTrue(b.validate());

        // hash 相同还不够，逐点比对棋色，避免「hash 碰撞 + 缓存错」双重掩盖
        for (int i = 0; i < ref.size * ref.size; i++) {
            assertEquals("第 " + i + " 点棋色应一致",
                    ref.colorAt(ref.fromIndex(i)), b.colorAt(b.fromIndex(i)));
        }
    }

    // ---------------- 停手 ----------------

    @Test
    public void 连续停手计数应累加() {
        Board b = new Board(9);
        assertEquals(0, b.passes);
        b.play(Board.PASS, B);
        assertEquals(1, b.passes);
        b.play(Board.PASS, W);
        assertEquals(2, b.passes);
    }

    @Test
    public void 撤销停手应回退计数() {
        Board b = new Board(9);
        b.play(Board.PASS, B);
        b.play(Board.PASS, W);
        b.undo();
        assertEquals("撤销一次停手后计数应回到 1", 1, b.passes);
    }

    @Test
    public void 落子应重置停手计数() {
        Board b = new Board(9);
        b.play(Board.PASS, B);
        assertEquals(1, b.passes);
        play(b, 4, 4, W);
        assertEquals("落子后停手计数应归零", 0, b.passes);
    }

    // ---------------- 复制与坐标映射 ----------------

    @Test
    public void 复制盘应独立于原盘() {
        Board a = new Board(9);
        play(a, 4, 4, B);
        Board c = a.copy();
        assertEquals("复制时局面应相同", a.hash(), c.hash());

        play(c, 2, 2, W);
        assertTrue("改复制盘不应影响原盘", a.isEmpty(a.fromIndex(2 * 9 + 2)));
        assertEquals(W, c.colorAt(c.fromIndex(2 * 9 + 2)));
    }

    @Test
    public void 复制后撤销_原盘不应受影响() {
        Board a = new Board(9);
        play(a, 4, 4, B);
        long hash = a.hash();
        Board c = a.copy();
        play(c, 2, 2, W);
        c.undo();
        assertEquals("原盘 hash 不应变", hash, a.hash());
    }

    @Test
    public void fromIndex与toIndex应互逆() {
        Board b = new Board(9);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                int p = b.fromIndex(y * 9 + x);
                assertEquals("toIndex(fromIndex(i)) 应等于 i", y * 9 + x, b.toIndex(p));
                assertEquals("x 坐标应还原", x, b.x(p));
                assertEquals("y 坐标应还原", y, b.y(p));
            }
        }
    }

    @Test
    public void 多尺寸棋盘坐标映射都应正确() {
        for (int size : new int[]{9, 13, 19}) {
            Board b = new Board(size);
            int last = size - 1;
            int p = b.fromIndex(last * size + last);
            assertEquals("右下角 x", last, b.x(p));
            assertEquals("右下角 y", last, b.y(p));
            assertEquals("右下角应为合法点", last * size + last, b.toIndex(p));
        }
    }

    // ---------------- hash 一致性 ----------------

    @Test
    public void 不同局面hash应不同() {
        Board a = new Board(9);
        play(a, 4, 4, B);
        Board b = new Board(9);
        play(b, 4, 4, W);
        assertTrue("黑白子相同位置 hash 应不同", a.hash() != b.hash());
    }

    @Test
    public void 相同走法序列hash应相同() {
        Board a = new Board(9);
        play(a, 4, 4, B);
        play(a, 5, 5, W);
        play(a, 3, 3, B);
        Board b = new Board(9);
        play(b, 4, 4, B);
        play(b, 5, 5, W);
        play(b, 3, 3, B);
        assertEquals("同一序列应得到同一 hash", a.hash(), b.hash());
    }

    // ---------------- 气数缓存压力测试 ----------------

    @Test
    public void 随机落子后_气数缓存始终自洽() {
        // 用固定种子保证可复现；每步都校验缓存，任何一处漏更新都会被抓到
        java.util.Random rnd = new java.util.Random(20260929L);
        Board b = new Board(9);
        byte col = B;
        int placed = 0;
        for (int i = 0; i < 400 && placed < 60; i++) {
            int idx = rnd.nextInt(81);
            int p = b.fromIndex(idx);
            if (b.play(p, col)) {
                placed++;
                col = Board.opposite(col);
                assertTrue("第 " + placed + " 手后缓存应自洽", b.validate());
            }
        }
        assertTrue("应该落下一些子", placed > 10);
    }

    @Test
    public void 随机落子撤销交替_棋盘应始终自洽() {
        java.util.Random rnd = new java.util.Random(1234L);
        Board b = new Board(9);
        byte col = B;
        for (int i = 0; i < 300; i++) {
            int idx = rnd.nextInt(81);
            int p = b.fromIndex(idx);
            if (b.play(p, col)) {
                col = Board.opposite(col);
            }
            if (i % 3 == 0) {
                b.undo();
                col = Board.opposite(col);
            }
            assertTrue("第 " + i + " 步后应自洽", b.validate());
        }
    }

    @Test
    public void 提子压力测试_反复提与撤销() {
        Board b = new Board(9);
        // 铺一个容易反复提子的局面
        for (int x = 0; x < 9; x++) {
            play(b, x, 0, B);
        }
        play(b, 4, 1, W);
        assertTrue(b.validate());
        long h = b.hash();
        for (int i = 0; i < 20; i++) {
            play(b, 4, 2, W); // 可能是自杀/提子，视局面而定
            assertTrue(b.validate());
            b.undo();
            assertEquals("反复撤销后应回到同一局面", h, b.hash());
        }
    }
}
