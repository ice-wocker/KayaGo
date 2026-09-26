package com.kayago.ai;

import com.kayago.game.Board;

/**
 * 启发式走子评估。
 *
 * <p>这套评估只依赖局部结构与棋理常识（提子、救棋、自紧气、线位、连络、
 * 局部应手），不含任何神经网络，因此可以极快地在上百万次节点评估中调用。
 * 它同时用于两处：
 * <ul>
 *   <li>根节点候选手的排序（决定搜索重点）；</li>
 *   <li>推演（playout）中的轻量走子倾向。</li>
 * </ul>
 */
public final class HeuristicPolicy {

    private HeuristicPolicy() {
    }

    /** 完整评估：用于根节点排序，代价较高（会扫描全盘找最近棋子）。 */
    public static int rootScore(Board b, int p, byte col, int lastMove) {
        int s = quickScore(b, p, col, lastMove);

        // 开局：偏好角部常见着点
        if (b.stones < b.size) {
            s += openingBonus(b, p);
        }

        // 与已有棋子的距离：过远则价值低
        int near = nearestStoneDistance(b, p);
        if (near < 0) {
            s += 0;
        } else if (near >= 2 && near <= 4) {
            s += 2;
        } else if (near > 6) {
            s -= 4;
        }

        // 中腹早早期价值偏低
        if (b.stones < b.size * 2) {
            int x = b.x(p);
            int y = b.y(p);
            int edge = Math.min(Math.min(x, b.size - 1 - x), Math.min(y, b.size - 1 - y));
            if (edge >= 4) {
                s -= 3;
            }
        }
        return s;
    }

    /**
     * 轻量评估：只做常数级局部计算，用于推演中的候选排序与根节点快速筛选。
     */
    public static int quickScore(Board b, int p, byte col, int lastMove) {
        byte opp = Board.opposite(col);
        int s = 0;

        // 1) 与前一手的关系（局部应手）
        if (lastMove >= 0) {
            int d = chebyshev(b, p, lastMove);
            if (d == 1) {
                s += 9;
            } else if (d == 2) {
                s += 6;
            } else if (d == 3) {
                s += 3;
            } else if (d <= 5) {
                s += 1;
            }
        }

        // 2) 邻接与对角
        int ownN = 0;
        int oppN = 0;
        int emptyN = 0;
        for (int d = 0; d < 4; d++) {
            int q = b.nb(p, d);
            byte c = b.color[q];
            if (c == col) {
                ownN++;
            } else if (c == opp) {
                oppN++;
            } else if (c == Board.EMPTY) {
                emptyN++;
            }
        }
        s += ownN * 4 + oppN * 5;

        int stride = b.stride;
        int d0 = p - stride - 1;
        int d1 = p - stride + 1;
        int d2 = p + stride - 1;
        int d3 = p + stride + 1;
        if (b.color[d0] == col) {
            s += 2;
        }
        if (b.color[d1] == col) {
            s += 2;
        }
        if (b.color[d2] == col) {
            s += 2;
        }
        if (b.color[d3] == col) {
            s += 2;
        }

        // 3) 提子 / 打吃敌方
        int capSize = 0;
        for (int d = 0; d < 4; d++) {
            int q = b.nb(p, d);
            if (b.color[q] == opp) {
                int c = b.cid[q];
                if (c >= 0 && b.chainLibs[c] == 1) {
                    capSize += b.chainSize[c];
                }
            }
        }
        if (capSize > 0) {
            s += 14 + 8 * capSize;
        }

        // 4) 救自己被打吃的棋
        int saveSize = 0;
        for (int d = 0; d < 4; d++) {
            int q = b.nb(p, d);
            if (b.color[q] == col) {
                int c = b.cid[q];
                if (c >= 0 && b.chainLibs[c] == 1) {
                    saveSize += b.chainSize[c];
                }
            }
        }
        if (saveSize > 0) {
            s += 10 + 5 * saveSize;
        }

        // 5) 自紧气（自己填自己的气，且无战术价值）
        if (ownN > 0 && emptyN == 0 && capSize == 0 && saveSize == 0) {
            s -= 14;
        }

        // 6) 线位
        int x = b.x(p);
        int y = b.y(p);
        int line = Math.min(Math.min(x, b.size - 1 - x), Math.min(y, b.size - 1 - y));
        if (line == 0) {
            s -= 9;
        } else if (line == 1) {
            s -= 5;
        } else if (line == 2) {
            s += 2;
        } else if (line == 3) {
            s += 3;
        } else {
            s -= 1;
        }

        return s;
    }

    /** 角部常见着点（三三、小目、星、目外）加成。 */
    private static int openingBonus(Board b, int p) {
        int x = b.x(p);
        int y = b.y(p);
        int cx = Math.min(x, b.size - 1 - x);
        int cy = Math.min(y, b.size - 1 - y);
        int s = 0;
        if (cx == 2 && cy == 2) {
            s += 10; // 三三
        } else if (cx == 3 && cy == 3) {
            s += 12; // 星
        } else if ((cx == 2 && cy == 3) || (cx == 3 && cy == 2)) {
            s += 8;  // 小目
        } else if ((cx == 3 && cy == 4) || (cx == 4 && cy == 3)) {
            s += 4;  // 目外
        }
        if (cx >= 2 && cy >= 2 && cx <= 4 && cy <= 4) {
            s += 2;
        }
        return s;
    }

    private static int chebyshev(Board b, int p, int q) {
        int dx = Math.abs(b.x(p) - b.x(q));
        int dy = Math.abs(b.y(p) - b.y(q));
        return Math.max(dx, dy);
    }

    /** 到最近棋子的切比雪夫距离；空盘返回 -1。 */
    private static int nearestStoneDistance(Board b, int p) {
        int best = Integer.MAX_VALUE;
        int px = b.x(p);
        int py = b.y(p);
        for (int y = 0; y < b.size; y++) {
            for (int x = 0; x < b.size; x++) {
                if (b.color[b.point(x, y)] == Board.EMPTY) {
                    continue;
                }
                int d = Math.max(Math.abs(px - x), Math.abs(py - y));
                if (d < best) {
                    best = d;
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }
}
