package com.kayago.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.kayago.R;

/**
 * 棋手卡片：棋子图标 + 名称 / 段位说明 + 提子数，思考时显示不确定进度条。
 *
 * <p>行棋方通过 {@link #setActive(boolean)} 高亮。
 */
public class PlayerCardView extends LinearLayout {

    private StoneView stoneView;
    private TextView nameView;
    private TextView subView;
    private TextView capturedView;
    private ProgressBar progress;

    private CharSequence baseSub = "";
    private boolean active;
    private boolean thinking;

    public PlayerCardView(Context context) {
        this(context, null);
    }

    public PlayerCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_player_card, this, true);

        stoneView = findViewById(R.id.pc_stone);
        nameView = findViewById(R.id.pc_name);
        subView = findViewById(R.id.pc_sub);
        capturedView = findViewById(R.id.pc_captured);
        progress = findViewById(R.id.pc_progress);

        setBackgroundResource(R.drawable.bg_card);
        progress.setVisibility(View.INVISIBLE);
        setCaptured(0);
    }

    /** 绑定棋色、名称与副标题（副标题形如「执白 · 等级3」）。 */
    public void bind(byte color, CharSequence name, CharSequence sub) {
        stoneView.setStoneColor(color);
        nameView.setText(name);
        baseSub = sub == null ? "" : sub;
        if (!thinking) {
            subView.setText(baseSub);
        }
    }

    public void setCaptured(int count) {
        capturedView.setText(getResources().getString(R.string.player_captured_fmt, count));
    }

    public void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        setBackgroundResource(value ? R.drawable.bg_card_active : R.drawable.bg_card);
    }

    public void setThinking(boolean value) {
        if (thinking == value) {
            return;
        }
        thinking = value;
        progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        subView.setText(value ? getResources().getString(R.string.player_thinking) : baseSub);
    }
}
