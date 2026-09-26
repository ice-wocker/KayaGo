package tools;

import com.kayago.ai.Tuned;

/**
 * 一组可进化的参数快照：进化工具用它表示「现任冠军」和「候选」。
 * 调用 {@link #apply()} 后，同一进程里随后的搜索就会使用这组参数。
 */
public final class Variant {

    public final String name;
    public final double[] weights;
    public final double passScore;
    public final double puctC;
    public final double raveK;
    public final double priorTemperature;
    public final double uctC;
    public final double scoreScale;

    public Variant(String name, double[] weights, double passScore, double puctC,
                   double raveK, double priorTemperature, double uctC, double scoreScale) {
        this.name = name;
        this.weights = weights.clone();
        this.passScore = passScore;
        this.puctC = puctC;
        this.raveK = raveK;
        this.priorTemperature = priorTemperature;
        this.uctC = uctC;
        this.scoreScale = scoreScale;
    }

    /** 取当前运行时参数作为快照。 */
    public static Variant current(String name) {
        return new Variant(name, Tuned.weights, Tuned.passScore, Tuned.puctC, Tuned.raveK,
                Tuned.priorTemperature, Tuned.uctC, Tuned.scoreScale);
    }

    /** 只替换权重，其余沿用本快照。 */
    public Variant withWeights(double[] w) {
        return new Variant(name + "+w", w, passScore, puctC, raveK, priorTemperature, uctC,
                scoreScale);
    }

    /** 只替换某个超参，用于邻域探测。 */
    public Variant with(String param, double value) {
        return new Variant(name + "/" + param + "=" + fmt(value), weights, passScore,
                param.equals("puctC") ? value : puctC,
                param.equals("raveK") ? value : raveK,
                param.equals("priorTemperature") ? value : priorTemperature,
                param.equals("uctC") ? value : uctC,
                param.equals("scoreScale") ? value : scoreScale);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.3g", v);
    }

    public void apply() {
        Tuned.weights = weights.clone();
        Tuned.passScore = passScore;
        Tuned.puctC = puctC;
        Tuned.raveK = raveK;
        Tuned.priorTemperature = priorTemperature;
        Tuned.uctC = uctC;
        Tuned.scoreScale = scoreScale;
    }

    @Override
    public String toString() {
        return name;
    }
}
