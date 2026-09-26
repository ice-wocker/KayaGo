package com.kayago.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kayago.R;

/**
 * 底部操作按钮：自绘图标 + 小字标签，无边框，按下有涟漪。
 *
 * <p>禁用时图标与文字一起变暗（{@link #setEnabled(boolean)} 会向下传递状态）。
 */
public class ActionButton extends LinearLayout {

    private ImageView iconView;
    private TextView labelView;

    public ActionButton(Context context) {
        this(context, null);
    }

    public ActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        LayoutInflater.from(context).inflate(R.layout.view_action_button, this, true);

        iconView = findViewById(R.id.ab_icon);
        labelView = findViewById(R.id.ab_label);

        setBackgroundResource(R.drawable.bg_action_ripple);
        setClickable(true);
        setFocusable(true);
        float density = getResources().getDisplayMetrics().density;
        setMinimumWidth((int) (48f * density));
        setMinimumHeight((int) (52f * density));
        setPadding(0, (int) (6f * density), 0, (int) (6f * density));
    }

    public void bind(int iconRes, int labelRes) {
        iconView.setImageResource(iconRes);
        labelView.setText(labelRes);
        setContentDescription(getResources().getString(labelRes));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        iconView.setEnabled(enabled);
        labelView.setEnabled(enabled);
    }
}
