package tools;

import com.kayago.ai.MctsEngine;
import com.kayago.ai.Playout;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.game.Board;
import com.kayago.game.Game;
import com.kayago.game.Scorer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * KayaGo 规则内核 + AI 引擎的 JVM 自测（不属于 APK 的一部分）。
 *
 * 编译运行：
 *   javac -d /tmp/kayago-test $(find app/src/main/java tools -name '*.java')
 *   java -cp /tmp/kayago-test tools.EngineSelfTest
 */
public final class EngineSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testBasicCapture();
        testSuicide();
        testCaptureThenLive();
        testKo();
        testEyeLike();
        testScoring();
        testUndoStress();
        testGameKoAndHistory();
        testEvaluatesScore();
        testOpeningSanity();
        bench();
        testEngineCapturesAtari();
        testEngineSelfPlay();

        System.out.println();
        System.out.println("==================================");
        System.out.println("通过: " + passed + "   失败: " + failed);
        System.out.println("==================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void testBasicCapture() {
        Board b = new Board(9);
        check("摆放白子", b.play(b.point(0, 0), Board.WHITE));
        check("黑(1,0)", b.play(b.point(1, 0), Board.BLACK));
        check("白子仍有气", b.colorAt(b.point(0, 0)) == Board.WHITE);
        check("黑(0,1) 提子", b.play(b.point(0, 1), Board.BLACK));
        check("白子被提", b.colorAt(b.point(0, 0)) == Board.EMPTY);
        check("提子计数", b.captured[Board.WHITE] == 1);
        check("空点数正确", b.emptyCount() == 81 - 2);
        check("hash 非零", b.hash() != 0);
        check("结构一致", b.validate());
    }

    private static void testSuicide() {
        Board b = new Board(9);
        b.play(b.point(1, 0), Board.WHITE);
        b.play(b.point(0, 1), Board.WHITE);
        check("禁止自杀", !b.play(b.point(0, 0), Board.BLACK));
        check("自杀未改变棋盘", b.colorAt(b.point(0, 0)) == Board.EMPTY);
    }

    private static void testCaptureThenLive() {
        // (0,0) 白子只剩 (0,1) 一口气；(0,1) 四周被占，黑直接扑进去可以提子
        Board b = new Board(9);
        b.play(b.point(0, 0), Board.WHITE);
        b.play(b.point(1, 0), Board.BLACK);
        b.play(b.point(0, 2), Board.BLACK);
        b.play(b.point(1, 1), Board.BLACK);
        check("白(0,0)只剩一气", b.chainLibsAt(b.point(0, 0)) == 1);
        check("先提子后有气，合法", b.play(b.point(0, 1), Board.BLACK));
        check("(0,0) 被提", b.colorAt(b.point(0, 0)) == Board.EMPTY);
        check("(0,1) 有黑子", b.colorAt(b.point(0, 1)) == Board.BLACK);
        check("结构一致", b.validate());
    }

    private static void testKo() {
        Board b = new Board(9);
        // 经典劫形
        b.play(b.point(1, 2), Board.BLACK);
        b.play(b.point(0, 1), Board.BLACK);
        b.play(b.point(1, 0), Board.BLACK);
        b.play(b.point(2, 2), Board.WHITE);
        b.play(b.point(1, 1), Board.WHITE);
        b.play(b.point(3, 1), Board.WHITE);
        b.play(b.point(2, 0), Board.WHITE);
        check("白(1,1)只剩一气", b.chainLibsAt(b.point(1, 1)) == 1);

        check("黑提劫", b.play(b.point(2, 1), Board.BLACK));
        check("(1,1) 被提", b.colorAt(b.point(1, 1)) == Board.EMPTY);
        check("形成劫禁着点", b.koPoint == b.point(1, 1));
        check("白不能立即提回", !b.play(b.point(1, 1), Board.WHITE));

        // 别处一手之后劫解除
        check("黑下别处", b.play(b.point(7, 7), Board.BLACK));
        check("劫已解除", b.koPoint == -1);
        check("白可提回", b.play(b.point(1, 1), Board.WHITE));
        check("黑(2,1) 被提", b.colorAt(b.point(2, 1)) == Board.EMPTY);
        check("结构一致", b.validate());
    }

    private static void testEyeLike() {
        Board b = new Board(9);
        b.play(b.point(0, 1), Board.BLACK);
        b.play(b.point(2, 1), Board.BLACK);
        b.play(b.point(1, 0), Board.BLACK);
        b.play(b.point(1, 2), Board.BLACK);
        check("(1,1) 是黑真眼", b.isEyeLike(b.point(1, 1), Board.BLACK));

        Board c = new Board(9);
        c.play(c.point(0, 1), Board.BLACK);
        c.play(c.point(2, 1), Board.BLACK);
        c.play(c.point(1, 0), Board.BLACK);
        c.play(c.point(1, 2), Board.BLACK);
        c.play(c.point(0, 2), Board.WHITE);
        c.play(c.point(2, 2), Board.WHITE);
        check("两个对角被占 -> 假眼", !c.isEyeLike(c.point(1, 1), Board.BLACK));
    }

    private static void testScoring() {
        Board b = new Board(9);
        Scorer s = new Scorer(b);
        check("空盘分差 = -贴目", Math.abs(s.areaDiff(6.5) + 6.5) < 1e-9);
        b.play(b.point(4, 4), Board.BLACK);
        b.play(b.point(4, 5), Board.WHITE);
        check("黑白各一子时分差 = -贴目", Math.abs(s.areaDiff(6.5) + 6.5) < 1e-9);

        // 黑围出左上角 2x2 实地：墙在 x=2 与 y=2，另外放一颗白子让外围成为单官
        Board d = new Board(9);
        d.play(d.point(2, 0), Board.BLACK);
        d.play(d.point(2, 1), Board.BLACK);
        d.play(d.point(0, 2), Board.BLACK);
        d.play(d.point(1, 2), Board.BLACK);
        d.play(d.point(8, 8), Board.WHITE);
        Scorer.Detail det = new Scorer(d).detail(0);
        check("黑地 = 4", det.blackTerritory == 4);
        check("黑子 = 4", det.blackStones == 4);
        check("白子 = 1", det.whiteStones == 1);
        check("单官 = 72", det.dame == 72);
        check("结构一致", d.validate());
    }

    private static void testUndoStress() {
        Random rnd = new Random(12345);
        boolean ok = true;
        for (int trial = 0; trial < 60 && ok; trial++) {
            Board b = new Board(9);
            List<Long> hashes = new ArrayList<>();
            List<Integer> played = new ArrayList<>();
            List<Integer> expectStones = new ArrayList<>();
            int moves = 0;
            for (int i = 0; i < 150; i++) {
                int p = pickRandomLegal(b, rnd, Board.BLACK);
                hashes.add(b.hash());
                expectStones.add(b.stones);
                if (!b.play(p, Board.BLACK)) {
                    ok = false;
                    System.out.println("  [FAIL] 随机落子被拒绝");
                    break;
                }
                played.add(p);
                moves++;
                if (!b.validate()) {
                    ok = false;
                    System.out.println("  [FAIL] 落子后结构不一致 p=" + p);
                    System.out.println("  历史: " + played);
                    System.out.println(diagnose(b));
                    break;
                }
                if (b.colorAt(p) != Board.BLACK && p != Board.PASS) {
                    ok = false;
                    System.out.println("  [FAIL] 落子点颜色不对");
                    break;
                }
            }
            // 逆序撤销，逐手校验
            for (int i = moves - 1; i >= 0 && ok; i--) {
                b.undo();
                if (b.hash() != hashes.get(i)) {
                    ok = false;
                    System.out.println("  [FAIL] 撤销后 hash 不一致，第 " + i + " 手");
                    break;
                }
                if (b.stones != expectStones.get(i)) {
                    ok = false;
                    System.out.println("  [FAIL] 撤销后子数不一致，第 " + i + " 手");
                    break;
                }
                if (!b.validate()) {
                    ok = false;
                    System.out.println("  [FAIL] 撤销后结构不一致，第 " + i + " 手");
                    break;
                }
                if (b.emptyCount() != naiveEmpty(b)) {
                    ok = false;
                    System.out.println("  [FAIL] 撤销后空点数不一致");
                    break;
                }
            }
        }
        check("随机落子/撤销压力测试", ok);
    }

    private static void testGameKoAndHistory() {
        Game g = new Game(9, 6.5);
        // 造一个劫，让同一局面重复出现，第 3 次应被禁止
        g.play(g.board().point(1, 2));
        g.play(g.board().point(2, 2));
        g.play(g.board().point(0, 1));
        g.play(g.board().point(1, 1));
        g.play(g.board().point(1, 0));
        g.play(g.board().point(3, 1));
        g.play(g.board().point(0, 0));   // 黑
        g.play(g.board().point(2, 0));   // 白

        check("黑提劫", g.play(g.board().point(2, 1)));
        check("白不能立即提回", !g.play(g.board().point(1, 1)));
        // 白下别处，黑也下别处，然后白提回，形成劫争
        check("白下别处", g.play(g.board().point(8, 8)));
        check("黑下别处", g.play(g.board().point(7, 8)));
        check("白提回", g.play(g.board().point(1, 1)));
        check("子数正确", g.board().stones == 10);
    }

    private static void testEngineCapturesAtari() {
        Board b = new Board(9);
        // 白 6 子一路，只剩 (6,0) 一口气，黑先必须吃掉
        for (int x = 0; x <= 5; x++) {
            b.play(b.point(x, 0), Board.WHITE);
        }
        for (int x = 0; x <= 5; x++) {
            b.play(b.point(x, 1), Board.BLACK);
        }
        check("白龙只剩一气", b.chainLibsAt(b.point(2, 0)) == 1);

        MctsEngine engine = new MctsEngine();
        SearchConfig cfg = new SearchConfig();
        cfg.maxTimeMs = 1500;
        cfg.threads = 2;
        cfg.seed = 7;

        SearchResult r = engine.think(b, Board.BLACK, b.point(5, 1), 0, cfg);
        System.out.println("     AI 选择: " + b.x(r.move) + "," + b.y(r.move));
        check("AI 吃掉被打吃的白龙", r.move == b.point(6, 0));
        check("AI 胜率合理", r.winRate >= 0 && r.winRate <= 1);
        System.out.println("     推演 " + r.playouts + " 次，用时 " + r.timeMs + "ms，胜率 "
                + String.format("%.3f", r.winRate));

        // 诊断：对比“提子”与“不提子”的形势估计
        Board cap = b.copy();
        cap.play(cap.point(6, 0), Board.BLACK);
        double dCap = MctsEngine.estimateScore(cap, Board.WHITE, Board.PASS, 0, 200, 2);
        double dNow = MctsEngine.estimateScore(b, Board.BLACK, b.point(5, 1), 0, 200, 2);
        System.out.println("     形势估计: 不提子 " + (int) dNow + " / 提子后 " + (int) dCap);
    }

    private static void testEvaluatesScore() {
        Board b = new Board(9);
        for (int x = 0; x < 9; x++) {
            b.play(b.point(x, 0), Board.BLACK);
        }
        for (int x = 0; x < 9; x++) {
            b.play(b.point(x, 1), Board.BLACK);
        }
        double lead = MctsEngine.estimateScore(b, Board.WHITE, Board.PASS, 0, 200, 2);
        check("形势判断：黑大优 (" + (int) lead + ")", lead > 5);

        Board c = new Board(9);
        for (int x = 0; x < 9; x++) {
            c.play(c.point(x, 0), Board.WHITE);
        }
        for (int x = 0; x < 9; x++) {
            c.play(c.point(x, 1), Board.WHITE);
        }
        double lead2 = MctsEngine.estimateScore(c, Board.BLACK, Board.PASS, 0, 200, 2);
        check("形势判断：白大优 (" + (int) lead2 + ")", lead2 < -5);
    }

    private static void testOpeningSanity() {
        MctsEngine engine = new MctsEngine();
        SearchConfig cfg = SearchConfig.forLevel(2, 9);
        cfg.maxTimeMs = 500;
        cfg.threads = 2;
        cfg.seed = 5;

        Board b = new Board(9);
        SearchResult r = engine.think(b, Board.BLACK, Board.PASS, 7.5, cfg);
        int x = b.x(r.move);
        int y = b.y(r.move);
        int edge = Math.min(Math.min(x, b.size - 1 - x), Math.min(y, b.size - 1 - y));
        System.out.println("     空盘第一手: " + x + "," + y + "（离边 " + edge + " 路，推演 "
                + r.playouts + " 次，胜率 " + String.format("%.3f", r.winRate) + "）");
        check("空盘第一手不走一路/二路", edge >= 2);
        check("空盘第一手落在角部一带", edge <= 4);

        // 对手占角后，己方第一手也应该是有价值的大场，而不是贴着边缘
        Board c = new Board(9);
        c.play(c.point(2, 2), Board.WHITE);
        SearchResult r2 = engine.think(c, Board.BLACK, c.point(2, 2), 7.5, cfg);
        int x2 = c.x(r2.move);
        int y2 = c.y(r2.move);
        int edge2 = Math.min(Math.min(x2, c.size - 1 - x2), Math.min(y2, c.size - 1 - y2));
        System.out.println("     白占三三后黑的第一手: " + x2 + "," + y2 + "（离边 " + edge2 + " 路）");
        check("第二手同样不走一路/二路", edge2 >= 2);
    }

    private static void bench() {
        Board b = new Board(9);
        Random rnd = new Random(4242);
        for (int i = 0; i < 24; i++) {
            byte c = i % 2 == 0 ? Board.BLACK : Board.WHITE;
            int p = pickRandomLegal(b, rnd, c);
            if (!b.play(p, c)) {
                break;
            }
        }
        Scorer sc = new Scorer(b);
        long t0 = System.nanoTime();
        int n = 0;
        while (System.nanoTime() - t0 < 1_500_000_000L) {
            Playout.run(b, Board.WHITE, Board.PASS, rnd, 2 * 81 + 60, sc, 7.5);
            n++;
        }
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.println("[BENCH] 单线程推演速度: " + (long) (n / sec) + " 局/秒");

        Board c = b.copy();
        long t1 = System.nanoTime();
        int m = 0;
        while (System.nanoTime() - t1 < 1_500_000_000L) {
            int p = pickRandomLegal(c, rnd, Board.WHITE);
            if (c.play(p, Board.WHITE)) {
                c.undo();
                m++;
            }
        }
        double sec2 = (System.nanoTime() - t1) / 1e9;
        System.out.println("[BENCH] play/undo 速度: " + (long) (m / sec2) + " 次/秒");
    }

    private static void testEngineSelfPlay() {
        Game g = new Game(9, 6.5);
        MctsEngine engine = new MctsEngine();
        SearchConfig cfg = new SearchConfig();
        cfg.maxTimeMs = 120;
        cfg.threads = 2;
        cfg.seed = 99;

        StringBuilder sb = new StringBuilder();
        int rejected = 0;
        int passes = 0;
        int limit = 200;
        for (int i = 0; i < limit && !g.isOver(); i++) {
            SearchResult r = engine.think(g.board(), g.toMove(), g.lastMove(), 6.5, cfg);
            int mv = r.move;
            if (mv != Board.PASS && !g.board().isLegal(mv, g.toMove())) {
                System.out.println("  [WARN] AI 给出非法着法，改停一手");
                mv = Board.PASS;
            }
            if (!g.play(mv)) {
                rejected++;
                g.play(Board.PASS);
            }
            if (mv == Board.PASS) {
                passes++;
                sb.append("pass ");
            } else {
                sb.append(g.board().x(mv)).append(",").append(g.board().y(mv)).append(" ");
            }
        }
        check("AI 自对弈完成", g.isOver() || g.moveNumber() >= limit);
        check("自对弈棋盘结构一致", g.board().validate());
        check("AI 没有被规则拒绝的着法 (" + rejected + ")", rejected == 0);
        System.out.println("     共 " + g.moveNumber() + " 手（其中停手 " + passes + "），结果: "
                + g.resultText());
        System.out.println("     着法: " + sb);
    }

    // ------------------------------------------------------------------

    private static int pickRandomLegal(Board b, Random rnd, byte col) {
        int ec = b.emptyCount();
        if (ec == 0) {
            return Board.PASS;
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            if (rnd.nextInt(100) < 3) {
                return Board.PASS;
            }
            int p = b.emptyAt(rnd.nextInt(ec));
            if (b.isLegal(p, col)) {
                return p;
            }
        }
        for (int i = 0; i < ec; i++) {
            int p = b.emptyAt(i);
            if (b.isLegal(p, col)) {
                return p;
            }
        }
        return Board.PASS;
    }

    private static int naiveEmpty(Board b) {
        int n = 0;
        for (int y = 0; y < b.size; y++) {
            for (int x = 0; x < b.size; x++) {
                if (b.colorAt(b.point(x, y)) == Board.EMPTY) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    /** 结构不一致时输出可读的现场信息。 */
    private static String diagnose(Board b) {
        StringBuilder sb = new StringBuilder();
        sb.append("  棋盘:\n");
        for (int y = 0; y < b.size; y++) {
            sb.append("    ");
            for (int x = 0; x < b.size; x++) {
                byte c = b.colorAt(b.point(x, y));
                sb.append(c == Board.BLACK ? 'X' : (c == Board.WHITE ? 'O' : '.'));
            }
            sb.append('\n');
        }
        for (int p = 0; p < b.total; p++) {
            byte c = b.color[p];
            if (c != Board.BLACK && c != Board.WHITE) {
                continue;
            }
            int head = b.cid[p];
            if (head < 0 || b.color[head] == Board.EMPTY) {
                sb.append("  cid 失效 @(").append(b.x(p)).append(',').append(b.y(p)).append(")\n");
                continue;
            }
            if (head != p) {
                continue;
            }
            int real = rawLibs(b, head);
            if (real != b.chainLibs[head] || real == 0) {
                sb.append("  链头(").append(b.x(head)).append(',').append(b.y(head))
                        .append(") size=").append(b.chainSize[head])
                        .append(" 缓存气=").append(b.chainLibs[head])
                        .append(" 实际气=").append(real).append('\n');
            }
        }
        return sb.toString();
    }

    private static int rawLibs(Board b, int head) {
        boolean[] seen = new boolean[b.total];
        int cnt = 0;
        int s = head;
        int guard = 0;
        while (s != -1) {
            if (++guard > b.points) {
                return -2; // 链表成环
            }
            for (int d = 0; d < 4; d++) {
                int q = b.nb(s, d);
                if (b.color[q] == Board.EMPTY && !seen[q]) {
                    seen[q] = true;
                    cnt++;
                }
            }
            s = b.chainNext[s];
        }
        return cnt;
    }
}
