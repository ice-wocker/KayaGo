package com.kayago.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.kayago.R;
import com.kayago.ai.MctsEngine;
import com.kayago.ai.SearchConfig;
import com.kayago.ai.SearchResult;
import com.kayago.game.Board;
import com.kayago.game.Game;
import com.kayago.game.Scorer;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对局界面：人机对弈、悔棋、提示、形势判断与设置。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "kayago";
    private static final String K_SIZE = "board_size";
    private static final String K_HUMAN = "human_color";
    private static final String K_LEVEL = "level";

    /** 中国规则数子，黑贴 7.5 子。 */
    private static final double KOMI = 7.5;

    private BoardView boardView;
    private TextView statusView;
    private Button btnUndo;
    private Button btnPass;
    private Button btnHint;
    private Button btnScore;

    private final MctsEngine engine = new MctsEngine();
    private ExecutorService pool;
    private Handler ui;

    private Game game;
    private int boardSize = 9;
    private byte humanColor = Board.BLACK;
    private int level = 3;

    private boolean thinking;
    private int stateToken;
    private double shownWinRate = -1;

    private int pendingSize = 9;
    private byte pendingHuman = Board.BLACK;
    private int pendingLevel = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ui = new Handler(Looper.getMainLooper());
        pool = Executors.newSingleThreadExecutor();

        boardView = findViewById(R.id.board);
        statusView = findViewById(R.id.status);
        Button btnNew = findViewById(R.id.btn_new);
        btnUndo = findViewById(R.id.btn_undo);
        btnPass = findViewById(R.id.btn_pass);
        Button btnResign = findViewById(R.id.btn_resign);
        btnHint = findViewById(R.id.btn_hint);
        btnScore = findViewById(R.id.btn_score);
        Button btnSettings = findViewById(R.id.btn_settings);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boardSize = sp.getInt(K_SIZE, 9);
        humanColor = (byte) sp.getInt(K_HUMAN, Board.BLACK);
        level = sp.getInt(K_LEVEL, 3);

        boardView.setOnMoveListener(this::onHumanMove);
        btnNew.setOnClickListener(v -> newGame());
        btnUndo.setOnClickListener(v -> undo());
        btnPass.setOnClickListener(v -> humanPass());
        btnResign.setOnClickListener(v -> confirmResign());
        btnHint.setOnClickListener(v -> requestHint());
        btnScore.setOnClickListener(v -> showScore());
        btnSettings.setOnClickListener(v -> showSettings());

        newGame();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 对局流程
    // ------------------------------------------------------------------

    private void newGame() {
        game = new Game(boardSize, KOMI);
        stateToken++;
        thinking = false;
        shownWinRate = -1;
        boardView.setGhostColor(humanColor);
        boardView.setBoard(game.board());
        boardView.setInputEnabled(true);
        setThinking(false);
        if (game.toMove() != humanColor) {
            aiMove();
        }
    }

    private boolean onHumanMove(int p) {
        if (thinking || game == null || game.isOver()) {
            return false;
        }
        if (game.toMove() != humanColor) {
            return false;
        }
        if (!game.play(p)) {
            toast("这里不能下（自杀或打劫禁着）");
            return false;
        }
        boardView.setLastMove(game.lastMove());
        afterMove();
        return true;
    }

    private void humanPass() {
        if (thinking || game == null || game.isOver() || game.toMove() != humanColor) {
            return;
        }
        game.play(Board.PASS);
        afterMove();
    }

    private void afterMove() {
        stateToken++;
        shownWinRate = -1;
        boardView.setOwnership(null);
        boardView.setLastMove(game.lastMove());
        if (game.isOver()) {
            updateStatus();
            boardView.setInputEnabled(false);
            showResult();
            return;
        }
        updateStatus();
        if (game.toMove() != humanColor) {
            aiMove();
        }
    }

    private void aiMove() {
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        final SearchConfig cfg = configForLevel(level);
        setThinking(true);

        pool.execute(() -> {
            SearchResult r;
            try {
                r = engine.think(snapshot, toMove, lastMove, KOMI, cfg);
            } catch (Throwable t) {
                r = null;
            }
            final SearchResult res = r;
            ui.post(() -> {
                if (token != stateToken) {
                    return; // 局面已经变了，丢弃这次结果
                }
                setThinking(false);
                if (res == null) {
                    toast("AI 思考出错了");
                    return;
                }
                int mv = res.move;
                if (mv != Board.PASS && !game.board().isLegal(mv, toMove)) {
                    mv = Board.PASS;
                }
                if (!game.play(mv)) {
                    game.play(Board.PASS);
                }
                shownWinRate = res.winRate;
                String note = res.timeMs > 0
                        ? String.format(Locale.US, "（%d 次推演 / %.1f 秒）",
                        res.playouts, res.timeMs / 1000.0)
                        : "";
                boardView.setLastMove(game.lastMove());
                afterAiMove(note);
            });
        });
    }

    private void afterAiMove(String note) {
        stateToken++;
        boardView.setOwnership(null);
        boardView.setLastMove(game.lastMove());
        if (game.isOver()) {
            updateStatus();
            boardView.setInputEnabled(false);
            showResult();
            return;
        }
        updateStatus();
        if (note != null && !note.isEmpty()) {
            statusView.append(" " + note);
        }
    }

    private void undo() {
        if (thinking || game == null || game.isResigned()) {
            return;
        }
        if (!game.canUndo()) {
            return;
        }
        game.undo();
        if (game.toMove() != humanColor && game.canUndo()) {
            game.undo();
        }
        stateToken++;
        shownWinRate = -1;
        boardView.setOwnership(null);
        boardView.setInputEnabled(true);
        boardView.setLastMove(game.lastMove());
        updateStatus();
        if (!game.isOver() && game.toMove() != humanColor) {
            aiMove();
        }
    }

    private void confirmResign() {
        if (game == null || game.isOver()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("认输")
                .setMessage("确定要认输吗？")
                .setPositiveButton("认输", (d, w) -> {
                    game.resign();
                    boardView.setInputEnabled(false);
                    updateStatus();
                    showResult();
                })
                .setNegativeButton("再想想", null)
                .show();
    }

    private void showResult() {
        Scorer.Detail d = game.detail();
        boolean humanWon = game.winner() == humanColor;
        String message = game.resultText()
                + String.format(Locale.US, "\n\n黑 %.1f 子 : 白 %.1f 子", d.blackScore, d.whiteScore);
        if (!game.isResigned()) {
            message += String.format(Locale.US, "\n（黑贴 %.1f 子，中国规则数子）", KOMI);
        }
        new AlertDialog.Builder(this)
                .setTitle(humanWon ? "你赢了！" : "AI 获胜")
                .setMessage(message)
                .setPositiveButton("再来一局", (dl, w) -> newGame())
                .setNegativeButton("看看棋盘", null)
                .show();
    }

    // ------------------------------------------------------------------
    // 提示 / 形势
    // ------------------------------------------------------------------

    private void requestHint() {
        if (thinking || game == null || game.isOver()) {
            return;
        }
        if (game.toMove() != humanColor) {
            toast("还没轮到你");
            return;
        }
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        final SearchConfig cfg = configForLevel(Math.max(1, level - 1));
        cfg.maxTimeMs = 900;
        setThinking(true);

        pool.execute(() -> {
            SearchResult r;
            try {
                r = engine.think(snapshot, toMove, lastMove, KOMI, cfg);
            } catch (Throwable t) {
                r = null;
            }
            final SearchResult res = r;
            ui.post(() -> {
                if (token != stateToken) {
                    return;
                }
                setThinking(false);
                if (res == null) {
                    toast("提示失败");
                    return;
                }
                boardView.setHint(res.move);
                boardView.refresh();
                toast(res.move == Board.PASS
                        ? "AI 建议：停一手"
                        : "AI 建议：" + coord(res.move) + String.format(Locale.US, "（胜率 %.0f%%）",
                        res.winRate * 100));
            });
        });
    }

    private void showScore() {
        if (thinking || game == null || game.isOver()) {
            return;
        }
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        setThinking(true);

        pool.execute(() -> {
            double diff = MctsEngine.estimateScore(snapshot, toMove, lastMove, KOMI, 600, 0);
            ui.post(() -> {
                if (token != stateToken) {
                    return;
                }
                setThinking(false);
                Scorer scorer = new Scorer(game.board());
                Scorer.Detail d = scorer.detail(KOMI);
                int[] map = new int[game.board().total];
                scorer.ownershipMap(map);
                boardView.setOwnership(map);
                String message = String.format(Locale.US,
                        "随机推演 600 局的平均结果：%s 领先 %.1f 子\n\n"
                                + "静态数子：黑 %.1f : 白 %.1f（含贴目 %.1f）\n"
                                + "已围成的空：黑 %d 子 / 白 %d 子 / 单官 %d\n\n"
                                + "棋盘上的小方块表示当前已确定的归属。",
                        diff >= 0 ? "黑" : "白", Math.abs(diff),
                        d.blackScore, d.whiteScore, d.komi,
                        d.blackTerritory, d.whiteTerritory, d.dame);
                new AlertDialog.Builder(this)
                        .setTitle("形势判断")
                        .setMessage(message)
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("清除标记", (dl, w) -> boardView.setOwnership(null))
                        .show();
            });
        });
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private void showSettings() {
        final String[] labels = {"9 路（手机友好，推荐）", "13 路", "19 路（标准）"};
        final int[] values = {9, 13, 19};
        int cur = boardSize == 9 ? 0 : (boardSize == 13 ? 1 : 2);
        pendingSize = boardSize;
        pendingHuman = humanColor;
        pendingLevel = level;
        new AlertDialog.Builder(this)
                .setTitle("棋盘大小")
                .setSingleChoiceItems(labels, cur, (d, w) -> pendingSize = values[w])
                .setPositiveButton("下一步", (d, w) -> showColorDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showColorDialog() {
        final String[] labels = {"我执黑（先行）", "我执白（后行）"};
        int cur = humanColor == Board.BLACK ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("执子")
                .setSingleChoiceItems(labels, cur,
                        (d, w) -> pendingHuman = w == 0 ? Board.BLACK : Board.WHITE)
                .setPositiveButton("下一步", (d, w) -> showLevelDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLevelDialog() {
        final String[] labels = {
                "1 入门（约 0.4 秒/手）",
                "2 初级（约 0.8 秒/手）",
                "3 中级（约 2 秒/手）",
                "4 高级（约 4 秒/手）",
                "5 大师（约 8 秒/手）"};
        new AlertDialog.Builder(this)
                .setTitle("AI 棋力")
                .setSingleChoiceItems(labels, pendingLevel - 1, (d, w) -> pendingLevel = w + 1)
                .setPositiveButton("开始新局", (d, w) -> applySettings())
                .setNegativeButton("取消", null)
                .show();
    }

    private void applySettings() {
        boardSize = pendingSize;
        humanColor = pendingHuman;
        level = pendingLevel;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(K_SIZE, boardSize)
                .putInt(K_HUMAN, humanColor)
                .putInt(K_LEVEL, level)
                .apply();
        newGame();
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private static SearchConfig configForLevel(int level) {
        SearchConfig c = new SearchConfig();
        c.level = level;
        switch (level) {
            case 1:
                c.maxTimeMs = 400;
                c.rootCandidates = 24;
                c.maxChildren = 30;
                break;
            case 2:
                c.maxTimeMs = 800;
                c.rootCandidates = 32;
                c.maxChildren = 40;
                break;
            case 4:
                c.maxTimeMs = 4000;
                c.rootCandidates = 48;
                c.maxChildren = 64;
                break;
            case 5:
                c.maxTimeMs = 8000;
                c.rootCandidates = 60;
                c.maxChildren = 80;
                break;
            default:
                c.maxTimeMs = 2000;
                c.rootCandidates = 40;
                c.maxChildren = 50;
                break;
        }
        return c;
    }

    private void setThinking(boolean value) {
        thinking = value;
        btnUndo.setEnabled(!value);
        btnPass.setEnabled(!value);
        btnHint.setEnabled(!value);
        btnScore.setEnabled(!value);
        boardView.setInputEnabled(!value && game != null && !game.isOver());
        updateStatus();
    }

    private void updateStatus() {
        if (game == null) {
            statusView.setText(R.string.app_name);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (thinking) {
            sb.append(getString(R.string.thinking));
        } else if (game.isOver()) {
            sb.append("对局结束 · ").append(game.resultText());
        } else {
            sb.append(game.toMove() == Board.BLACK ? "黑方行棋" : "白方行棋");
            sb.append(game.toMove() == humanColor ? "（你）" : "（AI）");
        }
        sb.append(" · 第 ").append(game.moveNumber()).append(" 手");
        sb.append(" · 提子 黑").append(game.board().captured[Board.WHITE])
                .append(" 白").append(game.board().captured[Board.BLACK]);
        if (shownWinRate >= 0) {
            sb.append(" · AI 胜率 ").append(String.format(Locale.US, "%.0f%%", shownWinRate * 100));
        }
        statusView.setText(sb);
    }

    private String coord(int p) {
        int x = game.board().x(p);
        int y = game.board().y(p);
        char letter = (char) ('A' + x);
        if (letter >= 'I') {
            letter++;
        }
        return "" + letter + (game.board().size - y);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
