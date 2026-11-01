package com.kayago.game;

/**
 * 中国规则（数子法）形势判断 / 终局计算。
 *
 * <p>可复用实例：内部缓冲区只分配一次，适合在蒙特卡洛推演里高频调用。
 *
 * <p><b>空区归属的判据</b>（两块，缺一不可）：
 * <ol>
 *   <li><b>只贴着一张棋</b>。空区边沿若出现两张不同的棋，说明谁也管不住这里，算单官；</li>
 *   <li><b>这张棋撑得起这块空</b>（面积 ÷ 棋子数 ≤ {@link #RATIO_LIMIT}）。
 *       只有第 1 条时，一张 1 颗子的棋就能把整盘空判给自己——几何上它确实"贴着"，
 *       但对手随手就能在里面做活，那不是实空。</li>
 * </ol>
 *
 * <p>早期版本只记录边沿上「有没有黑/白」，连第 1 条都没有，于是<b>一颗子贴着整盘空</b>
 * 就被整块判给它：盘上只落一颗黑子就终局时，剩下 80 个空点全算黑空，判黑赢 73.5 子。
 */
public final class Scorer {

    /** 形势明细。 */
    public static final class Detail {
        public int blackStones;
        public int whiteStones;
        public int blackTerritory;
        public int whiteTerritory;
        public int dame;
        public double komi;
        public double blackScore;
        public double whiteScore;

        public double diff() {
            return blackScore - whiteScore;
        }

        public byte winner() {
            return diff() > 0 ? Board.BLACK : Board.WHITE;
        }

        public double absDiff() {
            return Math.abs(diff());
        }
    }

    private final Board b;
    private final int[] stack;
    private final int[] mark;
    /** ownershipMap 用：每块空区的归属与代表点。 */
    private final byte[] ownerOf;
    private final int[] regionPoints;
    private int stamp;
    /**
     * 空区面积 ÷ 围它那块棋的棋子数 的上限。
     *
     * <p>补齐「同一张棋」判据挡不住的最后一种情况：一张弱小的棋同时贴着一块小眼
     * 和一大片开阔空区，这两块空区在几何上都被它"围住"了，可是大片那块显然不是实空——
     * 对手随时能在里面做活。几何无法判定死活，只能用比例近似。
     *
     * <p>实测的真实眼形比值：角上 1 目 {@code 1/3≈0.33}，角上 3x3 {@code 9/7≈1.29}，
     * 中央 2x2 {@code 4/8=0.5}；而敞开的大片空区是 {@code 65/7≈9.3}。
     * 取 2 能干净地把两者分开，且离真实眼形仍有余量。
     */
    private static final int RATIO_LIMIT = 2;

    /** 上一次 {@link #resolveRegion} 统计到的空区大小。 */
    private int regionSize;
    /** 上一次 {@link #resolveRegion} 里被空区贴着的唯一那张棋的颜色。 */
    private byte firstColor;


    public Scorer(Board board) {
        this.b = board;
        this.stack = new int[board.points];
        this.mark = new int[board.total];
        this.ownerOf = new byte[board.total];
        this.regionPoints = new int[board.points];
    }

    /**
     * 找出包含 {@code start} 的整块空区：标好 {@link #mark}、统计大小、
     * 返回归属（{@link Board#BLACK} / {@link Board#WHITE} / 0 表示单官）。
     *
     * <p>一次统计里所有空区共用同一个 {@code stamp}，被划过的空点会被打上
     * 同一个标记，调用方据此跳过已处理的点，保证 O(空点数)。
     */
    private byte resolveRegion(int start) {
        int sp = 0;
        stack[sp++] = start;
        mark[start] = stamp;
        int firstHead = -1;
        int size = 0;
        while (sp > 0) {
            int q = stack[--sp];
            size++;
            for (int d = 0; d < 4; d++) {
                int r = b.nb(q, d);
                byte c = b.color[r];
                if (c == Board.EMPTY) {
                    if (mark[r] != stamp) {
                        mark[r] = stamp;
                        stack[sp++] = r;
                    }
                } else if (c == Board.BLACK || c == Board.WHITE) {
                    // 只要出现两张不同的棋，这块空就是双方（或多块）共用，算单官。
                    int head = b.cid[r];
                    if (firstHead == -1) {
                        firstHead = head;
                        firstColor = c;
                    } else if (head != firstHead) {
                        firstHead = -2;
                    }
                }
                // WALL 是棋盘边：天然封死，既不是棋也不算缺口，直接忽略。
            }
        }
        regionSize = size;
        if (firstHead < 0) {
            return 0;
        }
        // 见类注释第 2 条：几何上"贴着"不等于"围住"，还要看围它的棋撑不撑得起这块空。
        return size <= RATIO_LIMIT * b.chainSize[firstHead] ? firstColor : 0;
    }

