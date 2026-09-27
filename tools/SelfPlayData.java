package tools;

import com.kayago.ai.MctsEngine;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.game.Board;
import com.kayago.game.Game;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 自对弈数据生成：跑若干局自对弈，把每局的着法以及**每个局面上引擎自己的根访问分布**
 * 写出来，供 {@link Trainer} 做策略蒸馏。
 *
 * <p>数据格式（逐行）：
 * <pre>
 * GAME &lt;size&gt; &lt;komi&gt;
 * RESULT &lt;winner&gt; &lt;diff&gt;                     # winner: 1=黑胜 2=白胜（用于胜负学习）
 * POS &lt;ply&gt; &lt;n&gt; &lt;move&gt; &lt;visits&gt; ...      # 该局面下引擎的根候选与访问次数
 * MOVE &lt;move&gt;                             # 紧接着实际走的一手
 * ...
 * END
 * </pre>
 * POS 一定出现在对应 MOVE 之前，所以读取时按顺序重放棋盘即可还原局面。
 * RESULT 写在 GAME 之后、POS 之前，读取端在造样本时就已知道该局胜负，
 * 于是每个局面都能标上「行棋方最后是赢还是输」——这是策略梯度的回报信号。
 */
public final class SelfPlayData {

    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        long ms = args.length > 1 ? Long.parseLong(args[1]) : 60;
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 9;
        String out = args.length > 3 ? args[3] : "/tmp/kayago-selfplay.txt";
        int seedBase = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        generate(games, ms, size, out, seedBase, true);
    }

    /** 用当前运行时参数自对弈并写数据。 */
    public static void generate(int games, long ms, int size, String out, int seedBase,
                               boolean verbose) throws Exception {
        MctsEngine engine = new MctsEngine();
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.println("# kayago self-play  size=" + size + " ms=" + ms + " games=" + games);
            for (int g = 0; g < games; g++) {
                oneGame(engine, w, size, ms, seedBase + g);
                if (verbose && (g + 1) % 10 == 0) {
                    System.out.println("  自对弈 " + (g + 1) + "/" + games + " 局");
                }
            }
        }
        if (verbose) {
            System.out.println("数据已写入 " + out);
        }
    }

    private static void oneGame(MctsEngine engine, PrintWriter w, int size, long ms, int seed) {
        Game game = new Game(size, MatchRunner.KOMI);
        SearchConfig cfg = SearchConfig.forLevel(3, size);
        cfg.maxTimeMs = ms;
        cfg.dumpRootStats = true;

        StringBuilder body = new StringBuilder(4096);
        int limit = 4 * size * size;
        for (int i = 0; i < limit && !game.isOver(); i++) {
            cfg.seed = seed * 100003L + i * 31L;
            SearchResult r = engine.think(game.board(), game.toMove(), game.lastMove(),
                    MatchRunner.KOMI, cfg);
            if (r.rootMoves != null && r.rootMoves.length >= 2) {
                body.append("POS ").append(i).append(' ').append(r.rootMoves.length);
                for (int k = 0; k < r.rootMoves.length; k++) {
                    body.append(' ').append(r.rootMoves[k]).append(' ')
                            .append(r.rootVisits[k]);
                }
                body.append('\n');
            }
            int mv = r.move;
            if (mv != Board.PASS && !game.board().isLegal(mv, game.toMove())) {
                mv = Board.PASS;
            }
            if (!game.play(mv)) {
                game.play(Board.PASS);
            }
            body.append("MOVE ").append(mv).append('\n');
        }
        // 上限到了也要给出一个胜负（按中国规则数子），否则这一局没有可用的回报信号。
        if (!game.isOver()) {
            game.finish();
        }
        byte winner = game.winner();
        double diff = Math.abs(game.detail().diff());
        w.println("GAME " + size + " " + String.format(Locale.US, "%.1f", MatchRunner.KOMI));
        w.println("RESULT " + winner + " " + String.format(Locale.US, "%.1f", diff));
        w.print(body);
        w.println("END");
    }
}
