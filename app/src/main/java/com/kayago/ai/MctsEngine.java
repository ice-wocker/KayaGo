package com.kayago.ai;

import com.kayago.game.Board;
import com.kayago.game.Scorer;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自研蒙特卡洛树搜索（MCTS / UCT）引擎。
 *
 * <p>要点：
 * <ul>
 *   <li>共享树 + 多线程并行，节点统计用原子量，选取时加入 virtual loss 降低线程撞车；</li>
 *   <li>根节点用启发式打分筛选候选手，把有限的推演集中在像样的点上；</li>
 *   <li>子节点扩展同样复用推演策略，保证扩张方向合理；</li>
 *   <li>推演策略见 {@link Playout}，终局用中国规则数子见 {@link Scorer}。</li>
 * </ul>
 *
 * <p>纯 Java、零依赖，可以直接在 JVM 上跑自测：见仓库 {@code tools/EngineSelfTest.java}。
 */
public final class MctsEngine implements GoAI {

    /** 内部哨兵：没有可扩展的着法。 */
    private static final int NO_MOVE = -2;

    private static final Node[] EMPTY_CHILDREN = new Node[0];

    /** 单次搜索的节点数上限，避免在手机内存上失控。 */
    private static final int MAX_NODES = 250_000;

    private final int maxNodes;

    public MctsEngine() {
        this(MAX_NODES);
    }

