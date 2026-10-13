package com.vvtool.elementcheck;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating draggable toolbar. Uses TYPE_ACCESSIBILITY_OVERLAY, so no
 * "draw over other apps" permission is needed on any Android version.
 */
final class OverlayController {
    private final ECAccessibilityService svc;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    private final TextView captureBtn, appBtn;
    private boolean added;
    private boolean collapsed;

    @SuppressLint("ClickableViewAccessibility")
    OverlayController(ECAccessibilityService svc) {
        this.svc = svc;
        wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.overlayX(svc);
        lp.y = Prefs.overlayY(svc);

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE60F172A);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0x553B82F6);
        root.setBackground(bg);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView handle = button("EC", 0xFF2563EB);
        captureBtn = button("◉", 0xFFDC2626);   // ◉ capture
        appBtn = button("☰", 0xFF334155);       // ☰ open app
        root.addView(handle);
        root.addView(captureBtn);
        root.addView(appBtn);

        captureBtn.setOnClickListener(v -> {
            v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(80)).start();
            svc.captureFromOverlay();
        });
        appBtn.setOnClickListener(v -> svc.startActivity(new Intent(svc, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        final int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging && added) {
                            Point s = svc.realSize();
                            lp.x = clamp(startX + (int) dx, 0, s.x - root.getWidth());
                            lp.y = clamp(startY + (int) dy, 0, s.y - root.getHeight());
                            wm.updateViewLayout(root, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) Prefs.setOverlayPos(svc, lp.x, lp.y);
                        else toggleCollapsed();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        int vis = collapsed ? View.GONE : View.VISIBLE;
        captureBtn.setVisibility(vis);
        appBtn.setVisibility(vis);
    }

    void show() {
        if (added) return;
        try {
            wm.addView(root, lp);
            added = true;
        } catch (Exception ignored) { }
    }

    void hide() {
        if (!added) return;
        try { wm.removeViewImmediate(root); } catch (Exception ignored) { }
        added = false;
    }

    boolean isShown() { return added; }

    void setInvisible(boolean invisible) {
        if (added) root.setVisibility(invisible ? View.INVISIBLE : View.VISIBLE);
    }

    private TextView button(String label, int color) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        t.setBackground(d);
        int s = dp(42);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(s, s);
        p.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(p);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }
}
