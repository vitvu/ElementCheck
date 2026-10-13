package com.vvtool.elementcheck;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xFFF4F7FD;
    static final int SURFACE = 0xFFFFFFFF;
    static final int PRIMARY = 0xFF1A56DB;
    static final int PRIMARY_SOFT = 0xFFE8EFFD;
    static final int TEXT = 0xFF0F172A;
    static final int TEXT_2 = 0xFF64748B;
    static final int OUTLINE = 0xFFE3E8F2;
    static final int GREEN = 0xFF16A34A;
    static final int RED = 0xFFDC2626;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    static TextView pill(Context c, String label, boolean filled, View.OnClickListener l) {
        TextView b = text(c, label, 15, filled ? 0xFFFFFFFF : PRIMARY, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setBackground(ripple(rounded(filled ? PRIMARY : PRIMARY_SOFT, dp(c, 24)), 0x33FFFFFF));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 20);
        l.setPadding(p, p, p, p);
        l.setBackground(rounded(SURFACE, dp(c, 24)));
        return l;
    }

    static LinearLayout.LayoutParams matchWrap(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    static LinearLayout header(android.app.Activity a, String title, TextView[] outTitle) {
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 8), dp(a, 8), dp(a, 16), dp(a, 8));
        TextView back = text(a, "←", 22, TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(ripple(rounded(0x00000000, dp(a, 24)), 0x221A56DB));
        back.setOnClickListener(v -> a.finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        TextView t = text(a, title, 20, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding(dp(a, 8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (outTitle != null) outTitle[0] = t;
        return bar;
    }

    @SuppressWarnings("deprecation")
    static void edgeToEdge(android.app.Activity a, View content) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        a.getWindow().getDecorView().setSystemUiVisibility(flags);
        applySystemBarInsets(content);
    }

    @SuppressWarnings("deprecation")
    private static void applySystemBarInsets(View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, in) -> {
            view.setPadding(l + in.getSystemWindowInsetLeft(), t + in.getSystemWindowInsetTop(),
                    r + in.getSystemWindowInsetRight(), b + in.getSystemWindowInsetBottom());
            return in;
        });
    }
}
