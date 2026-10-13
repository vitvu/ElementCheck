package com.vvtool.elementcheck;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Offline viewer: screenshot with node boxes; tap to inspect (tap again to cycle overlapping nodes). */
public class ViewerActivity extends Activity {
    private static final String[] FLAGS = {"clickable", "longClickable", "checkable", "checked", "enabled",
            "focusable", "focused", "scrollable", "selected", "editable", "password", "visible"};

    private InspectView view;
    private TextView details;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra("id");
        File dir = CaptureStore.dir(this, id);
        if (dir == null) { finish(); return; }
        setTitle(id);

        view = new InspectView(this);
        details = new TextView(this);
        details.setTextIsSelectable(true);
        details.setTextSize(12);
        int p = Ui.dp(this, 10);
        details.setPadding(p, p, p, p);
        details.setText("Chạm vào phần tử trên ảnh để xem chi tiết.");
        ScrollView sv = new ScrollView(this);
        sv.addView(details);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f));
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));
        Ui.padForSystemBars(col);
        setContentView(col);

        new Thread(() -> load(dir), "viewer-load").start();
    }

    private void load(File dir) {
        try {
            JSONObject root = new JSONObject(CaptureStore.readText(new File(dir, CaptureStore.TREE)));
            List<JSONObject> nodes = new ArrayList<>();
            JSONArray wins = root.optJSONArray("windows");
            if (wins != null) {
                for (int i = 0; i < wins.length(); i++) {
                    JSONObject r = wins.getJSONObject(i).optJSONObject("root");
                    if (r != null) flatten(r, nodes);
                }
            }
            int sw = root.optInt("w"), sh = root.optInt("h");
            Bitmap bmp = null;
            File img = new File(dir, CaptureStore.IMG);
            if (img.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(img.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 720) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(img.getPath(), o);
            }
            final Bitmap b = bmp;
            runOnUiThread(() -> view.set(b, sw, sh, nodes));
        } catch (Exception e) {
            runOnUiThread(() -> details.setText("Lỗi đọc capture: " + e));
        }
    }

    private static void flatten(JSONObject n, List<JSONObject> out) {
        out.add(n);
        JSONArray ch = n.optJSONArray("ch");
        if (ch == null) return;
        for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i);
            if (c != null) flatten(c, out);
        }
    }

    private void showDetails(JSONObject n) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        line(sb, "class", n.optString("cls"));
        line(sb, "resource-id", n.optString("rid"));
        line(sb, "text", n.optString("text"));
        line(sb, "content-desc", n.optString("desc"));
        line(sb, "package", n.optString("pkg"));
        line(sb, "xpath", n.optString("xpath"));
        JSONArray b = n.optJSONArray("b");
        if (b != null) line(sb, "bounds", "[" + b.optInt(0) + "," + b.optInt(1) + "][" + b.optInt(2) + "," + b.optInt(3) + "]");
        StringBuilder flags = new StringBuilder();
        for (String f : FLAGS) flags.append(f).append('=').append(n.optBoolean(f)).append("  ");
        line(sb, "flags", flags.toString());
        JSONArray acts = n.optJSONArray("actions");
        if (acts != null) line(sb, "actions", acts.toString());
        Iterator<String> keys = n.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.equals("ch") || k.equals("cls") || k.equals("rid") || k.equals("text") || k.equals("desc")
                    || k.equals("pkg") || k.equals("xpath") || k.equals("b") || k.equals("actions")) continue;
            boolean isFlag = false;
            for (String f : FLAGS) if (f.equals(k)) { isFlag = true; break; }
            if (!isFlag) line(sb, k, String.valueOf(n.opt(k)));
        }
        details.setText(sb);
    }

    private static void line(SpannableStringBuilder sb, String k, String v) {
        if (v == null || v.isEmpty()) return;
        int s = sb.length();
        sb.append(k).append(": ");
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(v).append('\n');
    }

    /** Draws the screenshot fitted to the view, plus node boxes. */
    private final class InspectView extends View {
        private Bitmap bmp;
        private int sw, sh;
        private List<JSONObject> nodes = new ArrayList<>();
        private final List<Rect> rects = new ArrayList<>();
        private final Paint thin = new Paint(), sel = new Paint(), fill = new Paint();
        private final RectF dst = new RectF(), tmp = new RectF();
        private int selected = -1;
        private float lastX = -1, lastY = -1;
        private final List<Integer> cycle = new ArrayList<>();
        private int cyclePos;

        InspectView(Context c) {
            super(c);
            setBackgroundColor(0xFF0F172A);
            thin.setStyle(Paint.Style.STROKE);
            thin.setColor(0x5538BDF8);
            thin.setStrokeWidth(1);
            sel.setStyle(Paint.Style.STROKE);
            sel.setColor(0xFFF43F5E);
            sel.setStrokeWidth(Ui.dp(ViewerActivity.this, 2));
            fill.setColor(0x33F43F5E);
        }

        void set(Bitmap b, int w, int h, List<JSONObject> list) {
            bmp = b;
            sw = w > 0 ? w : (b != null ? b.getWidth() : 1080);
            sh = h > 0 ? h : (b != null ? b.getHeight() : 1920);
            nodes = list;
            rects.clear();
            for (JSONObject n : list) {
                JSONArray a = n.optJSONArray("b");
                rects.add(a == null ? new Rect() : new Rect(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3)));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float scale = Math.min(getWidth() / (float) sw, getHeight() / (float) sh);
            float ox = (getWidth() - sw * scale) / 2f, oy = (getHeight() - sh * scale) / 2f;
            dst.set(ox, oy, ox + sw * scale, oy + sh * scale);
            if (bmp != null) c.drawBitmap(bmp, null, dst, null);
            else c.drawColor(Color.DKGRAY);
            for (Rect r : rects) {
                map(r, scale, ox, oy);
                c.drawRect(tmp, thin);
            }
            if (selected >= 0) {
                map(rects.get(selected), scale, ox, oy);
                c.drawRect(tmp, fill);
                c.drawRect(tmp, sel);
            }
        }

        private void map(Rect r, float s, float ox, float oy) {
            tmp.set(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            performClick();
            float scale = Math.min(getWidth() / (float) sw, getHeight() / (float) sh);
            float ox = (getWidth() - sw * scale) / 2f, oy = (getHeight() - sh * scale) / 2f;
            int x = (int) ((e.getX() - ox) / scale), y = (int) ((e.getY() - oy) / scale);
            boolean same = Math.abs(e.getX() - lastX) < 12 && Math.abs(e.getY() - lastY) < 12;
            lastX = e.getX(); lastY = e.getY();
            if (!same || cycle.isEmpty()) {
                cycle.clear();
                for (int i = 0; i < rects.size(); i++) {
                    Rect r = rects.get(i);
                    if (r.width() > 0 && r.height() > 0 && r.contains(x, y)) cycle.add(i);
                }
                // Smallest area first: the most specific element.
                java.util.Collections.sort(cycle, (a, b) -> Long.compare(area(rects.get(a)), area(rects.get(b))));
                cyclePos = 0;
            } else {
                cyclePos = (cyclePos + 1) % cycle.size();
            }
            if (!cycle.isEmpty()) {
                selected = cycle.get(cyclePos);
                showDetails(nodes.get(selected));
                invalidate();
            }
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        private long area(Rect r) { return (long) r.width() * r.height(); }
    }
}
