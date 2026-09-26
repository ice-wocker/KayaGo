package com.kayago.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一局棋：把 {@link Board} 的落子能力包装成带历史、超级劫、终局判定与撤销的对局。
 *
 * <p>采用中国规则（数子法）+ 位置超级劫（禁止全局同形再现）。
 */
public final class Game {

    private final Board board;
    private double komi;
    private byte toMove = Board.BLACK;

    private boolean over;
    private boolean resigned;
    private byte winner;
    private String resultText = "";

    private int lastMove = Board.PASS;
    private boolean lastWasPass;

    /** 历史记录：{点, 颜色}。位置超级劫由 {@link Board} 负责。 */
    private final List<int[]> hist = new ArrayList<>();

    public Game(int size, double komi) {
        this.board = new Board(size);
        this.komi = komi;
    }

    public Board board() {
        return board;
    }

    public int size() {
        return board.size;
    }

    public double komi() {
        return komi;
    }

    public void setKomi(double komi) {
        this.komi = komi;
        if (over && !resigned) {
            finish();
        }
    }

    public byte toMove() {
        return toMove;
    }

    public boolean isOver() {
        return over;
    }

    public boolean isResigned() {
        return resigned;
    }

    public byte winner() {
        return winner;
    }

    public String resultText() {
        return resultText;
    }

    public int lastMove() {
        return lastMove;
    }

    public boolean lastWasPass() {
        return lastWasPass;
    }

    public int moveNumber() {
        return hist.size();
    }

    public boolean canUndo() {
        return !hist.isEmpty() && !resigned;
    }

    /** 当前一方落子。非法（自杀、劫争、全局同形再现）返回 false。 */
    public boolean play(int p) {
        if (over) {
            return false;
        }
        byte col = toMove;
        if (!board.play(p, col)) {
            return false;
        }
        hist.add(new int[]{p, col});
        lastMove = p;
        lastWasPass = p == Board.PASS;
        toMove = Board.opposite(col);

        if (p == Board.PASS && board.passes >= 2) {
            finish();
        } else if (board.emptyCount() == 0) {
            finish();
        }
        return true;
    }

    /** 撤销上一手。 */
    public void undo() {
        if (hist.isEmpty() || resigned) {
            return;
        }
        hist.remove(hist.size() - 1);
        board.undo();
        toMove = Board.opposite(toMove);
        over = false;
        winner = 0;
        resultText = "";
        if (hist.isEmpty()) {
            lastMove = Board.PASS;
            lastWasPass = false;
        } else {
            int[] prev = hist.get(hist.size() - 1);
            lastMove = prev[0];
            lastWasPass = prev[0] == Board.PASS;
        }
    }

    /** 认输。 */
    public void resign() {
        if (over) {
            return;
        }
        resigned = true;
        over = true;
        winner = Board.opposite(toMove);
        resultText = colorName(winner) + "中盘胜（对方认输）";
    }

    /** 直接判终局（例如两人连续停手后由界面触发数子）。 */
    public void finish() {
        Scorer.Detail d = new Scorer(board).detail(komi);
        winner = d.winner();
        over = true;
        double diff = Math.abs(d.diff());
        resultText = colorName(winner) + "胜 " + String.format(Locale.US, "%.1f", diff) + " 子";
    }

    public Scorer.Detail detail() {
        return new Scorer(board).detail(komi);
    }

    public static String colorName(byte c) {
        return c == Board.BLACK ? "黑" : "白";
    }
}
