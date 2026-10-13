package com.vvtool.elementcheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CapturesActivity extends Activity {
    private final List<Item> items = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private Adapter adapter;
    private TextView title, empty;

    private static final class Item {
        String id, pkg, sub;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        TextView[] t = new TextView[1];
        root.addView(Ui.header(this, "Captures", t));
        title = t[0];

        empty = Ui.text(this, "No captures yet", 15, Ui.TEXT_2, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 64), 0, 0);
        root.addView(empty);

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0));
        list.setDividerHeight(Ui.dp(this, 12));
        list.setSelector(android.R.color.transparent);
        int p = Ui.dp(this, 16);
        list.setPadding(p, 0, p, p);
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((a, v, pos, id) ->
                startActivity(new Intent(this, ViewerActivity.class).putExtra("id", items.get(pos).id)));
        list.setOnItemLongClickListener((a, v, pos, id) -> {
            String cid = items.get(pos).id;
            new AlertDialog.Builder(this)
                    .setTitle("Delete capture?")
                    .setPositiveButton("Delete", (d, w) -> { CaptureStore.delete(this, cid); reload(); })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.edgeToEdge(this, root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        items.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        for (String id : CaptureStore.list(this)) {
            Item it = new Item();
            it.id = id;
            it.pkg = id;
            it.sub = "";
            try {
                JSONObject m = new JSONObject(CaptureStore.readText(new File(CaptureStore.dir(this, id), CaptureStore.META)));
                it.pkg = m.optString("pkg", id);
                it.sub = fmt.format(new Date(m.optLong("time"))) + " · " + m.optInt("nodes") + " nodes";
            } catch (Exception ignored) { }
            items.add(it);
        }
        title.setText("Captures · " + items.size());
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(CapturesActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(CapturesActivity.this, 12);
                row.setPadding(p, p, p, p);
                row.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(CapturesActivity.this, 20)));
                ImageView iv = new ImageView(CapturesActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.rounded(Ui.PRIMARY_SOFT, Ui.dp(CapturesActivity.this, 12)));
                iv.setClipToOutline(true);
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(CapturesActivity.this, 52), Ui.dp(CapturesActivity.this, 92)));
                LinearLayout texts = new LinearLayout(CapturesActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(Ui.dp(CapturesActivity.this, 16), 0, 0, 0);
                texts.addView(Ui.text(CapturesActivity.this, "", 16, Ui.TEXT, true));
                texts.addView(Ui.text(CapturesActivity.this, "", 13, Ui.TEXT_2, false));
                row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            Item it = items.get(pos);
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            ((TextView) texts.getChildAt(0)).setText(it.pkg);
            ((TextView) texts.getChildAt(1)).setText(it.sub);
            bindThumb(iv, it.id);
            return row;
        }

    }

    private void bindThumb(ImageView iv, String id) {
        iv.setTag(id);
        Bitmap cached = thumbs.get(id);
        iv.setImageBitmap(cached);
        if (cached != null) return;
        loader.execute(() -> {
            File f = new File(CaptureStore.dir(this, id), CaptureStore.IMG);
            if (!f.exists()) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return;
            thumbs.put(id, b);
            runOnUiThread(() -> { if (id.equals(iv.getTag())) iv.setImageBitmap(b); });
        });
    }
}
