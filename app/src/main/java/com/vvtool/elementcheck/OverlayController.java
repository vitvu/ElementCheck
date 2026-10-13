package com.vvtool.elementcheck;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

final class OverlayController {
    private final ECAccessibilityService svc;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    private final View captureBtn, closeBtn;
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.overlayX(svc);
        lp.y = Prefs.overlayY(svc);

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = Ui.rounded(0xF7FFFFFF, dp(30));
        bg.setStroke(dp(1), Ui.OUTLINE);
        root.setBackground(bg);
        root.setElevation(dp(6));
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView grip = glyph("∷", Ui.PRIMARY, Ui.PRIMARY_SOFT);
        captureBtn = shutter();
        closeBtn = glyph("✕", Ui.TEXT_2, 0xFFF1F5F9);
        root.addView(grip, size(44, 0));
        root.addView(captureBtn, size(52, 6));
        root.addView(closeBtn, size(36, 6));

        captureBtn.setOnClickListener(v -> {
            v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90)).start();
            svc.captureFromOverlay();
        });
        closeBtn.setOnClickListener(v -> svc.setOverlayVisible(false));

        final int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        grip.setOnTouchListener(new View.OnTouchListener() {
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
        closeBtn.setVisibility(vis);
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

    private TextView glyph(String label, int color, int bgColor) {
        TextView t = Ui.text(svc, label, 16, color, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bgColor);
        t.setBackground(d);
        return t;
    }

    private View shutter() {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Ui.PRIMARY);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(2), 0xFFFFFFFF);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, ring});
        ld.setLayerInset(1, dp(7), dp(7), dp(7), dp(7));
        View v = new View(svc);
        v.setBackground(ld);
        v.setClickable(true);
        return v;
    }

    private LinearLayout.LayoutParams size(int sizeDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(int v) { return Ui.dp(svc, v); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }
}
