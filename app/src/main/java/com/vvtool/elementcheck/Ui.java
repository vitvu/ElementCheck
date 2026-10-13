package com.vvtool.elementcheck;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building plain-framework UIs in code. */
final class Ui {
    private Ui() { }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static TextView text(Activity a, String s, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return t;
    }

    static Button button(Activity a, String s, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 6);
        b.setLayoutParams(lp);
        return b;
    }

    static LinearLayout card(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 14);
        l.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), 0xFFE2E8F0);
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 12);
        l.setLayoutParams(lp);
        return l;
    }

    /** Keeps content clear of system bars when Android 15+ forces edge-to-edge. */
    @SuppressWarnings("deprecation")
    static void padForSystemBars(View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, in) -> {
            view.setPadding(l + in.getSystemWindowInsetLeft(), t,
                    r + in.getSystemWindowInsetRight(), b + in.getSystemWindowInsetBottom());
            return in;
        });
    }
}
