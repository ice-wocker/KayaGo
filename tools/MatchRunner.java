package tools;

import com.kayago.ai.MctsEngine;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.game.Board;
import com.kayago.game.Game;

import java.util.Locale;

/**
 * 对局跑分：让两组参数互相对弈、每局交换黑白，用胜率判断哪一组更强。
 *
 * <p>用法：{@code java -cp &lt;classes&gt; tools.MatchRunner [局数] [每手毫秒] [路数] [线程] [seed]}
 * 默认比较「当前运行时参数」与「内置手写参数」，用来验证进化出来的参数是否真的更强。
 */
public final class MatchRunner {

    public static final double KOMI = 7.5;

    /** 一组对局的结果。 */
    public static final class Result {
        public int aWins;
        public int bWins;

        public int total() {
            return aWins + bWins;
        }

        public double aRate() {
            return total() > 0 ? 100.0 * aWins / total() : 0;
        }
    }

    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        long ms = args.length > 1 ? Long.parseLong(args[1]) : 150;
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 9;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int seed = args.length > 4 ? Integer.parseInt(args[4]) : 100;
        // 可选：只覆盖 A 方的某个超参（0 表示沿用当前值），用来单独验证一个改动。
        double aTemp = args.length > 5 ? Double.parseDouble(args[5]) : 0;
        double aPuct = args.length > 6 ? Double.parseDouble(args[6]) : 0;

        Variant a = Variant.current("current");
        if (aTemp > 0) {
            a = a.with("priorTemperature", aTemp);
        }
        if (aPuct > 0) {
            a = a.with("puctC", aPuct);
        }
        Variant b = new Variant("builtin", com.kayago.ai.Tuned.defaultWeights(),
                -8, 1.6, 800, 9.0, 1.15, 0);
        System.out.println("A = " + a.name + "   B = " + b.name);
        Result r = play(a, b, games, ms, size, threads, seed, true);
        System.out.println();
        System.out.println(String.format(Locale.US,
                "A 胜 %d / B 胜 %d  ->  A 胜率 %.1f%%  (共 %d 局, %dms/手, %dx%d)",
                r.aWins, r.bWins, r.aRate(), r.total(), ms, size, size));
        System.out.println(String.format(Locale.US, "Wilson 95%% 下界 %.1f%%",
                100 * wilsonLowerBound(r.aWins, r.total())));
    }

    /** 胜率（0~1）的 Wilson 95% 置信下界（与人机进化工具用的判据一致）。 */
    public static double wilsonLowerBound(int wins, int total) {
        if (total <= 0) {
            return 0;
        }
        double n = total;
        double p = (double) wins / n;
        double z = 1.96;
        double denom = 1 + z * z / n;
        double center = p + z * z / (2 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n));
        return (center - margin) / denom;
    }

    /**
     * 让 a 与 b 对弈若干局（每局交换黑白）。任一方参数在它自己的回合生效。
     */
    public static Result play(Variant a, Variant b, int games, long ms, int size, int threads,
                              int seedBase, boolean verbose) {
        MctsEngine engine = new MctsEngine();
        Result res = new Result();
        for (int g = 0; g < games; g++) {
            boolean aIsBlack = (g % 2 == 0);
            Game game = new Game(size, KOMI);
            int limit = 4 * size * size;
            for (int i = 0; i < limit && !game.isOver(); i++) {
                boolean aTurn = (game.toMove() == Board.BLACK) == aIsBlack;
                Variant v = aTurn ? a : b;
                v.apply();
                SearchConfig cfg = SearchConfig.forLevel(3, size);
                cfg.maxTimeMs = ms;
                cfg.threads = threads;
                cfg.seed = seedBase * 131L + g * 7919L + i;
                SearchResult r = engine.think(game.board(), game.toMove(), game.lastMove(),
                        KOMI, cfg);
                int mv = r.move;
                if (mv != Board.PASS && !game.board().isLegal(mv, game.toMove())) {
                    mv = Board.PASS;
                }
                if (!game.play(mv)) {
                    game.play(Board.PASS);
                }
            }
            if (!game.isOver()) {
                game.finish();
            }
            boolean aWon = (game.winner() == Board.BLACK) == aIsBlack;
            if (aWon) {
                res.aWins++;
            } else {
                res.bWins++;
            }
            if (verbose) {
                System.out.println(String.format(Locale.US,
                        "  第 %2d 局: %s 胜（%d 手）  A=%d B=%d", g + 1,
                        game.winner() == Board.BLACK ? "黑" : "白", game.moveNumber(),
                        res.aWins, res.bWins));
            }
        }
        return res;
    }
}