    /** 开始一轮新的空区统计：换一个标记戳，让上一轮的标记全部失效。 */
    private void beginPass() {
        stamp++;
        if (stamp == Integer.MAX_VALUE) {
            java.util.Arrays.fill(mark, 0);
            stamp = 1;
        }
    }

    /** 该空点是否已被本轮统计划过。 */
    private boolean isVisited(int p) {
        return mark[p] == stamp;
    }

    /** 黑方 - 白方（已扣除贴目）的分差，不分配对象，适合搜索内高频调用。 */
    public double areaDiff(double komi) {
        int black = 0;
        int white = 0;
        beginPass();
        int ec = b.emptyCount();
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (isVisited(start)) {
                continue;
            }
            byte owner = resolveRegion(start);
            if (owner == Board.BLACK) {
                black += regionSize;
            } else if (owner == Board.WHITE) {
                white += regionSize;
            }
        }
        for (int y = 0; y < b.size; y++) {
            for (int x = 0; x < b.size; x++) {
                byte c = b.color[b.point(x, y)];
                if (c == Board.BLACK) {
                    black++;
                } else if (c == Board.WHITE) {
                    white++;
                }
            }
        }
        return black - (white + komi);
    }

    public Detail detail(double komi) {
        Detail d = new Detail();
        d.komi = komi;
        beginPass();
        int ec = b.emptyCount();
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (isVisited(start)) {
                continue;
            }
            byte owner = resolveRegion(start);
            if (owner == Board.BLACK) {
                d.blackTerritory += regionSize;
            } else if (owner == Board.WHITE) {
                d.whiteTerritory += regionSize;
            } else {
                d.dame += regionSize;
            }
        }
        for (int y = 0; y < b.size; y++) {
            for (int x = 0; x < b.size; x++) {
                byte c = b.color[b.point(x, y)];
                if (c == Board.BLACK) {
                    d.blackStones++;
                } else if (c == Board.WHITE) {
                    d.whiteStones++;
                }
            }
        }
        d.blackScore = d.blackStones + d.blackTerritory;
        d.whiteScore = d.whiteStones + d.whiteTerritory + komi;
        return d;
    }

    /**
     * 填充每个空点的归属，用于界面上的形势显示。
     * {@code out[p]} 取 {@link Board#BLACK} / {@link Board#WHITE} / 0（单官）。
     * 数组长度需为 {@code board.total}。
     */
    public void ownershipMap(int[] out) {
        java.util.Arrays.fill(out, 0);
        beginPass();
        int ec = b.emptyCount();
        int nRegions = 0;
        // 第一遍：划出所有空区，记下代表点与归属。
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (mark[start] == stamp) {
                continue;
            }
            ownerOf[nRegions] = resolveRegion(start);
            regionPoints[nRegions] = start;
            nRegions++;
        }
        // 第二遍：每块空区各自从代表点扩散回填。此时 mark 全部等于 stamp，
        // 不能再用来区分区域，所以只跟着 out 的当前值判断是否已填过。
        for (int i = 0; i < nRegions; i++) {
            byte owner = ownerOf[i];
            if (owner == 0) {
                continue;
            }
            int start = regionPoints[i];
            int sp = 0;
            stack[sp++] = start;
            out[start] = owner;
            while (sp > 0) {
                int q = stack[--sp];
                for (int d = 0; d < 4; d++) {
                    int r = b.nb(q, d);
                    if (b.color[r] == Board.EMPTY && out[r] == 0) {
                        out[r] = owner;
                        stack[sp++] = r;
                    }
                }
            }
        }
    }
}