    public MctsEngine(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    @Override
    public SearchResult think(Board board, byte toMove, int lastMove, double komi, SearchConfig cfg) {
        long startNs = System.nanoTime();
        SearchConfig config = cfg != null ? cfg : new SearchConfig();

        SearchResult res = new SearchResult();
        int[] candidates = buildRootCandidates(board, toMove, lastMove, config);

        Node root = new Node(NO_MOVE, toMove, null, false);
        if (candidates.length == 0) {
            res.move = Board.PASS;
            res.winRate = 0.5;
            res.timeMs = 0;
            return res;
        }

        int threads = config.threads > 0
                ? config.threads
                : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        long seed = config.seed != 0 ? config.seed : System.nanoTime();
        long deadline = startNs + config.maxTimeMs * 1_000_000L;
        int maxPlayouts = config.maxPlayouts;
        int maxMoves = 2 * board.points + 60;

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong playouts = new AtomicLong();
        AtomicInteger nodeCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Worker w = new Worker(board, toMove, komi, config, root, candidates, maxMoves,
                    deadline, maxPlayouts, stop, playouts, nodeCount, seed + t * 7919L, latch);
            Thread th = new Thread(w, "kayago-mcts-" + t);
            th.setDaemon(true);
            th.start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 选最稳的一手（访问次数最多）
        Node[] children = root.children;
        int n = Math.min(root.nChildren, children == null ? 0 : children.length);
        int best = -1;
        int bestVisits = -1;
        double bestWr = -1;
        for (int i = 0; i < n; i++) {
            Node c = children[i];
            int v = c.visits.get();
            double wr = v > 0 ? (double) c.wins.get() / v : 0.0;
            if (v > bestVisits || (v == bestVisits && wr > bestWr)) {
                bestVisits = v;
                bestWr = wr;
                best = i;
            }
        }

        int rv = root.visits.get();
        res.winRate = rv > 0 ? (double) root.wins.get() / rv : 0.5;
        res.move = best >= 0 ? children[best].move : Board.PASS;
        res.playouts = playouts.get();
        Scorer scorer = new Scorer(board);
        res.scoreLead = scorer.areaDiff(komi);
        res.timeMs = (System.nanoTime() - startNs) / 1_000_000L;
        return res;
    }

    /**
     * 只做推演、不建树的形势判断：平均终局分差（黑 - 白）。
     * 用于界面上的“形势”按钮。
     */
    public static double estimateScore(Board board, byte toMove, int lastMove, double komi,
                                       int playouts, int threads) {
        int th = threads > 0 ? threads : Math.max(1, Math.min(8,
                Runtime.getRuntime().availableProcessors()));
        int perThread = Math.max(1, playouts / th);
        int maxMoves = 2 * board.points + 60;
        final double[] sums = new double[th];
        final CountDownLatch latch = new CountDownLatch(th);

        for (int t = 0; t < th; t++) {
            final int idx = t;
            Thread thread = new Thread(() -> {
                Board b = board.copy();
                Scorer sc = new Scorer(b);
                Random rnd = new Random(System.nanoTime() + idx * 104729L);
                int lm = lastMove;
                byte c = toMove;
                double sum = 0;
                for (int i = 0; i < perThread; i++) {
                    int winner = Playout.run(b, c, lm, rnd, maxMoves, sc, komi);
                    double diff = sc.areaDiff(komi);
                    // 以终局分差为尺度，同时保证符号与胜方一致
                    sum += (winner == Board.BLACK ? 1 : -1) * Math.abs(diff);
                }
                sums[idx] = sum / perThread;
                latch.countDown();
            }, "kayago-eval-" + t);
            thread.setDaemon(true);
            thread.start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double total = 0;
        int used = 0;
        for (int t = 0; t < th; t++) {
            if (!Double.isNaN(sums[t])) {
                total += sums[t];
                used++;
            }
        }
        return used > 0 ? total / used : 0;
    }

    // ------------------------------------------------------------------
    // 根候选
    // ------------------------------------------------------------------

    private static int[] buildRootCandidates(Board board, byte toMove, int lastMove,
                                             SearchConfig cfg) {
        int points = board.points;
        int[] moves = new int[points];
        int[] scores = new int[points];
        int n = 0;

        int ec = board.emptyCount();
        for (int i = 0; i < ec; i++) {
            int p = board.emptyAt(i);
            if (!board.isPlayable(p, toMove)) {
                continue; // 含自杀 / 简单劫 / 位置超级劫
            }
            if (board.isEyeLike(p, toMove)) {
                continue; // 不填自己的眼
            }
            moves[n] = p;
            scores[n] = HeuristicPolicy.rootScore(board, p, toMove, lastMove);
            n++;
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final int[] sc = scores;
        Arrays.sort(order, (a, b) -> Integer.compare(sc[b], sc[a]));

        int keep = Math.min(Math.max(1, cfg.rootCandidates), n);
        int[] out = new int[keep + 1];
        for (int i = 0; i < keep; i++) {
            out[i] = moves[order[i]];
        }
        out[keep] = Board.PASS;
        return out;
    }

    // ------------------------------------------------------------------
    // 搜索线程
    // ------------------------------------------------------------------

    private static final class Node {
        final int move;              // 内部索引或 Board.PASS
        final byte moverColor;       // 走出这一手的颜色
        final Node parent;
        volatile Node[] children = EMPTY_CHILDREN;
        volatile int nChildren;
        final AtomicInteger visits = new AtomicInteger();
        final AtomicInteger wins = new AtomicInteger();
        final AtomicInteger vloss = new AtomicInteger();
        final boolean terminal;

        Node(int move, byte moverColor, Node parent, boolean terminal) {
            this.move = move;
            this.moverColor = moverColor;
            this.parent = parent;
            this.terminal = terminal;
        }
    }

    private final class Worker implements Runnable {
        private final Board rootBoard;
        private final byte rootColor;
        private final double komi;
        private final SearchConfig cfg;
        private final Node root;
        private final int[] candidates;
        private final int maxMoves;
        private final long deadline;
        private final int maxPlayouts;
        private final AtomicBoolean stop;
        private final AtomicLong globalPlayouts;
        private final AtomicInteger nodeCount;
        private final long seed;
        private final CountDownLatch latch;

        Worker(Board rootBoard, byte rootColor, double komi, SearchConfig cfg, Node root,
               int[] candidates, int maxMoves, long deadline, int maxPlayouts,
               AtomicBoolean stop, AtomicLong globalPlayouts, AtomicInteger nodeCount,
               long seed, CountDownLatch latch) {
            this.rootBoard = rootBoard;
            this.rootColor = rootColor;
            this.komi = komi;
            this.cfg = cfg;
            this.root = root;
            this.candidates = candidates;
            this.maxMoves = maxMoves;
            this.deadline = deadline;
            this.maxPlayouts = maxPlayouts;
            this.stop = stop;
            this.globalPlayouts = globalPlayouts;
            this.nodeCount = nodeCount;
            this.seed = seed;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                search();
            } catch (Throwable t) {
                // 搜索线程内部的异常不应该让整盘棋崩掉
                stop.set(true);
            } finally {
                latch.countDown();
            }
        }

        private boolean canExpand(Node node) {
            if (node.terminal) {
                return false;
            }
            if (nodeCount.get() >= maxNodes) {
                return false;
            }
            int limit = node == root ? candidates.length : cfg.maxChildren;
            return node.nChildren < limit;
        }

        private void search() {
            Board board = rootBoard.copy();
            Scorer scorer = new Scorer(board);
            Random rnd = new Random(seed);
            Node[] path = new Node[board.points + 16];
            long local = 0;

            while (!stop.get()) {
                if ((local & 31) == 0) {
                    if (System.nanoTime() >= deadline) {
                        stop.set(true);
                        break;
                    }
                    if (maxPlayouts > 0 && globalPlayouts.get() >= maxPlayouts) {
                        stop.set(true);
                        break;
                    }
                }

                Node node = root;
                byte col = rootColor;
                int pathLen = 0;
                int vlossCount = 0;

                // 1) 选择
                while (!node.terminal && !canExpand(node)) {
                    Node child = selectChild(node);
                    if (child == null) {
                        break;
                    }
                    if (!board.play(child.move, child.moverColor)) {
                        child.vloss.decrementAndGet();
                        break;
                    }
                    path[pathLen++] = child;
                    vlossCount++;
                    node = child;
                    col = Board.opposite(child.moverColor);
                }

                // 2) 扩展
                if (canExpand(node)) {
                    Node child = expand(node, board, col, rnd);
                    if (child != null) {
                        if (board.play(child.move, col)) {
                            path[pathLen++] = child;
                            node = child;
                            col = Board.opposite(col);
                        }
                    }
                }

                // 3) 模拟
                int winner;
                if (node.terminal) {
                    winner = scorer.areaDiff(komi) > 0 ? Board.BLACK : Board.WHITE;
                } else {
                    winner = Playout.run(board, col, node.move, rnd, maxMoves, scorer, komi);
                }

                // 4) 回传
                for (int i = 0; i < pathLen; i++) {
                    Node nd = path[i];
                    nd.visits.incrementAndGet();
                    if (nd.moverColor == winner) {
                        nd.wins.incrementAndGet();
                    }
                }
                root.visits.incrementAndGet();
                if (rootColor == winner) {
                    root.wins.incrementAndGet();
                }
                for (int i = 0; i < vlossCount; i++) {
                    path[i].vloss.decrementAndGet();
                }

                // 5) 还原棋盘
                for (int i = 0; i < pathLen; i++) {
                    board.undo();
                }

                local++;
                if (local > 0 && (local & 15) == 0) {
                    globalPlayouts.addAndGet(16);
                }
            }
            globalPlayouts.addAndGet(local & 15);
        }

        private Node selectChild(Node node) {
            Node[] children = node.children;
            if (children == null) {
                return null;
            }
            int n = Math.min(node.nChildren, children.length);
            if (n == 0) {
                return null;
            }
            int parentVisits = node.visits.get() + node.vloss.get();
            double logPv = Math.log(parentVisits + 1.0);
            Node best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                Node c = children[i];
                int v = c.visits.get();
                double wr = v > 0 ? (double) c.wins.get() / v : 0.5;
                int eff = v + c.vloss.get();
                double uct = wr + cfg.uctC * Math.sqrt(logPv / (eff + 1.0));
                if (uct > bestScore) {
                    bestScore = uct;
                    best = c;
                }
            }
            if (best != null) {
                best.vloss.incrementAndGet();
            }
            return best;
        }

        /** 在 node 上新增一个子节点，返回新节点；无法扩展时返回 null。 */
        private Node expand(Node node, Board board, byte toMove, Random rnd) {
            synchronized (node) {
                if (node.terminal || node.nChildren >= (node == root ? candidates.length
                        : cfg.maxChildren)) {
                    return null;
                }
                if (nodeCount.get() >= maxNodes) {
                    return null;
                }
                int mv = NO_MOVE;
                if (node == root) {
                    mv = candidates[node.nChildren];
                } else {
                    for (int attempt = 0; attempt < 14; attempt++) {
                        int cand = Playout.selectMove(board, toMove, node.move, rnd);
                        if (cand == Board.PASS) {
                            if (!hasChild(node, Board.PASS)) {
                                mv = Board.PASS;
                                break;
                            }
                            continue;
                        }
                        if (hasChild(node, cand)) {
                            continue;
                        }
                        if (!board.isPlayable(cand, toMove)) {
                            continue;
                        }
                        mv = cand;
                        break;
                    }
                }
                if (mv == NO_MOVE) {
                    return null;
                }

                boolean terminal = mv == Board.PASS && node.move == Board.PASS;
                Node child = new Node(mv, toMove, node, terminal);

                Node[] children = node.children;
                if (node.nChildren >= children.length) {
                    children = Arrays.copyOf(children, Math.max(4, children.length * 2));
                    node.children = children;
                }
                children[node.nChildren] = child;
                node.nChildren = node.nChildren + 1;
                nodeCount.incrementAndGet();
                return child;
            }
        }

        private boolean hasChild(Node node, int move) {
            Node[] children = node.children;
            int n = Math.min(node.nChildren, children.length);
            for (int i = 0; i < n; i++) {
                if (children[i].move == move) {
                    return true;
                }
            }
            return false;
        }
    }
}
