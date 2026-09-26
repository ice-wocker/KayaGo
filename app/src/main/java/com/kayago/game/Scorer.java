package com.kayago.game;

/**
 * 中国规则（数子法）形势判断 / 终局计算。
 *
 * <p>可复用实例：内部缓冲区只分配一次，适合在蒙特卡洛推演里高频调用。
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
    private int stamp;

    public Scorer(Board board) {
        this.b = board;
        this.stack = new int[board.points];
        this.mark = new int[board.total];
    }

    /** 黑方 - 白方（已扣除贴目）的分差，不分配对象，适合搜索内高频调用。 */
    public double areaDiff(double komi) {
        int black = 0;
        int white = 0;
        stamp++;

        int ec = b.emptyCount();
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (mark[start] == stamp) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            mark[start] = stamp;
            int size = 0;
            int borders = 0;
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
                    } else if (c == Board.BLACK) {
                        borders |= 1;
                    } else if (c == Board.WHITE) {
                        borders |= 2;
                    }
                }
            }
            if (borders == 1) {
                black += size;
            } else if (borders == 2) {
                white += size;
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
        stamp++;

        int ec = b.emptyCount();
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (mark[start] == stamp) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            mark[start] = stamp;
            int size = 0;
            int borders = 0;
            while (sp > 0) {
                int q = stack[--sp];
                size++;
                for (int dd = 0; dd < 4; dd++) {
                    int r = b.nb(q, dd);
                    byte c = b.color[r];
                    if (c == Board.EMPTY) {
                        if (mark[r] != stamp) {
                            mark[r] = stamp;
                            stack[sp++] = r;
                        }
                    } else if (c == Board.BLACK) {
                        borders |= 1;
                    } else if (c == Board.WHITE) {
                        borders |= 2;
                    }
                }
            }
            if (borders == 1) {
                d.blackTerritory += size;
            } else if (borders == 2) {
                d.whiteTerritory += size;
            } else {
                d.dame += size;
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
        stamp++;
        int ec = b.emptyCount();
        for (int i = 0; i < ec; i++) {
            int start = b.emptyAt(i);
            if (mark[start] == stamp) {
                continue;
            }
            int sp = 0;
            stack[sp++] = start;
            mark[start] = stamp;
            int borders = 0;
            // 先把这块空区域全部标上本次戳，同时收集边界颜色
            while (sp > 0) {
                int q = stack[--sp];
                for (int d = 0; d < 4; d++) {
                    int r = b.nb(q, d);
                    byte c = b.color[r];
                    if (c == Board.EMPTY) {
                        if (mark[r] != stamp) {
                            mark[r] = stamp;
                            stack[sp++] = r;
                        }
                    } else if (c == Board.BLACK) {
                        borders |= 1;
                    } else if (c == Board.WHITE) {
                        borders |= 2;
                    }
                }
            }
            byte owner = borders == 1 ? Board.BLACK : (borders == 2 ? Board.WHITE : 0);
            // 再走一遍同样的连通块写归属
            int sp2 = 0;
            stack[sp2++] = start;
            mark[start] = stamp + 1000000; // 用另一个标记避免重复
            while (sp2 > 0) {
                int q = stack[--sp2];
                out[q] = owner;
                for (int d = 0; d < 4; d++) {
                    int r = b.nb(q, d);
                    if (b.color[r] == Board.EMPTY && mark[r] == stamp) {
                        mark[r] = stamp + 1000000;
                        stack[sp2++] = r;
                    }
                }
            }
        }
    }
}
