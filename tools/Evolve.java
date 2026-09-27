package tools;

import com.kayago.ai.MctsEngine;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.ai.Tuned;
import com.kayago.game.Board;

import java.util.List;
import java.util.Locale;

/**
 * 自对弈进化主循环（RSI 式闭环）。
 *
 * <p>每一轮做四件事：
 * <ol>
 *   <li><b>自对弈产数据</b>：用现任冠军参数自我对弈，记录每个局面上引擎自己的根访问分布，
 *       以及**每局的胜负**；</li>
 *   <li><b>训练</b>：混合目标 = 策略蒸馏（拟合访问分布）+ **胜负策略梯度**
 *       （用「行棋方最后是赢是输」把实际那手推高/压低）。后者是引擎自身先验之外的新信号；</li>
 *   <li><b>跑分验证</b>：候选与现任冠军互相对弈、每局交换黑白；</li>
 *   <li><b>晋升</b>：只有胜率达标才把候选写成新冠军并落盘到 TunedParams.java，否则丢弃。</li>
 * </ol>
 * 晋升后的冠军会成为下一轮的数据来源，于是「更强的策略 → 更好的数据 → 更强的策略」
 * 持续滚动。<b>每一轮都必须跑赢上一轮才会被采纳</b>，所以不会越进化越差。
 *
 * <p>用法：
 * <pre>
 * java -cp &lt;classes&gt; tools.Evolve [轮数] [每轮自对弈局数] [每手毫秒] [路数] [验证局数] [是否顺带调超参]
 * </pre>
 * 例：{@code tools.Evolve 3 40 60 9 20 0}
 */
public final class Evolve {

    private static final String TUNED_PATH = "app/src/main/java/com/kayago/ai/TunedParams.java";

    /**
     * 胜负策略梯度项的权重：蒸馏项负责「别偏离引擎已有判断太远」，
     * 这一项负责「把当时那手往真正赢棋的方向挪」。两者量级取相近。
     */
    private static final double PG_WEIGHT = 0.5;

    /**
     * 晋升门槛：用 Wilson 区间 95% 下界来判断「候选真的更强」。
     *
     * <p>只用原始胜率会有个隐蔽的坑：样本小的时候噪声极大——同一份候选参数，
     * 16 局里可能这一轮 43.8%、下一轮 62.5%，于是「晋升」变成了抽奖。
     * 这里改成先算胜率的 Wilson 95% 置信下界，只有下界仍然 &gt; 50% 才晋升，
     * 等价于要求「有 95% 把握候选强于冠军」，样本不足时宁可不动。
     */
    private static final double Z_95 = 1.96;

    /** 胜率（0~1）的 Wilson 95% 置信下界。 */
    private static double wilsonLowerBound(int wins, int total) {
        if (total <= 0) {
            return 0;
        }
        double n = total;
        double p = (double) wins / n;
        double denom = 1 + Z_95 * Z_95 / n;
        double center = p + Z_95 * Z_95 / (2 * n);
        double margin = Z_95 * Math.sqrt(p * (1 - p) / n + Z_95 * Z_95 / (4 * n * n));
        return (center - margin) / denom;
    }

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int gamesPerRound = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        long ms = args.length > 2 ? Long.parseLong(args[2]) : 60;
        int size = args.length > 3 ? Integer.parseInt(args[3]) : 9;
        int verifyGames = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        boolean tuneHyper = args.length > 5 && Integer.parseInt(args[5]) != 0;

        System.out.println("=== KayaGo 自对弈进化 ===");
        System.out.println(String.format(Locale.US,
                "轮数 %d，每轮自对弈 %d 局，%dms/手，%dx%d，验证 %d 局，调超参 %s",
                rounds, gamesPerRound, ms, size, size, verifyGames, tuneHyper ? "开" : "关"));

        Variant champion = Variant.current("builtin");

        if (tuneHyper) {
            champion = probeHyper(champion, ms, size);
        }

