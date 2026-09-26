package tools;

import com.kayago.ai.MoveFeatures;
import com.kayago.game.Board;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 用自对弈数据训练走子评估权重。
 *
 * <p>训练目标是**引擎自己中深搜索产生的根访问分布**（策略蒸馏）：
 * 对每个局面，把候选着法的特征喂给线性模型得到 logits，做 softmax 与访问分布算交叉熵，
 * 用梯度下降更新权重。起点是当前冠军权重，所以每轮都在「现有水平」上精修，
 * 而不是从随机权重乱走——这也是进化能持续累积的原因。
 */
public final class Trainer {

    /** 一个局面的训练样本。 */
    public static final class Sample {
        public double[][] feats;  // [候选][特征]
        public double[] target;   // 归一化后的访问分布
    }

    // ------------------------------------------------------------------
    // 数据读取
    // ------------------------------------------------------------------

    public static List<Sample> load(String path) throws Exception {
        List<Sample> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            Board board = null;
            byte toMove = Board.BLACK;
            int lastMove = Board.PASS;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] t = line.split("\\s+");
                if (t[0].equals("GAME")) {
                    board = new Board(Integer.parseInt(t[1]));
                    toMove = Board.BLACK;
                    lastMove = Board.PASS;
                } else if (t[0].equals("POS")) {
                    if (board == null) {
                        continue;
                    }
                    Sample s = parseSample(board, toMove, lastMove, t);
                    if (s != null) {
                        out.add(s);
                    }
                } else if (t[0].equals("MOVE") && board != null) {
                    int m = Integer.parseInt(t[1]);
                    if (!board.play(m, toMove)) {
                        board.play(Board.PASS, toMove);
                        m = Board.PASS;
                    }
                    lastMove = m;
                    toMove = Board.opposite(toMove);
                }
            }
        }
        return out;
    }

    private static Sample parseSample(Board board, byte toMove, int lastMove, String[] t) {
        int n = Integer.parseInt(t[2]);
        if (n < 2) {
            return null;
        }
        int[] mv = new int[n];
        int[] visits = new int[n];
        int total = 0;
        int real = 0;
        for (int i = 0; i < n; i++) {
            mv[i] = Integer.parseInt(t[3 + 2 * i]);
            visits[i] = Integer.parseInt(t[4 + 2 * i]);
            if (mv[i] != Board.PASS) {
                total += visits[i];
                real++;
            }
        }
        if (total <= 0 || real < 2) {
            return null;
        }
        Sample s = new Sample();
        s.feats = new double[real][];
        s.target = new double[real];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (mv[i] == Board.PASS) {
                continue; // 停一手不作为学习目标
            }
            double[] f = new double[MoveFeatures.COUNT];
            MoveFeatures.extract(board, mv[i], toMove, lastMove, f);
            s.feats[k] = f;
            s.target[k] = (double) visits[i] / total;
            k++;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 训练
    // ------------------------------------------------------------------

    private static final int BATCH = 32;

    /**
     * softmax 回归（策略蒸馏）。
     *
     * <p><b>关键：logit 必须按推理时的温度缩放。</b>引擎在根节点算的是
     * {@code prior ∝ softmax(Σw·f / priorTemperature)}；如果这里按 {@code softmax(Σw·f)}
     * 去拟合同一份访问分布，最优解会变成 {@code w ≈ w_冠军 / T}，权重被整体压扁，
     * 温度越高的特征（线位、角部）越先失真，下一轮先验更平——形成「越训练越糊」的
     * 退化闭环。这里用 {@code logitScale = 1/T} 让训练目标与推理完全一致，
     * 于是最优解就是冠军权重本身，训练只在「搜索相对先验认为更好的方向」上做精修。
     *
     * <p>同时把 L2 改成**向冠军权重收缩**（proximal：{@code l2·(w - initW)}）而不是
     * 向 0 收缩，保证每一轮都是小步精修、不跑偏。
     *
     * @param initW      初始权重（当前冠军），同时作为收缩锚点
     * @param epochs     训练轮数
     * @param lr         学习率
     * @param l2         向冠军权重收缩的强度
     * @param logitScale 推理温度倒数（{@code 1 / priorTemperature}）
     * @return 验证集上表现最好的权重
     */
    public static double[] train(List<Sample> samples, double[] initW, int epochs, double lr,
                                 double l2, double logitScale, long seed, boolean verbose) {
        final int F = MoveFeatures.COUNT;
        int n = samples.size();
        if (n == 0) {
            return initW.clone();
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Random rnd = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
        }
        int valCount = Math.max(1, Math.min(n / 10, 200));
        int trainEnd = n;

        double[] w = initW.clone();
        double[] best = w.clone();
        double[] grad = new double[F];
        double[] z = new double[256];

        double baseVal = evaluate(samples, idx, 0, valCount, w, z, logitScale);
        double bestVal = baseVal;
        if (verbose) {
            System.out.println(String.format(Locale.US,
                    "  训练样本 %d，验证样本 %d，初始验证交叉熵 %.4f", trainEnd - valCount,
                    valCount, baseVal));
        }

        for (int ep = 0; ep < epochs; ep++) {
            for (int i = 0; i < F; i++) {
                grad[i] = 0;
            }
            int inBatch = 0;
            double trainLoss = 0;
            for (int s = valCount; s < trainEnd; s++) {
                Sample sm = samples.get(idx[s]);
                int m = sm.feats.length;
                if (z.length < m) {
                    z = new double[m];
                }
                trainLoss += accumulate(sm, w, grad, z, logitScale);
                inBatch++;
                if (inBatch == BATCH) {
                    apply(w, grad, lr / BATCH, l2, initW);
                    for (int i = 0; i < F; i++) {
                        grad[i] = 0;
                    }
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                apply(w, grad, lr / inBatch, l2, initW);
            }
            double val = evaluate(samples, idx, 0, valCount, w, z, logitScale);
            if (val < bestVal) {
                bestVal = val;
                best = w.clone();
            }
            if (verbose && (ep % 20 == 0 || ep == epochs - 1)) {
                System.out.println(String.format(Locale.US,
                        "    epoch %3d  train %.4f  val %.4f%s", ep,
                        trainLoss / Math.max(1, trainEnd - valCount), val,
                        val <= bestVal ? "  *" : ""));
            }
        }
        if (verbose) {
            System.out.println(String.format(Locale.US,
                    "  验证交叉熵 %.4f -> %.4f", baseVal, bestVal));
        }
        return best;
    }

    /** 累加一个样本的梯度，返回该样本的交叉熵。 */
    private static double accumulate(Sample sm, double[] w, double[] grad, double[] z,
                                     double logitScale) {
        final int F = MoveFeatures.COUNT;
        int m = sm.feats.length;
        double mx = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < m; j++) {
            double[] f = sm.feats[j];
            double v = 0;
            for (int i = 0; i < F; i++) {
                v += w[i] * f[i];
            }
            v *= logitScale;
            z[j] = v;
            if (v > mx) {
                mx = v;
            }
        }
        double sum = 0;
        for (int j = 0; j < m; j++) {
            z[j] = Math.exp(z[j] - mx);
            sum += z[j];
        }
        double inv = 1.0 / sum;
        double loss = 0;
        for (int j = 0; j < m; j++) {
            double p = z[j] * inv;
            double d = p - sm.target[j];
            if (sm.target[j] > 0) {
                loss -= sm.target[j] * Math.log(p + 1e-12);
            }
            if (d != 0) {
                double[] f = sm.feats[j];
                for (int i = 0; i < F; i++) {
                    grad[i] += d * f[i];
                }
            }
        }
        return loss;
    }

    private static void apply(double[] w, double[] grad, double lr, double l2, double[] anchor) {
        for (int i = 0; i < w.length; i++) {
            w[i] -= lr * (grad[i] + l2 * (w[i] - anchor[i]));
        }
    }

    private static double evaluate(List<Sample> samples, int[] idx, int from, int to,
                                  double[] w, double[] z, double logitScale) {
        double[] g = new double[w.length];
        double[] zz = new double[256];
        double loss = 0;
        for (int s = from; s < to; s++) {
            Sample sm = samples.get(idx[s]);
            if (zz.length < sm.feats.length) {
                zz = new double[sm.feats.length];
            }
            java.util.Arrays.fill(g, 0);
            loss += accumulate(sm, w, g, zz, logitScale);
        }
        return to > from ? loss / (to - from) : 0;
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** 生成 TunedParams.java（进化产物的唯一落地点）。 */
    public static void writeTunedParams(String path, Variant v, String generatedAt)
            throws Exception {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("package com.kayago.ai;\n\n");
        sb.append("/**\n");
        sb.append(" * 由自对弈进化产出的参数快照。\n");
        sb.append(" *\n");
        sb.append(" * <p><b>本文件由 {@code tools/Evolve} 自动生成，请勿手改。</b>\n");
        sb.append(" * 运行时状态见 {@link Tuned}（它从这里取初值，并允许进化工具在内存里替换候选）。\n");
        sb.append(" */\n");
        sb.append("public final class TunedParams {\n\n");
        sb.append("    private TunedParams() {\n    }\n\n");
        sb.append("    /** 走子评估权重，顺序与 {@link MoveFeatures#NAMES} 一致。 */\n");
        sb.append("    public static final double[] WEIGHTS = {\n");
        String[] names = MoveFeatures.NAMES;
        for (int i = 0; i < v.weights.length; i++) {
            sb.append("            ");
            sb.append(trim(v.weights[i]));
            sb.append(',');
            if (i % 5 == 4 || i == v.weights.length - 1) {
                sb.append("   // ").append(i - (i % 5));
                for (int k = i - (i % 5); k <= i; k++) {
                    sb.append(' ').append(names[k]);
                }
                sb.append('\n');
            }
        }
        sb.append("    };\n\n");
        sb.append("    /** 停一手的分数。 */\n");
        sb.append("    public static final double PASS_SCORE = ").append(trim(v.passScore))
                .append(";\n\n");
        sb.append("    /** 根节点 PUCT 系数。 */\n");
        sb.append("    public static final double PUCT_C = ").append(trim(v.puctC)).append(";\n\n");
        sb.append("    /** 根节点 RAVE 系数。 */\n");
        sb.append("    public static final double RAVE_K = ").append(trim(v.raveK)).append(";\n\n");
        sb.append("    /** 先验温度。 */\n");
        sb.append("    public static final double PRIOR_TEMPERATURE = ")
                .append(trim(v.priorTemperature)).append(";\n\n");
        sb.append("    /** UCB1 探索常数。 */\n");
        sb.append("    public static final double UCT_C = ").append(trim(v.uctC)).append(";\n\n");
        sb.append("    /** 分差回报尺度，0 表示按棋盘大小自动。 */\n");
        sb.append("    public static final double SCORE_SCALE = ").append(trim(v.scoreScale))
                .append(";\n\n");
        sb.append("    /** 生成来源：").append(generatedAt.replace("*/", "")).append(" */\n");
        sb.append("    public static final String GENERATED_AT = \"")
                .append(generatedAt.replace("\"", "").replace("\\", "")).append("\";\n");
        sb.append("}\n");

        try (PrintWriter w = new PrintWriter(path, StandardCharsets.UTF_8)) {
            w.print(sb);
        }
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        // 去掉多余的 0，让生成的文件更干净
        if (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
