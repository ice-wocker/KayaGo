package tools;

import com.kayago.ai.MctsEngine;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.game.Board;
import com.kayago.game.Game;

import java.util.Locale;

/**
 * 引擎自对弈对局跑分工具（开发用，不参与 APK 打包）。
 *
 * 用同一套代码、两组不同搜索配置互相对弈，用来客观衡量某项改动是否真的变强。
 * 用法：
 *   java -cp &lt;classes&gt; tools.MatchRunner [局数] [每手毫秒] [棋盘路数]
 *
 * A 方配置来自 {@code configA()}，B 方来自 {@code configB()}，
 * 每局交换黑白以保证公平，最终输出胜率。
 */
public final class MatchRunner {

    private static final double KOMI = 7.5;

    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        long msPerMove = args.length > 1 ? Long.parseLong(args[1]) : 150;
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 9;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int mode = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        System.out.println(mode == 0
                ? "对照组：A = PUCT + RAVE + 分差回报，B = 纯 UCB1"
                : "对照组：A = 分差回报，B = 非胜即负（其余相同）");
        MctsEngine engine = new MctsEngine();
        int aWins = 0;
        int bWins = 0;

        for (int g = 0; g < games; g++) {
            boolean aIsBlack = (g % 2 == 0);
            SearchConfig cfgA = configA(msPerMove, size, threads);
            SearchConfig cfgB = configB(msPerMove, size, threads, mode);
            cfgA.seed = 1000 + g;
            cfgB.seed = 2000 + g;

            Game game = new Game(size, KOMI);
            int limit = 4 * size * size;
            for (int i = 0; i < limit && !game.isOver(); i++) {
                boolean aTurn = (game.toMove() == Board.BLACK) == aIsBlack;
                SearchConfig cfg = aTurn ? cfgA : cfgB;
                SearchResult r = engine.think(game.board(), game.toMove(), game.lastMove(), KOMI, cfg);
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
            byte winner = game.winner();
            boolean aWon = (winner == Board.BLACK) == aIsBlack;
            if (aWon) {
                aWins++;
            } else {
                bWins++;
            }
            System.out.println(String.format(Locale.US,
                    "  第 %2d 局: %s 胜（%d 手，%s）  A=%d B=%d",
                    g + 1, winner == Board.BLACK ? "黑" : "白", game.moveNumber(),
                    game.resultText(), aWins, bWins));
        }

        int total = aWins + bWins;
        double rate = total > 0 ? 100.0 * aWins / total : 0;
        System.out.println();
        System.out.println(String.format(Locale.US,
                "A 胜 %d / B 胜 %d  ->  A 胜率 %.1f%%  (共 %d 局, %dms/手, %dx%d)",
                aWins, bWins, rate, total, msPerMove, size, size));
    }

    /** A 方：当前引擎（PUCT + RAVE + 分差回报全开）。 */
    private static SearchConfig configA(long ms, int size, int threads) {
        SearchConfig c = SearchConfig.forLevel(3, size);
        c.maxTimeMs = ms;
        c.threads = threads;
        return c;
    }

    /**
     * B 方对照组：
     * mode 0 = 纯 UCB1（无先验、无 RAVE），验证 PUCT + RAVE 是否有效；
     * mode 1 = 其余相同但用非胜即负回报，验证分差回报是否有效。
     */
    private static SearchConfig configB(long ms, int size, int threads, int mode) {
        SearchConfig c = SearchConfig.forLevel(3, size);
        c.maxTimeMs = ms;
        c.threads = threads;
        if (mode == 0) {
            c.puctC = 0;
            c.raveK = 0;
        } else {
            c.scoreReward = false;
        }
        return c;
    }
}