        for (int round = 1; round <= rounds; round++) {
            System.out.println();
            System.out.println("=== 第 " + round + " 轮（冠军：" + champion.name + "）===");

            // 1) 自对弈产数据
            Tuned.weights = champion.weights.clone();
            champion.apply();
            String data = "/tmp/kayago-selfplay-r" + round + ".txt";
            SelfPlayData.generate(gamesPerRound, ms, size, data, 1000 + round * 97, true);

            // 2) 策略蒸馏
            List<Trainer.Sample> samples = Trainer.load(data);
            if (samples.size() < 50) {
                System.out.println("  样本太少（" + samples.size() + "），跳过本轮");
                continue;
            }
            double[] candW = Trainer.train(samples, champion.weights, 160, 40.0, 2e-3,
                    1.0 / champion.priorTemperature, PG_WEIGHT, 7, true);
            Variant cand = champion.withWeights(candW);
            System.out.println(String.format(Locale.US,
                    "  线位权重 line0..3：%.2f %.2f %.2f %.2f  ->  %.2f %.2f %.2f %.2f",
                    champion.weights[14], champion.weights[15], champion.weights[16],
                    champion.weights[17],
                    candW[14], candW[15], candW[16], candW[17]));

            // 3) 开局不变量闸门：候选不能把「开局不走一路/二路」这条已知的好习惯练没了。
            String gateFail = openingGateFail(candW);
            if (gateFail != null) {
                System.out.println("  -> 未通过开局不变量（" + gateFail + "），保留原冠军");
                continue;
            }

            // 4) 验证
            MatchRunner.Result r = MatchRunner.play(cand, champion, verifyGames, ms, size, 0,
                    5000 + round, false);
            double lb = wilsonLowerBound(r.aWins, r.total());
            System.out.println(String.format(Locale.US,
                    "  验证：候选 %d : 冠军 %d  ->  胜率 %.1f%%，Wilson 95%% 下界 %.1f%%",
                    r.aWins, r.bWins, r.aRate(), 100 * lb));

            // 5) 晋升：只有「95% 把握更强」才采纳，样本不足时宁可保留原冠军
            if (lb > 0.5 && r.aWins > r.bWins) {
                champion = new Variant("evolved-r" + round, candW, champion.passScore,
                        champion.puctC, champion.raveK, champion.priorTemperature,
                        champion.uctC, champion.scoreScale);
                Trainer.writeTunedParams(TUNED_PATH, champion,
                        "self-play evolution round " + round + " (verify "
                                + r.aWins + "-" + r.bWins + ")");
                System.out.println("  -> 晋升！已写入 " + TUNED_PATH);
            } else {
                System.out.println("  -> 证据不足以判定更强（Wilson 下界未过半），保留原冠军");
            }
        }

        champion.apply();
        System.out.println();
        System.out.println("=== 结束，最终冠军：" + champion.name + " ===");
        System.out.println("提示：若本轮有晋升，重新编译即可让 APK 用上新参数。");
    }

    /**
     * 已知不变量的硬闸门：让候选在空盘上下第一手，检查它仍然「不走一路/二路、落在角部一带」。
     *
     * <p>这是进化里的**回归护栏**。策略蒸馏一旦让线位类特征的相对权重失真，引擎就会退化成
     * 贴边乱走；与其等它对局跑分输掉，不如先用一条便宜的硬规则把这种候选挡在门外。
     *
     * @return 违反时的原因描述；通过则返回 {@code null}
     */
    private static String openingGateFail(double[] weights) {
        double[] saved = Tuned.weights;
        try {
            Tuned.weights = weights.clone();
            MctsEngine engine = new MctsEngine();
            SearchConfig cfg = SearchConfig.forLevel(2, 9);
            cfg.maxTimeMs = 400;
            cfg.threads = 2;
            cfg.seed = 5;
            Board b = new Board(9);
            SearchResult r = engine.think(b, Board.BLACK, Board.PASS, MatchRunner.KOMI, cfg);
            int x = b.x(r.move);
            int y = b.y(r.move);
            int edge = Math.min(Math.min(x, b.size - 1 - x), Math.min(y, b.size - 1 - y));
            System.out.println(String.format(Locale.US,
                    "  开局自检：首手 %d,%d（离边 %d 路）", x, y, edge));
            if (edge < 2) {
                return "首手离边太近（" + edge + " 路）";
            }
            if (edge > 4) {
                return "首手离角太远（" + edge + " 路）";
            }
            return null;
        } finally {
            Tuned.weights = saved;
        }
    }

    /**
     * 搜索超参的邻域探测：对每个超参试几个倍率，各跑一组短对局，
     * 只保留确实变强的那一档。这是「进化」的第二条路径（第一条是权重蒸馏）。
     */
    private static Variant probeHyper(Variant champion, long ms, int size) {
        String[] params = {"puctC", "priorTemperature", "raveK"};
        double[][] factors = {{0.7, 1.4}, {0.6, 1.6}, {0.5, 2.0}};
        System.out.println();
        System.out.println("=== 搜索超参邻域探测（起点：" + champion.name + "）===");
        for (int i = 0; i < params.length; i++) {
            String param = params[i];
            double base = valueOf(champion, param);
            Variant bestCand = null;
            double bestRate = 50.0;
            for (double f : factors[i]) {
                Variant cand = champion.with(param, base * f);
                MatchRunner.Result r = MatchRunner.play(cand, champion, 6, ms, size, 0, 9100 + i,
                        false);
                System.out.println(String.format(Locale.US,
                        "  %s = %.3f（×%.1f）: %d : %d  (%.1f%%)", param, base * f, f,
                        r.aWins, r.bWins, r.aRate()));
                if (r.aWins > r.bWins && r.aRate() > bestRate) {
                    bestRate = r.aRate();
                    bestCand = cand;
                }
            }
            if (bestCand != null) {
                champion = bestCand;
                System.out.println("  -> 采用 " + param + " = "
                        + String.format(Locale.US, "%.3f", valueOf(champion, param)));
            }
        }
        return champion;
    }

    private static double valueOf(Variant v, String param) {
        switch (param) {
            case "puctC":
                return v.puctC;
            case "raveK":
                return v.raveK;
            case "priorTemperature":
                return v.priorTemperature;
            case "uctC":
                return v.uctC;
            default:
                return v.scoreScale;
        }
    }
}
