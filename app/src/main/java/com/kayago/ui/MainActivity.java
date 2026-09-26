package com.kayago.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
 * 对局界面：人机对弈、悔棋、停一手、认输、提示、形势判断、设置与终局结算。
 *
 * <p>AI 思考与形势计算全部在后台线程池执行，并用状态令牌（generation）丢弃过期结果，
 * 主线程只负责刷新界面。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "kayago";
    private static final String K_SIZE = "board_size";
    private static final String K_HUMAN = "human_color";
    private static final String K_LEVEL = "level";

    /** 中国规则数子，黑贴 7.5 子。 */
    private static final double KOMI = 7.5;

    private static final int[] SIZE_VALUES = {9, 13, 19};

    private BoardView boardView;
    private PlayerCardView blackCard;
    private PlayerCardView whiteCard;
    private TextView statusView;
    private ActionButton btnUndo;
    private ActionButton btnPass;
    private ActionButton btnHint;
    private ActionButton btnScore;

    private final MctsEngine engine = new MctsEngine();
    private ExecutorService pool;
    private Handler ui;

    private Game game;
    private int boardSize = 9;
    private byte humanColor = Board.BLACK;
    private int level = 3;

    /** 界面忙碌（AI 思考或后台计算中），此期间禁止操作。 */
    private boolean busy;
    /** AI 是否正在思考这一手（用于在 AI 卡片上显示进度条）。 */
    private boolean aiThinking;
    private int stateToken;
    private double shownWinRate = -1;

    private final StringBuilder statusBuilder = new StringBuilder(96);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ui = new Handler(Looper.getMainLooper());
        pool = Executors.newSingleThreadExecutor();

        boardView = findViewById(R.id.board);
        blackCard = findViewById(R.id.card_black);
        whiteCard = findViewById(R.id.card_white);
        statusView = findViewById(R.id.status);

        ActionButton btnNew = findViewById(R.id.btn_new);
        btnUndo = findViewById(R.id.btn_undo);
        btnPass = findViewById(R.id.btn_pass);
        ActionButton btnResign = findViewById(R.id.btn_resign);
        btnHint = findViewById(R.id.btn_hint);
        btnScore = findViewById(R.id.btn_score);
        ActionButton btnSettings = findViewById(R.id.btn_settings);

        btnNew.bind(R.drawable.ic_action_new, R.string.btn_new);
        btnUndo.bind(R.drawable.ic_action_undo, R.string.btn_undo);
        btnPass.bind(R.drawable.ic_action_pass, R.string.btn_pass);
        btnResign.bind(R.drawable.ic_action_resign, R.string.btn_resign);
        btnHint.bind(R.drawable.ic_action_hint, R.string.btn_hint);
        btnScore.bind(R.drawable.ic_action_score, R.string.btn_score);
        btnSettings.bind(R.drawable.ic_action_settings, R.string.btn_settings);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boardSize = sanitizeSize(sp.getInt(K_SIZE, 9));
        int color = sp.getInt(K_HUMAN, Board.BLACK);
        humanColor = color == Board.WHITE ? Board.WHITE : Board.BLACK;
        level = sanitizeLevel(sp.getInt(K_LEVEL, 3));

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
        if (ui != null) {
            ui.removeCallbacksAndMessages(null);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 对局流程
    // ------------------------------------------------------------------

    private void newGame() {
        game = new Game(boardSize, KOMI);
        stateToken++;
        busy = false;
        aiThinking = false;
        shownWinRate = -1;
        boardView.setGhostColor(humanColor);
        boardView.setBoard(game.board());
        setBusy(false, false);
        if (game.toMove() != humanColor) {
            aiMove();
        }
    }

    private boolean onHumanMove(int p) {
        if (busy || game == null || game.isOver() || game.toMove() != humanColor) {
            return false;
        }
        if (!game.play(p)) {
            toast(getString(R.string.toast_illegal));
            return false;
        }
        afterMove();
        return true;
    }

    private void humanPass() {
        if (busy || game == null || game.isOver() || game.toMove() != humanColor) {
            return;
        }
        game.play(Board.PASS);
        afterMove();
    }

    /** 一手落定后的统一收尾：清标记、更新卡片与状态、必要时让 AI 接着走。 */
    private void afterMove() {
        stateToken++;
        shownWinRate = -1;
        boardView.setOwnership(null);
        int lm = game.lastMove();
        boardView.setLastMove(lm);
        if (lm != Board.PASS) {
            boardView.animateStoneAt(lm);
        }
        if (game.isOver()) {
            lockAfterGameOver();
            showResult();
            return;
        }
        setBusy(false, false);
        if (game.toMove() != humanColor) {
            aiMove();
        }
    }

    private void aiMove() {
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        final SearchConfig cfg = SearchConfig.forLevel(level, boardSize);
        setBusy(true, true);

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
                int mv = res == null ? Board.PASS : res.move;
                if (res == null) {
                    toast(getString(R.string.toast_ai_error));
                } else if (mv != Board.PASS && !game.board().isLegal(mv, toMove)) {
                    mv = Board.PASS;
                }
                if (!game.play(mv)) {
                    game.play(Board.PASS); // 极端情况下退化为停一手，避免界面卡死
                }
                if (res != null) {
                    shownWinRate = res.winRate;
                }
                setBusy(false, false);
                afterMove();
            });
        });
    }

    private void undo() {
        if (busy || game == null || game.isResigned()) {
            return;
        }
        if (!game.canUndo()) {
            return;
        }
        game.undo();
        // 一直撤到又轮到我方行棋
        if (game.toMove() != humanColor && game.canUndo()) {
            game.undo();
        }
        stateToken++;
        shownWinRate = -1;
        boardView.setOwnership(null);
        boardView.setLastMove(game.lastMove());
        setBusy(false, false);
        if (!game.isOver() && game.toMove() != humanColor) {
            aiMove();
        }
    }

    private void confirmResign() {
        if (game == null || game.isOver()) {
            return;
        }
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle(R.string.resign_title)
                .setMessage(R.string.resign_message)
                .setPositiveButton(R.string.resign_confirm, (d, w) -> {
                    game.resign();
                    lockAfterGameOver();
                    showResult();
                })
                .setNegativeButton(R.string.resign_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // 终局结算
    // ------------------------------------------------------------------

    private void showResult() {
        final Scorer.Detail d = game.detail();
        final boolean resigned = game.isResigned();
        final byte win = resigned ? game.winner() : d.winner();
        final boolean humanWon = win == humanColor;
        final boolean draw = !resigned && d.diff() == 0.0;

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_result, null);
        TextView title = content.findViewById(R.id.result_title);
        TextView sub = content.findViewById(R.id.result_sub);
        TextView lines = content.findViewById(R.id.result_lines);

        if (draw) {
            title.setText(R.string.result_draw);
            title.setTextColor(getColor(R.color.text_primary));
        } else if (humanWon) {
            title.setText(R.string.result_win);
            title.setTextColor(getColor(R.color.accent_soft));
        } else {
            title.setText(R.string.result_lose);
            title.setTextColor(getColor(R.color.danger));
        }

        if (resigned) {
            sub.setText(game.resultText());
            lines.setText(R.string.result_resigned_note);
        } else {
            sub.setText(getString(R.string.result_detail_fmt,
                    fmt(d.blackScore), fmt(d.whiteScore)));
            lines.setText(getString(R.string.result_lines_fmt,
                    d.blackStones, d.blackTerritory,
                    d.whiteStones, d.whiteTerritory,
                    fmt(d.komi)));
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(content)
                .create();
        dialog.show();
        dialog.setCanceledOnTouchOutside(false);
        content.findViewById(R.id.btn_result_again)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    newGame();
                });
        content.findViewById(R.id.btn_result_look)
                .setOnClickListener(v -> dialog.dismiss());
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    private void requestHint() {
        if (busy || game == null || game.isOver()) {
            return;
        }
        if (game.toMove() != humanColor) {
            toast(getString(R.string.toast_not_your_turn));
            return;
        }
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        final SearchConfig cfg = SearchConfig.forLevel(Math.max(1, level - 1), boardSize);
        setBusy(true, false);

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
                setBusy(false, false);
                if (res == null) {
                    toast(getString(R.string.toast_hint_failed));
                    return;
                }
                boardView.setHint(res.move);
                toast(res.move == Board.PASS
                        ? getString(R.string.toast_hint_pass)
                        : getString(R.string.toast_hint_fmt, coord(res.move), pct(res.winRate)));
            });
        });
    }

    // ------------------------------------------------------------------
    // 形势判断
    // ------------------------------------------------------------------

    private void showScore() {
        if (busy || game == null || game.isOver()) {
            return;
        }
        final int token = stateToken;
        final Board snapshot = game.board().copy();
        final byte toMove = game.toMove();
        final int lastMove = game.lastMove();
        setBusy(true, false);

        pool.execute(() -> {
            Scorer.Detail detail = null;
            int[] map = null;
            double playoutDiff = 0;
            try {
                playoutDiff = MctsEngine.estimateScore(snapshot, toMove, lastMove, KOMI, 600, 0);
                Scorer scorer = new Scorer(snapshot);
                detail = scorer.detail(KOMI);
                map = new int[snapshot.total];
                scorer.ownershipMap(map);
            } catch (Throwable ignored) {
                detail = null;
            }
            final Scorer.Detail d = detail;
            final int[] ownerMap = map;
            final double diff = playoutDiff;
            ui.post(() -> {
                if (token != stateToken) {
                    return;
                }
                setBusy(false, false);
                if (d == null || ownerMap == null) {
                    toast(getString(R.string.toast_score_failed));
                    return;
                }
                boardView.setOwnership(ownerMap);
                showScoreDialog(d, diff);
            });
        });
    }

    private void showScoreDialog(Scorer.Detail d, double playoutDiff) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_score, null);
        TextView lead = content.findViewById(R.id.score_lead);
        TextView blackValue = content.findViewById(R.id.score_black_value);
        TextView whiteValue = content.findViewById(R.id.score_white_value);
        TextView settled = content.findViewById(R.id.score_settled);
        TextView detail = content.findViewById(R.id.score_detail);
        TextView note = content.findViewById(R.id.score_note);
        View barBlack = content.findViewById(R.id.bar_black);
        View barWhite = content.findViewById(R.id.bar_white);

        double diff = d.diff();
        if (Math.abs(diff) < 0.05) {
            lead.setText(R.string.score_even);
        } else {
            lead.setText(getString(R.string.score_lead_fmt,
                    getString(diff > 0 ? R.string.score_black_name : R.string.score_white_name),
                    fmt(Math.abs(diff))));
        }

        blackValue.setText(getString(R.string.score_black_value_fmt, fmt(d.blackScore)));
        whiteValue.setText(getString(R.string.score_white_value_fmt, fmt(d.whiteScore)));

        int settledCount = d.blackTerritory + d.whiteTerritory;
        int emptyTotal = settledCount + d.dame;
        int settledPct = emptyTotal > 0
                ? (int) Math.round(settledCount * 100.0 / emptyTotal) : 100;
        settled.setText(getString(R.string.score_settled_fmt, settledPct, d.dame));

        detail.setText(getString(R.string.score_detail_fmt,
                d.blackStones, d.blackTerritory, fmt(d.blackScore),
                d.whiteStones, d.whiteTerritory, fmt(d.komi), fmt(d.whiteScore)));

        note.setText(getString(R.string.score_note) + "\n"
                + getString(R.string.score_playout_fmt, fmt(playoutDiff)));

        LinearLayout.LayoutParams lpBlack = (LinearLayout.LayoutParams) barBlack.getLayoutParams();
        lpBlack.weight = Math.max(1, d.blackTerritory);
        barBlack.setLayoutParams(lpBlack);
        LinearLayout.LayoutParams lpWhite = (LinearLayout.LayoutParams) barWhite.getLayoutParams();
        lpWhite.weight = Math.max(1, d.whiteTerritory);
        barWhite.setLayoutParams(lpWhite);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(content)
                .create();
        dialog.show();
        content.findViewById(R.id.btn_score_clear).setOnClickListener(v -> {
            boardView.setOwnership(null);
            dialog.dismiss();
        });
        content.findViewById(R.id.btn_score_close).setOnClickListener(v -> dialog.dismiss());
    }

    // ------------------------------------------------------------------
    // 新对局设置
    // ------------------------------------------------------------------

    private void showSettings() {
        final int[] pendingSize = {boardSize};
        final byte[] pendingColor = {humanColor};
        final int[] pendingLevel = {level};

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_new_game, null);
        final RadioGroup rgSize = content.findViewById(R.id.rg_size);
        final RadioGroup rgColor = content.findViewById(R.id.rg_color);
        final RadioGroup rgLevel = content.findViewById(R.id.rg_level);
        final TextView levelInfo = content.findViewById(R.id.level_info);

        for (int i = 0; i < rgSize.getChildCount() && i < SIZE_VALUES.length; i++) {
            final int idx = i;
            RadioButton rb = (RadioButton) rgSize.getChildAt(i);
            rb.setChecked(SIZE_VALUES[i] == boardSize);
            rb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    pendingSize[0] = SIZE_VALUES[idx];
                    refreshLevelInfo(levelInfo, pendingLevel[0], pendingSize[0]);
                }
            });
        }
        for (int i = 0; i < rgColor.getChildCount(); i++) {
            final byte color = i == 0 ? Board.BLACK : Board.WHITE;
            RadioButton rb = (RadioButton) rgColor.getChildAt(i);
            rb.setChecked(color == humanColor);
            rb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    pendingColor[0] = color;
                }
            });
        }
        for (int i = 0; i < rgLevel.getChildCount(); i++) {
            final int value = i + 1;
            RadioButton rb = (RadioButton) rgLevel.getChildAt(i);
            rb.setChecked(value == level);
            rb.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    pendingLevel[0] = value;
                    refreshLevelInfo(levelInfo, value, pendingSize[0]);
                }
            });
        }
        refreshLevelInfo(levelInfo, pendingLevel[0], pendingSize[0]);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(content)
                .create();
        dialog.show();
        content.findViewById(R.id.btn_start).setOnClickListener(v -> {
            dialog.dismiss();
            applySettings(pendingSize[0], pendingColor[0], pendingLevel[0]);
        });
        content.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
    }

    private void refreshLevelInfo(TextView view, int levelValue, int size) {
        view.setText(getString(R.string.settings_level_fmt, levelValue,
                secondsForLevel(levelValue, size)));
    }

    private void applySettings(int size, byte color, int levelValue) {
        boardSize = sanitizeSize(size);
        humanColor = color == Board.WHITE ? Board.WHITE : Board.BLACK;
        level = sanitizeLevel(levelValue);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(K_SIZE, boardSize)
                .putInt(K_HUMAN, humanColor)
                .putInt(K_LEVEL, level)
                .apply();
        newGame();
    }

    // ------------------------------------------------------------------
    // 界面刷新
    // ------------------------------------------------------------------

    private void setBusy(boolean value, boolean thinkingNow) {
        busy = value;
        aiThinking = value && thinkingNow;
        btnUndo.setEnabled(!value);
        btnPass.setEnabled(!value);
        btnHint.setEnabled(!value);
        btnScore.setEnabled(!value);
        boardView.setInputEnabled(!value && game != null && !game.isOver());
        updateCards();
        updateStatus();
    }

    /** 终局后锁住棋盘与对局操作，只留下「新局 / 设置」。 */
    private void lockAfterGameOver() {
        busy = false;
        aiThinking = false;
        boardView.setInputEnabled(false);
        btnUndo.setEnabled(false);
        btnPass.setEnabled(false);
        btnHint.setEnabled(false);
        btnScore.setEnabled(false);
        updateCards();
        updateStatus();
    }

    private void updateCards() {
        if (game == null) {
            return;
        }
        final Board b = game.board();
        final boolean humanBlack = humanColor == Board.BLACK;
        final byte aiColor = Board.opposite(humanColor);
        final String roleBlack = getString(R.string.role_black);
        final String roleWhite = getString(R.string.role_white);
        final String aiSub = getString(R.string.player_sub_fmt,
                humanBlack ? roleWhite : roleBlack,
                getString(R.string.player_level_fmt, level));
        final String humanSub = humanBlack ? roleBlack : roleWhite;

        blackCard.bind(Board.BLACK,
                getString(humanBlack ? R.string.player_you : R.string.player_ai),
                humanBlack ? humanSub : aiSub);
        whiteCard.bind(Board.WHITE,
                getString(humanBlack ? R.string.player_ai : R.string.player_you),
                humanBlack ? aiSub : humanSub);

        blackCard.setCaptured(b.captured[Board.WHITE]);
        whiteCard.setCaptured(b.captured[Board.BLACK]);

        final boolean over = game.isOver();
        blackCard.setActive(!over && game.toMove() == Board.BLACK);
        whiteCard.setActive(!over && game.toMove() == Board.WHITE);
        blackCard.setThinking(aiThinking && aiColor == Board.BLACK);
        whiteCard.setThinking(aiThinking && aiColor == Board.WHITE);
    }

    private void updateStatus() {
        if (game == null) {
            statusView.setText(R.string.app_name);
            return;
        }
        final String sep = getString(R.string.status_sep);
        StringBuilder sb = statusBuilder;
        sb.setLength(0);
        if (busy) {
            sb.append(getString(aiThinking ? R.string.thinking : R.string.calculating));
        } else if (game.isOver()) {
            sb.append(getString(R.string.status_over_fmt, game.resultText()));
        } else {
            sb.append(getString(game.toMove() == Board.BLACK
                    ? R.string.status_turn_black : R.string.status_turn_white));
            sb.append(game.toMove() == humanColor
                    ? getString(R.string.status_turn_you) : getString(R.string.status_turn_ai));
        }
        sb.append(sep).append(getString(R.string.status_move_fmt, game.moveNumber()));
        sb.append(sep).append(getString(R.string.status_komi_fmt, fmt(KOMI)));
        if (shownWinRate >= 0) {
            sb.append(sep).append(getString(R.string.status_winrate_fmt, pct(shownWinRate)));
        }
        statusView.setText(sb);
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /** 坐标格式如 D4：x → 字母（跳过 I），y → 自下往上。 */
    private String coord(int p) {
        int x = game.board().x(p);
        int y = game.board().y(p);
        char letter = (char) ('A' + x);
        if (letter >= 'I') {
            letter++;
        }
        return letter + String.valueOf(game.board().size - y);
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String pct(double rate) {
        return String.format(Locale.US, "%.0f%%", rate * 100);
    }

    /** 用引擎的等级配置换算「约 N 秒/手」。 */
    private static String secondsForLevel(int levelValue, int size) {
        long ms = SearchConfig.forLevel(levelValue, size).maxTimeMs;
        return String.format(Locale.US, "%.1f", ms / 1000.0);
    }

    private static int sanitizeSize(int size) {
        for (int value : SIZE_VALUES) {
            if (value == size) {
                return value;
            }
        }
        return 9;
    }

    private static int sanitizeLevel(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
