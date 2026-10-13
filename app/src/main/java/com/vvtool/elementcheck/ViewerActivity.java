package com.vvtool.elementcheck;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ViewerActivity extends Activity {
    private static final String[][] FLAGS = {{"clickable", "clickable"}, {"longClickable", "long-clickable"},
            {"scrollable", "scrollable"}, {"editable", "editable"}, {"checkable", "checkable"}, {"checked", "checked"},
            {"focusable", "focusable"}, {"focused", "focused"}, {"selected", "selected"}, {"enabled", "enabled"},
            {"password", "password"}, {"visible", "displayed"}};
    private static final int C_ID = 0xFF0E7490, C_TEXT = 0xFFB45309, C_DESC = 0xFF7C3AED, C_MUTED = 0xFF94A3B8;

    private static final class Win {
        String label;
        int color;
    }

    private static final class Node {
        JSONObject o;
        Rect b;
        int depth;
        Win win;
        String shortCls, rid, text, desc;
        boolean clickable, visible;
    }

    private static final class Row {
        Win win;
        Node node;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Node> matches = new ArrayList<>();
    private final Set<Node> matchSet = new HashSet<>();
    private int matchPos = -1;
    private Node selected;

    private InspectView view;
    private LinearLayout searchBar, detail;
    private EditText searchInput;
    private TextView countText, tabElement, tabTree;
    private ScrollView detailScroll;
    private ListView treeList;
    private TreeAdapter treeAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra("id");
        File dir = CaptureStore.dir(this, id);
        if (dir == null) { finish(); return; }
        int p = Ui.dp(this, 12);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);

        TextView[] title = new TextView[1];
        LinearLayout header = Ui.header(this, id, title);
        header.addView(Ui.iconButton(this, R.drawable.ic_search, Ui.TEXT, 0x00000000, v -> toggleSearch(true)),
                new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        col.addView(header);
        try {
            title[0].setText(CaptureStore.displayName(
                    new JSONObject(CaptureStore.readText(new File(dir, CaptureStore.META))), id));
        } catch (Exception ignored) { }

        col.addView(buildSearchBar());

        view = new InspectView(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f);
        vp.setMargins(p, 0, p, 0);
        col.addView(view, vp);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 24)));
        sheet.setClipToOutline(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.setMargins(p, p, p, p);
        col.addView(sheet, sp);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(p, p, p, Ui.dp(this, 4));
        tabElement = tab("Element", R.drawable.ic_info);
        tabTree = tab("Tree", R.drawable.ic_tree);
        tabs.addView(tabElement);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        tp.leftMargin = Ui.dp(this, 8);
        tabs.addView(tabTree, tp);
        tabElement.setOnClickListener(v -> showTab(false));
        tabTree.setOnClickListener(v -> showTab(true));
        sheet.addView(tabs);

        FrameLayout content = new FrameLayout(this);
        detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        detailScroll = new ScrollView(this);
        detailScroll.addView(detail);
        content.addView(detailScroll);
        treeList = new ListView(this);
        treeList.setDivider(null);
        treeList.setFastScrollEnabled(true);
        treeAdapter = new TreeAdapter();
        treeList.setAdapter(treeAdapter);
        treeList.setOnItemClickListener((a, v, pos, rid) -> {
            Row r = rows.get(pos);
            if (r.node != null) select(r.node, false);
        });
        content.addView(treeList);
        sheet.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);
        Ui.edgeToEdge(this, col);
        showTab(false);
        renderDetail();

        new Thread(() -> load(dir), "viewer-load").start();
    }

    private View buildSearchBar() {
        searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        searchBar.setVisibility(View.GONE);

        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 24)));
        field.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 14), 0);
        field.addView(Ui.icon(this, R.drawable.ic_search, Ui.TEXT_2));
        searchInput = new EditText(this);
        searchInput.setHint("Text, id, desc, class");
        searchInput.setTextSize(15);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        field.addView(searchInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        countText = Ui.text(this, "", 13, Ui.TEXT_2, false);
        field.addView(countText);
        searchBar.addView(field, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));

        int s = Ui.dp(this, 44);
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_up, Ui.TEXT, 0x00000000, v -> step(-1)), new LinearLayout.LayoutParams(s, s));
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_down, Ui.TEXT, 0x00000000, v -> step(1)), new LinearLayout.LayoutParams(s, s));
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_close, Ui.TEXT_2, 0x00000000, v -> toggleSearch(false)), new LinearLayout.LayoutParams(s, s));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) { runSearch(); }
        });
        searchInput.setOnEditorActionListener((v, action, ev) -> { step(1); return true; });
        return searchBar;
    }

    private TextView tab(String label, int icon) {
        TextView t = Ui.text(this, label, 14, Ui.TEXT_2, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawablePadding(Ui.dp(this, 6));
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 16), 0);
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        int sz = Ui.dp(this, 18);
        d.setBounds(0, 0, sz, sz);
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setMinHeight(Ui.dp(this, 36));
        return t;
    }

    private void showTab(boolean tree) {
        styleTab(tabElement, !tree);
        styleTab(tabTree, tree);
        detailScroll.setVisibility(tree ? View.GONE : View.VISIBLE);
        treeList.setVisibility(tree ? View.VISIBLE : View.GONE);
        if (tree) scrollTreeTo(selected);
    }

    private void styleTab(TextView t, boolean on) {
        int fg = on ? 0xFFFFFFFF : Ui.TEXT_2;
        t.setTextColor(fg);
        t.setBackground(Ui.chip(on ? Ui.PRIMARY : Ui.BG));
        t.getCompoundDrawablesRelative()[0].setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private void toggleSearch(boolean show) {
        searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (show) {
            searchInput.requestFocus();
            imm.showSoftInput(searchInput, 0);
        } else {
            searchInput.setText("");
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void load(File dir) {
        try {
            JSONObject root = new JSONObject(CaptureStore.readText(new File(dir, CaptureStore.TREE)));
            List<Node> list = new ArrayList<>();
            List<Row> rowList = new ArrayList<>();
            JSONArray wins = root.optJSONArray("windows");
            if (wins != null) {
                for (int i = 0; i < wins.length(); i++) {
                    JSONObject w = wins.getJSONObject(i);
                    JSONObject r = w.optJSONObject("root");
                    if (r == null) continue;
                    Row header = new Row();
                    header.win = window(w, r);
                    rowList.add(header);
                    flatten(r, 0, header.win, list, rowList);
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
            runOnUiThread(() -> {
                nodes.addAll(list);
                rows.addAll(rowList);
                treeAdapter.notifyDataSetChanged();
                view.set(b, sw, sh);
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Failed to load: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private static Win window(JSONObject w, JSONObject root) {
        Win win = new Win();
        String type = w.optString("type");
        switch (type) {
            case "application": win.label = "APP"; win.color = Ui.PRIMARY; break;
            case "inputMethod": win.label = "KEYBOARD"; win.color = C_DESC; break;
            case "system": win.label = "SYSTEM"; win.color = 0xFF475569; break;
            default: win.label = type.toUpperCase(Locale.US); win.color = C_ID;
        }
        String pkg = root.optString("pkg");
        win.label += "  ·  " + (pkg.isEmpty() ? w.optString("title") : pkg);
        return win;
    }

    private static void flatten(JSONObject o, int depth, Win win, List<Node> out, List<Row> rowsOut) {
        Node n = new Node();
        n.o = o;
        n.depth = depth;
        n.win = win;
        JSONArray a = o.optJSONArray("b");
        n.b = a == null ? new Rect() : new Rect(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3));
        String cls = o.optString("cls");
        n.shortCls = cls.substring(cls.lastIndexOf('.') + 1);
        n.rid = o.optString("rid");
        int k = n.rid.indexOf(":id/");
        if (k >= 0) n.rid = n.rid.substring(k + 4);
        n.text = o.optString("text");
        n.desc = o.optString("desc");
        n.clickable = o.optBoolean("clickable");
        n.visible = o.optBoolean("visible");
        out.add(n);
        Row r = new Row();
        r.node = n;
        rowsOut.add(r);
        JSONArray ch = o.optJSONArray("ch");
        if (ch == null) return;
        for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i);
            if (c != null) flatten(c, depth + 1, win, out, rowsOut);
        }
    }

    private void runSearch() {
        String q = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        matches.clear();
        matchSet.clear();
        matchPos = -1;
        if (!q.isEmpty()) {
            for (Node n : nodes) {
                if (n.text.toLowerCase(Locale.ROOT).contains(q) || n.desc.toLowerCase(Locale.ROOT).contains(q)
                        || n.o.optString("rid").toLowerCase(Locale.ROOT).contains(q)
                        || n.o.optString("cls").toLowerCase(Locale.ROOT).contains(q)) {
                    matches.add(n);
                }
            }
            matchSet.addAll(matches);
        }
        countText.setText(q.isEmpty() ? "" : String.valueOf(matches.size()));
        view.invalidate();
        treeAdapter.notifyDataSetChanged();
    }

    private void step(int dir) {
        if (matches.isEmpty()) return;
        matchPos = (matchPos + dir + matches.size()) % matches.size();
        select(matches.get(matchPos), true);
        countText.setText((matchPos + 1) + "/" + matches.size());
    }

    private void select(Node n, boolean scrollTree) {
        selected = n;
        view.invalidate();
        treeAdapter.notifyDataSetChanged();
        renderDetail();
        detailScroll.scrollTo(0, 0);
        if (scrollTree) scrollTreeTo(n);
    }

    private void scrollTreeTo(Node n) {
        if (n == null) return;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).node == n) {
                int pos = i;
                treeList.post(() -> treeList.setSelectionFromTop(pos, treeList.getHeight() / 3));
                return;
            }
        }
    }

    private void renderDetail() {
        detail.removeAllViews();
        Node n = selected;
        if (n == null) {
            TextView t = Ui.text(this, "Tap an element on the screenshot", 14, Ui.TEXT_2, false);
            t.setPadding(0, Ui.dp(this, 16), 0, 0);
            detail.addView(t);
            return;
        }
        JSONObject o = n.o;
        detail.addView(Ui.text(this, n.shortCls, 20, n.clickable ? Ui.PRIMARY : Ui.TEXT, true));
        detail.addView(Ui.text(this, n.win.label, 12, n.win.color, true));

        LinearLayout chips = new LinearLayout(this);
        for (String[] f : FLAGS) {
            if (!o.optBoolean(f[0])) continue;
            TextView c = Ui.text(this, f[1], 12, Ui.PRIMARY, true);
            c.setBackground(Ui.chip(Ui.PRIMARY_SOFT));
            c.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            chips.addView(c, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        detail.addView(hs, Ui.matchWrap(this, 10));

        prop("text", o.optString("text"));
        prop("resource-id", o.optString("rid"));
        prop("content-desc", o.optString("desc"));
        prop("xpath", o.optString("xpath"));
        Rect b = n.b;
        prop("bounds", "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]  ·  " + b.width() + " × " + b.height());
        prop("class", o.optString("cls"));
        prop("package", o.optString("pkg"));
        List<String> rest = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) rest.add(it.next());
        Collections.sort(rest);
        for (String k : rest) {
            if (k.equals("ch") || k.equals("cls") || k.equals("rid") || k.equals("text") || k.equals("desc") || k.equals("b")
                    || k.equals("pkg") || k.equals("xpath") || k.equals("i") || isFlag(k)) continue;
            Object v = o.opt(k);
            prop(k, v instanceof JSONArray ? join((JSONArray) v) : String.valueOf(v));
        }
    }

    private static boolean isFlag(String k) {
        for (String[] f : FLAGS) if (f[0].equals(k)) return true;
        return false;
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.opt(i));
        }
        return sb.toString();
    }

    private void prop(String key, String value) {
        if (value == null || value.isEmpty()) return;
        View line = new View(this);
        line.setBackgroundColor(Ui.OUTLINE);
        detail.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        row.addView(Ui.text(this, key, 12, Ui.TEXT_2, false));
        TextView v = Ui.text(this, value, 14, Ui.TEXT, false);
        row.addView(v);
        View.OnLongClickListener copy = x -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(key, value));
            Toast.makeText(this, "Copied " + key, Toast.LENGTH_SHORT).show();
            return true;
        };
        row.setOnLongClickListener(copy);
        v.setOnLongClickListener(copy);
        detail.addView(row);
    }

    private final class TreeAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i).node == null ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return rows.get(i).node != null; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Activity a = ViewerActivity.this;
            Row r = rows.get(pos);
            TextView t = (TextView) convert;
            if (r.node == null) {
                if (t == null) {
                    t = Ui.text(a, "", 12, Ui.TEXT, true);
                    t.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    t.setBackgroundColor(Ui.BG);
                }
                t.setText(r.win.label);
                t.setTextColor(r.win.color);
                return t;
            }
            if (t == null) {
                t = Ui.text(a, "", 13, Ui.TEXT, false);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setMinHeight(Ui.dp(a, 36));
                t.setGravity(Gravity.CENTER_VERTICAL);
            }
            Node n = r.node;
            t.setPadding(Ui.dp(a, 12 + Math.min(n.depth, 16) * 10), 0, Ui.dp(a, 12), 0);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, n.shortCls, n.clickable ? Ui.PRIMARY : (n.visible ? Ui.TEXT : C_MUTED), n.clickable);
            if (!n.rid.isEmpty()) append(sb, "  #" + n.rid, C_ID, false);
            if (!n.text.isEmpty()) append(sb, "  \"" + n.text + "\"", C_TEXT, false);
            else if (!n.desc.isEmpty()) append(sb, "  " + n.desc, C_DESC, false);
            t.setText(sb);
            t.setBackgroundColor(n == selected ? Ui.PRIMARY_SOFT : (matchSet.contains(n) ? 0xFFFEF3C7 : Ui.SURFACE));
            return t;
        }

        private void append(SpannableStringBuilder sb, String s, int color, boolean bold) {
            int st = sb.length();
            sb.append(s);
            sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private final class InspectView extends View {
        private Bitmap bmp;
        private int sw = 1080, sh = 1920;
        private final Paint thin = new Paint(), sel = new Paint(), selFill = new Paint(), match = new Paint();
        private final RectF dst = new RectF(), tmp = new RectF();
        private float lastX = -1, lastY = -1;
        private final List<Node> cycle = new ArrayList<>();
        private int cyclePos;

        InspectView(Context c) {
            super(c);
            setBackground(Ui.rounded(0xFFE8EDF6, Ui.dp(c, 20)));
            thin.setStyle(Paint.Style.STROKE);
            thin.setColor(0x551A56DB);
            sel.setStyle(Paint.Style.STROKE);
            sel.setColor(Ui.PRIMARY);
            sel.setStrokeWidth(Ui.dp(c, 2));
            selFill.setColor(0x331A56DB);
            match.setColor(0x55FACC15);
        }

        void set(Bitmap b, int w, int h) {
            bmp = b;
            if (w > 0 && h > 0) { sw = w; sh = h; }
            else if (b != null) { sw = b.getWidth(); sh = b.getHeight(); }
            invalidate();
        }

        private float scale() { return Math.min(getWidth() / (float) sw, getHeight() / (float) sh); }

        @Override
        protected void onDraw(Canvas c) {
            float s = scale();
            float ox = (getWidth() - sw * s) / 2f, oy = (getHeight() - sh * s) / 2f;
            dst.set(ox, oy, ox + sw * s, oy + sh * s);
            if (bmp != null) c.drawBitmap(bmp, null, dst, null);
            for (Node n : nodes) {
                if (!n.clickable) continue;
                map(n.b, s, ox, oy);
                c.drawRect(tmp, thin);
            }
            for (Node n : matches) {
                map(n.b, s, ox, oy);
                c.drawRect(tmp, match);
            }
            if (selected != null) {
                map(selected.b, s, ox, oy);
                c.drawRect(tmp, selFill);
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
            float s = scale();
            float ox = (getWidth() - sw * s) / 2f, oy = (getHeight() - sh * s) / 2f;
            int x = (int) ((e.getX() - ox) / s), y = (int) ((e.getY() - oy) / s);
            boolean same = Math.abs(e.getX() - lastX) < 16 && Math.abs(e.getY() - lastY) < 16;
            lastX = e.getX();
            lastY = e.getY();
            if (!same || cycle.isEmpty()) {
                cycle.clear();
                for (Node n : nodes) if (n.b.width() > 0 && n.b.height() > 0 && n.b.contains(x, y)) cycle.add(n);
                Collections.sort(cycle, (a, b) -> Long.compare(area(a.b), area(b.b)));
                cyclePos = 0;
            } else {
                cyclePos = (cyclePos + 1) % cycle.size();
            }
            if (!cycle.isEmpty()) select(cycle.get(cyclePos), true);
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }

        private long area(Rect r) { return (long) r.width() * r.height(); }
    }
}
