package com.kayago.ai;

import com.kayago.game.Board;
import com.kayago.game.Scorer;

import java.util.Random;

/**
 * 蒙特卡洛推演（playout）的走子策略。
 *
 * <p>策略非常“轻”，只依赖常数级的局部判断，但覆盖了围棋里最重要的几条常识：
 * 能提就提、被打吃就逃、不填自己的眼、不自紧气、优先在上一手附近应。
 * 这让随机模拟的结果远比纯随机接近真实胜负。
 */
public final class Playout {

    private Playout() {
    }

    /**
     * 为 {@code col} 选择一手推演着法，返回内部索引或 {@link Board#PASS}。
     */
    public static int selectMove(Board b, byte col, int lastMove, Random rnd) {
        final byte opp = Board.opposite(col);

        if (lastMove >= 0) {
            // 1) 能提子就提
            for (int d = 0; d < 4; d++) {
                int q = b.nb(lastMove, d);
                if (b.color[q] == opp) {
                    int c = b.cid[q];
                    if (c >= 0 && b.chainLibs[c] == 1) {
                        int lib = b.firstLiberty(q);
                        if (lib >= 0 && b.isLegal(lib, col)) {
                            return lib;
                        }
                    }
                }
            }
            // 2) 自己被打吃就逃（有一定概率放弃无谓的逃）
            if (rnd.nextInt(100) < 70) {
                for (int d = 0; d < 4; d++) {
                    int q = b.nb(lastMove, d);
                    if (b.color[q] == col) {
                        int c = b.cid[q];
                        if (c >= 0 && b.chainLibs[c] == 1) {
                            int lib = b.firstLiberty(q);
                            if (lib >= 0 && lib != lastMove && b.isLegal(lib, col)) {
                                return lib;
                            }
                        }
                    }
                }
                if (b.color[lastMove] == col) {
                    int c = b.cid[lastMove];
                    if (c >= 0 && b.chainLibs[c] == 1) {
                        int lib = b.firstLiberty(lastMove);
                        if (lib >= 0 && b.isLegal(lib, col)) {
                            return lib;
                        }
                    }
                }
            }
        }

        // 3) 局部随机应手
        for (int attempt = 0; attempt < 10; attempt++) {
            int p;
            if (lastMove >= 0 && rnd.nextInt(100) < 80) {
                p = randomNear(b, lastMove, 2, rnd);
            } else {
                p = randomEmpty(b, rnd);
            }
            if (p < 0 || b.color[p] != Board.EMPTY || p == b.koPoint) {
                continue;
            }
            // 用一次邻居扫描完成大部分筛选，避免调用完整合法性判断（较贵）
            int emptyN = 0;
            boolean capture = false;
            boolean save = false;
            boolean healthyOwn = false;
            for (int d = 0; d < 4; d++) {
                int q = b.nb(p, d);
                byte c = b.color[q];
                if (c == Board.EMPTY) {
                    emptyN++;
                } else if (c == opp) {
                    int id = b.cid[q];
                    if (id >= 0 && b.chainLibs[id] == 1) {
                        capture = true;
                    }
                } else if (c == col) {
                    int id = b.cid[q];
                    if (id >= 0) {
                        if (b.chainLibs[id] == 1) {
                            save = true;
                        } else {
                            healthyOwn = true;
                        }
                    }
                }
            }
            if (capture) {
                return p; // 能提子就提
            }
            if (emptyN == 0) {
                continue; // 四周被占又提不到子
            }
            if (b.isEyeLike(p, col) || fillsOwnArea(b, p, col)) {
                continue;
            }
            if (emptyN == 1 && !save && !healthyOwn) {
                continue; // 自紧成一气
            }
            return p;
        }
        return Board.PASS;
    }

    /**
     * 从当前局面推演到终局，返回胜方（黑白），并把棋盘恢复到推演前的状态。
     */
    public static int run(Board b, byte col, int lastMove, Random rnd, int maxMoves,
                          Scorer scorer, double komi) {
        int applied = 0;
        int passes = 0;
        byte c = col;
        int lm = lastMove;

        for (int i = 0; i < maxMoves; i++) {
            int mv = selectMove(b, c, lm, rnd);
            if (!b.play(mv, c)) {
                // 极少见：着法违反位置超级劫（或选点非法），退化为停一手
                mv = Board.PASS;
                if (!b.play(mv, c)) {
                    break;
                }
            }
            applied++;
            if (mv == Board.PASS) {
                passes++;
                if (passes >= 2) {
                    break;
                }
            } else {
                passes = 0;
            }
            lm = mv;
            c = Board.opposite(c);
        }

        int winner = scorer.areaDiff(komi) > 0 ? Board.BLACK : Board.WHITE;
        for (int i = 0; i < applied; i++) {
            b.undo();
        }
        return winner;
    }

    // ------------------------------------------------------------------

    private static int randomNear(Board b, int center, int r, Random rnd) {
        int x = b.x(center) + rnd.nextInt(2 * r + 1) - r;
        int y = b.y(center) + rnd.nextInt(2 * r + 1) - r;
        if (x < 0 || y < 0 || x >= b.size || y >= b.size) {
            return -1;
        }
        int p = b.point(x, y);
        return b.color[p] == Board.EMPTY ? p : -1;
    }

    private static int randomEmpty(Board b, Random rnd) {
        int ec = b.emptyCount();
        if (ec == 0) {
            return -1;
        }
        return b.emptyAt(rnd.nextInt(ec));
    }

    /** 四正交全是自己的子或边，说明是往自己空里填。 */
    private static boolean fillsOwnArea(Board b, int p, byte col) {
        for (int d = 0; d < 4; d++) {
            byte c = b.color[b.nb(p, d)];
            if (c != col && c != Board.WALL) {
                return false;
            }
        }
        return true;
    }
}
